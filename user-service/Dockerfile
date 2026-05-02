# syntax=docker/dockerfile:1.7
# Build from repo root:
#     docker build -f user-service/Dockerfile -t ticketing-user-service .

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Copy every module's pom so the Maven reactor can resolve; sources come later.
COPY api-gateway/pom.xml api-gateway/
COPY user-service/pom.xml user-service/
COPY event-service/pom.xml event-service/
COPY seat-service/pom.xml seat-service/
COPY booking-service/pom.xml booking-service/
COPY payment-service/pom.xml payment-service/

# Warm the dependency cache. Layer survives until a pom changes.
RUN ./mvnw -B -pl user-service -am -DskipTests dependency:go-offline || true

COPY user-service/src user-service/src
RUN ./mvnw -B -pl user-service -am -DskipTests package \
    && cp user-service/target/user-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# Pull in any apk security patches published since the base image was tagged.
# Trivy fails the build on HIGH/CRITICAL CVEs (see .github/workflows/docker.yml),
# and the floating eclipse-temurin:21-*-alpine tag isn't always rebuilt promptly.
RUN apk --no-cache upgrade \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/app.jar /app/app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
