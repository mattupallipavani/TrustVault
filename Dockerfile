# ---------------------------------------------------------------------------
# Build Stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Cache Maven wrapper and dependencies
ENV MAVEN_OPTS="-Xmx512m -XX:+UseSerialGC"
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:resolve

# Compile and package application jar
COPY src/ src/
RUN ./mvnw -B -DskipTests package

# ---------------------------------------------------------------------------
# Runtime Stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install curl for container health check
RUN apk add --no-cache curl

# Create non-root system group and user
RUN addgroup -S -g 10001 trustvault && \
    adduser -S -u 10001 -G trustvault -h /app trustvault && \
    mkdir -p /data /app/trustvault-storage && \
    chown -R trustvault:trustvault /app /data /app/trustvault-storage

COPY --from=build --chown=trustvault:trustvault /app/target/*.jar /app/app.jar

USER trustvault:trustvault

ENV STORAGE_LOCATION=/data \
    SESSION_COOKIE_SECURE=true \
    JAVA_OPTS="-XX:MaxRAMPercentage=55.0 -XX:+UseSerialGC -Xss512k -XX:MetaspaceSize=96m -XX:MaxMetaspaceSize=128m -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -f http://localhost:${PORT:-8080}/actuator/health || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "mkdir -p /data /app/trustvault-storage 2>/dev/null || true; exec java $JAVA_OPTS -jar /app/app.jar"]
