#!/usr/bin/env bash
#
# Writes a Java argfile with the JVM options that must include literal
# spaces (commons-exec splits plain <option> values on whitespace, but
# @argfile is a single token). Invoked by exec-maven-plugin in the
# generate-resources phase.
#
# Arguments:
#   $1 — output file path (e.g. target/jvm-args.txt)
#
set -euo pipefail

OUT="${1:?usage: $0 <out_file>}"
mkdir -p "$(dirname "${OUT}")"

# The Dock/app-name options exist only on macOS — an unknown -Xdock option
# stops the JVM from starting on Linux.
if [[ "$(uname -s)" == "Darwin" ]]; then
    cat > "${OUT}" <<'ARGFILE'
-Xdock:name="VLESS Client"
-Dapple.awt.application.name="VLESS Client"
-Dcom.apple.mrj.application.apple.menu.about.name="VLESS Client"
-Dvless.log.level=DEBUG
ARGFILE
else
    cat > "${OUT}" <<'ARGFILE'
-Dvless.log.level=DEBUG
ARGFILE
fi
echo "[write-jvm-args] wrote ${OUT}"
