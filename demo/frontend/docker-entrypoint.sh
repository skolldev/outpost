#!/bin/sh
# Templates the SPA's runtime config from env vars, optionally self-uploads the
# source maps that were injected at image build time, then starts nginx.
set -eu

HTML=/usr/share/nginx/html

cat > "$HTML/config.json" << EOF
{
  "sentryDsn": "${DEMO_SENTRY_DSN:-http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@localhost:8080/1}",
  "environment": "${DEMO_SENTRY_ENVIRONMENT:-dev}",
  "release": "${DEMO_SENTRY_RELEASE:-shop-frontend@1.5.0}",
  "apiBase": "${DEMO_API_BASE:-http://localhost:8081}"
}
EOF
echo "config.json written: $(cat "$HTML/config.json")"

# The bundles in this image were debug-id-injected at build time; the matching
# maps live in /sourcemaps. With a token we can upload them so stacktraces from
# this exact image symbolicate — no host-side sentry-cli needed.
if [ -n "${SENTRY_AUTH_TOKEN:-}" ]; then
  OUTPOST="${OUTPOST_INTERNAL_URL:-http://outpost:8080}"
  # Outpost may still be starting; the upload needs it ready.
  tries=0
  until wget -q -T 2 -O /dev/null "$OUTPOST/readyz" 2>/dev/null || [ "$tries" -ge 30 ]; do
    tries=$((tries + 1))
    sleep 2
  done
  echo "Uploading source maps to $OUTPOST ..."
  sentry-cli --url "$OUTPOST" --auth-token "$SENTRY_AUTH_TOKEN" \
    sourcemaps upload --org outpost --project shop-frontend /sourcemaps \
    || echo "WARNING: source map upload failed — stacktraces will show minified frames"
else
  echo "SENTRY_AUTH_TOKEN not set — skipping source map upload (frontend stacktraces stay minified)"
fi

exec nginx -g 'daemon off;'
