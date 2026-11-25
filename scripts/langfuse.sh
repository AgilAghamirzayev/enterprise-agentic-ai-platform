#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
config_dir=${XDG_STATE_HOME:-"$HOME/.local/state"}/agent-platform/langfuse
settings="$config_dir/local.conf"

if [ ! -f "$settings" ]; then
    umask 077
    mkdir -p "$config_dir"
    temp=$(mktemp "$config_dir/bootstrap.XXXXXX")
    trap 'rm -f "$temp"' EXIT
    {
        for name in LF_POSTGRES LF_REDIS LF_CLICKHOUSE LF_STORAGE LF_SALT LF_ENCRYPTION LF_SESSION LF_ADMIN_PASSWORD; do
            printf '%s=%s\n' "$name" "$(openssl rand -hex 32)"
        done
        printf 'LF_PUBLIC_KEY=pk-lf-%s\n' "$(openssl rand -hex 16)"
        printf 'LF_SECRET_KEY=sk-lf-%s\n' "$(openssl rand -hex 32)"
    } > "$temp"
    # Publish a complete configuration even if two terminals initialize together.
    ln "$temp" "$settings" || test -f "$settings"
    rm -f "$temp"
fi
set -a
. "$settings"
set +a
exec docker compose -f compose.yaml -f deployments/langfuse/compose.yaml "$@"
