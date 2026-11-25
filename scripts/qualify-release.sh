#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 RELEASE_DIRECTORY SIGNING_KEY_FINGERPRINT" >&2
  exit 2
fi
release_dir=$1
signer=$2
case "$signer" in
  *[!0123456789ABCDEFabcdef]*|'') echo "Supply a full GPG fingerprint" >&2; exit 2 ;;
esac
case ${#signer} in
  40|64) ;;
  *) echo "Supply a full GPG fingerprint" >&2; exit 2 ;;
esac
command -v trivy >/dev/null || { echo "Install Trivy with its vulnerability databases" >&2; exit 1; }
command -v gpg >/dev/null || { echo "Install GnuPG for release signing" >&2; exit 1; }

cd "$release_dir"
shasum -a 256 -c SHA256SUMS
for sbom in maven-sbom.json studio-sbom.json; do
  trivy sbom --offline-scan --skip-db-update --skip-java-db-update --severity HIGH,CRITICAL --exit-code 1 "$sbom"
done
gpg --armor --local-user "$signer" --detach-sign --output SHA256SUMS.asc SHA256SUMS
gpg --verify SHA256SUMS.asc SHA256SUMS
