# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f discovery-server/Dockerfile -t ticketing-discovery-server .

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN for i in 1 2 3; do ./mvnw -B -ntp -version && break || sleep 15; done

# Copy every module's pom so the Maven reactor can resolve; sources come later.
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY seat-service/pom.xml seat-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/
COPY loyalty-service/pom.xml loyalty-service/

# Warm the dependency cache. Layer survives until a pom changes.
RUN ./mvnw -B -pl discovery-server -am -DskipTests dependency:go-offline || true

COPY discovery-server/src discovery-server/src
RUN ./mvnw -B -pl discovery-server -am -DskipTests package \
    && cp discovery-server/target/discovery-server-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Pull in any apk security patches published since the base image was tagged.
RUN apk --no-cache upgrade \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8761

# The registry's own readiness: Spring Boot returns 200 once the Eureka server
# context is up and accepting registrations.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8761/actuator/health || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
