# ---------------------------------------------------------------------------
# Build Stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Cache Maven wrapper and dependencies
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# Compile and package application jar
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package

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
    mkdir -p /data /tmp/trustvault && \
    chown -R trustvault:trustvault /app /data /tmp/trustvault

COPY --from=build --chown=trustvault:trustvault /app/target/*.jar /app/app.jar

USER trustvault:trustvault

ENV STORAGE_LOCATION=/data \
    SESSION_COOKIE_SECURE=true \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp/trustvault"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
