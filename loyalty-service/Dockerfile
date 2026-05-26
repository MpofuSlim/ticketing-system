# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f loyalty-service/Dockerfile -t ticketing-loyalty-service .

FROM eclipse-temurin:21-jdk-alpine AS builder
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
COPY discovery-server/pom.xml discovery-server/
COPY loyalty-service/pom.xml loyalty-service/

RUN ./mvnw -B -pl loyalty-service -am -DskipTests dependency:go-offline || true

COPY loyalty-service/src loyalty-service/src
RUN ./mvnw -B -pl loyalty-service -am -DskipTests package \
    && cp loyalty-service/target/loyalty-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Pull in the latest patched alpine packages on every rebuild so CVEs
# fixed upstream (e.g. CVE-2026-33845 in gnutls) close as soon as the
# alpine repo publishes them, without waiting for a base-image retag.
RUN apk --no-cache upgrade
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data \
    && chown -R app:app /app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8086

# k8s-style probe: hits actuator/health from inside the container. Spring Boot
# returns 200 + {"status":"UP"} once the context is fully refreshed; before
# that, hits to /actuator/health return 503 and the orchestrator holds traffic.
# start-period gives the JVM + Flyway + Hibernate enough time to come up before
# failures start counting against retries.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8086/actuator/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
