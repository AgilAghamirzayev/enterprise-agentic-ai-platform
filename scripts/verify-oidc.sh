#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
stack() { docker compose -f compose.yaml -f deployments/verification/oidc-compose.yaml --profile verification "$@"; }
# Remove only the three disposable verification services.
trap 'stack rm --stop --force oidc-studio oidc-api oidc-fixture >/dev/null' EXIT
stack up --build -d --wait worker oidc-studio
stack build oidc-browser
stack run --rm --no-deps oidc-browser
