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
cat > "${OUT}" <<'ARGFILE'
-Xdock:name="VLESS Client"
-Dapple.awt.application.name="VLESS Client"
-Dcom.apple.mrj.application.apple.menu.about.name="VLESS Client"
ARGFILE
echo "[write-jvm-args] wrote ${OUT}"
