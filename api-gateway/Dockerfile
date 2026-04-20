# syntax=docker/dockerfile:1.7
#
# Build context expected to be the repository root so the Maven reactor
# (parent pom + all child poms) is visible to mvnw.
# Build from repo root:
#     docker build -f api-gateway/Dockerfile -t ticketing-api-gateway .

# ---------- build stage ----------
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

# Warm the dependency cache. This layer is reused across rebuilds until a pom changes.
RUN ./mvnw -B -pl api-gateway -am -DskipTests dependency:go-offline || true

COPY api-gateway/src api-gateway/src
RUN ./mvnw -B -pl api-gateway -am -DskipTests package

# Extract Spring Boot layered jar so the runtime stage can COPY layers individually.
RUN java -Djarmode=tools -jar api-gateway/target/*.jar extract --layers --destination /workspace/extracted

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
USER app

COPY --from=builder --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
