#!/usr/bin/env bash
#
# Downloads and bundles the sing-box binary for both macOS architectures into
# the Maven build output directory. Invoked by the exec-maven-plugin during
# the generate-resources phase.
#
# Arguments:
#   $1 — output directory (e.g. target/classes/native)
#   $2 — sing-box version (e.g. 1.13.8)
#
# Reuses a ~/.cache/vless-client-build/sing-box-<version> directory so repeated
# builds don't re-download. SHA-256 checksums are verified against known-good
# values.
#
set -euo pipefail

OUT_DIR="${1:?usage: $0 <out_dir> <version>}"
VERSION="${2:?usage: $0 <out_dir> <version>}"

CACHE_DIR="${HOME}/.cache/vless-client-build/sing-box-${VERSION}"
mkdir -p "${CACHE_DIR}" "${OUT_DIR}"

# Known-good checksums. Keep in sync with SingBoxInstaller.EXPECTED_SHA256.
# Using a case statement instead of an associative array for compatibility
# with the default /bin/bash 3.2 shipped on macOS.
expected_sha_for() {
    case "$1" in
        arm64) echo "e9e4c72a4a64c19d515b800b7191c50367522c8169654c569677b15873e08249" ;;
        amd64) echo "0db6aca503dcdd5a816e668669e79231f991cdbbd13fcbf6dd4f9bcb8a1c3b0e" ;;
        *) echo ""; return 1 ;;
    esac
}

for arch in arm64 amd64; do
    target_dir="${OUT_DIR}/darwin-${arch}"
    target_binary="${target_dir}/sing-box"

    if [[ -x "${target_binary}" ]]; then
        echo "[bundle-singbox] already present: ${target_binary}"
        continue
    fi

    tarball="${CACHE_DIR}/sing-box-${VERSION}-darwin-${arch}.tar.gz"
    if [[ ! -f "${tarball}" ]]; then
        url="https://github.com/SagerNet/sing-box/releases/download/v${VERSION}/sing-box-${VERSION}-darwin-${arch}.tar.gz"
        echo "[bundle-singbox] downloading ${url}"
        curl --fail --silent --show-error --location --output "${tarball}.part" "${url}"
        mv "${tarball}.part" "${tarball}"
    fi

    expected=$(expected_sha_for "${arch}")
    actual=$(shasum -a 256 "${tarball}" | awk '{print $1}')
    if [[ "${actual}" != "${expected}" ]]; then
        echo "[bundle-singbox] SHA-256 mismatch for ${arch}:" >&2
        echo "  expected ${expected}" >&2
        echo "  got      ${actual}" >&2
        rm -f "${tarball}"
        exit 1
    fi

    mkdir -p "${target_dir}"
    tar -xzf "${tarball}" -C "${target_dir}" \
        --strip-components=1 \
        "sing-box-${VERSION}-darwin-${arch}/sing-box"
    chmod +x "${target_binary}"
    echo "[bundle-singbox] bundled ${target_binary}"
done
