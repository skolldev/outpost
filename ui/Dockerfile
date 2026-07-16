# ── Stage 1: Angular build ──────────────────────────────────────────
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . ./
# Base path the app is served under. If changed, the html/outpost target
# below and the /outpost/ literals in nginx.conf must change with it.
ARG BASE_HREF=/outpost/
# The trailing slash is load-bearing (relative URL resolution against the base
# href drops a slashless last segment) — normalize rather than trust the arg.
RUN BASE_HREF="${BASE_HREF%/}/" && pnpm exec ng build --base-href="${BASE_HREF}"

# ── Stage 2: nginx runtime ──────────────────────────────────────────
FROM nginx:1.29-alpine
# Upstream for /outpost/api/ — substituted into nginx.conf at container start.
ENV OUTPOST_API_URL=http://outpost:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/outpost-ui/browser /usr/share/nginx/html/outpost
EXPOSE 80
