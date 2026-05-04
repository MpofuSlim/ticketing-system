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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
