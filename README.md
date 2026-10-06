# Innbucks Ticketing System

A microservices-based online ticketing platform built with Spring Boot 4 and
Spring Cloud Gateway. Each service owns its own database; services find each
other by Kubernetes Service name and communicate over REST.

## Services

| Service            | Port | Responsibility                                                              |
|--------------------|------|-----------------------------------------------------------------------------|
| `api-gateway`      | 8080 | Public entry point. Routing, CORS, Redis-backed rate limiting.              |
| `user-service`     | 8081 | Auth: registration, login, JWT issuance, OTP, token revocation; admin & shop-staff users. |
| `event-service`    | 8082 | Event catalogue and tenant-scoped event admin.                              |
| `seat-service`     | 8083 | Seat inventory, categories, optimistic-locked holds.                        |
| `booking-service`  | 8084 | Booking creation, idempotency, payment hand-off. |
| `payment-service`  | 8085 | Booking/order payments — InnBucks 2D code + ZimSwitch card rails. Opt-in (`payments` profile). |
| `loyalty-service`  | 8086 | Loyalty & merchant platform: merchants, shops, vouchers, invoices, QR, points. |
| `marketplace-service` | 8087 | Marketplace: listings, catalogue, cart, orders (repo `MpofuSlim/market-place`). |
| `loans-service`    | 8088 | Lending API at `/lending/**`, with its own sign-in (repo `MpofuSlim/innbucks-loans`; staging only). |

Shared infrastructure: PostgreSQL 16 (one database per service, schema owned by
Flyway migrations), Redis 7 (distributed locks, idempotency, and the gateway's
rate-limit buckets).

## Quick start

Prerequisites: Docker, Docker Compose, JDK 21 (only if running services
outside containers).

```bash
cp .env.example .env
# Generate a JWT secret and paste it into .env
openssl rand -base64 48
# Then fill in the other required secrets (see Configuration / .env.example):
# POSTGRES_PASSWORD, INTERNAL_API_TOKEN, WHATSAPP_API_KEY

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

- **Tracing** — Micrometer Tracing with the OpenTelemetry bridge: trace and
  span ids are always generated, propagated as W3C `traceparent` (gateway →
  services, Feign, RestClient/RestTemplate, async executors — never to
  partners) and printed in every log line. **Export is off by default**; set
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to a collector to ship spans, sampled at
  `TRACING_SAMPLING_PROBABILITY` (0.1). See CLAUDE.md "Tracing and compression".
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

## Domain events (in-process)

`booking-service` and `payment-service` publish domain events via Spring's
`ApplicationEventPublisher` and consume them with
`@TransactionalEventListener` — **in-process only, no message broker**. This
drives the side-effects that fire only after the owning transaction commits
(e.g. the booking confirmation / cancellation notifications, the payment
confirmation WhatsApp), so a rolled-back transaction never produces a ghost
side-effect.

> A Kafka bus was previously wired as a producer-only broker (nothing consumed
> the topics; loyalty earn ran on a synchronous Feign + DB-retry path instead).
> It was removed to shed the unused broker and its per-cell cost. If a real
> event-consumer use case lands, reintroduce a broker deliberately with actual
> consumers rather than a publish-only bus.

## Service discovery (Kubernetes Service DNS)

Services resolve their siblings **by name**, never by a hardcoded host:port,
and the name is the sibling's Kubernetes `Service`:

- Gateway routes target `lb://<service-name>`, `booking-service` uses
  `@FeignClient(name = "...")` with no `url`, and `payment`/`seat`/`user`/`event`
  call `http://<service-name>` through a `@LoadBalanced` `RestClient`.
- Spring Cloud LoadBalancer resolves each name through a static map at the end
  of every service's `application.yaml`
  (`spring.cloud.discovery.client.simple.instances`), pointing at
  `http://<name>:<port>` — the k8s Service, which routes only to ready pods and
  balances across replicas. A `local` profile maps the same names to
  `localhost`. `FleetServiceMapTest` keeps every copy identical and every port
  equal to its k8s Service.
- The Eureka registry is being retired: it only mapped each name to the same
  Service name. Per-request balancing across replicas and in-cluster mTLS are
  planned via the Linkerd service mesh, with no change to this map.

The deliberately non-discovery clients are the external payment/notification
providers (InnBucks, ZimSwitch, the WhatsApp gateway) — plain `RestClient`s
with explicit URLs. Tests disable discovery via
`spring.cloud.discovery.enabled: false` in the `test`/`it` profiles.

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
├── user-service/          Auth + JWT + OTP
├── event-service/         Event catalogue
├── seat-service/          Seat inventory + holds
├── booking-service/       Booking + idempotency
├── payment-service/       Booking/order payments — InnBucks code + card (opt-in)
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
