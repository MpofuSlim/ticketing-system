# Innbucks Ticketing System

A microservices-based online ticketing platform built with Spring Boot 4 and
Spring Cloud Gateway. Each service owns its own database; services find each
other through a Eureka registry, communicate over REST, and publish domain
events to Kafka.

## Services

| Service            | Port | Responsibility                                                              |
|--------------------|------|-----------------------------------------------------------------------------|
| `api-gateway`      | 8080 | Public entry point. Routing, CORS, Redis-backed rate limiting.              |
| `discovery-server` | 8761 | Netflix Eureka registry. Every service registers here and resolves siblings by name. |
| `user-service`     | 8081 | Auth: registration, login, JWT issuance, OTP, token revocation; admin & shop-staff users. |
| `event-service`    | 8082 | Event catalogue and tenant-scoped event admin.                              |
| `seat-service`     | 8083 | Seat inventory, categories, optimistic-locked holds.                        |
| `booking-service`  | 8084 | Booking creation, idempotency, payment hand-off; publishes Kafka domain events. |
| `payment-service`  | 8085 | Booking payments + wallet transfers/withdrawals via the Oradian middleware. Opt-in (`payments` profile). |
| `loyalty-service`  | 8086 | Loyalty & merchant platform: merchants, shops, vouchers, invoices, QR, points. |

Shared infrastructure: PostgreSQL 16 (one database per service, schema owned by
Flyway migrations), Redis 7 (distributed locks, idempotency, and the gateway's
rate-limit buckets), and Kafka (single-node KRaft) for booking domain events.

## Quick start

Prerequisites: Docker, Docker Compose, JDK 21 (only if running services
outside containers).

```bash
cp .env.example .env
# Generate a JWT secret and paste it into .env
openssl rand -base64 48
# Then fill in the other required secrets (see Configuration / .env.example):
# POSTGRES_PASSWORD, INTERNAL_API_TOKEN, ORADIAN_INTERNAL_TOKEN, WHATSAPP_API_KEY

docker compose up --build
```

The gateway is then reachable at <http://localhost:8080> and aggregated
Swagger UI at <http://localhost:8080/swagger-ui/index.html>.

To start the optional payment service:

```bash
docker compose --profile payments up
```

Stop and wipe all data:

```bash
docker compose down -v
```

## Configuration

All configuration is environment-driven. Copy `.env.example` to `.env` and
fill in real values. Required:

| Variable                | Purpose                                                                 |
|-------------------------|-------------------------------------------------------------------------|
| `JWT_SECRET`            | HS256 signing key. Must be ≥32 chars; shared across services.           |
| `POSTGRES_PASSWORD`     | Password for the Postgres container. Required by docker-compose.        |
| `INTERNAL_API_TOKEN`    | Shared secret for service-to-service calls (loyalty webhooks, event/booking internal endpoints). |
| `ORADIAN_INTERNAL_TOKEN`| `X-Internal-Token` sent to the Oradian middleware; must match its `INTERNAL_API_TOKEN`. |
| `WHATSAPP_API_KEY`      | API key for the WhatsApp notification gateway (OTP + payment confirmations). |
| `POSTGRES_USER`         | Postgres user. Defaults to `postgres`.                                  |
| `CORS_ALLOWED_ORIGINS`  | Origins allowed by the gateway and services. **Set this to your exact frontend origins in staging/prod** — the default is a dev-oriented Vercel/localhost pattern. |

`SPRING_PROFILES_ACTIVE` defaults to `prod,json` in the deployed stack: `prod`
activates the production secrets guard (boot fails on placeholder secrets) and
`json` switches logging to structured JSON. Use `dev` for local human-readable
logs and relaxed secrets.

Per-service overrides (`DB_URL`, `DB_POOL_MAX`, `FEIGN_*_TIMEOUT_MS`, etc.)
live in each service's `application.yaml` and can be overridden via env vars.

## Local development without Docker

Bring up Postgres (and Redis if you set `LOCK_STORE=redis` or
`IDEMPOTENCY_STORE=redis`) before running a service from your IDE:

```bash
docker compose up -d postgres redis
./mvnw -pl seat-service spring-boot:run
```

`DB_PASSWORD` (and `DB_USERNAME` if not `postgres`) must be exported in
the shell — see `scripts/dev-env.sh` for a helper that loads `.env`.

Unit tests use in-memory H2 in PostgreSQL compatibility mode; the integration
tests (`*IT`) spin up a real Postgres via Testcontainers, so Docker must be
running for those:

```bash
./mvnw test     # unit tests only (H2)
./mvnw verify   # + integration tests (Testcontainers Postgres — needs Docker)
```

## Observability

Each service exposes Spring Boot Actuator endpoints:

- `GET /actuator/health` — liveness/readiness probes (always public)
- `GET /actuator/health` with details — visible to authenticated callers only
- `GET /actuator/info` — build/info metadata (public)
- `GET /actuator/prometheus` — Micrometer metrics in Prometheus format
  (configure your scraper with the expected credential)

All metrics carry an `application` tag set to `spring.application.name` so
dashboards can slice by service. A `prometheus/` directory holds the scrape
config, alert rules, and an SLO doc.

- **Tracing** — Micrometer Tracing with the OpenTelemetry bridge is wired but
  **off by default**. Set `TRACING_ENABLED=true` (and an OTLP endpoint) to
  export spans.
- **Logging** — human-readable in dev; **structured JSON** (Logstash encoder)
  in deployed profiles (`json`), so Loki/ELK/CloudWatch can ingest. Every
  request carries a correlation ID, propagated across services and into the
  log pattern (`[service,traceId,spanId,correlationId]`). Errors ship to
  Sentry when `SENTRY_DSN` is set.

