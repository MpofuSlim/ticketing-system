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

## Swagger response examples

**Every endpoint you add or modify MUST have meaningful `@ApiResponses` with
`@ExampleObject` bodies — never leave the default springdoc placeholder
(`additionalProp1/2/3: string`) for clients to read.**

Concretely:

- Document the success response (200/201) AND the realistic failure shapes
  (typically 400, 401/403, 404) for every endpoint.
- Each `@ExampleObject` body must use the project's standard `ApiResult`
  envelope: `{ "code": "...", "message": "...", "data": ... }`. Match the
  shape of `ApiResult.ok` / `ApiResult.created` / `ApiResult.error`.
- **Cross-endpoint consistency on the same controller**: if `POST /foo`
  creates an entity, the `GET /foo` and `GET /foo/{id}` examples should
  show records that look like what the POST returned — same IDs, same
  field values. A reader should be able to run the POSTs in order and
  see exactly those records appear in the GETs.
- Failure bodies must use real messages thrown by the service code, not
  placeholders. Grep for the exception's `message` text to confirm.
- Look at `MerchantController` and `ShopController` (loyalty-service) and
  `ShopStaffController` (user-service) for the canonical shape — copy
  that style for new controllers.

## Active branch

- `claude/add-loyalty-service` — the only working branch. PostgreSQL +
  Flyway. Schema changes go in `src/main/resources/db/migration/V<N>__*.sql`.

The previous `claude/add-loyalty-service-h2` sibling (H2 file-based,
Flyway disabled, `ddl-auto=update`) has been deleted. Commits no longer
need to be cherry-picked anywhere; develop, commit, push once.
