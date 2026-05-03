# Innbucks Ticketing System

A microservices-based online ticketing platform built with Spring Boot 4 and
Spring Cloud Gateway. Each service owns its own Postgres schema and
communicates over REST.

## Services

| Service           | Port | Responsibility                                          |
|-------------------|------|---------------------------------------------------------|
| `api-gateway`     | 8080 | Public entry point. Routes traffic, terminates CORS.    |
| `user-service`    | 8081 | Registration, login, JWT issuance, OTP, token revocation. |
| `event-service`   | 8082 | Event catalogue and tenant-scoped event admin.          |
| `seat-service`    | 8083 | Seat inventory, categories, optimistic-locked holds.    |
| `booking-service` | 8084 | Booking creation, idempotency, payment hand-off.        |
| `payment-service` | 8085 | Payment integration (Stripe). Off by default; opt-in.   |
| `loyalty-service` | 8086 | Multi-tenant loyalty + voucher management (LVMP).       |

Shared infrastructure: PostgreSQL 16 (one DB per service) and Redis 7
(distributed locks + idempotency).

## Quick start

Prerequisites: Docker, Docker Compose, JDK 21 (only if running services
outside containers).

```bash
cp .env.example .env
# Generate a JWT secret and paste it into .env
openssl rand -base64 48

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

| Variable               | Purpose                                             |
|------------------------|-----------------------------------------------------|
| `JWT_SECRET`           | HS256 signing key. Must be ≥32 chars; share across services. |
| `POSTGRES_USER`        | Postgres user (dev default: `innbucks`).            |
| `POSTGRES_PASSWORD`    | Postgres password (dev default: `innbucks`).        |
| `LOYALTY_VOUCHER_SECRET` | HMAC key for voucher code signatures. Generate with `openssl rand -base64 48`. |
| `LOYALTY_QR_SECRET`    | HMAC key for QR token signatures. Independent from the voucher key. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list of origins allowed by the gateway and event-service. Defaults to `http://localhost:3000`. |

Per-service overrides (`DB_URL`, `DB_POOL_MAX`, `FEIGN_*_TIMEOUT_MS`, etc.)
live in each service's `application.yaml` and can be overridden via env vars.

## Local development without Docker

Bring up only the data stores, then run the service of your choice from
your IDE or Maven:

```bash
docker compose up -d postgres redis
./mvnw -pl seat-service spring-boot:run
```

Tests use in-memory H2 and do not need Postgres:

```bash
./mvnw test
```

## Observability

Each service exposes Spring Boot Actuator endpoints:

- `GET /actuator/health` — liveness/readiness probes (always public)
- `GET /actuator/health` with details — visible to authenticated callers only
- `GET /actuator/info` — build/info metadata (public)
- `GET /actuator/prometheus` — Micrometer metrics in Prometheus format
  (authenticated; configure your scraper with a JWT)

All metrics carry an `application` tag set to `spring.application.name` so
dashboards can slice by service.

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

## Service discovery

There is none. Inter-service URLs are explicit and resolved by Docker
Compose / Kubernetes DNS (`http://seat-service:8083`, etc.). Eureka was
previously wired but disabled; it has been removed entirely in favour of
the simpler DNS approach.

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

- `ci.yml` — runs `mvn test` on every PR/push to `master`.
- `docker.yml` — builds each service image, scans it with Trivy
  (CRITICAL/HIGH fixable vulns fail the build), uploads SARIF to the
  Security tab, and pushes to GHCR on merges to `master` or version tags.
- `qodana_code_quality.yml` — JetBrains Qodana static analysis. Quality
  gates in `qodana.yaml` enforce ≥50% total / ≥70% fresh coverage and cap
  problems at 15 total / 5 critical.

## Project layout

```
.
├── api-gateway/           Spring Cloud Gateway (WebFlux)
├── user-service/          Auth + JWT
├── event-service/         Event catalogue
├── seat-service/          Seat inventory + holds
├── booking-service/       Booking + idempotency
├── payment-service/       Stripe integration (opt-in)
├── docker/                Postgres init script
├── docker-compose.yml     Local dev orchestration
├── pom.xml                Multi-module Maven parent
└── .env.example           Template for local secrets
```

## Production readiness

This codebase is suitable for staging but **not** yet hardened for
production. Notable gaps:

- No TLS termination configured (delegate to ingress / reverse proxy)
- No Kubernetes manifests or Helm chart
- No distributed tracing (OpenTelemetry not wired up)
- No Postgres backup / WAL archive strategy
- No API versioning or pagination on list endpoints
- Docker Compose is for local dev only; do not deploy it to production

See the production-readiness scorecard for the full picture.
