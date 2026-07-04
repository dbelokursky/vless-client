# Windows support — implementation plan

Status: proposal. Target: a functional Windows build alongside macOS, shipped
incrementally so `main` stays green and macOS keeps working at every step.

## Goal & scope

Make the VLESS client run on Windows 10/11 (x64): install, connect in both
SYSTEM_PROXY and TUN modes, autostart, update itself and the sing-box core.
Out of scope for now: Linux, ARM64 Windows, Microsoft Store/MSIX packaging.

The UI is already cross-platform — JavaFX (all views, config generation,
subscriptions, routing, traffic, latency, ~70% of the code) runs on Windows
unchanged. The work is confined to a small platform layer.

## The macOS-bound surface (what must be ported)

| Concern | File(s) | macOS mechanism | Windows target |
|---|---|---|---|
| App data / logs paths | `ConfigStore` (`~/Library/Application Support/VlessClient`), `logback.xml` (`LOG_DIR`) | hardcoded `~/Library/...` | `%APPDATA%\VlessClient` |
| Core binary bundle/install | `SingBoxInstaller`, `scripts/bundle-singbox.sh` | `darwin-{arch}` `.tar.gz`, `/usr/bin/tar`, exec bit | `windows-amd64.zip` → `sing-box.exe`, Java unzip |
| Core process launch (system-proxy) | `SingBoxEngine.startDirect` | `ProcessBuilder(binary, run, -c ...)` | same, `.exe` path (already portable) |
| Core process launch (TUN, elevated) | `SingBoxEngine.startWithPrivileges`, `PrivilegeHelper` | `sudo` NOPASSWD + `osascript`, `/tmp` signal file | UAC elevation + Wintun; scheduled-task or elevated helper |
| System proxy on/off | `NetworkManager` (12× `networksetup`) | `networksetup` CLI | WinINET (registry + `InternetSetOption`) |
| Autostart | `LoginItemService` | launchd LaunchAgent plist | `HKCU\…\Run` key or Startup shortcut |
| App chrome | `VlessClientApp` (Dock icon, `-Xdock:name`) | AWT `Taskbar`, JVM `-Xdock` | no-op / taskbar defaults |
| Packaging | `scripts/package-dmg.sh`, `release.yml`, `build.yml` | jpackage `.dmg` + `.icns` | jpackage `.msi`/app-image + `.ico` |
| App self-update | `UpdateManager` | downloads `.dmg` | downloads `.msi`/`.exe` |
| Core self-update | `CoreUpdateService` | managed path + sudoers | per-OS binary + Windows elevation |
| Tests | `SingBoxRealBinarySmokeTest`, `SingBoxInstallerTest`, `LoginItemServiceTest`, `NetworkManagerTest`, `PrivilegeHelperTest` | darwin binary, mac paths/procs | guard per-OS / per-OS impls |

No OS detection exists today — the code assumes macOS throughout.

## Guiding principles

1. **Abstract, don't fork.** Put each platform concern behind a small interface
   with a macOS impl (unchanged behavior) and a Windows impl. A `Platform`
   factory picks by `os.name`.
2. **macOS stays green.** Every phase keeps `mvn clean verify -Psmoke` passing
   on macOS; Windows is additive.
3. **Ship per phase.** Each phase has a demoable outcome. A partial Windows
   port that builds + connects in system-proxy is already useful.
4. **Test what CI can.** Unit-test the pure logic (paths, asset naming, proxy
   registry values) on any OS; the real-binary smoke runs on a `windows-latest`
   runner; TUN/elevation is verified manually (needs interactive admin).

---

## Phase 0 — Platform abstraction layer (no behavior change)

Extract the platform-bound bits behind interfaces; implement macOS only. Pure
refactor — macOS behavior identical, Windows impls are stubs that throw
`UnsupportedOperationException` for now.

Interfaces (new package `com.vlessclient.platform`):
- `PlatformPaths` — `dataDir()`, `logsDir()`, `cacheDir()`, `downloadsDir()`.
- `CorePlatform` — asset name (`sing-box-<v>-<os>-<arch>.<ext>`), archive kind,
  binary filename (`sing-box` vs `sing-box.exe`), `extract(archive, dest)`.
