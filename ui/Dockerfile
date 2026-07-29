# ── Stage 1: Angular build ──────────────────────────────────────────
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . ./
# Path the app is served under. The default root deployment needs nothing; a
# sub-path deployment behind an ingress that does NOT strip the prefix passes
# e.g. --build-arg BASE_HREF=/outpost.
ARG BASE_HREF=/
# Normalize to leading+trailing slash ("/", "/outpost/") rather than trust the
# arg. The trailing slash is load-bearing: relative URLs resolve against the
# base href, which drops a slashless last segment. The build output is staged
# under /site in the exact shape nginx serves it, so the runtime stage copies
# from a fixed path.
RUN BASE_HREF="/${BASE_HREF#/}" && BASE_HREF="${BASE_HREF%/}/" \
 && pnpm exec ng build --base-href="${BASE_HREF}" \
 && mkdir -p "/site${BASE_HREF}" \
 && cp -r dist/outpost-ui/browser/. "/site${BASE_HREF}"

# ── Stage 2: nginx runtime ──────────────────────────────────────────
FROM nginx:1.29-alpine
# Upstream for the API proxy — substituted into nginx.conf at container start.
ENV OUTPOST_API_URL=http://outpost:8080
COPY --from=build /site /usr/share/nginx/html
COPY nginx.conf /etc/nginx/templates/default.conf.template
# Bake the serving path into the template: __BASE__ is the prefix without a
# trailing slash, empty at the root. The prefix-only redirect block would loop
# (and not parse) at the root, so drop it there. Re-declared because ARGs do
# not cross a FROM.
ARG BASE_HREF=/
RUN BASE="/${BASE_HREF#/}" && BASE="${BASE%/}" \
 && if [ -z "$BASE" ]; then \
        sed -i '/@prefix-only-start/,/@prefix-only-end/d' /etc/nginx/templates/default.conf.template; \
    fi \
 && sed -i "s|__BASE__|${BASE}|g" /etc/nginx/templates/default.conf.template
EXPOSE 80
