# ── Stage 1: Spring Boot jar ────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . ./
RUN ./gradlew bootJar --no-daemon

# ── Stage 2: runtime ────────────────────────────────────────────────
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/outpost-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