- `SystemProxy` — `enable(host, socks, http)`, `disable()`.
- `Autostart` — `isEnabled()`, `setEnabled(bool)`, `refresh()`.
- `TunLauncher` — `start(configPath)`, `stop()` (encapsulates elevation).
- `AppChrome` — `setDockIcon()`, `applicationName()` (no-op on Windows).
- `Platform.current()` factory → returns the macOS or Windows bundle by `os.name`.

Wire existing services to the interfaces:
- `ConfigStore`, `SingBoxInstaller`, `CoreUpdateService` → `PlatformPaths` +
  `CorePlatform` (replace `/usr/bin/tar` with `CorePlatform.extract`; use a
  Java `.tar.gz` extractor on mac to drop the shell-out, and Java `.zip` on win).
- `NetworkManager` → `SystemProxy` (macOS impl = current `networksetup` code).
- `LoginItemService` → `Autostart`.
- `SingBoxEngine` TUN path → `TunLauncher` (macOS impl = current sudoers/osascript).
- `VlessClientApp` dock code → `AppChrome`.
- Logging: compute `LOG_DIR` in `Launcher` before logging init and pass it as
  `-Dvless.log.dir`; `logback.xml` uses `${vless.log.dir}`.

Acceptance: macOS `mvn clean verify -Psmoke` green; zero behavior change; the
platform tests now target the macOS impls. **Shippable:** yes (refactor only).

Effort: ~3–4 days.

---

## Phase 1 — Build & bundle for Windows (installable artifact, launches)

Make the project build and package on Windows; the app opens but Connect is
not wired yet.

- `singbox.properties`: add `singbox.sha256.windows-amd64`.
- `bundle-singbox.sh` → also fetch `windows-amd64.zip`; or add a portable
  `bundle-singbox` step that runs on the Windows runner (bash is available on
  GitHub windows images, or rewrite in a small cross-platform script).
- `SingBoxInstaller`/`CorePlatform`: Windows resource path
  `/native/windows-amd64/sing-box.exe`, Java `.zip` extraction, no exec bit.
- `PlatformPaths` Windows impl: `%APPDATA%\VlessClient`.
- Packaging: `scripts/package-windows.*` (jpackage `--type app-image` → zip for
  no-WiX safety, plus optional `--type msi` when WiX is present) + a `.ico`
  generated from the existing icon.
- `build.yml`: add a `windows-latest` job — `mvn clean verify -Psmoke` (with
  the macOS-only tests guarded via `@EnabledOnOs(MAC)` / `@DisabledOnOs(WINDOWS)`
  or a `windows` surefire profile) + package the app-image artifact.
- `dev-latest` rolling release: attach the Windows zip alongside the DMG.

Acceptance: Windows CI green; Windows artifact launches to the dashboard;
`sing-box.exe version` smoke passes on the runner. Connect still no-op.

Effort: ~4–5 days.

---

## Phase 2 — SYSTEM_PROXY connect on Windows (first real connection)

The tracer bullet: a working connection in system-proxy mode (no admin needed).

- `SingBoxEngine.startDirect`: already portable — run `sing-box.exe run -c`
  directly; stop via `Process.destroy()` (no `/bin/sh` wrapper on Windows).
- `SystemProxy` Windows impl: set/clear the WinINET proxy — write
  `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`
  (`ProxyEnable`, `ProxyServer=127.0.0.1:<httpPort>`, `ProxyOverride` for
  `<local>` + LAN) and broadcast `InternetSetOption(INTERNET_OPTION_SETTINGS_CHANGED / REFRESH)`.
  Registry via `java.util.prefs`/`reg.exe`; the `InternetSetOption` broadcast
  via JNA (add `net.java.dev.jna:jna-platform`) — otherwise apps won't pick up
  the change until restart.
- Config generator review for Windows: `route_exclude_address`/`ip_is_private`,
  `.local`/localhost direct rules already portable; confirm no macOS-only
  assumptions (interface names only matter for TUN).

Acceptance: on a real Windows box, Connect (SYSTEM_PROXY) routes a browser
through the tunnel; Disconnect restores the proxy; the health-check card and
traffic monitor work. Verified manually + a `SystemProxy` unit test asserting
the registry values written.

Effort: ~3–4 days (mostly WinINET/JNA + manual testing).

---

## Phase 3 — TUN mode + elevation on Windows (the hard part)

