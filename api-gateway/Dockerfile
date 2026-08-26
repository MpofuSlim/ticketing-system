# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f api-gateway/Dockerfile -t ticketing-api-gateway .

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN for i in 1 2 3; do ./mvnw -B -ntp -version && break || sleep 15; done

COPY common/pom.xml common/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY seat-service/pom.xml seat-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/
COPY discovery-server/pom.xml discovery-server/

RUN ./mvnw -B -pl api-gateway -am -DskipTests dependency:go-offline || true

COPY api-gateway/src api-gateway/src
RUN ./mvnw -B -pl api-gateway -am -DskipTests package spring-boot:repackage \
    && cp api-gateway/target/api-gateway-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Refresh the apk index, then force each security-relevant package to a PINNED
# MINIMUM version. The floor is what makes this work: a bare `apk upgrade <pkg>`
# is a constant RUN string, so buildx replays the CACHED layer and the upgrade
# never re-runs when a new Alpine point release ships a fix — which is how
# CVE-2026-14456 shipped in every image despite openssl already being listed
# here. Bumping a floor changes the instruction (busting the cache) AND makes
# apk fail loudly if the fixed version is not reachable, instead of silently
# keeping the vulnerable one. Raise the floor when Trivy reports a new CVE.
# libssl3 / libcrypto3 / openssl >= 3.5.8-r0
#   CVE-2026-14456 (HIGH) — unbounded memory growth in QUIC server handling.
#   CVE-2026-45447 (HIGH) — PKCS#7 / S/MIME signed-message handling.
# p11-kit / p11-kit-trust >= 0.26.2-r0
#   CVE-2026-2100 (HIGH) — NULL-deref via C_DeriveKey with NULL params.
# libexpat >= 2.8.2-r0
#   CVE-2026-56131 / CVE-2026-56407 / CVE-2026-56408 (HIGH) — missing call-depth
#   tracking + integer overflows in doProlog / copyString.
RUN apk update \
 && apk add --no-cache --upgrade \
      'libssl3>=3.5.8-r0' 'libcrypto3>=3.5.8-r0' 'openssl>=3.5.8-r0' \
      'p11-kit>=0.26.2-r0' 'p11-kit-trust>=0.26.2-r0' \
      'libexpat>=2.8.2-r0' \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8080

# k8s-style probe: hits actuator/health from inside the container. Spring Boot
# returns 200 + {"status":"UP"} once the context is fully refreshed; before
# that, hits to /actuator/health return 503 and the orchestrator holds traffic.
# start-period gives the JVM + Flyway + Hibernate enough time to come up before
# failures start counting against retries.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
