#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
    echo ".env already exists. Keeping existing credentials."
    exit 0
fi
umask 077
set -C
{
    for key in POSTGRES_PASSWORD PLATFORM_DB_PASSWORD PLATFORM_MIGRATION_PASSWORD PLATFORM_LOCAL_PASSWORD PLATFORM_REVIEWER_PASSWORD; do
        value=$(openssl rand -hex 24)
        printf '%s=%s\n' "$key" "$value"
    done
} > .env
echo "Created .env with local database and Studio credentials."