- Bundle/resolve **Wintun** (`wintun.dll`) next to `sing-box.exe`; sing-box uses
  it for the TUN adapter on Windows.
- `TunLauncher` Windows impl: launch elevated. Options, in preference order:
  1. **Scheduled task with highest privileges** created on first TUN use (one
     UAC prompt at setup, silent thereafter) — the Windows analogue of the
     macOS sudoers-NOPASSWD trick. Start/stop the task to control sing-box.
  2. Fallback: `ShellExecuteEx` `runas` (UAC prompt on every connect).
- Stop: signal the elevated process (named event / task stop) since a
  non-elevated parent can't `kill` an elevated child directly.
- Config: Windows TUN uses an auto/interface name (not `utun99`); make the
  interface name platform-defaulted in the generator.
- `PrivilegeHelper` equivalent behind `TunLauncher` (probe that elevation works;
  fall back to system-proxy with a clear message if the user declines UAC).

Acceptance: TUN connect works on real Windows (traffic through the adapter,
LAN/`.local` still direct); clean disconnect; declining UAC degrades gracefully.
Manual verification (CI can't do interactive admin).

Effort: ~1–1.5 weeks (highest risk; Wintun + elevation + real testing).

---

## Phase 4 — Autostart, tray, first-run polish

- `Autostart` Windows impl: `HKCU\…\Run` entry (or Startup-folder shortcut)
  pointing at the installed exe; keep the existing "Launch at login" toggle.
- Tray icon (`TrayIconService`) — AWT SystemTray works on Windows; verify menu,
  icon, and quit behavior; guard the mac-specific Dock/quit-handler bits.
- First-run: the installer/bundle already ships `sing-box.exe`; confirm the
  no-network first launch path; Windows Defender SmartScreen note (see Phase 5).

Acceptance: launch-at-login works; tray menu works; clean quit on Windows.

Effort: ~2–3 days.

---

## Phase 5 — Distribution: signing, release matrix, updaters

- **Release pipeline**: `release.yml` becomes a matrix (macos + windows);
  Windows job builds the MSI/exe (WiX) and attaches to the GitHub release.
- **Authenticode signing**: sign the Windows installer (cert required) or accept
  SmartScreen warnings initially; document the trade-off. (Parallel to the
  still-open macOS Developer-ID/notarization gap.)
- **App self-update** (`UpdateManager`): pick the `.msi`/`.exe` asset on Windows
  (currently hardcoded to `.dmg`); download + launch the installer.
- **Core self-update** (`CoreUpdateService`): download `windows-amd64.zip`,
  verify SHA-256 (GitHub API digest), swap `sing-box.exe`; elevation model from
  Phase 3 applies. Note: Windows can't overwrite a running `.exe` — swap while
  the core is stopped (the service already gates promote on "stopped").
- Rolling `dev-latest`: attach the Windows artifact.

Acceptance: tagged release produces signed (or documented-unsigned) Windows
installer; in-app app + core updates work on Windows.

Effort: ~3–5 days (excluding cert procurement lead time).

---

## Risks & open questions

- **Elevation UX** is the biggest unknown — the scheduled-task approach needs
  real-world validation (Group Policy, non-admin accounts, AV interference).
- **Testing** requires real Windows environments; CI covers build + smoke +
  unit logic, not TUN/elevation/proxy end-to-end.
- **Code signing** cost/lead time for Authenticode; unsigned installers trip
  SmartScreen.
- **Maintenance ×2** — every network/autostart/paths feature now needs a Windows
  path and testing.
- **Sanity check**: is there real Windows demand, or is macOS-only fine for now?
  Phase 0 (abstraction) is worth doing regardless as a cleanup; commit to
  Phases 2–3 only if Windows users are real.

## Rough total

~4–6 weeks of focused work to Phase 5, front-loaded value: **Phase 0–2 (~1.5–2
weeks) already yields a Windows build that installs, launches, and connects in
system-proxy mode.** TUN (Phase 3) is the expensive, risky remainder.

## Suggested sequencing

Phase 0 → 1 → 2 (ship a "Windows beta: system-proxy only"), gather feedback,
then 3 → 4 → 5 if warranted. One PR per phase (or per interface within Phase 0),
each green on macOS CI + the new Windows CI job.
