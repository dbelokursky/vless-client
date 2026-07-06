# Linux support — implementation plan

Status: shipped (2026-07). Linux is built and packaged in CI
(`ubuntu-latest` jobs in build.yml, `package-linux.sh`) and published on the
Releases page. Kept for the design rationale; the "Risks & open questions"
section below still applies to non-GNOME desktops and non-deb distros.

Original target: a functional Linux build alongside macOS and Windows,
shipped incrementally so `main` stays green and both existing platforms
keep working at every step.

## Goal & scope

Make the VLESS client run on desktop Linux (x86-64, glibc — Ubuntu 22.04+,
Debian 12+, Fedora and friends): install, connect in both SYSTEM_PROXY and
TUN modes, autostart, update itself and the sing-box core.
Out of scope for now: musl/Alpine, ARM64 (add later like Windows arm64),
Flatpak/Snap/AppImage store distribution, non-desktop (headless) use.

## Why this is much cheaper than Windows was

The Windows port (PRs #29–#37) already carved every OS-bound concern into a
platform seam under `com.vlessclient.platform`. Linux is the **third
implementation of existing interfaces**, not a new abstraction effort — and
Linux is Unix, so several macOS implementations (tar extraction, shell
wrappers, stop-file contract) port with minimal changes.

The catch today: `Platform` detects `OTHER` for Linux, and every `current()`
selector falls back to the **Mac** implementation — darwin release assets,
`~/Library/...` paths, launchd plists, osascript. The app is silently broken
on Linux rather than unsupported. Phase 0 fixes exactly that.

## The per-seam surface (what exists, what Linux needs)

| Seam | macOS impl | Windows impl | Linux impl needed |
|---|---|---|---|
| `PlatformPaths` | `~/Library/Application Support` | `%APPDATA%` | XDG: `~/.local/share/vless-client` (honor `$XDG_DATA_HOME`), `~/Downloads` (honor `$XDG_DOWNLOAD_DIR`) |
| `CorePlatform` | `darwin-*.tar.gz`, BSD tar | `windows-*.zip`, Java unzip | `linux-<arch>.tar.gz` (glibc build), GNU tar — reuse the tar flow |
| `Autostart` | launchd plist | `HKCU\…\Run` (exe-aware) | XDG autostart `~/.config/autostart/vless-client.desktop` (exe-aware, same `ProcessHandle` trick) |
| `SystemProxyGuard` | `networksetup` | WinINET registry | `gsettings org.gnome.system.proxy` (GNOME; best-effort elsewhere) |
| `TunLauncher` | sudoers-NOPASSWD → osascript fallback | UAC PowerShell pair | **setcap fast path** → pkexec fallback (details in Phase 4) |
| Core bundling | `bundle-singbox.sh` (darwin loop) | `bundle-singbox.ps1` | extend the `.sh` with a linux loop + pins |
| JVM argfile | `-Xdock` flags | plain (PowerShell) | plain (extend `write-jvm-args.sh` per-OS) |
| Packaging | jpackage `.dmg` + `.icns` | jpackage `.msi` + `.ico` | jpackage `.deb` (+ `.rpm` optional); PNG icons already committed |
| App self-update | `.dmg` asset | `.msi` asset | `.deb` asset (extend `installerExtension()`) |
| Core self-update | `CorePlatform`-driven ✓ | `CorePlatform`-driven ✓ | free once `LinuxCorePlatform` exists |
| CI | `macos-latest` verify -Psmoke | `windows-latest` verify -Psmoke | `ubuntu-latest` verify -Psmoke (cheapest runner; Monocle headless already proven) |
| Tray / app chrome | AWT tray + Dock guards | AWT tray, `Taskbar` no-ops | AWT tray **may be absent on GNOME** — needs a close-behavior fallback (Phase 5) |

## Guiding principles

Same as the Windows plan, plus one Linux-specific rule:

1. **Third impl, not a fork** — new classes beside `Mac*`/`Windows*`;
   `Platform` gains `LINUX` and every `current()` switches on it explicitly.
   `OTHER` keeps the mac fallback (unchanged behavior for exotic unixes).
2. **macOS and Windows stay green** — every phase keeps
   `mvn clean verify -Psmoke` passing on both existing CI jobs.
3. **Ship per phase** — a Linux build that connects in system-proxy mode is
   already useful without TUN.
4. **CI-first validation** — the ubuntu runner is the cheapest and the most
   capable of the three: it has passwordless sudo, so even the TUN path can
   get a real CI probe (better than Windows, where only the already-elevated
   case was testable).

---

## Phase 0 — `Platform.LINUX` + paths + core (stop being silently broken)

- `Platform`: add `LINUX` (`os.name` contains "linux"), keep `OTHER`→mac
  fallback for the rest.
- `LinuxPlatformPaths`: data/logs under `$XDG_DATA_HOME` (default
  `~/.local/share/vless-client`); downloads via `$XDG_DOWNLOAD_DIR` user-dirs
  lookup, falling back to `~/Downloads`.
- `LinuxCorePlatform`: `osKey "linux"`, `binaryName "sing-box"`,
  `tar.gz` extraction — factor the tar invocation out of `MacCorePlatform`
  (only the tar binary path differs: resolve from PATH instead of
  `/usr/bin/tar`).
- Wire all `current()` selectors; extend `PlatformPathsTest`/`CorePlatformTest`
  (XDG env override seam, mirroring the `APPDATA` test seam).

Acceptance: on a Linux JVM the app resolves XDG paths and linux release
assets; mac/windows tests untouched.
Effort: ~0.5 day.

## Phase 1 — Bundle + Linux CI (the suite runs on ubuntu-latest)

- `singbox.properties`: pin `singbox.sha256.linux-amd64` (bump script gains
  the asset, same API-digest cross-check).
- `scripts/bundle-singbox.sh`: parameterize the darwin loop into
  `(os, arch, ext)` entries; on a Linux host bundle `linux-amd64` into
  `/native/linux-amd64/sing-box`.
- pom: `bundle-core-linux` profile (`<os><family>unix</family>` minus mac —
  activation via `<name>Linux</name>`), running the same `.sh` + a plain
  `write-jvm-args` variant (no `-Xdock`).
- build.yml: `test-linux` job (`ubuntu-latest`, `mvn -B clean verify
  -Psmoke`). The real-binary smoke (version/check/live-run) runs natively —
  this is the moment the whole suite first executes on Linux.
- Guarded suites: `@EnabledOnOs({MAC, LINUX})` tests (engine, installer,
  core-update) run on Linux by design — expect and fix small assumptions
  (e.g. `/usr/bin/tar` in test helpers).

Acceptance: `test-linux` green with smoke; bundling verified on the runner.
Effort: ~0.5–1 day.

## Phase 2 — Packaging: `.deb` (+ optional `.rpm`), dev-latest, releases

- `scripts/package-linux.sh`: jpackage `--type deb` from the shaded JAR
  (PNG icons already in resources; `--linux-shortcut`, menu group,
  `--linux-package-name vless-client`). `.rpm` behind a flag (runner needs
  `rpm-build`); evaluate one `--type app-image` tar.gz as the
  distro-agnostic artifact.
- build.yml: package the `.deb` on main merges (artifact), `dev-release`
  attaches DMG + MSI + DEB together.
- release.yml: `release-linux` job mirroring the other two (fat JAR →
  real-binary smoke → jpackage → release asset).
- MSI lesson applies: keep the version mapping explicit (deb versions are
  more permissive than MSI, so dev labels can pass through).

Acceptance: dev-latest carries all three installers; tagged release does too.
Effort: ~1 day.

## Phase 3 — SYSTEM_PROXY connect on Linux

- The generator already emits `set_system_proxy` per settings — **verify what
  sing-box actually does with it on Linux** (upstream implements it via the
  GNOME/`gsettings` proxy keys; KDE and others don't share that store).
  Empirical check on the runner + a GNOME VM.
- `LinuxSystemProxyGuard`: `gsettings get/set org.gnome.system.proxy` with
  the same points-at-us check (never touch a foreign proxy); no-op when
  `gsettings` is absent.
- Document the DE reality in README: GNOME gets automatic proxy; other DEs
  use TUN mode or set the proxy manually (the local listeners always work).

Acceptance: on GNOME, connect sets the proxy and disconnect restores it;
guard cleans up after a kill; non-GNOME degrades to local listeners with a
clear note.
Effort: ~0.5–1 day (research-flagged).

## Phase 4 — TUN mode + elevation on Linux

The Linux answer to sudoers-NOPASSWD is **file capabilities** — nicer than
both other platforms:

1. **Fast path: one-time setcap.** On first TUN use, run
   `pkexec setcap cap_net_admin+ep <managed sing-box>` (one PolicyKit prompt,
   ever). After that the core creates its TUN device and installs routes as a
   normal user process — plain `startDirect`, live logs, normal
   `Process.destroy()`, **no wrapper at all**.
   - Re-apply the capability after every core update/extract (hook in
     `SingBoxInstaller`/`CoreUpdateService.promote` — capability is an xattr
     lost on file replacement).
   - Probe with `getcap` (or a `sing-box check`-style dry run) to decide the
     path, mirroring `PrivilegeHelper.isConfigured`.
2. **Fallback: pkexec-per-connect.** If the user declines setcap (or the
   filesystem drops xattrs), `pkexec <wrapper.sh>` with the existing
   stop-file contract — a straight port of the mac osascript wrapper
   (`LinuxTunLauncher` shares the shell-wrapper text with
   `MacTunLauncher` where practical).
- CI: ubuntu runners have passwordless sudo → a real TUN probe *and* a real
  setcap probe are both CI-testable (auto_route off, same safety rule as the
  Windows probe).

Acceptance: TUN connect works on a real desktop (traffic through the
adapter, LAN/`.local` still direct); declining PolicyKit degrades gracefully;
CI probes green.
Effort: ~1–2 days.

## Phase 5 — Autostart, tray, close-behavior

- `LinuxAutostart`: write/remove
  `~/.config/autostart/vless-client.desktop` (`Exec=` the installed launcher
  via the `ProcessHandle` trick, java fallback for dev runs; `Hidden=false`,
  `X-GNOME-Autostart-enabled=true` for compatibility).
- Tray: AWT `SystemTray` works on KDE/XFCE/MATE; **GNOME needs an extension
  and often has no tray at all**. Today "close window" hides to tray — on a
  trayless desktop that would strand the app. Add: if
  `SystemTray.isSupported()` is false, close = quit (with the existing
  shutdown path), and the Settings "close to tray" affordance hides.
  Evaluate `dorkbox/SystemTray` later if AppIndicator support matters.
- First-run: confirm the no-network first launch (bundled core extraction to
  XDG data dir) and the logback dir property on Linux.

Acceptance: autostart survives reboot; tray works where trays exist; close
never strands the app.
Effort: ~1 day.

## Phase 6 — Updaters + docs + manual pass

- `UpdateManager.installerExtension()` → `.deb` on Linux (asset selection +
  Downloads dir already platform-routed). In-app core update needs no work
  beyond Phase 0 (`CorePlatform`-driven since #37) except the Phase 4
  re-setcap hook.
- README: supported distros/DEs, GNOME proxy note, tray note, unsigned-deb
  note (apt/gdebi warnings; repo signing is out of scope).
- Manual checklist on a real desktop (Ubuntu GNOME + one KDE distro):
  install, both connect modes, PolicyKit prompt accept/decline, autostart,
  tray/no-tray close behavior, in-app updates.

Acceptance: checklist passes on GNOME + KDE boxes.
Effort: ~0.5 day + manual time.

---

## Risks & open questions

- **`set_system_proxy` coverage on Linux** — GNOME-only upstream; other DEs
  fall back to TUN/manual. Verify early (Phase 3 is research-flagged).
- **Tray on GNOME** — may simply not exist; the close-behavior fallback is
  the mitigation, AppIndicator libraries a possible later upgrade.
- **Wayland** — JavaFX runs through XWayland; watch for scaling/tray quirks
  on fractional-scale setups.
- **Distro spread** — `.deb` covers Debian/Ubuntu; Fedora/Arch need `.rpm` /
  AUR / app-image. v1 ships deb (+ optional rpm) and documents the rest.
- **glibc floor** — bundled Temurin JRE + upstream glibc sing-box builds set
  the minimum (Ubuntu 22.04-era glibc); musl is out of scope.

## Rough total

~5–7 working days of implementation (vs. the 3–4 weeks the Windows port
took) — the platform layer, CI patterns, packaging scripts, updater routing
and the validation playbook (temp branch triggers, probe-style smoke tests)
all exist and transfer directly.

## Suggested sequencing

Sequential PRs off `main`, one per phase (no stacking — see the #29–#35
lesson). Phases 0+1 can land as one PR (small, and CI proof arrives with
Phase 1). Each phase ends with `mvn clean verify -Psmoke` green on all three
CI jobs.
