#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./mvnw --batch-mode --no-transfer-progress verify
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
./scripts/init-local.sh
docker compose config --quiet
docker compose up --build -d --wait
docker compose --profile verification run --no-deps --rm smoke
docker compose --profile verification build browser-test
docker compose --profile verification run --no-deps --rm browser-test
./scripts/verify-oidc.sh
