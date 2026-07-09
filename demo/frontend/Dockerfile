# ── Stage 1: Angular build + debug-id injection ─────────────────────
FROM node:24-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . ./
RUN pnpm build && pnpm exec sentry-cli sourcemaps inject dist/shop-frontend/browser
# Served bundles must NOT expose the maps; the full injected dist (js + maps)
# is kept aside for the entrypoint's optional self-upload.
RUN cp -r dist/shop-frontend/browser /sourcemaps \
    && find dist/shop-frontend/browser -name '*.map' -delete

# ── Stage 2: nginx runtime with sentry-cli for self-upload ─────────
FROM nginx:alpine
COPY --from=getsentry/sentry-cli:2 /bin/sentry-cli /usr/local/bin/sentry-cli
COPY --from=build /workspace/dist/shop-frontend/browser /usr/share/nginx/html
COPY --from=build /sourcemaps /sourcemaps
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint-demo.sh
RUN chmod +x /docker-entrypoint-demo.sh
EXPOSE 80
ENTRYPOINT ["/docker-entrypoint-demo.sh"]
