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
- Use the `lb://<service-name>` URI pattern for the `uri:` (e.g.
  `lb://user-service`). The gateway resolves it from the Eureka registry via
  Spring Cloud LoadBalancer. The old `${SERVICE_URI:http://localhost:PORT}`
  env-var pattern was removed in the service-discovery migration.

## Internal endpoints — three files must agree

**An internal-only endpoint (e.g. `/events/*/availability/consume`,
`/loyalty/internal/**`, `/users/internal/**`) is only correct when ALL THREE
of these line up — adding one without the others is the recipe for either
silent 401s, an accidentally-public endpoint, or a defence-in-depth gap.**

1. **The controller** declares the mapping AND enforces the shared secret
   (`X-Internal-Token`) with a constant-time compare. Example: `EventController`'s
   `authorizedInternal()` helper, mirrored in `loyalty-service` and elsewhere.
2. **The service's `SecurityConfig`** has a `.requestMatchers(HttpMethod.X, "/path/...")
   .permitAll()` for the exact same path. Without this, Spring Security's
   `.anyRequest().authenticated()` 401s the call before the controller's token
   check ever runs — and CI catches it with cryptic "expected 200 was 401"
   failures (see PR #145's first build).
3. **The gateway's `application.yaml`** has an `*-internal-deny` /
   `event-availability-deny` route that forwards the path to
   `forward:/__edge_deny__`, BEFORE the catch-all service route, so the
   endpoint is unreachable from the public internet even though the
   controller would reject an unauthenticated call anyway.

Test assertions for these endpoints should use `.isBadRequest()` or
`.isUnauthorized()` (specific code) — never `.is4xxClientError()`, which
silently passes for a Spring-Security 401 even when the controller never
ran. That's how #145's test missed the SecurityConfig gap in local dev.

## Service discovery (Eureka)

The fleet uses client-side service discovery. The `discovery-server` module is
a standalone Netflix Eureka registry (port 8761); every other service is a
Eureka **client** and resolves siblings **by name**, not by hardcoded URL:

- The gateway routes target `lb://<service-name>`.
- `booking-service`'s `@FeignClient(name = "...")` clients carry no `url`.
- The `RestClient` / `RestTemplate` callers in payment/seat/loyalty/user/event
  use a `@LoadBalanced` builder (see `LoadBalancedRestClientConfig` /
  `HttpClientConfig`) and call `http://<service-name>`.

So **do not** reintroduce explicit `http://host:port` inter-service URLs or
`*_SERVICE_URL` / `*_SERVICE_URI` env vars. The only deliberately non-discovery
client is the external **Oradian middleware** (not in our registry) — it keeps
a plain `RestClient` + explicit URL. Tests disable discovery via
`spring.cloud.discovery.enabled: false` in the `test` / `it` profiles; keep
that when adding a new service so `@SpringBootTest` doesn't try to register.

## External-service contract tests (WireMock)

**Every client that calls an external HTTP service (Oradian middleware, the
WhatsApp gateway, the InnBucks core adapter, etc.) MUST have a WireMock-driven
contract test that pins one assertion per response shape we've observed in
production.** This is the test that fails the build when an upstream service
quietly reshapes its envelope, so a regression surfaces at PR time instead of
at 2am in prod.

Canonical example: `user-service/src/test/.../client/SmsNotificationClientContractTest.java`.

Shape to follow:

- **Pure JUnit + WireMock, no `@SpringBootTest`** — keeps each case under a
  second and surgical to the wire contract. Construct the production
  `RestClient` / `Feign` client with the same shape its config bean produces,
  just pointed at WireMock's port.
- **One test per recorded response shape**: the happy 2xx, every distinct
  non-2xx error envelope you've actually seen, AND a connect-refused / fault
  case (point a separate client at a known-closed port; do not stop/restart
  the shared WireMock — the second start gets a different dynamic port and
  breaks other tests).
- **Verify the wire contract too**, not just the client behaviour. Use
  `wireMock.verify(postRequestedFor(...)
      .withRequestBody(matchingJsonPath("$.field", equalTo("value"))))`
  so a change in the OUTBOUND payload shape (renamed field, missing header)
  also fails the test.
- **Cover the client's guard rails**: blank inputs, null defaults that get
  auto-generated (e.g. our `TKT-SMS-<uuid>` reference auto-fill) must be
  asserted with `wireMock.verify(0, postRequestedFor(...))` so a regression
  that drops the guard and starts hitting the network shows up.
