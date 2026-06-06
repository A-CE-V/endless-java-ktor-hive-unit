# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM gradle:8.7-jdk17 AS build
WORKDIR /app

# Copy build files FIRST so dependency downloads are cached
# as a separate Docker layer — only invalidated if deps change.
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Pre-fetch all runtime dependencies (cached layer)
RUN gradle dependencies --no-daemon --configuration runtimeClasspath 2>/dev/null || true

# Now copy source and build the fat JAR
COPY src ./src
RUN gradle shadowJar --no-daemon

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*-all.jar app.jar

EXPOSE 8080

# JVM flags tuned for Render free tier (512 MB total RAM):
#   -Xmx350m          → cap heap below OS+JVM-overhead limit
#   -XX:+UseSerialGC  → lowest GC overhead for single-threaded bursts
#   -XX:MaxMetaspaceSize=96m → cap class-metadata growth (5 heavy decompiler libs)
#   -Djava.security.egd → faster SecureRandom on Linux; cuts startup time
ENTRYPOINT ["java", "-Xms64m", "-Xmx350m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=96m", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
