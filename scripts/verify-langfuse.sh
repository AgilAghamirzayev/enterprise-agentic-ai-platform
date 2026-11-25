#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/init-local.sh
./mvnw --batch-mode --no-transfer-progress -DskipTests package
./scripts/langfuse.sh up --build -d --wait
./scripts/langfuse.sh --profile verification run --rm --build --no-deps lf-test
