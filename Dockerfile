# ── Stage 1: Angular UI ─────────────────────────────────────────────
FROM node:24-alpine AS ui-build
WORKDIR /workspace/ui
RUN corepack enable
COPY ui/package.json ui/pnpm-lock.yaml ui/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY ui/ ./
RUN pnpm build

# ── Stage 2: Spring Boot jar (UI packaged into static/) ────────────
FROM eclipse-temurin:25-jdk AS server-build
WORKDIR /workspace/server
COPY server/ ./
COPY --from=ui-build /workspace/ui/dist /workspace/ui/dist
RUN ./gradlew bootJar --no-daemon

# ── Stage 3: runtime ────────────────────────────────────────────────
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=server-build /workspace/server/build/libs/outpost-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
