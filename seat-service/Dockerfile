# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f seat-service/Dockerfile -t ticketing-seat-service .

FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY seat-service/pom.xml seat-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/
COPY loyalty-service/pom.xml loyalty-service/

RUN ./mvnw -B -pl seat-service -am -DskipTests dependency:go-offline || true

COPY seat-service/src seat-service/src
RUN ./mvnw -B -pl seat-service -am -DskipTests package \
    && cp seat-service/target/seat-service-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
# Pull in any apk security patches published since the base image was tagged.
# Trivy fails the build on HIGH/CRITICAL CVEs (see .github/workflows/docker.yml),
# and the floating eclipse-temurin:21-*-alpine tag isn't always rebuilt promptly.
RUN apk --no-cache upgrade \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8083

# k8s-style probe: hits actuator/health from inside the container. Spring Boot
# returns 200 + {"status":"UP"} once the context is fully refreshed; before
# that, hits to /actuator/health return 503 and the orchestrator holds traffic.
# start-period gives the JVM + Flyway + Hibernate enough time to come up before
# failures start counting against retries.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
