#!/usr/bin/env bash
# Prints the demo projects' DSNs (project ids are DB-assigned, so they differ
# per installation). With --write-env, upserts DEMO_FRONTEND_PROJECT_ID and
# DEMO_BACKEND_PROJECT_ID into the repo-root .env so the compose demo profile
# picks them up.
#
# Usage: demo/scripts/print-dsns.sh [--write-env]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PUBLIC_HOST="${OUTPOST_PUBLIC_HOST:-localhost:8080}"

rows="$(cd "$REPO_ROOT" && docker compose exec -T db psql -U outpost -d outpost -Atc \
  "SELECT p.slug, p.id, k.public_key FROM project p
   JOIN project_key k ON k.project_id = p.id AND k.is_active
   WHERE p.slug IN ('shop-frontend','shop-backend') ORDER BY p.slug")"

if [ -z "$rows" ]; then
  echo "No demo projects found — run demo/scripts/demo-setup.sql first:" >&2
  echo "  docker compose exec -T db psql -U outpost -d outpost < demo/scripts/demo-setup.sql" >&2
  exit 1
fi

fe_id=""
be_id=""
while IFS='|' read -r slug id key; do
  echo "$slug (project $id): http://$key@$PUBLIC_HOST/$id"
  case "$slug" in
    shop-frontend) fe_id="$id" ;;
    shop-backend)  be_id="$id" ;;
  esac
done <<< "$rows"

if [ "${1:-}" = "--write-env" ]; then
  env_file="$REPO_ROOT/.env"
  touch "$env_file"
  for pair in "DEMO_FRONTEND_PROJECT_ID=$fe_id" "DEMO_BACKEND_PROJECT_ID=$be_id"; do
    name="${pair%%=*}"
    if grep -q "^$name=" "$env_file"; then
      sed -i.bak "s|^$name=.*|$pair|" "$env_file" && rm -f "$env_file.bak"
    else
      echo "$pair" >> "$env_file"
    fi
  done
  echo "Wrote DEMO_FRONTEND_PROJECT_ID=$fe_id and DEMO_BACKEND_PROJECT_ID=$be_id to $env_file"
fi
