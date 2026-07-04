#!/usr/bin/env bash
#
# Builds the macOS DMG from the shaded JAR via jpackage. Shared by the two
# CI paths so they can never drift: build.yml packages every merge to main
# (uploaded as a workflow artifact), release.yml packages tagged releases.
#
# Usage:
#   scripts/package-dmg.sh <app-version-label> [cfbundle-version]
#
#   app-version-label — human-readable version passed to the app via
#                       -Dapp.version (e.g. "0.2.0" or "0.1.0-dev-abc1234")
#   cfbundle-version  — numeric x.y.z for macOS CFBundleVersion. Defaults to
#                       the label with a zero major normalised to 1
#                       ("The first number in an app-version cannot be zero
#                       or negative"), which only works for plain x.y.z
#                       labels — dev builds must pass this explicitly.
#
# Expects `mvn package` to have produced the shaded JAR already.
#
set -euo pipefail

VERSION="${1:?usage: $0 <app-version-label> [cfbundle-version]}"
MAC_VERSION="${2:-$(echo "${VERSION}" | sed 's/^0\./1./')}"

if ! [[ "${MAC_VERSION}" =~ ^[1-9][0-9]*(\.[0-9]+){0,2}$ ]]; then
    echo "[package-dmg] CFBundle version '${MAC_VERSION}' is not a valid" >&2
    echo "  numeric x.y.z with a non-zero major — pass it explicitly as \$2" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

JAR_NAME="vless-client-0.1.0-SNAPSHOT.jar"
if [[ ! -f "target/${JAR_NAME}" ]]; then
    echo "[package-dmg] missing target/${JAR_NAME} — run 'mvn package' first" >&2
    exit 1
fi

# Stage just the shaded jar (not the original-*.jar the shade plugin
# leaves alongside it).
rm -rf staging dist
mkdir -p staging
cp "target/${JAR_NAME}" staging/

jpackage \
    --type dmg \
    --name "VLESS Client" \
    --app-version "${MAC_VERSION}" \
    --input staging \
    --main-jar "${JAR_NAME}" \
    --main-class com.vlessclient.app.Launcher \
    --icon src/main/resources/icons/app-icon.icns \
    --dest dist \
    --mac-package-name "VLESSClient" \
    --java-options "--enable-preview" \
    --java-options "-Dapp.version=${VERSION}" \
    --verbose

echo "[package-dmg] built: $(ls dist/*.dmg) (app-version=${VERSION}, CFBundleVersion=${MAC_VERSION})"
