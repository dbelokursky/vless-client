#!/usr/bin/env bash
#
# Bumps the pinned sing-box release in src/main/resources/singbox.properties —
# the single source of truth for pom.xml, bundle-singbox.sh, and
# SingBoxInstaller.
#
# Usage: scripts/bump-singbox.sh <version>       (e.g. 1.13.14)
#
# For each darwin arch the script downloads the release tarball, computes its
# SHA-256 locally, and cross-checks it against the digest published by the
# GitHub Releases API. The two values travel different paths (CDN download vs
# API metadata), so a tampered or corrupted download fails the bump instead of
# getting pinned. Downloads land in the same build cache bundle-singbox.sh
# uses, so the follow-up build doesn't re-download.
#
# After a successful bump, verify before committing:
#   mvn clean verify -Psmoke
#
set -euo pipefail

VERSION="${1:?usage: $0 <version>   (e.g. 1.13.14)}"
if ! [[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "[bump-singbox] '${VERSION}' is not a plain x.y.z version" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS_FILE="${REPO_ROOT}/src/main/resources/singbox.properties"
CACHE_DIR="${HOME}/.cache/vless-client-build/sing-box-${VERSION}"
API_URL="https://api.github.com/repos/SagerNet/sing-box/releases/tags/v${VERSION}"

mkdir -p "${CACHE_DIR}"

echo "[bump-singbox] fetching release metadata: ${API_URL}"
release_json="$(curl --fail --silent --show-error \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "${API_URL}")"

# Extracts the sha256 digest the API publishes for one asset name.
api_digest_for() {
    python3 -c '
import json, sys
release = json.load(sys.stdin)
if release.get("prerelease") or release.get("draft"):
    sys.exit("refusing to pin a prerelease/draft release")
name = sys.argv[1]
for asset in release.get("assets", []):
    if asset.get("name") == name:
        digest = asset.get("digest") or ""
        if not digest.startswith("sha256:"):
            sys.exit(f"asset {name} has no sha256 digest in the API response")
        print(digest[len("sha256:"):])
        break
else:
    sys.exit(f"asset {name} not found in the release")
' "$1" <<<"${release_json}"
}

declare_line() {
    printf 'singbox.sha256.darwin-%s=%s\n' "$1" "$2"
}

sha_arm64=""
sha_amd64=""
for arch in arm64 amd64; do
    asset="sing-box-${VERSION}-darwin-${arch}.tar.gz"
    tarball="${CACHE_DIR}/${asset}"
    url="https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/${asset}"

    if [[ ! -f "${tarball}" ]]; then
        echo "[bump-singbox] downloading ${url}"
        curl --fail --silent --show-error --location --output "${tarball}.part" "${url}"
        mv "${tarball}.part" "${tarball}"
    fi

    local_sha=$(shasum -a 256 "${tarball}" | awk '{print $1}')
    api_sha=$(api_digest_for "${asset}")
    if [[ "${local_sha}" != "${api_sha}" ]]; then
        echo "[bump-singbox] SHA-256 mismatch for ${arch}:" >&2
        echo "  downloaded bytes: ${local_sha}" >&2
        echo "  GitHub API says:  ${api_sha}" >&2
        echo "  The download may be corrupted or tampered with — NOT pinning." >&2
        rm -f "${tarball}"
        exit 1
    fi
    echo "[bump-singbox] ${arch}: ${local_sha} (matches API digest)"
    if [[ "${arch}" == arm64 ]]; then sha_arm64="${local_sha}"; else sha_amd64="${local_sha}"; fi
done

# Sanity: the host-arch binary must actually run and report the version.
host_arch=$(uname -m)
case "${host_arch}" in
    arm64|aarch64) probe_arch=arm64 ;;
    *)             probe_arch=amd64 ;;
esac
probe_dir="$(mktemp -d)"
trap 'rm -rf "${probe_dir}"' EXIT
tar -xzf "${CACHE_DIR}/sing-box-${VERSION}-darwin-${probe_arch}.tar.gz" \
    -C "${probe_dir}" --strip-components=1
reported="$("${probe_dir}/sing-box" version | head -n1)"
if [[ "${reported}" != "sing-box version ${VERSION}" ]]; then
    echo "[bump-singbox] binary reports '${reported}', expected 'sing-box version ${VERSION}'" >&2
    exit 1
fi
echo "[bump-singbox] binary check OK: ${reported}"

tmp_props="$(mktemp)"
{
    # Preserve everything except the three managed keys, then append them.
    grep -vE '^[[:space:]]*singbox\.(version|sha256\.darwin-(arm64|amd64))[[:space:]]*=' \
        "${PROPS_FILE}"
    printf 'singbox.version=%s\n' "${VERSION}"
    declare_line arm64 "${sha_arm64}"
    declare_line amd64 "${sha_amd64}"
} > "${tmp_props}"
mv "${tmp_props}" "${PROPS_FILE}"

echo "[bump-singbox] pinned sing-box ${VERSION} in ${PROPS_FILE}"
echo "[bump-singbox] next: mvn clean verify -Psmoke"
