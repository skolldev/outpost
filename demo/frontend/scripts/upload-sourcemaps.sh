#!/usr/bin/env bash
# Inject debug IDs into the built bundles and upload the source maps to Outpost.
#
# Prereqs:
#   - a production build in dist/shop-frontend/browser (pnpm build)
#   - SENTRY_AUTH_TOKEN: an Outpost API token with artifacts:write scope
#     (Outpost UI → Settings → API tokens, or POST /api/internal/tokens)
#
# IMPORTANT: inject rewrites the bundles in place — always serve the SAME dist
# that was injected + uploaded, or the debug IDs won't match (that's what
# `pnpm demo:prod` chains together).
set -euo pipefail

: "${SENTRY_AUTH_TOKEN:?Set SENTRY_AUTH_TOKEN to an Outpost API token with artifacts:write scope}"
OUTPOST_URL="${OUTPOST_URL:-http://localhost:8080}"
DIST="${DIST:-dist/shop-frontend/browser}"

if [ ! -d "$DIST" ]; then
  echo "No build at $DIST — run 'pnpm build' first" >&2
  exit 1
fi

pnpm exec sentry-cli sourcemaps inject "$DIST"
# Outpost ignores the org slug; matching happens by injected debug_id, so no
# release flag is needed either.
pnpm exec sentry-cli --url "$OUTPOST_URL" --auth-token "$SENTRY_AUTH_TOKEN" \
  sourcemaps upload --org outpost --project shop-frontend "$DIST"

echo "Source maps uploaded to $OUTPOST_URL"
