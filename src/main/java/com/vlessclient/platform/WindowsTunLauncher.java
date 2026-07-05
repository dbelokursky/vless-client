package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows TUN launcher: sing-box must run elevated (administrator) to create
 * its TUN adapter, but the app itself is not — and a non-elevated process can
 * neither read an elevated child's stdout nor kill it.
 *
 * <p>The launch is therefore split into two generated PowerShell scripts:</p>
 *
 * <ol>
 *   <li><b>Outer</b> (non-elevated; the {@link Process} Java observes):
 *       requests elevation of the wrapper via {@code Start-Process -Verb
 *       RunAs} — one UAC prompt per connect — then tails the core's log
 *       files to its own stdout until the wrapper exits, so the app streams
 *       live logs exactly like in system-proxy mode. If the user declines
 *       the UAC prompt, it prints a FATAL line and exits, which the engine
 *       reports as a connection error.</li>
 *   <li><b>Wrapper</b> (elevated): starts sing-box with output redirected to
 *       the log files and loops until sing-box dies, the stop-signal file
 *       appears (elevated-to-elevated kill is permitted), or the app process
 *       disappears — so a crashed app never leaks a privileged core.</li>
 * </ol>
 */
public final class WindowsTunLauncher implements TunLauncher {

    private static final Logger log = LoggerFactory.getLogger(WindowsTunLauncher.class);

    @Override
    public Launched launch(Path binary, Path configFile) throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        String token = Long.toString(System.nanoTime());
        Path stopSignalFile = tempDir.resolve("vless-client-stop-" + token + ".signal");
        Files.deleteIfExists(stopSignalFile);

        Path logOut = tempDir.resolve("vless-tun-" + token + ".out.log");
        Path logErr = tempDir.resolve("vless-tun-" + token + ".err.log");
        Path wrapper = tempDir.resolve("vless-tun-wrapper-" + token + ".ps1");
        Path outer = tempDir.resolve("vless-tun-outer-" + token + ".ps1");
        Files.writeString(wrapper, wrapperScript());
        Files.writeString(outer, outerScript());

        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", outer.toString(),
                wrapper.toString(),
                binary.toAbsolutePath().toString(),
                configFile.toAbsolutePath().toString(),
                logOut.toString(),
                logErr.toString(),
                stopSignalFile.toString(),
                Long.toString(ProcessHandle.current().pid()));
        pb.directory(binary.getParent().toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        log.info("Started sing-box TUN launch via UAC elevation (prompt expected)");
        return new Launched(process, stopSignalFile);
    }

    /**
     * The non-elevated outer script: elevates the wrapper, then tails the
     * core's log files to stdout until the wrapper exits.
     */
    static String outerScript() {
        return """
                param(
                    [Parameter(Mandatory=$true)][string]$Wrapper,
                    [Parameter(Mandatory=$true)][string]$Binary,
                    [Parameter(Mandatory=$true)][string]$Config,
                    [Parameter(Mandatory=$true)][string]$LogOut,
                    [Parameter(Mandatory=$true)][string]$LogErr,
                    [Parameter(Mandatory=$true)][string]$StopFile,
                    [Parameter(Mandatory=$true)][string]$OwnerPid
                )
                $ErrorActionPreference = 'Stop'
                New-Item -ItemType File -Force -Path $LogOut, $LogErr | Out-Null

                function Emit-New([string]$path, [ref]$pos) {
                    # Read anything appended past $pos, sharing the file with the
                    # elevated writer, and forward it line by line to stdout.
                    try {
                        $fs = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
                    } catch {
                        return
                    }
                    try {
                        if ($fs.Length -le $pos.Value) { return }
                        $fs.Position = $pos.Value
                        $sr = New-Object System.IO.StreamReader($fs)
                        while ($null -ne ($line = $sr.ReadLine())) { Write-Output $line }
                        $pos.Value = $fs.Length
                    } finally {
                        $fs.Dispose()
                    }
                }

                try {
                    $wrapperArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass',
                        '-WindowStyle', 'Hidden', '-File', $Wrapper,
                        $Binary, $Config, $LogOut, $LogErr, $StopFile, $OwnerPid)
                    $w = Start-Process -FilePath 'powershell' -Verb RunAs `
                        -WindowStyle Hidden -ArgumentList $wrapperArgs -PassThru
                } catch {
                    Write-Output ('FATAL: administrator elevation was declined or failed: ' `
                        + $_.Exception.Message)
                    exit 3
                }

                $posOut = 0
                $posErr = 0
                while (-not $w.HasExited) {
                    Emit-New $LogErr ([ref]$posErr)
                    Emit-New $LogOut ([ref]$posOut)
                    Start-Sleep -Milliseconds 250
                }
                Emit-New $LogErr ([ref]$posErr)
                Emit-New $LogOut ([ref]$posOut)
                try { exit $w.ExitCode } catch { exit 0 }
                """;
    }

    /**
     * The elevated wrapper script: runs sing-box redirected to the log files
     * and stops it when the stop file appears or the app process dies.
     */
    static String wrapperScript() {
        return """
                param(
                    [Parameter(Mandatory=$true)][string]$Binary,
                    [Parameter(Mandatory=$true)][string]$Config,
                    [Parameter(Mandatory=$true)][string]$LogOut,
                    [Parameter(Mandatory=$true)][string]$LogErr,
                    [Parameter(Mandatory=$true)][string]$StopFile,
                    [Parameter(Mandatory=$true)][string]$OwnerPid
                )
                $ErrorActionPreference = 'Stop'
                $proc = Start-Process -FilePath $Binary `
                    -ArgumentList @('run', '-c', $Config) `
                    -RedirectStandardOutput $LogOut -RedirectStandardError $LogErr `
                    -NoNewWindow -PassThru
                try {
                    while (-not $proc.HasExited) {
                        if (Test-Path -LiteralPath $StopFile) { break }
                        if (-not (Get-Process -Id ([int]$OwnerPid) `
                            -ErrorAction SilentlyContinue)) { break }
                        Start-Sleep -Milliseconds 300
                    }
                } finally {
                    if (-not $proc.HasExited) {
                        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                    }
                    Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue
                }
                """;
    }
}
