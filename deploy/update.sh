#!/usr/bin/env sh
# Full update: fetch latest infra from main, ensure edge net, pull, restart.
set -eu
BASE="https://raw.githubusercontent.com/unividuell/mobility-manager/main/deploy"

curl -fsSL "$BASE/compose.prod.yaml" -o compose.prod.yaml
curl -fsSL "$BASE/update.sh"         -o update.sh.new && chmod +x update.sh.new && mv update.sh.new update.sh

if [ ! -f .env ]; then
  curl -fsSL "$BASE/.env.example" -o .env
  echo ".env created from template — fill in MOBILITY_MANAGER_GITHUB_CLIENT_SECRET, then re-run ./update.sh"
  exit 1
fi

# SQLite data dir must be owned by the buildpack run user (uid 1002, gid 1000)
mkdir -p data
docker network create edge 2>/dev/null || true
docker compose --env-file .env -f compose.prod.yaml pull
docker compose --env-file .env -f compose.prod.yaml up -d
docker image prune -f
echo "mobility-manager update complete."