- Use the standalone classifier:
  `<dependency><groupId>org.wiremock</groupId><artifactId>wiremock-standalone</artifactId>...`
  — pulls a self-contained shaded jar so the test deps don't fight with the
  project's Jetty/Jackson versions.

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

## Timestamps — store everything in UTC

The user/booking/seat/event services map timestamps as `LocalDateTime`
(loyalty/payment use `Instant`, which is always UTC). `LocalDateTime`
carries no zone, so "what instant is this" depends on the JVM default
timezone. To keep every service's timestamps comparable (and comparable
with the `Instant`/`timestamptz` services), we pin UTC two ways:

1. **Containers** — every Dockerfile's ENTRYPOINT passes
   `-Duser.timezone=UTC`, so `LocalDateTime.now()` is UTC in
   staging/prod regardless of host TZ.
2. **Code** — call `LocalDateTime.now(ZoneOffset.UTC)`, never bare
   `LocalDateTime.now()`. This keeps data correct even outside a
   container (local dev on a non-UTC laptop, a stray `java -jar`).

**Never write `LocalDateTime.now()` without the `ZoneOffset.UTC` arg.**
A bare call on a non-UTC JVM silently stores local time into a
zone-less column — the bug surfaces hours-off, days later.

The proper long-term fix (LocalDateTime → Instant + `timestamptz`
columns) is deferred: it changes the API wire format (`...T10:00:00`
gains a `Z`), so it needs FE coordination. Until then, the UTC
convention above keeps the dormant bug dormant.

## Branching

New work goes on a **`feature/<short-kebab-description>`** branch cut from the
latest `master`, where the suffix names the feature being added (e.g.
`feature/api-gateway-route-tests`). One feature per branch; push with
`git push -u origin <branch>` and open a **draft** PR.

Schema changes go in `src/main/resources/db/migration/V<N>__*.sql`
(PostgreSQL + Flyway, `ddl-auto: validate` on every data service). The
loyalty platform landed via `claude/add-loyalty-service` (merged in #91) and
the old H2 sibling branch was deleted — the legacy `claude/*` branch names
are history; use `feature/*` from now on.

## Veengu API reference — source of truth for payment integrations

**The canonical spec for every veengu-backed payment integration in this
repo is `docs/api/veengu-openapi.json`.** That file is the official
**Veengu Platform Frontend API v3.1.0** (168 endpoints, 50 tags, contact
`dev@veengu.com`, sandbox base URL `https://demo.veengu.cloud/api/`). Any
new payment, transfer, beneficiary, top-up, statement, KYC or
profile-related client we add to talk to veengu MUST be modelled against
this spec — not against ad-hoc field names from a chat or a Postman
export. If the wire shape we observe in production diverges, update the
JSON in-tree and ship the change in the same PR as the client change so
the contract test (per the WireMock convention above) is anchored to a
versioned source.

The spec is ~1 MB — too large to inline anywhere or grep usefully. Use
the snippets below (same shape as `OradianMiddleware/docs/oradian-swagger.json`)
to inspect it:

```bash
# List endpoints under a tag (try: "P2P", "Purchase", "Payout", "Top-Up account",
# "Outgoing transfer", "Direct transfer", "Cash Withdrawal", "Authentication management"):
python3 -c "
import json
spec = json.load(open('docs/api/veengu-openapi.json'))
TAG = 'P2P'
for p, ops in sorted(spec['paths'].items()):
    for m, op in ops.items():
        if m in {'get','post','put','patch','delete'} and TAG in op.get('tags', []):
            print(m.upper(), p, '->', op.get('summary'))
"

# Inspect a specific request / response schema by name:
python3 -c "
import json
spec = json.load(open('docs/api/veengu-openapi.json'))
print(json.dumps(spec['components']['schemas']['<SchemaName>'], indent=2))
"

# Dump every tag with its endpoint count (handy for orienting on a new area):
python3 -c "
import json, collections
spec = json.load(open('docs/api/veengu-openapi.json'))
c = collections.Counter()
for p, ops in spec['paths'].items():
    for m, op in ops.items():
        if m in {'get','post','put','patch','delete'}:
            for t in op.get('tags', []): c[t] += 1
for t, n in c.most_common(): print(f'{n:4d}  {t}')
"
```

As with Oradian, do NOT try to model every field of every veengu DTO —
trim aggressively to only what we actually consume on the wire, and let
the unknown fields fall through as ignored JSON. The pinned spec exists
so anyone can re-derive the shape they need without rediscovering it
from runtime traces.
