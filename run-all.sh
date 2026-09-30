#!/usr/bin/env bash
# Run the ticketing stack from Spring Boot fat jars on a single host (e.g. EC2),
# with Postgres / Redis / Kafka provided separately (Docker or native).
#
# Build the jars first (the repackage goal is required — this repo uses the
# import-BOM pattern, so `mvn package` alone produces non-executable thin jars):
#     ./mvnw clean package spring-boot:repackage -Dmaven.test.skip=true
#
# Then run from a working dir that contains:
#     ./jars/*.jar     the 7 service jars (copy them from */target/)
#     ./.env           secrets + DB/Redis/Kafka config (see .env.example)
#
# Host prerequisites: Java 21; PostgreSQL with the 6 *_service databases;
# Redis; Kafka (booking + payment only); ~6-8 GB RAM for all 7 JVMs.
#
# Siblings are found by NAME through the static discovery map at the end of
# each application.yaml (http://user-service:8081, ...). Outside k8s those
# names must resolve to this host, so either run with the `local` profile
# (which maps them to localhost, but also relaxes the production-secrets
# guard) or alias them in /etc/hosts:
#     127.0.0.1 user-service event-service seat-service booking-service payment-service loyalty-service marketplace-service loans-service
#
# Usage:  ./run-all.sh start | stop
set -euo pipefail

JAR_DIR="${JAR_DIR:-./jars}"
LOG_DIR="${LOG_DIR:-./logs}"
JAVA_OPTS="${JAVA_OPTS:--Duser.timezone=UTC -Xms256m -Xmx512m}"
mkdir -p "$LOG_DIR"

# Export every var in .env so each `java -jar` inherits the config.
if [ -f .env ]; then set -a; . ./.env; set +a; fi

start_one() { # name jar
  local name="$1" jar="$2"
  echo ">> starting $name"
  nohup java $JAVA_OPTS -jar "$JAR_DIR/$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
}

wait_health() { # name port
  local name="$1" port="$2" tries=90
  echo -n "   waiting for $name :$port "
  until curl -fs "http://localhost:$port/actuator/health" >/dev/null 2>&1; do
    tries=$((tries-1)); [ "$tries" -le 0 ] && { echo " TIMEOUT (see $LOG_DIR/$name.log)"; return 0; }
    echo -n "."; sleep 2
  done
  echo " UP"
}

start_all() {
  # 1) Backend services.
  start_one user-service    user-service-1.0.0.jar
  start_one event-service   event-service-1.0.0.jar
  start_one seat-service    seat-service-1.0.0.jar
  start_one booking-service booking-service-1.0.0.jar
  start_one payment-service payment-service-1.0.0.jar
  start_one loyalty-service loyalty-service-1.0.0.jar
  wait_health user-service 8081
  # 2) Gateway last -- it resolves the services by name (discovery map).
  start_one api-gateway api-gateway-1.0.0.jar
  wait_health api-gateway 8080
  echo "All started. Gateway -> http://localhost:8080  (logs in $LOG_DIR/)"
}

stop_all() {
  for pid in "$LOG_DIR"/*.pid; do
    [ -f "$pid" ] || continue
    kill "$(cat "$pid")" 2>/dev/null && echo "stopped $(basename "$pid" .pid)" || true
    rm -f "$pid"
  done
}

case "${1:-start}" in
  start) start_all ;;
  stop)  stop_all ;;
  *) echo "usage: $0 {start|stop}"; exit 1 ;;
esac
