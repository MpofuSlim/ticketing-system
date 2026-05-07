# Ticketing System — Claude memory

Project-wide instructions for Claude when working in this repo.

## API gateway routing

**Whenever you add a new HTTP endpoint to any backend service, you MUST also
add a corresponding route to the API gateway** at
`api-gateway/src/main/resources/application.yaml`.

The gateway does NOT use a catch-all "forward any path to the matching
service" rule — it has explicit `Path=/<prefix>/**` routes per service.
Anything not matched falls through to the default static-resource handler
and returns a 404 (`NoResourceFoundException`). So adding an endpoint at,
say, `/users/me/foo` in user-service is a no-op for clients calling through
the gateway unless `/users/**` is routed.

When adding a route:

- Mirror the predicate prefix from the controller's `@RequestMapping`.
- Apply the same `RequestRateLimiter` filter and `key-resolver` as the
  other authenticated routes (look at `user-admin-route` for the canonical
  shape) — except for `/auth/**`-style public endpoints that intentionally
  skip the rate limiter.
- Use the existing `${SERVICE_URI:http://localhost:PORT}` env-var pattern
  for the `uri:`.
- Add the route on **both** active branches when the feature lives on both
  (currently `claude/add-loyalty-service` and `claude/add-loyalty-service-h2`).

## Active branches

- `claude/add-loyalty-service` — PostgreSQL + Flyway. Schema changes go in
  `src/main/resources/db/migration/V<N>__*.sql`.
- `claude/add-loyalty-service-h2` — H2 file-based, Flyway disabled,
  Hibernate `ddl-auto=update` manages the schema. Migration files committed
  here are dormant; safe to keep in sync with the Postgres branch.

When changes apply to both branches, develop on `claude/add-loyalty-service`
first, then cherry-pick onto `claude/add-loyalty-service-h2`.