## Resilience

Inter-service calls are wrapped in Resilience4j circuit breakers with
exponential-backoff retries:

- `booking-service` → `seat-service` (Feign): breaker `SeatServiceClient`,
  fallback raises `SeatServiceUnavailableException` (mapped to 503).
- `event-service` → `seat-service` (RestTemplate): breaker `seatCategories`,
  fallback returns an empty category list so events stay viewable.

Defaults: 50% failure-rate threshold over 20 calls (min 10), 30s open
window, 3 retries with 500ms initial wait and 2× backoff.

The api-gateway applies a Redis-backed token bucket rate limit
(`RequestRateLimiter`) to every route. Bucket key is the bearer token if
present, otherwise the client IP. Defaults: 50 req/s sustained,
100 burst — overridable via `RATE_LIMIT_REPLENISH_PER_SECOND` and
`RATE_LIMIT_BURST_CAPACITY`. Over-budget requests get HTTP 429 with
`X-RateLimit-Remaining` and `Retry-After` headers.

## Domain events (Kafka)

`booking-service` publishes domain events as a side-effect of its existing
flows; consumers can subscribe incrementally. Topics:

| Topic                | When                              | Key         |
|----------------------|-----------------------------------|-------------|
| `booking.created`    | `POST /bookings` succeeds         | `bookingId` |
| `booking.confirmed`  | `confirmBooking` succeeds         | `bookingId` |
| `booking.cancelled`  | `cancelBooking` succeeds          | `bookingId` |

Topic names are env-overridable (`BOOKING_CREATED_TOPIC`,
`BOOKING_CONFIRMED_TOPIC`, `BOOKING_CANCELLED_TOPIC`). Events are emitted
via `ApplicationEventPublisher` inside the transaction and forwarded to
Kafka by a `@TransactionalEventListener(AFTER_COMMIT)`, so a rolled-back
booking never produces a ghost event. Producer config: `acks=all` plus
idempotence; payload is JSON.

The local Kafka broker runs in single-node KRaft mode (no Zookeeper).
Inside the Docker network it's reachable as `kafka:9092`; from your host
it's `localhost:29092` for tools like `kafkacat`.

## Service discovery (Eureka)

The fleet uses **client-side service discovery**. `discovery-server` is a
standalone Netflix Eureka registry (port 8761); every other service registers
on boot and resolves its siblings **by name**, never by a hardcoded host:port:

- Gateway routes target `lb://<service-name>` (resolved via Spring Cloud
  LoadBalancer).
- `booking-service` uses `@FeignClient(name = "...")` with no `url`.
- `payment`/`seat`/`loyalty`/`user`/`event` call `http://<service-name>` through
  a `@LoadBalanced` `RestClient`/`RestTemplate`.

The only deliberately non-discovery client is the external **Oradian
middleware** (not in our registry) — a plain `RestClient` with an explicit
URL. Tests disable discovery via `spring.cloud.discovery.enabled: false` in the
`test`/`it` profiles.

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

- `ci.yml` — runs `./mvnw verify` (Surefire unit tests + Failsafe `*IT`
  integration tests on Testcontainers Postgres) on `master` and the active
  development branches (`feature/**`, `claude/**`, `develop`, `release/**`,
  `hotfix/**`).
- `release.yml` — on push to `master` (and tags): builds each service image,
  scans it with Trivy (CRITICAL/HIGH fixable vulns fail the build), pushes
  `sha-<commit>`-tagged images to GHCR, then deploys to the EC2 host by pulling
  those exact images and running `docker compose up` (never `:latest`). Pull
  requests build and scan only — no push, no deploy.
- `dependabot-auto-merge.yml` — auto-merges green Dependabot patch/minor bumps.
- `qodana_code_quality.yml` — JetBrains Qodana static analysis. Quality gates
  in `qodana.yaml` enforce ≥50% total / ≥70% fresh coverage and cap problems at
  15 total / 5 critical.

## Project layout

```
.
├── api-gateway/           Spring Cloud Gateway (WebFlux)
├── discovery-server/      Netflix Eureka registry
├── user-service/          Auth + JWT + OTP
├── event-service/         Event catalogue
├── seat-service/          Seat inventory + holds
├── booking-service/       Booking + idempotency + Kafka events
├── payment-service/       Booking payments + Oradian wallet transfers (opt-in)
├── loyalty-service/       Loyalty + merchant platform
├── prometheus/            Scrape config, alert rules, SLO doc
├── scripts/               dev-env / deploy / backup helpers
├── docker/                Postgres init script
├── docker-compose.yml     Local dev + EC2 deploy orchestration
├── pom.xml                Multi-module Maven parent
└── .env.example           Template for local secrets
```

## Production readiness

This codebase is suitable for staging but **not** yet fully hardened for
production. See the staging-readiness scorecard for the full picture. Notable
remaining gaps:

- No in-stack TLS termination — terminate at the ingress / reverse proxy.
- No Kubernetes manifests or Helm chart — deployment is Docker Compose on EC2
  (`release.yml` + `scripts/deploy.sh`); the same compose file serves local dev
  and the EC2 stack, pinned to `sha-<commit>` images in deployment.
- CORS defaults to a dev-oriented Vercel/localhost pattern — set
  `CORS_ALLOWED_ORIGINS` to exact origins before exposing staging.
- No API versioning; not every list endpoint is paginated.
- `scripts/backup-postgres.sh` takes a basic dump; wire it to a schedule +
  offsite copy for real durability (no WAL archiving yet).
