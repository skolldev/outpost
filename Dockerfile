# Outpost as a single image: the Angular bundle is compiled into the Spring Boot
# jar, which serves it (dev.outpost.config.WebConfig). The UI and the server are
# not independently deployable — ui/src/app/core/models.ts mirrors the server's
# DTOs field for field, so a version skew between them is a broken app rather
# than a degraded one. One artifact makes that coupling structural.
#
# Build context is the repo root: `docker build -t outpost .`
#
# The app is served at the host root only. Sub-path deployments are not
# supported — put the reverse proxy at a subdomain instead.

# ── Stage 1: Angular bundle ─────────────────────────────────────────
FROM node:25-alpine AS ui
WORKDIR /ui
RUN corepack enable
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY ui/ ./
RUN pnpm exec ng build

# ── Stage 2: Spring Boot jar, bundle included ───────────────────────
FROM eclipse-temurin:25-jdk AS server
WORKDIR /workspace
COPY server/ ./
# Staged before bootJar so the bundle lands in BOOT-INF/classes/static, where
# WebConfig's classpath:/static/ handlers find it.
COPY --from=ui /ui/dist/outpost-ui/browser/ src/main/resources/static/
RUN ./gradlew bootJar --no-daemon

# ── Stage 3: runtime ────────────────────────────────────────────────
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=server /workspace/build/libs/outpost-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
