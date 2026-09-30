# Ticketing System — Claude memory

Project-wide instructions for Claude when working in this repo.

> [!IMPORTANT]
> **Branch naming: always `feature/<short-kebab-description>`, cut from `master`.**
> NEVER commit or push feature work on the session's auto-assigned
> `claude/<random-words>` branch (e.g. `claude/happy-archimedes-4zz7fc`) — that
> name is a harness default, not our convention, and must never appear on a PR.
> Create the `feature/*` branch first. Full rules under [Branching](#branching).

## Frontend integration docs on merge (standing)

> [!IMPORTANT]
> **Every time a PR that adds or changes a frontend-facing HTTP surface merges to
> `master`, automatically produce a frontend integration Markdown guide for that
> change and deliver it to the user in-session (`SendUserFile`) — without being
> asked, in the same session that observes the merge.** This holds in every
> session, not just the one that wrote the code. **Skip only when an equivalent
> guide for the same change already exists** (the user made it, or one was
> produced earlier for that surface).

- **"Frontend-facing"** = a new endpoint, or a changed request/response shape,
  auth, headers, status/error codes, or client-visible behaviour. Pure
  backend/infra/schema/CI/test/doc changes with **no** client surface need no
  guide — say so briefly instead of inventing one.
- **Shape** — mirror the guides already shared with the FE (the
  `*-Frontend-Integration.md` deliverables: My-Tickets, Redemption,
  ShopUser-Bulk-Upload): base URL + auth + required headers (note when a call
  needs `X-Tenant-Id` and when it doesn't), each endpoint with request/response
  JSON, error handling split into top-level vs per-row/field, realistic
  request examples, and a gotchas checklist. Anchor every field to the merged
  code, not memory.
- The redemption guide also covers the staging `/loyalty/public/**` surface and
  its planned `x-api-key` gating — keep new guides consistent with that
  authenticated-public-for-staging posture where relevant.

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
  `lb://user-service`). Spring Cloud LoadBalancer resolves it through the
  static discovery map (see [Service discovery](#service-discovery--kubernetes-service-dns-eureka-retired)).
  A NEW service also needs one line in that map in every service, or its
  `lb://` route is a "No servers available" 503 — `FleetServiceMapTest` fails
  the build first.

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

## Service discovery — Kubernetes Service DNS (Eureka retired)

**A sibling is found by its Kubernetes Service name, and the mapping from name
to address is a static block in every service's `application.yaml`.** The
Eureka registry (`discovery-server`) added nothing on k8s: every service
registered under its own Service name (`EUREKA_INSTANCE_HOSTNAME=<svc>`), so
Eureka answered "where is `event-service`?" with `event-service:8082` — the
name k8s DNS already resolves — while costing two JVMs, a password in every
pod's env, an image to patch (its FreeMarker CVE red-lined Release), and a
registry-fetch window after every restart. k8s Services also drop an unready
pod the moment its readiness probe fails, where Eureka took up to ~90s.

How it is wired — **callers are unchanged**, only the resolver moved:

- The gateway routes still target `lb://<service-name>`; booking-service's
  `@FeignClient(name = "...")` clients still carry no `url`; the `@LoadBalanced`
  `RestClient`s (`LoadBalancedRestClientConfig` / `HttpClientConfig`) still call
  `http://<service-name>`. Spring Cloud LoadBalancer resolves every one of them.
- The resolver is Spring Cloud's static `SimpleDiscoveryClient` (and its
  reactive twin for the gateway), fed by
  `spring.cloud.discovery.client.simple.instances.<name>[0].uri =
  http://<name>:<port>` — a separate YAML document at the END of every
  service's `application.yaml`. A second document under the `local` profile
  maps the same names to `localhost` for running jars directly.
- `spring-cloud-starter-loadbalancer` **and Apache `httpclient5`** are declared
  explicitly in each pom; both used to arrive only transitively through
  `eureka-client`. The second one is the trap: Spring Boot picks the request
  factory for every `RestClient`/`RestTemplate` that does not set one from
  what is on the classpath, so losing `httpclient5` silently moved the
  notification clients (and anything else on the default) onto the JDK
  `HttpClient`, which negotiates HTTP/2 — their WireMock contract tests failed
  with `RST_STREAM`. Removing a dependency is not config-only: diff the
  runtime `dependency:list` against master before calling it that.
- **The map is identical in every service and each port equals its k8s
  Service port.** `FleetServiceMapTest` (api-gateway) enforces both, plus: every
  `lb://` route and every `http://<x>-service` address in main code has an
  entry, no service configures Eureka, and the YAML binds into the real
  `SimpleDiscoveryClient`. It exists because each of those failures is silent
  until the call is made in the cell (503 "No servers available", or
  connection refused).
- **Adding a service** = its k8s `Service`, plus one line in every copy of the
  map (and the gateway route). Replicas need nothing: the Service balances
  across ready pods.

**Multiple replicas and the mesh.** A ClusterIP Service balances per
CONNECTION (kube-proxy, L4), and pooled keep-alive connections stick to one
pod, so with several replicas the load is uneven. The planned answer is
**Linkerd**: its proxy balances per REQUEST across ready pods, adds retries,
and gives in-cluster mTLS (closing the A02 "In-cluster TLS/mTLS" deferral
below) — with no change to this map, because callers keep using the same
Service names. Do not reintroduce a registry or client-side pod discovery to
get per-request balancing; the mesh is where that lives.

**Replicas also need every `@Scheduled` job to be safe on N pods at once** —
the full per-service checklist is `deploy/k8s/LINKERD_RUNBOOK.md` §8. The rule:
a job either carries `@SchedulerLock` or has a written reason it is
idempotent. payment-service locks all six of its jobs over a
`KeepAliveLockProvider` (a slow resolution pass keeps its lock for as long as
it runs; a dead holder frees it within `lockAtMostFor`), and
`SchedulerLockTest` fails the build on an unlocked `@Scheduled` method, a
duplicate lock name or a `lockAtMostFor` under 30s — the keep-alive throws on
those at LOCK time, every tick, so the sweep would silently never run. Never
lock a job that tends per-pod memory (`LoginRateLimiter.purgeFallbackWindows`):
it has to run on every replica.

So **do not** reintroduce explicit `http://host:port` inter-service URLs in
code or `*_SERVICE_URL` / `*_SERVICE_URI` env vars — the map is the one place
addresses live. The only deliberately non-discovery clients are the external
payment/notification providers (InnBucks, ZimSwitch, the WhatsApp gateway),
which keep a plain `RestClient` + explicit URL. Tests disable discovery via
`spring.cloud.discovery.enabled: false` in the `test` / `it` profiles; keep
that in a new service's test profiles.

**`discovery-server` is deleted** — module, `02-discovery.yaml`, every
`EUREKA_*` key (cell env and Deployments) and its Release matrix entry — once
all eight services (these six, InnRewards' `loyalty-service`, market-place's
`marketplace-service`) ran the Service-DNS build. Two things a deletion like
this does NOT do on its own, worth knowing for the next one: **`kubectl apply`
never deletes an object whose manifest was removed** (the running Deployments
and Services had to be deleted by hand), and **the Deployments' `EUREKA_*`
`configMapKeyRef`/`secretKeyRef` entries had to go BEFORE the keys left the
cell ConfigMap/Secret** — a pod referencing a missing key is
`CreateContainerConfigError`, not a warning. The FreeMarker CVE override went
with it: nothing else in the reactor pulls FreeMarker.

## External-service contract tests (WireMock)

**Every client that calls an external HTTP service (the
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

## Roles are DATA, permissions are CODE (user-service V35)

**An operator creates roles at runtime (`POST /admin/roles`); permissions are a
fixed vocabulary defined in `PermissionCatalog.java` and can only be added by a
code change.** That asymmetry is the whole design, not an unfinished half of it:

- A permission is real only because a `@PreAuthorize` names it. One invented at
  runtime would be a string nothing consults — exactly the inert-label trap
  `PRODUCT_OFFICER` sat in for months ("no `@PreAuthorize` names them, so a
  holder is authorized for exactly what a role-less account is"). So there is
  **no `POST /admin/permissions`, deliberately** — don't add one.
- A role is just a named bundle of permissions that already exist and are
  already enforced, so composing one is useful the instant it is saved.

Concretely, when you add a permission: add the constant to `PermissionCatalog`
as `entry(code, description, Scope)` — **the `Scope` (`PLATFORM` / `TENANT`) is a
required argument, there is no default** — use it in the `@PreAuthorize`, and
**grant it to the built-in roles that should hold it in a migration**.
`PermissionCatalogInitializer` upserts the catalog into the `permissions` table
at boot, so the permission itself needs no migration — only the grants do. **But
the initializer runs AFTER Flyway**, so a migration that grants a code the table
may not hold yet (any code added since V35 — the device-security ones, for
example) must `INSERT INTO permissions … ON CONFLICT DO NOTHING` first, or the
`role_permissions` foreign key fails the migration (V43 does this). The catalog
listing is `GET /admin/roles/permissions` (it lives under `/admin/roles` so one
gateway route covers the feature). **Never enumerate permissions for
`SUPER_ADMIN`**: it holds the `*` wildcard, which `PermissionResolver` expands
against the live catalog at token-mint time. Enumerating would silently lock the
platform owner out of every endpoint added afterwards, presenting as a
mysterious 403 that reads like a bug in the new endpoint.

Other load-bearing details:

- **`User.roles` is `Set<String>`, not the `User.Role` enum.** The enum survives
  as a constants holder for the built-ins (V35's nine plus V43's three), so code says
  `Role.SUPER_ADMIN.name()` rather than a literal and a typo fails the build.
  `user_roles.role` was always `VARCHAR(255)` with no CHECK (V3; V22 dropped a
  stray one), so this needed no migration. Don't add a role constant to the enum
  for a new operator-facing role — create it through the API. Add one only when
  this service's code must name it.
- **`UserAdminService.setRoles` validates names against the `roles` table, and
  that check is load-bearing.** It did not need to exist before: Jackson's enum
  binding used to 400 an unknown name before the controller ran. Now nothing
  upstream does, so without it `EVENT_ORGANISER` would persist happily and
  present as a user who authenticates fine and is refused everywhere.
- **Migrating a check from `hasRole` to `hasAuthority` is NOT behaviour-neutral
  on its own** — this is the trap that red-lined the first CI run of #542.
  Tokens already in users' browsers carry `roles` but no `perms` claim, so the
  moment a migrated build rolls out, every logged-in admin 403s on every
  migrated endpoint until their session turns over. `JwtFilter.permissionsFor`
  is the bridge: when `perms` is absent it re-derives permissions from the roles
  claim through the same `PermissionResolver`, so it grants no more than a fresh
  token would and stops firing as sessions turn over. **Keep it until every
  service is migrated and no pre-`perms` token can still be in flight.**
  `JwtFilterPermissionBackfillTest` fails if it is removed.
- **A test authenticating as `@WithMockUser(roles = "SUPER_ADMIN")` models a
  caller that can no longer exist** — the role with no permissions is exactly
  the in-flight-token shape above. Use `@WithSuperAdmin`, whose factory reads
  `PermissionCatalog.concrete()` so it cannot drift when a permission is added.
- **Roles and permissions become authorities in different namespaces** —
  `ROLE_<UPPER_SNAKE>` vs the bare, lowercase, colon-namespaced permission code.
  They cannot collide, and `PermissionResolverTest` pins that. Both are granted
  by `JwtFilter` because the `hasRole` → `hasAuthority` migration runs one
  service at a time.
- **Only user-service's own checks are migrated.** booking/event/seat still use
  `hasRole`, so a custom role grants nothing there yet. Worse and more subtle:
  `ShopStaffService` and `TeamMemberService` keep **service-layer** built-in-role
  guards, so a custom role holding `shop-admins:write` or `team-members:write`
  passes `@PreAuthorize` and is then refused inside the service. Those handlers
  derive the caller's merchant/shop scope from the role, so making them
  role-agnostic is a design change, not a rename. Both sites carry a NOTE.
- Deleting a role any account still holds is refused (409), and built-ins can
  never be deleted or renamed — code references them by literal name, so a
  rename would stop matching silently rather than fail loudly. Their permissions
  *are* editable; that is the supported way to change what a built-in can do.
- `ROLE_CREATED` / `ROLE_PERMISSIONS_CHANGED` / `ROLE_DELETED` — and
  `USER_ROLES_CHANGED` — go through the tamper-evident audit chain, because "who
  could do what, when" is no longer answerable from the code once roles are data.
  **They are REQUIRED audits** (`AuditService.recordRequired`, V43's PR): if the
  row cannot be written the change is refused with `503 audit_unavailable` and
  rolls back. The call is the transaction's LAST statement, after a flush, so the
  window in which a committed audit row can describe a rolled-back change is just
  the outer commit. Every other audit stays best-effort (a login is never broken
  by the audit path); every failure of either kind moves
  `security.audit.write_failed{mode}` (alert `AuditWriteFailed`).

### No escalation: nobody hands out more than they hold (user-service V43)

**Before this, `users:roles:write` could put any role on anyone — the caller
included — and `roles:write` could add any code to any role, including one the
caller held.** The endpoint permission said who may administer roles; nothing
bounded what they could hand out. `RoleGrantGuard` now does, and every rule
reads the caller's **LIVE** authority (their current roles through
`PermissionResolver`), never the token's `perms` — a token minted before a
release that added a code would otherwise refuse the platform owner a code they
hold. A caller that does not resolve to an active account is refused every
grant and every target check outright — including a role or account carrying no
permission, where "holds everything it holds" would be vacuously true.

- **Scope is code.** `PLATFORM` = acts across every business (`users:*`,
  `roles:*`, `service-requests:*`, `organizations:read`, `device-security:*`, and
  every future `staff:*` / `support-*`); `TENANT` = inside the caller's own
  business (`team-members:*`, `shop-*`). **A code the catalog does not define
  classifies as PLATFORM** (fails closed) — a stale `role_permissions` row can
  never be what lets a role out of the staff rules. `PermissionCatalogScopeTest`.
- **Codes reserved to the wildcard** (`PermissionCatalog.WILDCARD_RESERVED`):
  `roles:write`, `users:roles:write`, `staff:read`, `staff:create`, `staff:manage`,
  `organizations:manage`. **Never grantable through the API — to any role, custom
  or built-in, even by SUPER_ADMIN** (400 `permission_not_assignable`, reason
  `reserved_to_super_admin`). SUPER_ADMIN holds them through `*`; only a reviewed
  migration could grant one to a built-in, and none does. A role an earlier
  release let hold one keeps it (the pre-deploy query flags them), and removing
  codes is never refused on authority or reserved-code grounds (a role must still
  keep at least one code — that is validation). **Such a legacy role can be put
  on an account by SUPER_ADMIN only** (400 `role_not_assignable`,
  `reserved_to_super_admin`) — otherwise its holders could spread `roles:write` /
  `users:roles:write` through `PUT /admin/users/{id}/roles`. A new code that
  hands out authority itself belongs in this set.
- **A staff role** (`StaffRoles`) is one whose name is in `StaffRoles.NAMED`
  (`SUPER_ADMIN`, `PRODUCT_OFFICER`, `PRODUCT_MANAGER`, `CALL_CENTER_AGENT`,
  `CALL_CENTER_SUPERVISOR`, `FRAUD_DESK`), or that holds `*`, or any PLATFORM
  code. The NAME half exists because booking and event grant cross-organizer
  access by role name, so a NAMED role is staff even with its permissions
  emptied; the permission half because a custom role is staff by what it can do.
  The V35 business built-ins are not staff. `StaffRoleClassificationTest`.
- **The rules:** adding a code to a role (create or edit) needs the caller to
  hold it (`exceeds_your_authority`); adding a role to an account needs the
  caller to hold everything it grants, and a NAMED role also needs the caller to
  hold that role or `*` (400 `role_not_assignable`, `data.roles` = name → reason);
  **removing** a role from an account, **deactivating a staff-role holder**, and
  **resetting anyone's 2FA** need the caller to hold everything the target holds
  AND every NAMED role it holds, or `*` (403 `target_not_manageable`, `reason:
  exceeds_your_authority`). **The NAME half is load-bearing on both sides**:
  `PRODUCT_MANAGER` resolves to one read-only code here while its authority is
  its name in event and booking, so comparing codes alone let a custom role that
  covered that one code strip, switch off or reset the 2FA of a product manager
  it could never have appointed. Business accounts are not gated on deactivation
  — their authority reaches one business. Only ADDED roles/codes are checked;
  what an account or role already holds is not.
- **The two sides are compared differently, on purpose.** The caller's
  authority is what their roles EFFECTIVELY grant (a stale code grants them
  nothing); what they hand out or act against is read as STORED, so a stale code
  on a role or account counts as one the caller lacks and only `*` covers it.
  Dropping unknown codes before the comparison would have been the one place an
  unknown code failed open.
- **Removing a PLATFORM code from a role signs every holder out at once** — one
  atomic `UPDATE users … WHERE id IN (SELECT user_id FROM user_roles WHERE role =
  :name) RETURNING …` through `TokenVersionBumper.bumpAllHolding` (still the one
  writer of `token_version`), the new versions published after commit through
  ONE synchronization, as pipelined `EVAL`s 500 per round trip
  (`TokenVersionPublisher.publishAll`) — never one synchronous Redis call per
  holder on the request thread. **It is sized by the ROLE's holders**: a PLATFORM
  or stale code removed from `MERCHANT_ADMIN` signs every business out at once.
  Above 500 holders it logs a WARN and counts `user.tokenver.bulk_bump.large`.
  **Removing only TENANT codes does not bump**: the change reaches holders at
  their next refresh (≤ 15 minutes), so trimming MERCHANT_ADMIN's shop codes does
  not sign every business out. Adding codes never bumps (and `perms: []` tokens
  pick an addition up on their next request).
- **Reserved role names:** `ADMIN` (never a platform role — V3 rewrote it to
  SUPER_ADMIN, nothing names it), a bare `CALL_CENTER`, and any `CALL_CENTRE…`
  spelling are refused by `POST /admin/roles` (400). The spelling is `CALL_CENTER`
  fleet-wide.
- **The customer-support built-ins (V43):** `CALL_CENTER_AGENT` and
  `CALL_CENTER_SUPERVISOR` hold `device-security:read` + `:manage`; `FRAUD_DESK`
  (an add-on, held with one of them) holds `:read` + `:fraud`. **V43 FAILS rather
  than adopt** a same-named `roles` row or orphan `user_roles` string — adopting
  would merge an operator's grants into an undeletable built-in and hand
  device-security authority to unchecked holders, at once for `perms: []`
  tokens. A later grant migration must follow the same rule and only target
  these built-ins. **Never add `CALL_CENTER_*` to booking/event
  `PLATFORM_STAFF_ROLES`, and never name a support role in a `hasRole()`**:
  support reaches customer data only through user-service endpoints that check a
  permission.
- **Support permissions enforced OUTSIDE user-service (V47).**
  - **The codes:**
    - `marketplace-support:read|manage|supervise`;
    - `loyalty-support:read|manage|supervise`;
    - `customer-messages:send`.
  - **Where they are checked:**
    - marketplace-service (`/marketplace/support/**`) and loyalty-service (`/loyalty/support/**`) read the token's `perms` claim and gate on `hasAuthority`.
    - Nothing in user-service checks them. They live in `PermissionCatalog` only because it is the fleet's one vocabulary, and `role_permissions` can reference nothing else.
    - "Support reaches customer data only through endpoints that check a permission" now covers those two services too. They still never name a support role in a `hasRole()`.
  - **The grants:**
    - AGENT: `read` + `manage` for both products, plus `customer-messages:send`.
    - SUPERVISOR: the agent's grants plus both `supervise` codes.
    - FRAUD_DESK: nothing new (it is an add-on).
    - SUPER_ADMIN: all of them through `*`.
  - **Marketplace dispute DECISIONS stay with SUPER_ADMIN/finance** (owner's call, 2026-09-30). Resolving a dispute as REFUND records a transfer reference, i.e. asserts money already left. So V43's "dispute decisions" for the supervisor does not reach marketplace until a decide-then-pay step exists.
  - **A new code grants nothing until the other service ships its endpoint.** A grant is also picked up only at the agent's next sign-in or refresh: a grant does not bump `tokenVersion`.
  - **Deploy order:** either repo can deploy first. The support endpoints simply 403 until tokens carry the codes.
- **Staff reset their password by EMAIL only.** `PasswordResetService` makes both
  phone steps no-ops (the generic 200 / "Invalid or expired code") for any
  staff-role holder — a phone on a staff account is a takeover path.
- **Every session-issuing response carries `permissions`** (the list exactly as
  minted into `perms`, wildcard expanded) — login, 2FA step, refresh,
  organization switch, exchange, enrolment-complete. Gate console screens on it,
  not on role names. `AuthResponsePermissionsFieldTest`.
- `ShopStaffService` / `TeamMemberService` service-layer built-in-role guards are
  deliberately untouched by any of this.

## The gate-operator 2FA exemption, and the refresh hole it left open

`MfaPolicy.gateOperatorExempt` — gate staff scanning tickets on a shared
handset are never challenged for a second factor. The policy itself is
documented at length in the class javadoc; the two things worth repeating
here are the shape of the key and the hole it opened one level up:

- **The exemption is keyed on exact set equality `{TEAM_MEMBER}` AND on the
  account resolving to ZERO permissions** — not on "holds TEAM_MEMBER". Both
  halves are load-bearing: containment would invert `isSystemUser` into a
  fleet-wide opt-out (reachable via a service-request approval or the
  bootstrap-admin role merge), and the permission check is what stops a
  runtime `PUT /admin/roles/TEAM_MEMBER/permissions` grant from silently
  minting a privileged-and-exempt account. It fails CLOSED: give gate staff
  real authority and they stop being exempt, automatically.
- **It ignores `mfaEnabled` on purpose.** TEAM_MEMBER used to be a system
  user, so existing gate staff carry a force-enrolled secret; honouring the
  flag would have applied the carve-out only to accounts created after it
  shipped. The secret is retained, not cleared, so it starts being honoured
  again the moment the account holds any other role. **This is why no
  data migration was needed** — don't add one.
- **The refresh guard exists because the exemption is evaluated at LOGIN
  only.** `UserAdminService.setRoles` bumps `tokenVersion`, which kills the
  access token but does NOT revoke the refresh row — and `/auth/refresh`
  re-reads the LIVE user, so a gate operator widened to a privileged role
  could otherwise mint privileged access tokens for the life of the refresh
  chain, having never passed a second factor. `AuthService.refresh` refuses
  that shape: **403 `mfa_enrollment_required`**, audit
  `AUTH_REFRESH_MFA_REQUIRED`, family NOT revoked (the token is genuine, not
  stolen — the FE routes to a full login, which lands on forced enrolment).
  The same guard closes a pre-existing dodge of `MfaService.adminReset`'s
  documented "must re-enrol on next login": a live session could previously
  ride refresh straight past it.
  (The class javadoc's aside that "role mutation does not bump
  `tokenVersion`" is inaccurate — it does, in `UserAdminService.setRoles`. The
  hole it describes is real regardless, because the refresh ROW survives.)

## Deactivation ends sessions at once; `token_version` is written atomically (user-service, V42)

**`PUT /admin/users/{id}/active` with `false` (and the organizer's team-member
disable) signs the person out everywhere, immediately, through
`AccountSessionRevoker.revokeAll`.** Before it, deactivation flipped `active`
and nothing else: no version bump, no Redis publish, no refresh revocation, and
`/auth/refresh` never read `active`, so a deactivated person kept every session
for the life of the refresh chain. In the caller's transaction it now: sets
`active = false` and bumps `token_version` in ONE `UPDATE ... RETURNING`,
revokes every refresh family, clears device trust, and deletes the live
reset-OTP rows for the account's email and phone (so a code requested BEFORE
the deactivation cannot set a password for after a reactivation; one cannot be
requested DURING it — `PasswordResetService` treats `approved && !active` like
an unknown account, and `resetPassword` answers it "Invalid or expired code").
**Reactivation sweeps the same way** (`sweepOnReactivation`: bump, revoke,
clear — both `PUT active true` on an approved account and the organizer's
team-member re-enable), because an account deactivated before this release
still holds live families that switching it back on would return.

**Scope limit — loyalty is NOT reached.** A customer deactivated here keeps any
loyalty-phone-keyed `LRT-` chain, and `POST /auth/otp/verify` still mints a
fresh `loyalty-otp` session (and still promotes the phone in loyalty) for that
customer's phone: the token is roles-empty and fleet-inert, and whether a
user-service deactivation should switch loyalty off is loyalty's decision
(it has its own INACTIVE/BLOCKED states). Documented, not fixed.

- **Every session path refuses an inactive account:** refresh rotation (which
  also serves `/auth/organization-context`) — checked BEFORE replay detection,
  because deactivation revokes every family and a later check would answer
  "reuse detected" (a false theft alarm and a 400) instead of `401
  account_inactive`; the MFA step and enrolment (`MfaTokenService.verify`); the
  mint chokepoint `AuthService.buildResponse` (the backstop no path can skip);
  and `JwtFilter`, whose one per-request read is now `(token_version, active)`
  (`401 ACCOUNT_DEACTIVATED`). **`JwtFilter` skips `/auth`**, so the two `/auth`
  handlers that authenticate from their own Bearer header —
  `/auth/change-password` and `/auth/mfa/disable` — apply the same gate
  themselves (`TokenRevocationService.requireCurrentSession`: `401
  account_inactive` / `401 session_superseded`). A new such handler must too.
- **The mfaToken is bound to `token_version`** (`tv` claim) and refused unless it
  equals the live version; a successful step SPENDS the PRESENTED token with a
  compare-and-set bump on its own `tv` (`bumpIfCurrent`) before minting. So any
  bump in between (deactivation, role or password change, admin MFA reset, a
  newer login) kills pending challenges, and a token can never mint twice.
  Removing either half reopens the hole. A token with no `tv` is refused like a
  stale one. **Enrolment spends the ENROLLMENT token itself**
  (`AuthService.completeEnrollmentAndSignIn`: spend, enrol, mint — one
  transaction that rolls back on any refusal); it used to commit the enrolment
  and then mint a fresh login token at whatever version the row held, so the
  presented token was never spent and a double submit could sign in twice. A
  lost compare-and-set is `MfaTokenSpentException`, in the login step's
  `rollbackFor` (the more specific rule beats the `noRollbackFor` on its parent)
  so it cannot burn the backup code the code check just consumed —
  `MfaStepRollbackRulesTest`.
- **`token_version` has exactly one writer: `TokenVersionBumper`.** Every bump
  is an atomic `UPDATE users SET token_version = token_version + 1 ...
  RETURNING` and the entity column is `updatable = false`, so a stale entity
  save can no longer write an old version back (a login racing a deactivation
  used to revive its sessions). `User` is now also `@DynamicUpdate`, so a stale
  save of an UNRELATED column (a password reset racing a deactivation) no longer
  writes `active = true` back either. The password step's
  bump is conditional on `active = true` in the same statement, so that race
  now either refuses the login or is bumped past. `TokenVersionBumpSitesTest`
  fails the build on a `setTokenVersion` anywhere else in `src/main`.
- **The Redis publish happens AFTER COMMIT** (`TokenVersionPublisher
  .publishAfterCommit`). `setRoles` used to publish inside its transaction, so a
  rollback left Redis ahead of Postgres. **And it never moves Redis backwards**:
  two bumps' after-commit callbacks can land in either order (a login's v+1
  after a deactivation's v+2 would re-admit the login's token downstream), so
  the write is a Lua compare-and-set that keeps the higher value
  (`TokenVersionPublisherRedisIT`). After restoring user-service's database to
  an earlier point, delete `auth:tokenver:*`. A failed publish increments
  `user.tokenver.publish_failed` — registered at 0 at startup, because
  `increase()` cannot see a series' first sample (alert
  `TokenVersionPublishFailing`); other services then fail open until each
  access token expires.
- **`POST /admin/users/{id}/mfa/reset`** records the ADMIN as actor and the user
  as target (it used to name the target as its own actor), takes an optional
  `{"note"}` (≤ 500 at the edge; `MfaService.cleanNote` strips markup and
  control/bidi characters and caps it again for any other caller; audit row
  only), bumps `token_version`, and refuses a SUPER_ADMIN target with `403
  target_not_manageable` (`StaffPolicyException`).
- **V42 widens `audit_events.actor_id`/`target_id` to `VARCHAR(254)`** — an admin
  email over 64 characters used to fail the audit insert silently (the write is
  fail-open). Metadata-only in Postgres, so every `row_hmac`/`chain_hmac` still
  verifies; `SET LOCAL lock_timeout` makes it fail fast rather than queue
  behind live audit writers.

## Staff accounts are INVITED, never registered (user-service V44)

**An InnBucks staff account is created by `POST /admin/staff` (`staff:create`,
wildcard-only) and becomes usable only when its invite is redeemed.** Before
V44 the console made "staff" through `/auth/register`, which silently dropped
the `roles` it sent and minted an organizer or merchant admin with a business of
its own, approved by a temporary password mailed to whatever phone the form
held. Now registration refuses a non-empty `roles` (400 `roles_not_accepted`;
absent and `[]` still accepted) and every self-service email writer refuses a
staff address.

- **Staff-eligible** (`StaffEligibility`) = active, not SUPER_ADMIN, email on
  `STAFF_ALLOWED_EMAIL_DOMAINS` (exact domain, `StaffEmailPolicy`), email PROVEN
  (`users.email_verified_at`, set only by accepting an invite) and a
  `staff_profiles` row whose invite was accepted. **Only a staff-eligible account
  may newly gain staff authority**: a staff role through `setRoles`, a PLATFORM
  code added to a role it holds (every holder but SUPER_ADMIN checked — 400
  `staff_holders_ineligible` with counts, reasons and a sample), or a role
  created under a name orphan `user_roles` strings already hold. REMOVING is
  never refused on these grounds. Every refusal is audited
  `STAFF_GRANT_REFUSED`. SUPER_ADMIN is exempt from all of it.
- **A business built-in never becomes a staff role**: a PLATFORM code (or `*`)
  on EVENT_ORGANIZER, MERCHANT_ADMIN, SHOP_ADMIN, SHOP_USER, TEAM_MEMBER or
  CUSTOMER is 400 `permission_not_assignable` (`business_role`), whoever asks.
  Registration + approval, shop-staff and team-member create and the OTP /
  federation creators hand those roles out with NO eligibility check, so the
  staff rules only hold while they grant nothing platform-wide.
- **Both halves of a grant lock the `roles` rows first** (`RoleRepository
  .lockAllByNameIn`, `SELECT … FOR UPDATE` in name order) — `setRoles`, role edits,
  staff create and service-request approval. Without it, "give X role R" and "add
  a PLATFORM code to R" each pass against the state the other has not committed
  yet. `RoleGrantRaceIT` fails in its first round with the locks removed.
- **The staff lifecycle locks the ACCOUNT row first** (`UserRepository.lockById`):
  resend/adopt, deactivate, reactivate — and invite accept, which reads the
  invite unlocked only to learn whose row to lock, THEN consumes it. One order
  everywhere (users, then `staff_invites`), so two resends cannot leave two live
  links (`StaffInviteLinkLifecycleIT`: four without the lock, one with it) and an
  accept racing a deactivation cannot deadlock. Consuming first would invert the
  order against a resend.
- **The invite**: `STI-` + 32 random bytes, only the SHA-256 stored
  (`staff_invites`), bound to the address it was sent to, 72h
  (`STAFF_INVITE_TTL`), one use (a conditional `UPDATE … WHERE used_at IS NULL
  AND revoked_at IS NULL AND expires_at > now`), revoked by deactivation,
  reactivation, a resend and acceptance. The link is
  `STAFF_CONSOLE_BASE_URL + STAFF_INVITE_PATH + "#token=STI-…"` — in the
  FRAGMENT, so no browser sends it to a server or a log. It goes out through
  the ordinary email path (`EmailNotificationClient`, SES then the notification
  API) as a branded CTA button, AFTER COMMIT, reference `STAFF-INVITE-<id>`.
  **Never log the link, the token or the body** — the mailer logs reference and
  upstream status only, and the client withholds the upstream reply for any
  email carrying a CTA. A replayed link is `STAFF_INVITE_REPLAYED`.
  Unconfigured (no console URL, or no transport) is 503
  `staff_invites_unconfigured` plus a HALF-PROVISIONED boot ERROR; no domains is
  503 `staff_domains_unconfigured`.
- **INVITED (profiled, `invite_accepted_at IS NULL`) cannot hold a session.**
  Creation, adoption and reactivation all leave it an unusable password, so the
  password step is the ordinary 400 (401 `staff_invite_pending` there is only a
  backstop); refresh and organization switch are 401 `staff_invite_pending`
  from the rotation itself, ahead of replay detection (same reason as
  `account_inactive`) and in its own transaction — nothing outside it writes;
  the mint refuses it; forgot and reset-password are no-ops for it by ANY
  identifier (this also closes the shared-OTP-row path).
- **Accepting strips the old sign-in material**: `users.phone_number` becomes
  NULL (moved to `staff_profiles.contact_phone`), TOTP/backup codes/device trust
  cleared, every session ended. **A staff account has no sign-in phone —
  `users.phone_number` is nullable since V44, and every reader must be
  null-safe.** Phone-based reset and the CUSTOMER creators (OTP, `/auth/exchange`)
  match by phone, so they cannot reach a profiled account.
- **Legacy staff are ADOPTED, not re-created**: `POST /admin/staff/{id}/resend-invite`
  on a profile-less account creates the profile (INVITED at once), ends every
  session AND replaces the password, clears the TOTP, backup codes and trusted
  devices, then emails an invite — the squatter lockout: nothing the old holder
  knew works again, even past the profile check. Refused 409
  `adoption_blocked` (`holds_non_staff_roles` → remove them with `setRoles`;
  `organization_member` → `POST /admin/organizations/{id}/suspend`,
  `organizations:manage`; `off_domain` → demote). Reactivation also returns an
  account to INVITED (unusable password, 2FA cleared, email unproven) — it never
  restores the old credentials. **`PUT /admin/users/{id}/active` with `true` on
  staff is 409 `use_staff_endpoints`**, and `reset-temp-password` on a profiled
  or adoptable account is 409 `use_staff_invite`.
- **Staff never belong to a business.** `changeRole` and service request
  submit/approve refuse a staff account (409 `staff_account_not_eligible`);
  `addMember` answers a staff account, or ANY staff-domain address, exactly like
  an unknown email (404 `account_not_found`, still audited
  `STAFF_GRANT_REFUSED`) so a business owner cannot probe which addresses are
  staff; tier-2 refuses a staff account's phone in its own words. The mint
  never puts `orgId`/`orgRole`/
  `products` on a profiled account's token whatever `organization_members`
  holds (`ProfiledAccountNeverGetsOrgClaimsIT`). Service-request approval that
  adds a role now records `USER_ROLES_CHANGED`.
- **A staff address is reserved** at every other `User.email` writer —
  register, tier-2, shop-staff and team-member create, and the FIRST approval of
  a registration — 400 `email_domain_reserved`, checked FIRST (before the
  bootstrap-admin and duplicate checks) so none of them is an oracle for which
  staff addresses exist, the platform admin's among them. Reserved
  means the domain or any subdomain. `RoleWriterInventoryTest` lists every
  writer of `User.roles`, `User.email`, memberships and products and fails on a
  new one — decide guarded or unreachable, and write the reason there.
- **The mint-time backstop** (`StaffMintFilter`, `AuthService.buildResponse` and
  `JwtFilter`'s perms-less legacy path): an ineligible holder of staff authority
  (a NAMED role name or a PLATFORM code) is counted on
  `user.staff.ineligible_holder{reason}` in `STAFF_ELIGIBILITY_ENFORCEMENT=watch`
  (the default), and in `enforce` has every PLATFORM code AND every NAMED role
  name withheld (booking/event grant by name). Flip to `enforce` only once the
  counter reads 0 — i.e. every legacy holder is adopted or demoted.
  `user.staff.invariant_breach{kind}` (15-minute read-only job, deliberately no
  lock) must stay 0.
- **Config** (`deploy/cells/cell.<iso>.env`, committed): `STAFF_ALLOWED_EMAIL_DOMAINS`,
  `STAFF_CONSOLE_BASE_URL` (`https://foundry.innbucks.co.zw`),
  `STAFF_INVITE_PATH`, `STAFF_INVITE_TTL`, `STAFF_INVITE_RESEND_LIMIT` (5/24h,
  429 `invite_resend_limited`), `STAFF_CREATE_DAILY_LIMIT` (20/24h per creator,
  429 `staff_create_limited`), `STAFF_ELIGIBILITY_ENFORCEMENT`. On a live cell
  add them to the `cell-zw` ConfigMap ONE KEY AT A TIME (`kubectl patch` /
  `jq`), never a `--from-env-file` rebuild — see
  `~/ticketing-system/deploy/PROD_UPGRADE_RUNBOOK.md` §5. **No mail change is
  needed:** invites use the normal email path (SES where `MAIL_ENABLED=true`,
  else the notification API — `BANK_API_*` must be configured). The public
  `/auth/staff-invite/{inspect,accept}` ride the gateway's
  `auth-staff-invite-route` (POST, IP-keyed fail-safe limiter, before
  `user-auth-route`).
- **Rollback caveat — a security regression, not a crash.** V44 is additive and
  the previous image boots and signs in accounts with a NULL `phone_number`
  (its sign-in paths are null-safe on the phone). What it does is IGNORE
  `staff_profiles` and `email_verified_at`: an INVITED account — new, adopted or
  reactivated — can set a password through forgot-password BY EMAIL without ever
  redeeming its invite; the mint filter and every staff refusal (reserved
  domain, eligibility, business role, no-business-for-staff) stop; and `/auth/staff-invite/**` is a 404, so pending
  invites cannot be redeemed. Adopted accounts do NOT reopen to their previous
  holder — adoption already replaced the password and cleared the TOTP. Roll
  back only as an emergency, and re-invite the INVITED accounts after rolling
  forward.

## Bookings carry WHO is coming (booking-service V22)

`bookings.customer_name` is the purchaser's full name; `booking_items.attendee_*`
(name/email/phone) is an OPTIONAL per-ticket guest. Together they turn the
organizer's bookings view from a list of MSISDNs into a guest list.

- **`customerName` is required at `POST /bookings`**, resolved in the controller:
  body value wins, an authenticated customer falls back to the JWT's
  `firstName`/`lastName`, a guest with neither is a `400 "Please provide your
  full name."`. The resolved value is written BACK onto the request before the
  service reads it (same convention as `phoneNumber`); the service re-checks it
  as defence in depth. Nullable in the DB only for pre-V22 rows.
- **Tickets are issued in REQUEST order, not grouped by category.** `items[i]`
  is `seats[i]`. The old loop iterated `qtyByCategory` and would have shuffled a
  guest's name onto the wrong ticket whenever categories interleaved. Capacity
  is still claimed per category; only the item build order changed.
- **An attendee phone is validated + canonicalised exactly like the
  purchaser's** (`MsisdnValidator`, 400 naming the attendee on a bad number).
  Skipping that would store a malformed MSISDN and lose that guest's QR at
  Twilio (63024) with nothing in our logs.
- **Attendee contact is PII with the same posture as the purchaser's.** It
  travels on the AUTHENTICATED views (`BookingResponseDTO` via
  `toItemDTOs(booking, true)`, `CategoryBookingDTO`) and is NEVER set on the
  public magic-link / phone-wallet views (`toItemDTOs(booking, false)`,
  `toPublicDTO`), where the `@JsonInclude(NON_NULL)` on the two contact fields
  keeps the keys out of the payload entirely. The attendee NAME is on every
  view — it is printed on the ticket face and is what tells a buyer which QR to
  hand to whom. Don't add the contact to a public DTO.
- **Delivery routes each ticket's WhatsApp QR to its HOLDER's phone,
  exclusively** (`TicketDeliveryService.attendeeHasOwnPhone` decides): a ticket
  whose attendee has their own phone goes to that attendee ONLY — the purchaser
  does not get a copy ("A gets his ticket, B gets his"). Tickets with no
  attendee phone (none named, name-only, email-only, or the attendee IS the
  purchaser) stay on the purchaser's WhatsApp so the gate credential always
  reaches a real phone. **A synchronously-FAILED attendee QR send falls back to
  the buyer** — the QR must never be lost, and the fallback is what makes the
  resend endpoint recover a mistyped guest number (re-run → fails again → buyer
  gets it to forward). Attendee sends run BEFORE the purchaser's receipt email,
  which therefore reports actual outcomes (delivered directly / fell back), not
  intentions. A buyer with no email whose tickets were ALL attendee-routed gets
  one SMS receipt so the payer never hears nothing. The purchaser's email stays
  the full-booking receipt; an attendee with an email also gets a one-ticket
  email. `Outcome.qrTicketsTotal` counts PURCHASER-routed QRs (own + fallbacks)
  and `attendeeDeliveriesSent/Total` the attendee-contactable tickets — the
  buckets overlap for an email-only attendee (QR to buyer, email to attendee).
- **`holderName`** (= attendee if named, else purchaser) is on `CategoryBookingDTO`
  and on the scan response (`ALLOWED` / `ALREADY_REDEEMED`) so the gate can greet
  or ID-check the holder; the ticket HTML/email print "Ticket for <holder>".
- The organizer CSV export gained `customerName` + `attendees` (names, `; `-joined)
  and stays one row per BOOKING — it is the accountant's ledger; the per-ticket
  guest list with contacts is `GET /bookings/by-event/{id}`.
- **Not done, deliberately:** attendees do NOT get the pre-event reminders or the
  event-change/cancel notifications — those still go to the purchaser only.
  Extending them is a separate decision (it multiplies SMS cost per booking).

## A MERCHANT_ADMIN's token carries NO `merchantId` claim — the organization is the scope

**Step 2 of the organizations plan removed `AuthService.resolveMerchantIdClaim`
and its login-time loyalty lookup.** A merchant admin's scope is now the
ORGANIZATION their session acts for: `orgId` + `orgRole` + `products` (V39),
which loyalty (InnRewards V51) and marketplace-service each read directly.

- **Why the claim went.** It was minted from loyalty's `merchants.admin_email`
  — one email per merchant — and was withheld whenever that email matched more
  than one merchant, so an admin running two businesses had no scope at all,
  and a colleague could never be added. The organization carries both: several
  people per business, several businesses per person (picked through
  `POST /auth/organization-context`, never guessed).
- **The consumers decide authority from the ORG claims, not the role.** Both
  loyalty and marketplace grant their merchant-admin authority only to an
  OWNER/ADMIN of an organization holding THEIR product (`loyalty` /
  `marketplace`); a bare `MERCHANT_ADMIN` role grants nothing there. So a
  marketplace-only business cannot administer loyalty, and an ADMIN colleague
  with no staff role at all can sell.
- **Shop staff keep their row-stamped claims.** SHOP_ADMIN / SHOP_USER still get
  `merchantId` + `shopId` from their `User` row (no network call) — moving shop
  staff under organization membership is a separate design change.
  `MerchantIdClaimTest` pins both halves.
- **`ShopStaffService.resolveCallerMerchantIds`** asks loyalty
  `GET /loyalty/internal/merchants/ids-by-organization` for the session
  organization's merchants — only when the caller RUNS it (OWNER/ADMIN, read
  live from `organization_members`, so a demotion bites at once). Best-effort:
  any miss is an empty set, and an empty set fails every ownership check
  closed. Pinned by `LoyaltyMerchantIdsByOrganizationContractTest` and
  `ShopStaffServiceTest`.
- **Retired with the email binding:** `/users/internal/merchants/{id}/admins`
  (marketplace now calls `/users/internal/organizations/{id}/admins`) and
  `/users/internal/merchants/assigned` (backed loyalty's removed
  `?unassigned`).
- **`GET /admin/organizations`** (`organizations:read`, SUPER_ADMIN via its
  wildcard; not granted to PRODUCT_OFFICER/MANAGER, whose remit V35 left
  deliberately undecided) is the directory an operator picks from when acting
  on a business's behalf — loyalty's merchant create and marketplace's
  on-behalf listing now take an ORGANIZATION id, and `/organizations/**` only
  lists the caller's own. Filters are appended Criteria predicates (never a
  null bind); the sort ends on `id` so paging is a total order. Pinned by
  `AdminOrganizationControllerTest`.
- `Listing.merchantId` in marketplace-service now holds the seller's
  ORGANIZATION id (the API name was kept). `GET /admin/organizations` is the
  registry for it — **not** `GET /admin/users/merchants` (account `userUuid`s)
  and no longer `GET /loyalty/merchants`.

## Service requests can be REJECTED (user-service V36)

`PUT /admin/service-requests/{id}/reject` with a required `{ "reason": "..." }`.

- **Why it had to exist.** `Status` was `PENDING, APPROVED` only and there was no
  reject endpoint, so an admin who decided against a request had **no action
  available** and it stayed PENDING forever — the queue could not drain. The
  console was already rendering, filtering and counting a `REJECTED` status the
  database could not store.
- **`reason` (requester's) and `decision_reason` (reviewer's) are different
  columns.** `service_requests.reason` is NOT NULL and holds the applicant's own
  justification; writing the admin's words there would overwrite it. V36 adds
  `decision_reason`, nullable — null on every pre-V36 row and on an approval
  with no note. Required on a reject: a refusal that does not say why is what
  makes people re-submit the identical request.
- **No CHECK change was needed** — V4 declared `status` as a bare `VARCHAR(32)`.
  And the pending-uniqueness index is scoped `WHERE status = 'PENDING'`, so a
  rejected row does **not** block re-requesting the same bundle. A rejection
  decides one request; it is not a standing ban.
- **Reject grants nothing** — the only mutation is on the request row. Pinned by
  `ServiceRequestRejectTest`.
- **Same permission as approve** (`service-requests:approve`), deliberately: both
  are the power to *decide*. A role that could approve but not reject could only
  ever say yes, recreating the undrainable queue.
- **Both outcomes now notify the requester** via `ServiceRequestDecided` +
  `@TransactionalEventListener(AFTER_COMMIT)` → `UserNotificationDispatcher`.
  Approval used to be silent — a merchant learned their request was granted by
  noticing a new menu item. AFTER_COMMIT so a rolled-back decision never
  announces itself; the approval copy tells them to sign in again, because the
  grant rides in the JWT and is invisible until a fresh token is minted.
- **Both decision paths throw TYPED exceptions** (`NotFoundException` → 404,
  `ResponseStatusException` → 400 naming the status already held). A bare
  `RuntimeException` is collapsed by `GlobalExceptionHandler` into a 400 reading
  *"We couldn't process your request. Please try again."*, which invited an admin
  to retry an operation that can never succeed and hid that a colleague had
  already decided the row. `approve` was fixed alongside `reject` — its Swagger
  had been documenting a 404 it did not actually return.

## Organizations — the business is the tenant (user-service V39)

`organizations` + `organization_members` (`OWNER` / `ADMIN` / `STAFF`) +
`organization_products` (`ticketing` / `loyalty` / `marketplace`). Until V39 a
"tenant" was a PERSON: roles sat on one account, merchant scope was recovered
from loyalty's `merchants.admin_email`, nobody could add a colleague, and an
admin running two businesses got no `merchantId` claim at all. This was **step 1**
of the organizations plan: user-service only, additive, safe to ship to
production ahead of everything else. Step 2 (the section above) moved loyalty and
the marketplace onto it.

- **The token gains `orgId`, `orgRole`, `products` — additively.** An account
  with no organization mints a token byte-identical to pre-V39; one with an
  organization gains exactly those three claims and nothing else changes.
  Pinned by `OrganizationClaimsTest`. After the backfill every organizer and
  merchant admin is in the second group, so their tokens DO change shape —
  additively, which every consumer already tolerates.
- **Which organization a session acts for is never guessed.** Exactly one
  ACTIVE membership → chosen automatically. Several → none, the auth response
  carries `organizationSelectionRequired: true`, and the person picks through
  `POST /auth/organization-context` (the refresh token as Bearer +
  `X-Device-Id`, body `{ organizationId }`). Same reason the `merchantId`
  claim is withheld from a multi-merchant admin: a pick we made would
  attribute their actions to a business they did not choose.
- **The choice rides the REFRESH ROW (`refresh_tokens.organization_id`)**, same
  lifecycle as `phone_proof`, because `/auth/refresh` re-derives claims from the
  live user. Unlike `phone_proof` it can change — that is how switching works —
  and it is **re-validated on every rotation**: removed from the organization,
  or the organization suspended, and the next refresh falls back to the
  default. A role change or a removal also bumps the member's `tokenVersion`,
  so authority that was taken away dies with the access token at once rather
  than at expiry. An ADD does not: it only grants, and the new organization
  arrives at the member's next refresh — behind the MFA guard below.
- **Phone-proof sessions carry no organization claims and cannot switch**
  (403 `organization_context_not_allowed`), for the same reason they carry no
  `merchantId`: a phone proof is not a staff login.
- **Belonging to an organization requires 2FA** (`MfaPolicy.isSystemUser`),
  whatever the roles say: a plain CUSTOMER added as STAFF now works for a
  business and is challenged like staff, and a gate operator added to one loses
  the gate exemption. Fails CLOSED. The **refresh MFA guard skips phone-proof
  families**: what they mint is CUSTOMER-only with no scope claims — the same
  authority the exchange login grants without a second factor — and without the
  skip a shopper added as STAFF lost their super-app session at the next
  refresh, with no enrolment flow in the app. Pinned by the two
  `refresh_*OrganizationMember*` cases in `AuthServiceTest`.
- **Authority inside an organization is ordered OWNER > ADMIN > STAFF.** Any
  member may read it; OWNER/ADMIN edit it and list members; an OWNER adds or
  removes anyone and is the only one who changes roles; an ADMIN adds or
  removes STAFF only. The last OWNER can be neither demoted nor removed (409
  `last_owner`), so an organization can never be orphaned. Non-members get a
  404 indistinguishable from a missing organization — no existence oracle.
- **Products are granted by service-request APPROVAL.** The request is stamped
  at submission with the organization it is FOR (the session's organization if
  the requester is OWNER/ADMIN there, else the one they own); approval grants
  the product to that organization, else to their sole owned one, else creates
  one for them (approving a business product is what makes them a business).
  An owner of several with nothing stamped is **skipped with a WARN, never
  guessed**. The user-level bundle and role are still granted exactly as
  before — roles remain how every service authorizes today.
- **Registration** creates the organization in the same transaction as the
  user (every self-registered account owns a business), named after the
  business when there is one and after the person otherwise.
- **The backfill** (V39 itself) gives every `EVENT_ORGANIZER` /
  `MERCHANT_ADMIN` account one organization it OWNS, named from its tenant
  profile or the person, with products from `user_default_services`
  (normalised, unknown values skipped). Customers and platform staff get none.
  Pinned against real Postgres by `OrganizationBackfillPostgresIT`.
- **S2S for step 2's consumers:** `GET /users/internal/organizations/names?ids=`
  (≤ 200 per call) and `/{organizationId}/admins` — both behind the existing
  `/users/internal/**` permitAll + `user-internal-deny`. Gateway route
  `user-organizations-route` (`/organizations/**`), pinned by
  `GatewayRouteTableTest`.
- **Every response that issues a session carries the scope, INCLUDING
  `POST /auth/mfa/enroll/complete`** — `organizationId`, `organizationRole`,
  `organizationProducts` (the token's `products`, so a client gates menus
  without decoding the JWT) and `organizationSelectionRequired`. Enrolment is
  where every staff account's FIRST sign-in ends (belonging to an organization
  forces 2FA), and it used to return only the tokens, so a colleague's first
  session never learned which business it acted for or that it had to pick.
  `MfaEnrollCompleteResponseDTO.from` copies them; pinned end to end by
  `OrganizationColleagueSignInIT` (an ADMIN colleague with NO platform role,
  and one who also owns a business of their own).
- **A colleague must already have an account with an email** —
  `POST /organizations/{id}/members` adds, it does not create. Today that is a
  super-app customer who reached tier 2, or a self-registered portal account
  (which always creates the registrant's OWN organization, so they then belong
  to two and pick at every sign-in). There is no invite-by-email flow yet.
- **Step 2 has landed**: the `merchantId` claim and its login-time loyalty
  lookup are gone for merchant admins, and loyalty + marketplace scope on
  `orgId` instead — see the section above. Those three changes deploy
  together.
- Every membership and product change is on the tamper-evident chain
  (`ORGANIZATION_*`), target = the organization id, the affected person named by
  `userUuid` — never by email.

## Super-app customers federate from the InnBucks middleware (`POST /auth/exchange`)

**Two audiences, two identity providers, one token shape.** Merchants and admins
log in HERE with a password through the admin portal — unchanged. The super app's
CUSTOMERS log in at the **InnBucks middleware** (`POST /auth/client-service/user/login`),
whose `accessToken` this fleet can neither verify (no key) nor introspect (no
endpoint — measured for InnRewards V42, do not re-try). So the middleware signs a
**short-lived RS256 assertion** (`iss`/`aud` as provisioned, `sub` = phone, `jti`,
`iat`, `exp − iat ≤ 300s`) after each login, and the app trades it at
`POST /auth/exchange` for a normal CUSTOMER access + refresh token. Everything
gated on `hasRole('CUSTOMER')` — marketplace orders above all — then works with
no further change and cannot tell how the customer proved themselves.

- **The assertion contract is loyalty's, verbatim.** `FederationAssertionVerifier`
  is `RegistrationAssertionVerifier` carried across so the middleware signs ONE
  shape for the whole fleet; keep them in lock-step. The **audience differs on
  purpose** (`innbucks-foundry` vs `innbucks-loyalty`): a registration proof must
  never double as a login, and the verifier requires both `iss` and `aud`.
- **Only ever a CUSTOMER — enforced in TWO places, because the guard alone was a
  hole.** `FederatedLoginService` refuses a phone belonging to an account with NO
  customer role (`not_a_customer`). That check reads like it delivers the
  headline, and for a *pure staff* account it does. **It never did for a DUAL-role
  account**: a merchant admin who also shops holds both roles, passes the guard,
  and `buildResponse` used to hand back every role it found plus the `merchantId`
  scope claim — so phone possession alone reached every merchant surface in the
  fleet (marketplace's payout destination among them), having never passed the
  MFA challenge `MfaPolicy.required` mandates for a system user on the password
  path. `AuthService.refresh`'s MFA guard did not catch it either: it checks a
  secret is *enrolled*, not that it was *used*, and an active merchant admin is
  force-enrolled.
  So the session is now **SCOPED at the mint** (`issuePhoneProofToken`): roles
  narrowed to `CUSTOMER`, and the `merchantId` / `shopId` / `organizerUuid` scope
  claims withheld — a claim naming authority the roles no longer grant is worse
  than no claim, since it waits for the first consumer that trusts the claim
  alone. **A merchant admin CAN be a customer on the same number** (the operator's
  explicit requirement, 2026-09-22): they shop in the super app and sell in the
  admin portal behind password + MFA, and the account itself is untouched. The
  earlier note here — "a staff member who also shops must use a different number"
  — was the workaround for the hole, not a policy; it is gone.
  Pinned by `PhoneProofScopeTest` and the dual-role case in
  `FederatedLoginServiceTest`.
- **The scope rides the REFRESH ROW (`refresh_tokens.phone_proof`, V38), not the
  access token.** `/auth/refresh` re-reads the LIVE user and re-derives claims
  from their current roles, so a login-time narrowing kept anywhere else would
  evaporate on the first rotation — the same shape as the gate-operator hole
  above. Stamped once when the family is minted and copied onto every successor,
  exactly like `device_id_hash`. Default FALSE, which is the truth for every
  pre-V38 row rather than a guess.
- **One use per assertion.** The `jti` is SETNX'd in Redis for the assertion's
  remaining lifetime + 60s grace BEFORE any account work. If Redis cannot answer
  the login is refused with a retryable **503** — a session that could not be
  replay-checked is not issued. Redis is boot-required on every cell, so this is
  an outage signal, not a routine path.
- **It mints a LOGIN token, not a new token shape.** `AuthService.issuePhoneProofToken`
  runs the same `buildResponse` every password login and refresh goes through:
  same claims, same refresh family, same tokenVersion revocation, so no consumer
  needs to learn a second format. What differs is the **content** of the roles
  claim (see the scoping above), never the shape. **Still do not add a
  roles-EMPTY variant here** — that token is loyalty's, deliberately inert
  fleet-wide, and a phone-proof session is the opposite: a fully ordinary
  CUSTOMER session that simply declines to also be a staff one.
- **First sign-in creates the customer, shaped exactly like
  `OtpService.materializeOrRefreshLocalAccount`** (CUSTOMER, active, approved,
  placeholder name, tier-1 profile with `phoneVerified` stamped) with one
  difference: **no chosen password**. `users.password` is NOT NULL, so an
  unusable random Argon2 hash is stored; nobody ever knows the plaintext. The
  OTP-gated forgot-password flow can set one later. A lost create race
  (`DataIntegrityViolationException` on the phone unique index) re-reads the
  winner's row rather than failing the customer.
- **The assertion is a phone proof** and is treated like an OTP verify: it stamps
  `phoneVerified`/`phoneVerifiedAt` and calls `loyaltyServiceClient.promoteUserByPhone`
  (best-effort), so loyalty projections activate on first sign-in without an SMS.
- **Every refusal is one opaque `401 "Assertion rejected"`**; which check failed
  goes to the audit log as `AUTH_FEDERATED_LOGIN_REJECTED` with a `failure_reason`.
  Off by default = **404**; enabled with a blank key = **503** plus a
  HALF-PROVISIONED boot ERROR (`FederationProvisioningCheck`). Env:
  `AUTH_FEDERATION_ENABLED` / `_PUBLIC_KEY` / `_PREVIOUS_PUBLIC_KEY` / `_ISSUER` /
  `_AUDIENCE` / `_MAX_TTL_SECONDS` in `deploy/cells/cell.<iso>.env` (committed OFF,
  enabled per host in the gitignored local file).
- **Gateway: `auth-exchange-route`** — POST-only, exact path, IP-keyed fail-safe
  limiter (`AUTH_EXCHANGE_RATE_LIMIT_*`, 5/20), ordered before the limiter-free
  `/auth/**` catch-all; pinned in `GatewayRouteTableTest`.
- **What still needs the middleware team:** sign the assertion at login and hand
  over the public key. Until then the endpoint stays off and the super app has no
  path to any `CUSTOMER`-gated endpoint — that is the documented state, not a bug.

## DTX device security — the phone is checked before staging's PIN login (user-service V40)

`devicesecurity/` implements the InnBucks 2.0 app contract *"Device Registration,
Fraud Detection and Sign-In Through DTX"* (v2.1). **DTX is this fleet**
(`dtx.innbucks.co.zw/foundry`): the super app calls `POST /auth/client-service`
(through the broker) BEFORE staging's user login, DTX decides TOKEN /
OTP_REQUIRED / TEMP_BLOCKED / BANNED, and only a TOKEN carries staging's
client-service token plus a single-use RS256 `loginTicket`. DTX is the ONLY
holder of the staging client-service credential. The *569# USSD service unlocks
and blocks phones on `/device-security/ussd/**`; the call center works it from
`/admin/device-security/**`.

- **The PIN never reaches DTX.** It goes app → broker → staging only. A correct
  OTP moves a phone to PENDING_PIN; it becomes TRUSTED only when the broker
  reports a successful login for that ticket (`/device-security/broker/login-result`).
  A PENDING_PIN phone keeps a 10-minute grace (`trust.pending-pin-grace`) so a
  mistyped PIN costs a retry, not another SMS — staging's own lock-out stays the
  authority on wrong PINs; a correct OTP followed by LOCKED blocks the DEVICE.
- **The install id is never stored or returned raw.** It is the device identity
  until a hardware id ships, so leaking a trusted phone's install id would let
  anyone present it. Rows hold its SHA-256; every endpoint names a phone by the
  opaque `publicId` (`deviceId`). Don't add the install id to any response.
- **Keyed by MSISDN, not `users.id`**: most super-app customers have no users row
  when DTX first sees them. `/auth/devices` resolves the number from the caller's
  fleet session (the `/auth/exchange` token), never from the request.
- **`/auth/devices` and `/auth/device/**` are filtered by `JwtFilter`** despite
  living under `/auth` (the send-money precedent). The blanket `/auth` skip would
  leave them anonymous and every call would 401. `DeviceSecurityFlowTest` drives
  them with a REAL minted JWT for exactly this reason — injecting an
  Authentication would hide the bug. `JwtFilterTest` pins the paths.
- **Partner endpoints are edge-REACHABLE, key-authenticated.** `/device-security/**`
  is permitAll in `SecurityConfig` and checked in the controller by
  `PartnerKeyAuthorizer` (broker key ≠ USSD key); the gateway gives it an IP-keyed,
  fail-safe route. It is NOT `/users/internal/**` — both callers live outside
  the cluster. Keys are checked BEFORE bean validation (`RequestValidation`), so
  an unauthenticated caller never gets field-level detail, and a switched-off
  cell answers 404 before either.
- **Transaction shape**: decide in one short transaction, then fetch staging's
  token OUTSIDE it; an OTP send commits the new code, delivers, and reverts on
  failure — a hung gateway must never hold a pooled connection. A refusal that
  must still commit (a wrong OTP spends an attempt) is returned from the
  transaction and thrown after. `DeviceOtpService.open` is
  `noRollbackFor = OtpCeilingException` — without it the caught ceiling marked
  the caller's transaction rollback-only and the VELOCITY block it placed died
  with an `UnexpectedRollbackException`.
- **Two logs, on purpose.** Every decision (incl. every ~15-min silent renewal)
  goes to `device_security_events` (12 months, not chained — the audit chain's
  head lock would serialise sign-ins). State changes a PERSON makes (support,
  USSD) also go on the tamper-evident `audit_events` chain (`DEVICE_SECURITY_*`).
- **Enforcement is the default** (`DEVICE_SECURITY_*`): off = 404; on = every
  family (OTP, BLOCKS, BANS) enforces, because the app and DTX launch together —
  there is no watch period (operator's decision, 2026-09-29). All `ENFORCE_*`
  false = watch mode (log `evaluated_decision`, answer TOKEN), an explicit
  opt-out for tuning only. A block/ban verdict whose family is off degrades to an
  OTP when OTP is on. Staff/USSD actions are always enforced.
- **Trust earned while watching is PROVISIONAL** (V41 `otp_verified_at`). In
  watch mode a PIN login binds the phone TRUSTED without it ever proving the SIM,
  so that trust is marked unproven: it sends no "new phone" notice and starts no
  cooling (else every existing customer is told a new phone signed in), and once
  OTP is enforced `DeviceSignInService.input` shows the engine the phone as NEW —
  one code, on a silent RENEW too — before it is trusted for real. Without this,
  every phone that signed in during watch mode (a stolen-PIN one included) stayed
  exempt for the full 90-day window after enforcement began. Revoke and
  trust-reset clear the proof. Pinned by
  `watchModeTrust_isProvisional_untilACodeIsVerified`.
- **The new-phone cooling period is RECORDED, not ENFORCED — so it is not announced.**
  A phone's first confirmed bind sets `cooling_until` (24h, §8.6), but no service
  that moves money reads it (the middleware applies its usual step-up thresholds),
  so the sign-in answer reports `limits.cooling: false` and the plain "Enter your
  PIN to continue." line. It used to say "Some limits are lower until 11.58" — a
  protection that did not exist. Support still sees the period, worded as "no
  lower limits apply". **Re-announce it only in the change that makes the money
  path lower limits for it** (DTX → broker → middleware claim), never on its own.
- **A trusted phone is never re-asked for a STANDING condition** (`RiskEngine.AMBIENT`:
  `APP_CHECK_ABSENT`, `INTEGRITY_UNAVAILABLE`, `OUTSIDE_KNOWN_PLACES`,
  `PIN_RECENTLY_ISSUED`, `TOO_MANY_DEVICES`, `LOCATION_REFUSED`). They are the same on
  every request from that phone, so as step-up reasons they asked for a code on EVERY
  sign-in — found on staging 2026-09-30, where the broker reports app check `absent`
  on every call and every login cost an SMS. Inside the trust window they are logged
  and scored but never a reason on their own, and never count toward the block score
  (a traveller must not be tipped into a block by the same missing app check).
  A phone not yet trusted still counts them (that is the one time they matter); a
  CHANGE still asks — different handset, impossible travel, mocked location,
  emulator/debugger, wrong-PIN burst, dormancy, PIN issue. Adding a signal: decide
  which kind it is, and put a standing one in `AMBIENT`. Pinned by
  `trustedPhone_standingConditions_neverAskAgain` and
  `trustedPhone_appCheckAbsent_signsInWithoutACode`.
- **Permissions**: `device-security:read` / `:manage` / `:fraud`. Lifting a
  SHARED_DEVICE / CONFIRMED_FRAUD / SIM_SWAP / device-wide ban needs `:fraud` —
  checked in the service too, so the call center cannot undo the fraud desk on a
  caller's say-so. **Assign the built-in roles (V43)** — `CALL_CENTER_AGENT` /
  `CALL_CENTER_SUPERVISOR` (read + manage) and the `FRAUD_DESK` add-on (read +
  fraud). Do NOT compose same-named roles at runtime (the old advice): V43 fails
  on a name collision rather than adopt one, and `ADMIN` / `CALL_CENTER` /
  `CALL_CENTRE…` are reserved names.
- **SMS copy**: the gateway rejects `*`, so SMS says "star 569 hash" and writes
  times as `14.30`; WhatsApp gets `*569#`. `DeviceSecurityMessagesTest` asserts
  every SMS template round-trips `SmsTextSanitizer` unchanged and every message
  is one line with no link.

## Customer support — one search, enforced in user-service (Ask C, V45/V46)

`POST /admin/support/customers/search` finds a customer by phone, email or a
`SEC-` reference and returns one section per product the agent may see; the
Foundry console account and the InnBucks 2.0 app (DTX) are built in-process,
and Ticketize / InnRewards / Marketplace arrive as further sections (PRs 3–5)
without a change to the response shape. Writes live under
`/admin/support/<section>/…`. **Everything is enforced HERE**, because
user-service is the only service that reads the `perms` claim: permissions,
lookup binding, the access log, the per-agent limit, masking and the
server-rendered text. Don't add a support read or write to a product that an
agent token can call directly — it would bypass all of it.

- **The query rides the BODY, never a URL** (nginx, the gateway, Cloudflare and
  browser history all log URLs). The classifier (`SupportQueryClassifier`) is
  anchored, row-ordered, and proves the `SEC-` prefix BEFORE
  `SupportRefs.normalise` (which prepends `SEC-` to anything). Card-shaped digits
  (13–19) and 12-character codes are refused `query_not_accepted`; reference
  kinds whose section isn't built are `query_not_supported`. **A refused query is
  logged by KIND only — never its text** (it may be a card number).
- **A reference returns ONLY its owning section** (+ `focus`); the cross-product
  view is always a second, explicit phone/email search. A reference is printed on
  shared tickets and screenshots — it must not open a customer's whole record.
- **Every detail read and write is bound to a lookup** (`SupportLookupBinding`):
  the agent's OWN lookup, ≤ `SUPPORT_LOOKUP_BINDING_TTL` (30 min), that returned
  the section, with the target among that section's results. Unknown / foreign /
  stale are the same `409 lookup_expired` (no oracle); an id outside the lookup is
  `404 target_not_found`. That check — not the id format — stops enumeration.
- **Write order is part of the security** (`ConsoleSupportActions`): permission →
  binding → self-action (`403 support_self_action`) → staff targets (a FRESH read;
  `403 staff_target_requires_supervisor` without `support-staff-targets:manage`)
  → `Idempotency-Key` (UUID, `support_actions` UNIQUE per agent; a repeat returns
  the stored outcome, `replayed: true`) → act → seal (`SUPPORT_*`,
  `recordRequired`, LAST statement — an in-process write that can't be sealed is
  not made) → `whatHappensNext`. Staff and SUPER_ADMIN accounts are never console
  write targets (`403 console_staff_account`, on a FRESH read of the account under
  its row lock — so one that became staff after the search is refused too), even
  for a supervisor; a deactivated or pending account is `409 account_inactive` for
  every write. The note is sealed CLEANED (`MfaService.cleanNote`, ≤ 500 — the
  request bound matches); one that is empty once cleaned is `400 note_required`.
- **Nothing a write causes leaves before its seal commits.** The owners' notice
  and the account's alert are AFTER_COMMIT listeners, and `send-password-reset`
  issues the OTP inside the transaction but emails it after commit
  (`OtpService.sendPasswordResetOtpToEmailAfterCommit`, on the notification
  executor) — never while the account row lock is held, never for a write whose
  seal rolled back. So the agent is told the code is ON ITS WAY, not delivered:
  `reset_delivery_failed` (502) is gone, and delivery shows up only as
  `user.support.reset_delivery{outcome}`.
- **A staff account (SUPER_ADMIN included) is a STUB in the console section** —
  `staffAccount: true`, the "ask a SUPER_ADMIN" guidance, no actions — never its
  id, roles, second factor, lockout, sign-in or contact details, and its id is
  kept out of the lookup's targets, so a detail read or write aimed at it is
  `404 target_not_found`. The lookup is still flagged and alerted
  (`staff_target_lookup`, the log row's `staff_account`). `identityWarnings` and
  the response's `staffAccount` flag are shown only to an agent who can see the
  console section: a `device-security:read`-only agent must not learn about
  console accounts through them.
- **A search whose records can't be read at all** (resolving the query, the staff
  check) is `503 support_search_unavailable` with a best-effort `SEARCH_FAILED`
  row; a section whose reads fail renders `UNAVAILABLE`; identity warnings that
  can't be read say so (`identity_check_unavailable`). `support_log_unavailable`
  stays the fail-closed answer for a row that can't be written — only the
  search's lookup-id draw gets a constraint violation back to retry.
- **`support_access_log` is not on the audit chain** (D10 — the head lock would
  serialise every lookup). Customer data leaving the service is logged
  FAIL-CLOSED (`503 support_log_unavailable`); refusals are best-effort. It holds
  the resolved keys in full (binding needs them) and is swept after 12 months by
  an unlocked, idempotent `DELETE` (the written reason is on the job).
- **The per-agent limit** (60 / 10 min, 400 / day, sliding) counts searches,
  detail reads, writes AND the `GET /admin/device-security/**` reads. Redis ZSET +
  Lua with Redis `TIME`; Redis down → a per-replica window (never fail-open;
  effective limit × replicas; `user.support.limiter.degraded`). The device-security
  READS were brought under the limit and the access log without any other change
  — they still take no `lookupId` — and stay under both when `SUPPORT_ENABLED=false`
  (that switch removes the new screen, not the record of who looked).
- **Never shown**: passwords, OTPs, TOTP secrets, install ids, voucher/collection
  codes, full ticket numbers. Masked: DTX `lastIp`/event addresses, third-party
  contacts. The customer's own phone/email are shown whole (the agent verifies the
  caller with them). Every response DTO is an allow-list — `SupportResponseAllowListTest`.
- **`support-console:mfa:reset` is SUPERVISOR-only** (the classic help-desk
  takeover: stolen password + one phone call). It bumps `tokenVersion` through
  `MfaService.resetForSupport` and alerts the account AND emails every OTHER OWNER
  of its organizations — each owner about the businesses THEY own, never the
  account's other ones. There is no caller-covers-target comparison: a non-staff
  account holds no PLATFORM code (a role granting one is a staff role), and staff
  are refused first. `send-password-reset` passes ONLY the account's email to
  `PasswordResetService`.
- **The support assertion** (`X-Support-Assertion`, RS256, ≤ 60s) is signed here
  by `SupportAssertionSigner` for every S2S support call (PRs 3–5). Its PRIVATE key
  lives ONLY in the `user-service-support-signing` Secret (`secretKeyRef`,
  `optional: true`) — never in `cell-zw-secrets`, which every pod receives. If it
  ever lands there, api-gateway (`SupportKeyCustodyGuard`) and booking / event /
  seat / payment (`ProductionSecretsGuard`) refuse to boot under a deployment
  profile: remove it and rotate. loyalty and marketplace (other repos) don't check
  yet. The
  public half is `SUPPORT_ASSERTION_PUBLIC_KEY` in the cell ConfigMap, patched per
  key. Verifiers pin `user-service/src/test/resources/support-assertion/test-vector.json`
  (its private half was discarded; `SupportAssertionSignerTest` fails if the
  signer's claim shape drifts from it). `aud` is emitted as a one-element array.
- **Grants (V46)**: `CALL_CENTER_AGENT` += `support-console:read`/`:manage`;
  `CALL_CENTER_SUPERVISOR` += those + `support-console:mfa:reset` +
  `support-staff-targets:manage`. Every later support grant migration inserts the
  `permissions` rows first, asserts its targets are `builtin = TRUE` in a `DO`
  block, and never names SUPER_ADMIN (`SupportGrantMigrationContentTest`).

## SUPER_ADMIN is never scoped out of a read (owner decision, 2026-09-30)

**Every GET — reports, lists, detail views — answers SUPER_ADMIN for every
merchant, shop, organization and organizer.** SUPER_ADMIN is the account that
oversees the whole platform; a read that 403s it is a bug, not a safeguard.

The trap is ownership checks that resolve the caller's scope from their
ORGANIZATION (`resolveCallerMerchantIds`, "does this shop belong to a merchant
your organization owns"): SUPER_ADMIN belongs to none, so the check is
vacuously false and the platform owner is refused everywhere — found on the
console's Shop Users screen ("Shop does not belong to your merchant").
`ShopStaffService.isPlatformOwner` bypasses them for reads and for
`createShopAdmin` (the merchant is still taken from the SHOP, never from the
caller); `/admin/shop-staff/mine` gives it every shop's staff, and
`OrganizationService.get` / `listMembers` read any organization for it
(`yourRole: null`) — its writes still go through membership. A new ownership-scoped read must do the same, keyed on the built-in
role — a custom role holding the same permission stays scoped. This is about
reads: writes keep their own rules, and SUPER_ADMIN's write powers are decided
per endpoint.

## Platform staff means one role set — use it

`AuthenticatedCaller.PLATFORM_STAFF_ROLES` (`SUPER_ADMIN`, `PRODUCT_OFFICER`,
`PRODUCT_MANAGER`) is event-service's definition of *"sees every organizer's
events, not just their own"*, and booking-service's report controller carries the
same four-role list. A cross-organizer **read** should be gated on that set, not
on `hasRole('SUPER_ADMIN')` — `/events/by-organizer` was the odd one out, which
403'd the console's organiser filter for exactly the staff whose remit is
reviewing events.

**Writes are narrower and stay that way.** `callerMayPublishAnyEvent` is
deliberately SUPER_ADMIN + PRODUCT_MANAGER only, because `PRODUCT_OFFICER` is
read-only. Don't collapse the two sets: "may look at anyone's event" and "may
act on anyone's event" are different questions.

## Report responses carry their own provenance (`ApiResult.meta`)

Every organizer report defaults its window when `from`/`to` are omitted, so the
body describes a period the caller never named — and once exported to a
spreadsheet it becomes a column of figures about nothing in particular. The five
`/event-organizer/reports/**` endpoints therefore stamp a `ReportMetaDTO`
(resolved `from`/`to`, `eventId`, `organizerUuid`, `platformWide`).

- **The dates are the RESOLVED window**, taken from the same
  `OrganizerReportService.resolveRange` the queries use (`resolvedMeta`
  delegates to it). Re-deriving the defaults at the edge is how the printed
  period and the rows drift apart; it also means an inverted range fails
  identically whether or not the meta is read.
- **`meta` lives on the shared `ApiResult` with field-level
  `@JsonInclude(NON_NULL)`, overriding the class-level `ALWAYS`.** Without that
  override every other endpoint in booking-service would start emitting
  `"meta": null` — a response-shape change for clients with no reason to expect
  one. Only endpoints that set it show the key.
- **`platformWide` is explicit, not inferred from a null `organizerUuid`**,
  because a null reads equally like "unknown".
- **The CSV export puts the period in the FILENAME**, not a preamble row: a
  leading comment line breaks every parser that treats line 1 as the header, and
  the filename is what survives into someone's Downloads folder.

## Notifications are a RESOURCE, not three list fetches (user-service V37)

`notifications` + `GET /notifications`, `/unread-count`, `POST /{id}/read`,
`/read-all`. Replaces a console badge fabricated by polling `/admin/users`,
`/admin/service-requests` and `/events/inactive` every 60s per signed-in admin
and counting rows — which knew only about the three collections someone thought
to poll, had no read state, and **reached no non-admin at all**.

- **Every endpoint is scoped to the CALLER by SHAPE, not by a check.** The
  recipient is the JWT's `userUuid`; there is no path or query parameter naming
  a user, so there is nothing to point at someone else. `markRead` scopes by
  recipient **in the query**, so another user's notification is a 404
  indistinguishable from a missing one — no existence oracle. A token with no
  `userUuid` claim is a **400**, never an empty list, which would read as "you
  have nothing" and hide a broken session.
- **The type column is a VARCHAR and `NotificationType` is a constants holder,
  deliberately NOT an enum.** Producers live in other services and repos: an
  enum here means a marketplace deploy could emit a type user-service refuses to
  store, losing the notification exactly when it mattered. Unknown types are
  stored and served; an unrecognised severity falls back to INFO. **Never
  tighten this into an enum.**
- **The S2S ingress is the existing `POST /users/internal/{uuid}/notify`,
  extended additively.** Everything past `message` is optional, so producers
  already calling it — marketplace restock alerts, event-service approvals —
  keep working untouched and start populating the bell immediately as
  `GENERAL`/`INFO`. Filling `type`/`severity`/`subject`/`deepLink` makes a
  notification actionable rather than merely visible.
- **The in-app copy is recorded BEFORE the outbound dispatch**, synchronously.
  The dispatch is `@Async` and best-effort by design, so ordering it second
  means a dead SMS gateway costs the email, not the bell — and the bell is the
  one channel that exists for an account with no email or phone on file.
- **`deepLink` is server-supplied** so the console keeps no client-side
  type→route map that would go stale silently. `subject` is what lets it
  de-duplicate and refresh one screen instead of reloading everything.
- **`unread-count` is ETagged, and the validator is `count-latestCreatedAt`.**
  Neither half alone works: the count is unchanged when one arrives and another
  is read; the timestamp is unchanged when the user reads something. Together
  they move whenever the badge's meaning does. A matching `If-None-Match` is a
  **304 with no body but WITH the ETag**, so a long run of 304s keeps the
  client's validator fresh.
- **`read-all` is one statement** (`@Modifying` + `read_at IS NULL`), not a
  page-and-save loop — an admin back from leave can have thousands unread, and
  the loop is unbounded work inside their request. The predicate also makes it
  idempotent and preserves the original read time.
- **Marking read is idempotent and keeps the FIRST read time.** "When did they
  first see this" is the answer with any value.
- **Producers wired today:** service request submitted (fan-out to active
  `SUPER_ADMIN`s) and approved/rejected (**to the requester** — the §1.5 gap
  that made people re-visit pages). The submission fan-out is best-effort in a
  try/catch: the request is already saved, so a notification failure must never
  surface as a failed submission. `REVIEWER_ROLES` names built-ins only — a
  custom role holding `service-requests:approve` is not included, because a role
  created at runtime cannot be named in a constant.
- Everything else in the console's taxonomy has a name in `NotificationType`
  and **no producer yet**; adding one is a single call to the ingress, not new
  plumbing. The gateway route is `user-notifications-route`, pinned by
  `GatewayRouteTableTest`.
- **Not built: SSE.** `GET /notifications/stream` needs an emitter registry and
  Redis pub/sub fan-out (the cell runs multiple replicas, so a write on replica
  A must reach a connection held by replica B). The ETag makes polling cheap
  enough that this is a clean follow-up rather than a prerequisite.

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

This applies to **test code too**. CI runners are UTC, so a bare `now()`
in a test agrees with the UTC-stamping code under test and stays green
there — it only breaks locally, and every market we serve is UTC+1 to
UTC+3, so "only locally" means every developer. Qualify a test's `now()`
wherever the code under test reads a UTC clock or the fixture fills a UTC
column; a test that passes its own `now` into the method under test (e.g.
`TicketWindow.classify(start, end, now)`) is self-consistent and needs no
change. Check a timestamp fix under both `TZ=UTC` and `TZ=Africa/Harare`.

3. **Wire format — the BE renders, the FE parses nothing.** The client
   prints the string we send, verbatim. No client-side timezone arithmetic,
   no re-interpreting our value through `new Date(...)`, no appending a `Z`
   to patch one up. If a timestamp reads wrong on a screen, the fix belongs
   at our DTO edge — never in the client.

   - **User-facing surfaces serve the MARKET OFFSET**, e.g.
     `2026-09-09T08:10:22+02:00`, rendered from the stored UTC instant by the
     service's own `MarketTimeZone.atMarket`. Same instant as `...T06:10:22Z`
     and equally unambiguous ISO-8601, but the leading characters are the wall
     clock the reader is actually standing in, so a screen that prints them
     verbatim is correct with no conversion. A ZW cell shows Harare time
     because the BE resolved Harare — not because the reader's device
     happened to be in Harare.
   - **S2S payloads stay `Z`.** The consumer is another service that parses
     properly, and a per-cell offset there just invites double-conversion.
     Market offset is for human-facing surfaces only.
   - **Inbound stays permissive** — `Z`-suffixed, `±HH:mm` offsets
     (normalized to UTC) and legacy zoneless strings all parse — so S2S calls
     and in-flight clients survive rolling deploys. Clients send the wall clock
     the user typed and do no zone arithmetic of their own; event-service's
     `MarketTimeZone.toUtc` converts it. Contract pinned per service by
     `UtcJsonTimeConfigTest`.
   - **At rest is untouched.** Only the DTO edge renders at an offset. Columns,
     queries and every comparison stay UTC — see the storage rule above.

   **How the split is decided — per request, not per type.** The two surfaces
   share DTO classes: `BookingResponseDTO` is returned by `GET /bookings/{id}`
   *and* `GET /bookings/internal/{id}`, so the audience cannot live on the
   type. `WireAudience.isServiceToService()` reads it off the request and the
   Jackson 3 serializer in `UtcJsonTimeConfig` branches on it. Two markers,
   because neither alone is enough:

   - the `/internal/` path segment, covering endpoints built for S2S; and
   - the `X-Innbucks-S2S` header, stamped on every outbound Feign call by
     `S2sMarkerFeignInterceptor`. This is what closes the hole the path
     convention leaves — booking-service fetches `GET /events/{id}`, a public
     path, and that response carries `startDateTime`.

   With no request in scope at all — a domain event, a scheduled job — the
   answer is S2S, so nothing off the response path ever shifts. **Only the
   Jackson 3 module renders at an offset.** The Jackson 2 module (Feign request
   bodies, jjwt) stays on `Z`, so an outbound S2S call can't carry a per-cell
   offset. All four pinned by `UtcJsonTimeConfigTest`.

   Scan reports reach the same result by a different route: their DTOs are
   typed `OffsetDateTime` and converted explicitly in `ScanReportService`,
   because they start as `Instant` rather than a UTC `LocalDateTime`.

   **payment-service is covered too** (#581): it has its own `UtcJsonTimeConfig`
   + `MarketTimeZone` + `WireAudience`, so `promptExpiresAt`,
   `paymentCodeExpiresAt` and `checkoutExpiresAt` reach the customer at the
   market offset like every other human-facing timestamp.

The remaining long-term step (LocalDateTime → Instant + `timestamptz`
columns) is now invisible on the wire — the `Z` already ships — so it can
be done per-service without FE coordination whenever convenient.

**Where the rule came from.** The gate dashboard displayed raw UTC and every
scan read two hours early. The instruction was explicit — *"everything done on
the BE… no FE parsing"* — so `/scans/**` moved to the market offset and
`scan_attempts.attempted_at` stayed exactly as it was: a `timestamptz` mapped as
`Instant`, every query still in UTC, only the DTO edge changed. Read that as the
worked example of the rule in §3 above, not as a one-off carve-out for one
endpoint: rendering belongs on the BE everywhere a human reads the value.

## A booked seat category can't be deleted — but CAN be repriced

`SeatCategoryService.deleteCategory` refuses (409) while the category still has
**active** bookings; `updateCategory` is deliberately left unguarded. The two
halves look like one rule and are not.

- **Delete had no check at all.** It verified event ownership and nothing else,
  so an organizer could soft-delete a category holding paid tickets. The
  `booking_items` rows survive — nothing cascades — but the category they name is
  gone, stranding every holder on a category the event no longer lists.
- **"Active" is PENDING or CONFIRMED**, per booking-service's
  `/bookings/internal/categories/active-counts`. CANCELLED is excluded, so a
  category whose sales were all refunded IS deletable — the case an organizer
  actually needs.
- **Reprice is NOT blocked, and this is the deliberate half.** A booking freezes
  `BookingItem.priceAtBooking` at purchase (`BookingService:311`), and every later
  read — the ticket, the receipt, `OrganizerReportRepository`'s revenue sums —
  uses that stored value, never the category's current price. So a reprice cannot
  restate or invalidate a sold ticket; it only sets what the NEXT buyer pays,
  which is ordinary early-bird/late-release pricing. Blocking it would mean one
  sale locks a category's price for the life of the event. The console's own rule
  list asked for both to be refused on the grounds that repricing "silently
  invalidates paid tickets" — that premise is wrong, and
  `updateCategory_repriceStaysAllowedWithActiveBookings` exists to stop someone
  "completing" the guard by extending it to updates.
- **The guard FAILS CLOSED, and that is its whole point.**
  `BookingServiceClient.fetchActiveCountsByCategories` returns an empty `Optional`
  on any failure because its *other* caller renders public availability and must
  degrade rather than 500. A guard reusing that convention would read
  "booking-service is down" as "no bookings" and wave the delete through at
  exactly the moment it cannot be checked. An unanswerable question is refused
  with **503** (`ServiceUnavailableException`, new — 503 says *the server could
  not check*, where 409 says *the server checked and the state says no*), never
  assumed safe. Pinned by
  `deleteCategory_refusedWhenBookingServiceCannotBeReached`.
## The oversell guard is on ALLOCATION, not on approval

**The sellable ceiling is `SUM(seat_categories.total_seats)`, not
`events.total_capacity`.** booking-service claims capacity per category
(`categoryInventoryRepository.tryClaim`, seeded from `category.totalSeats`) and
its own comment says so: *"the per-category counter — not a seat row — is the
oversell guard now."* `consumeEventAvailability` runs AFTERWARDS, swallows every
failure and blocks nothing — `availableTickets` is a display mirror. So
categories summing above `totalCapacity` genuinely sell more tickets than the
venue holds, and the guard is enforced at both writes that can create that state:

1. **seat-service `createCategory`** — 409 when
   `sum(live categories) + new > event.totalCapacity`.
2. **event-service `updateEvent`** — 409 when a new `totalCapacity` is below the
   already-allocated sum. Without this the same bad state is reachable from the
   other side: allocate 100 of 100, then edit the event down to 80.

- **Do NOT "fix" this by guarding `approveEvent` instead** — that was the
  console's literal request and it would be theatre. `Event.rejected` defaults to
  **false** and `active` to **true**, so a new event is sellable from creation and
  never passes through approval at all; booking-service's `EventLookupDTO` carries
  no state field and never checks one. The dangerous event is the one nobody ever
  rejected. Allocation is when the over-sale becomes possible, so allocation is
  where it is refused.
- **Under-allocation is allowed, deliberately.** Only exceeding capacity can
  oversell; falling short just means not all capacity is on sale, which is every
  intermediate state of building a seat map one category at a time. Requiring
  equality would refuse each step.
- **Both halves fail CLOSED, and both had to opt out of a fail-open convention.**
  seat-service's `EventServiceClient.fetchEvent` and event-service's
  `SeatCategoryGateway.fetchForEvent` both degrade on failure (empty Optional /
  empty list) because their other callers render pages that must survive an
  outage. A guard reusing either would read "the other service is down" as "no
  capacity limit" / "nothing allocated". Hence `fetchAllocatedSeats` returns
  `Optional<Long>` — **empty means "could not ask", `Optional.of(0L)` means
  "asked, nothing allocated"** — and both guards raise `ServiceUnavailableException`
  (**503**, new in each service) rather than assuming. 503 says the server could
  not check and the client should retry unchanged; 409 says it checked and the
  state says no.
- **seat-service needed no new endpoint and event-service no new field.**
  `totalCapacity` was already on the public `GET /events/{id}` — seat-service's
  `EventLookupDTO` simply stopped discarding it (it is `Integer`, and a null reads
  as *unknown* → refuse, never as zero). The allocation is summed from the section
  seat counts the existing `GET /seat-categories` listing already returns; that
  IS `totalSeats`. Deliberately **not** `availableSeats`, which is live remaining
  stock rather than the allocation.
- **Legacy events already over-allocated** can only have capacity edited UP to at
  least the allocation — a partial correction still below it stays refused,
  because it is still an oversold event. The other way out is deleting categories.

## Scan reports: `to` is widened server-side, and why

`GET /scans/**` takes `from` + an **optional** `to`. A supplied `to` is rounded
up to the end of its **market-local** day (`MarketTimeZone.endOfLocalDay`);
omitting it means "up to this instant", evaluated per request.

- **The bug it fixes.** The bound is applied as a closed
  `BETWEEN :from AND :to`, and a dashboard naturally computes "now" once when
  its screen mounts, then re-sends that frozen value on every refresh. Every
  scan performed after page-load is then `> to` and invisible no matter how
  often the operator refreshes — which reads as "the report is stale" when the
  row committed correctly all along. The write is synchronous and in the same
  transaction as the redemption, and nothing caches the read; the window was
  always the whole problem.
- **Why widening is safe rather than a guess.** A scan attempt is stamped
  `Instant.now()` as it happens and can never be recorded in the future, so
  extending the bound can only admit rows that have genuinely already occurred.
  And it is NOT "always clamp to now": a bound on a past day still ends on that
  past day, so "what happened on the 3rd" keeps its exact meaning. Pinned by
  `ScanReportWindowTest`.
- **Known edge:** a screen open since before midnight sends a `to` on
  yesterday's local day, so post-midnight scans fall outside it until the range
  is re-picked. Omitting `to` avoids this entirely — prefer that for live views.
- Responses are `Cache-Control: no-store`. They previously carried **no** cache
  header at all, which leaves Cloudflare / a corporate proxy / the browser's
  heuristic freshness free to invent one and produce the same complaint for a
  completely different reason.

## Event times are MARKET-LOCAL on the wire in, UTC out

`POST/PUT /events` interpret `startDateTime` / `endDateTime` as **the local
wall-clock of the market the cell serves**, not UTC. `EventService` converts to
UTC via `MarketTimeZone` before anything reads them; responses stay UTC with the
`Z` suffix per the rule above.

- **Why.** An organizer types the time on the poster — "07:00" for a 7am Harare
  fun run. Storing that verbatim in a zone-less column that is then serialized as
  `07:00Z` asserts 07:00 UTC, i.e. 09:00 in Harare: the event was a genuinely
  wrong instant, two hours late in the detail page, reminders and scan windows.
  The conversion is deliberately server-side — clients send what the organizer
  typed and do no timezone arithmetic.
- **Clients must NOT pre-convert** or send an offset. A client that sends
  `+02:00` would be double-converted. The FE keeps rendering the `Z` response in
  the viewer's locale, which is display, not conversion.
- **`MarketTimeZone` must stay in lock-step with `CountryMdcConfig.KNOWN_COUNTRIES`.**
  An unmapped country throws at construction (taking the cell down) rather than
  defaulting to UTC — a UTC fallback would store every event at the wrong instant
  for that market while looking perfectly healthy. Not all markets share an
  offset: KE is UTC+3 and NG UTC+1, so never hardcode +2.
- **No supported market observes DST**, which is why `toUtc` needs no gap/overlap
  policy; `MarketTimeZoneTest` fails if a DST market is ever added.
- **Convert BEFORE the duplicate probe and the merged start/end order check** —
  both compare against stored UTC values, so an unconverted probe compares two
  different clocks and is wrong by the market offset.
- **Pre-fix rows are stored `+offset` wrong** (a 07:00 Harare event sits at
  07:00Z instead of 05:00Z). Fixing the write path does not correct them; they
  need a one-off shift per cell, which is a deliberate data decision — see the
  PR for the query.

## Cloudflare blocks `PUT` + multipart — uploads take `POST`

**The banner upload accepts BOTH `POST` and `PUT` on `/events/{id}/banner`, and
`POST` is the one that works from a browser.** Cloudflare's WAF on the
`innbucks.co.zw` zone refuses a `PUT` carrying a `multipart/form-data` body
before it reaches origin. Any new upload endpoint must be reachable by `POST`.

- **The symptom is a lie, and that is the expensive part.** The CORS preflight
  is an `OPTIONS` with no body, so it passes cleanly — the browser therefore
  sends the upload, Cloudflare answers `403` with its HTML block page, and that
  page carries no `Access-Control-Allow-Origin`, so the browser cannot read the
  403 either. The console shows *"Network error. Please check your internet
  connection"*. **Nothing reaches nginx, the gateway or the service**, so every
  server-side log is empty and the endpoint looks broken in Java.
- **Measured on the ZW cell 2026-09-21**, identical 300 KB body, only the verb
  or content type varying: `PUT` multipart → `403 text/html` (4/4, `server:
  cloudflare`); `POST` multipart → `401` JSON (3/3); `PUT` with a JSON body,
  `PUT` with no body, and `DELETE` → `401` JSON. So the trigger is `PUT`
  **combined with** a multipart body — not the method alone (`DELETE` is fine),
  not multipart alone (`POST` is fine), and not the body size (nginx allows
  50 MB and never logged a rejection). Ray ID `a3e73ca4aea45b7c`.
- **`PUT` is kept, deliberately.** It is the correct verb, no existing client
  breaks, and the endpoint is right again the moment a WAF exception lands.
  `EventControllerTest.replaceBanner_acceptsPOST_asWellAsPUT_soTheEdgeCannotBlockTheUpload`
  fails if the alias is removed; a second test pins that the alias does NOT
  widen the ownership rule — one handler serves both verbs, so they cannot drift.
- **Debugging rule this bought:** an unexplained "network error" on a write is
  an EDGE question before it is a code question. Read nginx's access log first —
  a request with no line there never reached us, and no amount of reading Java
  will explain it. `curl` the same call unauthenticated from the box; a
  `text/html` body means Cloudflare answered, `application/json` means we did.
  Same family as the EcoCash Cloudflare bot-challenge trap above: a partner (or
  our own) edge refusing a request the application never sees.

## Branching

> [!IMPORTANT]
> **All feature work goes on a `feature/<short-kebab-description>` branch — no exceptions.**
> Claude Code / web sessions frequently start on an auto-assigned
> `claude/<random-words>` branch (e.g. `claude/happy-archimedes-4zz7fc`). That is
> a harness artifact, **NOT** our branch convention — do not commit to it, push
> it, or open a PR from it. Before committing, create a feature branch from the
> latest `master` (`git checkout -b feature/<name> origin/master`) and work
> there. If you only notice after committing, rename with
> `git branch -m feature/<name>` before you push.

New work goes on a **`feature/<short-kebab-description>`** branch cut from the
latest `master`, where the suffix names the feature being added (e.g.
`feature/api-gateway-route-tests`). One feature per branch; push with
`git push -u origin <branch>` and open the PR **ready for review, not a
draft**.

> [!IMPORTANT]
> **Don't open draft PRs here, and reach for the REST API rather than `gh pr *`.**
> A draft PR cannot be merged, and taking a PR out of draft is a **GraphQL-only**
> operation — there is no REST field for it. GraphQL on this account gets refused
> with `graphql_rate_limit` often enough to matter, and misleadingly: `gh api
> rate_limit` can report `graphql: 5000/5000` while mutations are still being
> refused, because it is a secondary limit rather than the hourly quota. That
> combination stranded PR #570 fully green but unmergeable, and it had to be
> merged by hand.
>
> `gh pr create`, `gh pr ready`, `gh pr merge` and `gh pr list` all go through
> GraphQL and fail the same way. The REST equivalents do not:
>
> ```sh
> # create (accepts "draft": false in the JSON body)
> gh api repos/MpofuSlim/ticketing-system/pulls --method POST --input pr.json
> # edit the body
> gh api repos/MpofuSlim/ticketing-system/pulls/<n> --method PATCH --input body.json
> # poll CI for a commit
> gh api repos/MpofuSlim/ticketing-system/commits/<sha>/check-runs \
>   --jq '.check_runs[] | "\(.name): \(.status) \(.conclusion)"'
> # merge (this repo uses merge commits — see the (#NNN) two-parent history)
> gh api repos/MpofuSlim/ticketing-system/pulls/<n>/merge --method PUT --input merge.json
> ```
>
> Un-drafting is the one step with no REST equivalent, which is the whole reason
> not to open drafts in the first place.

Schema changes go in `src/main/resources/db/migration/V<N>__*.sql`
(PostgreSQL + Flyway, `ddl-auto: validate` on every data service). The
loyalty platform landed via `claude/add-loyalty-service` (merged in #91) and
the old H2 sibling branch was deleted — the legacy `claude/*` branch names
are history; use `feature/*` from now on.

## CI/CD & supply-chain integrity (OWASP A08)

**These are invariants — a change that weakens any of them needs a deliberate,
called-out reason, not a silent revert.**

- **Every third-party GitHub Action is pinned to an immutable commit SHA**, with
  a trailing `# vX.Y.Z` comment — never a movable tag (`@v4`, `@main`). A
  movable tag lets a hijacked/retagged release run arbitrary code in CI with the
  workflow's token. Dependabot's `github-actions` ecosystem bumps the SHA + the
  version comment together; keep them in lock-step. When adding a new action,
  resolve its SHA (`git ls-remote https://github.com/<owner>/<repo> refs/tags/<tag>`)
  and pin it — do NOT paste a floating tag.
- **Every workflow declares least-privilege `permissions:`.** Default to
  `contents: read`; escalate per-job only where required (`pull-requests: write`
  for the dependency-review comment; `packages: write` + `id-token: write` +
  `attestations: write` on the Release build for GHCR push + provenance).
- **The Release build scans before it pushes, then signs.** Trivy scans the
  locally-loaded image (CRITICAL/HIGH, os+library, `--ignorefile .trivyignore`)
  and gates the push; only then is the image pushed **with a SLSA provenance
  attestation + SBOM** (`provenance: mode=max`, `sbom: true`) and a GitHub-native
  signed build-provenance attestation. Verify a deployed digest with
  `gh attestation verify oci://ghcr.io/<owner>/<service>@<digest> --repo MpofuSlim/ticketing-system`.
  **Called-out exception (this repo is now PRIVATE):** the two `attest-build-provenance`
  steps are gated on `github.event.repository.private == false`. GitHub does not
  offer build-provenance attestations for **user-owned private** repos — the step
  fails `Failed to persist attestation: Feature not available for user-owned
  private repositories`. That's a permanent capability gap, not the transient OIDC
  blip the retry wrapper was built for, so the retry (which deliberately has no
  `continue-on-error`) failed on every run and **red-lined the whole Release
  workflow after the image was already scanned and pushed** — all 7 service jobs
  on the #472 merge commit failed exactly this way, at the final step, with the
  images sitting in GHCR. Only the GitHub-native attestation is lost: images still
  ship with buildx SLSA provenance (`provenance: mode=max`) + SBOM, and Trivy still
  gates the push. `gh attestation verify` will not work until the repo is public or
  org-owned. **Watch for this failure mode:** a red Release run no longer implies
  the image is missing — check whether the push step itself succeeded before
  concluding a deploy is blocked.
- **Lombok is declared as an explicit `annotationProcessorPaths` entry** in the
  root pom's `pluginManagement`, and the base images build on the SAME JDK the
  tests run on. Both halves matter. Recent JDKs (23+) no longer run annotation
  processors that javac merely discovers on the classpath, so when Dependabot
  bumped six `eclipse-temurin` builders from 21 to 24, Lombok silently stopped
  running and every Release build died with hundreds of `cannot find symbol:
  variable log / getEventId()`. **`ci.yml` stayed green throughout** — it pins
  `java-version: 21`, so only the Docker builds saw JDK 24. Watch for that
  asymmetry: a green CI does not mean the images build. The processor path makes
  Lombok work on any JDK; `<release>` (not `-source`/`-target`) makes bytecode
  genuinely target-compatible. If you DO want to adopt a newer JDK, move
  `ci.yml`'s `java-version` and `<java.version>` in the same PR as the base
  images, so tests run on what production runs. **It recurred** (#621-#626:
  six images moved 21 -> 24, a non-LTS JDK past end of support whose frozen
  Alpine 3.22 base failed the Trivy gate on 27 OS CVEs; reverted in #630), so
  `dependabot.yml` now **ignores `eclipse-temurin` semver-major bumps** on
  every Docker entry. Remove that ignore in the same PR that moves the JDK —
  and move to an LTS.
- **`.trivyignore` is a governed waiver list** — every entry needs an owner +
  reason + review-date comment (rules are in the file). Prefer fixing/upgrading
  over waiving; the root `pom.xml` carries the CVE version-overrides.
- **PR-time SCA**: `ci.yml`'s `dependency-review` job flags any *new* High/Critical
  direct dependency a PR introduces (diff-scoped — it won't fail on the existing
  baseline). Transitive/library CVEs are caught by the Release Trivy image scan.
  **Called-out exception (this repo is now PRIVATE):** the job is gated to public
  repos (`github.event.repository.private == false`) AND its step carries
  `continue-on-error: true`. The action needs GitHub's Dependency Graph, which on
  a private repo requires paid GitHub Advanced Security; without it the action
  hard-errors `Dependency review is not supported on this repository` and reds
  **every** PR regardless of its contents (PR #472 introduced no dependencies at
  all and still failed). Same fix, same rationale as InnRewards — where an
  earlier gate on `repository.visibility == 'public'` did NOT skip (that payload
  field reads as truthy on a private repo), which is why the `if` uses the
  canonical `repository.private` boolean AND `continue-on-error` as a second
  guard. This drops only the PR-time *direct-dependency* advisory surface;
  transitive/library CVEs remain covered by the Release Trivy image scan. The job
  auto-re-enables if the repo goes public again or GHAS is licensed.
- **`innbucks-core-gateway` was retired** (A06) — it was an EOL Spring Boot
  3.2.4 connectivity spike, not a reactor module and not containerized, so
  nothing built or scanned it. It has been deleted from the repo. If the
  veengu/messenger integration it prototyped is rebuilt, do it on the Boot-4
  line as a proper reactor module (built + Trivy-scanned + attested like the
  rest) — do not resurrect a standalone 3.2.4 jar.

Deferred (documented, not yet done): base-image **digest**-pinning in the
Dockerfiles (would activate the already-configured Dependabot `docker`
ecosystem — currently a no-op against the floating `21-jre-alpine` tag; must be
paired so security point-releases still flow), and **verify-at-deploy** (a
`gh attestation verify` / cosign gate in the pull step so the box refuses an
unattested image).

## Cryptography & key management (OWASP A02)

**At-rest is keyed/hashed, never plaintext, for every sensitive field** — and
new sensitive columns MUST follow suit:

- Passwords + MFA backup codes: **Argon2id** (delegating `PasswordEncoder`,
  legacy-bcrypt verify only). TOTP secret: **AES-GCM-256** (`MfaSecretCipher`).
  National ID, audit rows, **OTP codes**, loyalty voucher/QR: **HMAC-SHA256**
  (keyed). Refresh/device/denylist tokens: SHA-256 (already high-entropy).
- **Low-entropy secrets (OTP is 6 digits) MUST be HMAC-keyed, not bare-hashed** —
  a fast unkeyed hash of a million-value space is trivially reversed from a DB
  read. `OtpHasher` (key `otp.hmac-secret`) mirrors `NationalIdHasher`.
- **Every keyed secret is env-var + guarded**: `ProductionSecretsGuard` refuses
  to boot under a deployment profile on a `change-me` placeholder. Boot-required
  set now includes `AUDIT_HMAC_SECRET` (A09) and **`OTP_HMAC_SECRET`** (A02) —
  provision both per cell (`openssl rand -base64 48`) or user-service won't start.
  `AUDIT_HMAC_SECRET` is now **also** guarded by payment-service, which grew its
  own tamper-evident `audit_events` table (A09 — money-movement events: payment
  code generation, confirmation, failure/unknown, settlement discrepancies) that
  seals each row with the same keyed HMAC + nightly `AuditIntegrityVerifier`
  (metric `payment.audit.integrity.broken`, alert `PaymentAuditIntegrityBroken`).
  Wire audit into new payment states via `PaymentRecordService.transition()` (the
  single lifecycle chokepoint). k8s auto-flows the secret via `envFrom: secretRef`
  (compose maps it explicitly — payment-service now has its own `AUDIT_HMAC_SECRET`).
  **Hash-chaining (A09, user-service, V32):** `row_hmac` only proves a row's
  *content* is intact — it can't detect a whole row being **deleted, reordered,
  or truncated** from the tail (each survivor still self-verifies). So every row
  also carries `chain_hmac = HMAC(key, prev_chain_hmac ‖ row_hmac)`, binding it to
  its predecessor; deleting any row breaks the link at the next survivor and the
  attacker can't repair the downstream chain without the key. Writes serialise on
  a single-row `audit_chain_head` table locked `SELECT … FOR UPDATE` inside the
  REQUIRES_NEW audit tx (DB-agnostic; stops two writers forking the chain).
  `AuditIntegrityVerifier` walks the chain oldest-first and exports
  `security.audit.chain.broken` → alert `AuditChainBroken` (**ticket**, vs the
  content-tamper `AuditIntegrityBroken` **page** — surviving content is intact and
  a break can also be a benign secret rotation). Pre-V32 rows have
  `chain_hmac = NULL` (legacy, never a break), same as pre-V29 for `row_hmac`.
  **Now also in payment-service** (V10, same design): `chain_hmac` +
  `audit_chain_head`, `payment.audit.chain.broken` → alert `PaymentAuditChainBroken`
  (ticket, vs the `PaymentAuditIntegrityBroken` page). Both services' audit logs
  now detect row deletion/reordering, not just content tampering.
  The guard also **fails boot on a blank `spring.data.redis.password` under a
  deployment profile** (all six data services) — Redis holds session-revocation
  + rate-limit state, so an unauthenticated Redis is a tamper surface; compose/k8s
  already require `REDIS_PASSWORD`, and this makes a forgotten one fail fast.
  **A02-M3 (done):** the guard is now **fail-closed on an EMPTY active-profile
  set** across all six data services' guards (discovery-server had a seventh
  until it was deleted) —
  "deployment" = an active-profile set containing NO `dev/test/it/local` profile,
  which now includes the empty set. A prod container launched without
  `SPRING_PROFILES_ACTIVE` no longer boots on the placeholders; local dev / a
  stray `java -jar` must opt out explicitly with a `dev`/`test`/`local` profile.
  No test normalisation was needed — every `@SpringBootTest` already pins an
  explicit `test`/`it` profile (the earlier "~8 no-profile tests" concern was a
  false positive: those files only *mention* `@SpringBootTest` in comments).
  Contract pinned by `user-service/.../config/ProductionSecretsGuardTest.java`.

Deferred (the A02 A−→A crux — **infra migrations, needs the running cluster +
staged rollout, NOT a code-only PR**):

- **Retire shared-secret HS256 → RS256/JWKS.** Today `jwt.secret` is a symmetric
  key present in every service, so any compromised service can *mint* fleet-wide
  tokens (not just verify). Fix: user-service signs with a private key; others
  verify via a published public key (JWKS). Migrate with a dual-verify window
  (verifiers accept HS256 **and** RS256), then flip minting to RS256, then drop
  HS256 — the security benefit only lands after the flip. Touches all six
  `JwtUtil`s. No FE impact (backends verify, not the app).
  - **Stage 1 (dual-verify) is DONE in code** — all six `JwtUtil`s now select the
    verification key by the token's own `alg` header (`keyLocator`): RS* → an
    optional `jwt.public-key` (PEM), else the HS256 secret. user-service can also
    MINT RS256 behind `jwt.signing-algorithm=RS256` (+ `jwt.private-key`, optional
    `jwt.key-id` for a `kid`), defaulting to HS256 so merge is a no-op. All keys
    are optional env vars (`JWT_PUBLIC_KEY` / `JWT_PRIVATE_KEY` / `JWT_SIGNING_ALG`
    / `JWT_KEY_ID`); RS256-signing misconfig fails fast at boot. Contract pinned by
    `user-service/.../security/JwtUtilRs256Test.java`.
  - **Remaining (operational, per the "needs the running cluster" caveat):**
    (1) generate an RSA keypair per cell + provision `JWT_PUBLIC_KEY` fleet-wide
    and roll every service (verifiers now accept both); (2) set user-service
    `JWT_SIGNING_ALG=RS256` + `JWT_PRIVATE_KEY` and roll it (mint flips to RS256 —
    this is where the security benefit lands); (3) once no HS256 tokens remain in
    flight (≥ max token TTL after the flip), drop `JWT_SECRET`. Do NOT flip (2)
    before (1) is deployed everywhere or the fleet can't verify the new tokens.
- **KMS/Vault custody + rotation** for `jwt.secret`, `mfa.encryption-key`, the
  HMAC secrets, and internal tokens. No rotation exists today (JWT has no `kid`;
  `MfaSecretCipher`'s `v1:` prefix already scaffolds multi-key).
- **In-cluster TLS/mTLS.** Only the Cloudflare/nginx edge is encrypted; service↔
  service, ↔Postgres (`sslmode=verify-full`), ↔Redis (TLS) are plaintext behind
  the edge. `06-networkpolicy.yaml` is segmentation, not encryption. Needs a
  mesh or per-hop TLS + cert management.

## Core banking — none (Oradian removed)

**There is no server-side core-banking integration.** The Oradian middleware
client is gone from both services that used it; the frontend calls **Veengu
directly** for wallet operations. Do not reintroduce a server-side wallet rail
without a deliberate decision — the FE owning it is the current architecture,
not an accident.

What went with it:

- **user-service** — the whole `corebanking` package (`CoreBankingPort` and its
  only adapter), `OradianClient` + config/properties, and the two endpoints that
  existed solely to read Oradian deposit accounts: `GET /auth/customer/deposits`
  and `GET /auth/customer/send-money/details/{phone}`. **Tier-2 registration is
  now purely local** — it used to mirror the customer into Oradian and roll the
  whole transaction back if that call failed (surfacing as a 502), so the failure
  mode where a customer could not reach tier 2 because an upstream was down is
  gone with it. `INNBUCKS_CORE_BANKING` / `ORADIAN_*` env vars are removed.
- **payment-service** — the entire wallet-transfer subsystem:
  `POST /payments/transfer`, `POST /payments/withdraw`,
  `GET /payments/transactions[/{id}]`, `OradianMiddlewareClient`,
  `TransactionService`/`Repository`/entity, `TransferLimitService` (velocity
  caps), `ReconciliationJob`, and the `TransactionCompletedEvent` →
  `PaymentNotificationListener` WhatsApp side-effect. The **ticket/order payment
  rails are untouched** — `POST /payments` (InnBucks 2D code + ZimSwitch card),
  `POST /payments/shop-checkout`, `Payment`/`PaymentEvent`, the settlement
  reconciler and the audit chain all stay.

**Two tables are left dormant rather than dropped**, same call as `event_outbox`
after Kafka: payment-service's `transactions` (V1/V2) and user-service's
core-banking linkage columns on `customer_profiles` (`oradian_external_id`,
`oradian_client_id` from V10; `core_banking_provider`, `core_banking_profile_id`
from V19). Applied migrations are never edited, unmapped columns and tables are
harmless under `ddl-auto: validate`, and the existing rows are real history —
they name the Oradian record each pre-cutover tier-2 customer was mirrored into,
which is exactly what a support or reconciliation query would need. Drop them in
a later migration once that history is genuinely worthless.

Also note `Payments-Frontend-Integration.md` now documents only auth, device
binding, idempotency and gateway rate limits — its transfer/withdraw/history/
deposits sections described the removed API and were cut rather than left
lying about what the backend serves.

## Messaging — no broker (Kafka removed)

**There is no message broker.** Kafka was removed once the review found it was a
**producer-only bus**: booking/payment published `booking.*` / `payment.*`
events through an outbox + `KafkaTemplate`, but **nothing consumed any topic**
(loyalty earn ran on a synchronous Feign call + a DB retry table instead). The
broker (a StatefulSet + ~1.5GB/cell) was pure cost, so it was decommissioned —
producer code, the booking transactional-outbox subsystem
(`event/BookingEventPublisher`, `outbox/*`), the payment `TransactionEventPublisher`,
`spring-kafka` deps, and the Kafka container/StatefulSet/NetworkPolicy entries all
deleted. The **empty `event_outbox` table is left dormant** (harmless under
`ddl-auto: validate`; no entity maps it) rather than dropped — drop it in a later
migration if you want.

Domain events are now **in-process only**: `ApplicationEventPublisher` +
`@TransactionalEventListener(AFTER_COMMIT)` still drive the notification
side-effects (`BookingConfirmed/Cancelled` → notifications, `TransactionCompletedEvent`
→ payment WhatsApp), so a rolled-back tx never fires a ghost side-effect — there
is just no cross-service bus. **Do not reintroduce a producer-only Kafka bus.**
If a genuine event-consumer use case lands, add a broker deliberately *with real
consumers* (and consumer-side idempotency), not a publish-only spike.

## Deploying to the EC2 k3s cell after a merge

> [!IMPORTANT]
> **Every time a PR merges to `master`, output the exact deploy commands for the
> service(s) whose code changed.** This is a standing expectation — don't make
> the operator ask.

The ZW cell runs on single-node **k3s** on the EC2 box (`10.0.146.246`), so
**deploys are manual via `kubectl`**. The Release workflow ends at build → scan →
push → attest and pushes every image twice: `:latest` and `:sha-<full commit>`.
(Its old `Deploy to EC2` job — a docker-compose-over-SSH deploy that predated
k3s and failed on every run at "Prepare SSH" — has been removed.)

> [!IMPORTANT]
> **The cell runs PINNED images, not `:latest`.** Every deployment's image is a
> `sha-<commit>` tag or an `@sha256:` digest (check with the `get deploy`
> command below). So **`kubectl rollout restart` deploys nothing new** — it
> re-runs the exact build already pinned, whatever was merged since. Measured
> 2026-09-30: loyalty-service was restarted after a merge, rolled out
> "successfully", and kept running the previous build (Flyway still reported the
> old schema version). A deploy is a `set image` to the merge's tag.

After a merge, once the merge commit's `Build, scan, push (<service>)` job in
the **Release** workflow is green:

```sh
git -C ~/ticketing-system pull
# pin ONLY the service(s) whose code changed to the merge commit's tag
# (full 40-char SHA; a short SHA is not a tag that exists):
kubectl -n ticketing set image deployment/<service> \
  '*=ghcr.io/mpofuslim/<service>:sha-<merge commit sha>'
kubectl -n ticketing rollout status deployment/<service>
```

- `set image` starts the rollout by itself — no restart needed after it.
- `<service>` = the owning module(s) of the merged diff (e.g. `user-service`,
  `api-gateway`). Pin just those.
- **See what each service is running:**
  `kubectl -n ticketing get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'`
- **Manifests still say `:latest`, so `kubectl apply -f deploy/k8s/` UN-PINS
  every Deployment it touches** back to whatever `:latest` is. Only apply when a
  manifest actually changed, apply the one file, and re-run the `set image` for
  each service in it afterwards so the cell is pinned again.
- Verify through the edge after the rollout — e.g. an unauthenticated call to a
  secured endpoint returns `401` (new image present) rather than `404` (old). A
  service with Flyway migrations can also be checked by its
  `flyway_schema_history` top row.
- InnRewards (`MpofuSlim/innrewards`) builds `loyalty-service` in its own repo
  and pins it the same way, with its own merge commit's SHA.

### Rolling back

There is no rollback workflow; a rollback is the same `set image`, to the
previous known-good pin (note it BEFORE you deploy — the `get deploy` output
above is the list):

```sh
kubectl -n ticketing set image deployment/<service> \
  '*=ghcr.io/mpofuslim/<service>:sha-<good-commit>'   # or @sha256:<digest>
kubectl -n ticketing rollout status deployment/<service>
```

- `kubectl rollout undo` also works now that images are pinned (each revision
  records its own tag), but an explicit `set image` says exactly which build you
  landed on — prefer it.
- A rollback across a Flyway migration is safe only if the migration was
  additive (new nullable column / table). The old build ignores columns it does
  not map; it cannot un-apply a migration.
- The old `Rollback` GitHub Action was a docker-compose-over-SSH deploy from
  before the k3s migration and never worked against the k3s cell; it has been
  **removed**.

## InnBucks Merchant API — the primary ticket-payment rail (2D code)

**Ticket payments (`POST /payments` in payment-service) run on THREE rails:
the InnBucks 2D-code rail (the default — `paymentRail` omitted), the
ZimSwitch COPYandPAY card rail (additive, `paymentRail=ZIMSWITCH_CARD`) and
the EcoCash EIP wallet rail (additive, `paymentRail=ECOCASH`; see the
sections below).** The earlier "exclusively InnBucks" wording predates the
other rails; what remains
non-negotiable is that the earlier server-side wallet debit
(`/bank/api/payment`) was removed at the InnBucks team's direction — do not
reintroduce it.

All three rails collect for ANY product behind an `OrderGateway`
(`orderType` + `orderRef`): `BOOKING` (the historical `bookingId` contract),
`MARKETPLACE` (`MKT-...` refs) and **`LOYALTY_VOUCHER`** (`VCH-...` refs —
InnRewards V47 voucher purchase orders, where a gift voucher is PAID FOR
before it exists and loyalty issues it, and sends its WhatsApp messages, the
moment the payment confirms). `LoyaltyVoucherOrderGateway` +
`LoyaltyVoucherOrderClient` speak loyalty's internal
`/loyalty/internal/voucher-orders/**` surface — loyalty serves DECIMAL major
units and PLAIN-MAP bodies (no ApiResult envelope; a bad internal token is a
bodyless 401), so the gateway owns the cents conversion and the client's
parsing deliberately differs from `MarketplaceOrderClient`'s. Cash voucher
payments never touch payment-service (staff confirm them in loyalty).
The voucher confirm also sends `paymentRail` (`INNBUCKS_CODE` / `ZIMSWITCH_CARD` /
`ECOCASH`) through `OrderGateway.confirm(…, rail)` — a default method, so only
the loyalty gateway uses it — so loyalty's voucher report can filter by payment
type; it is omitted when null and loyalty treats a missing rail as "electronic".
**A new `OrderType`, `PaymentRail` or `PaymentStatus` needs a migration that
re-creates its CHECK** (`chk_payment_order_type` / `_rail` / `_status`):
`LOYALTY_VOUCHER` shipped without one and Postgres refused every voucher
payment row until V16 — reported as a 409 "already in progress", because
`openPending` read every integrity failure as the one-payment-per-order race.
Now only `uq_payment_active_order` maps to that 409
(`PaymentRecordService.isActiveOrderConflict`), and
`LedgerVocabularyMigrationTest` fails the build when an enum outgrows its CHECK. The InnBucks canonical spec is
`docs/api/InnBucks_Merchant_Api_Doc_v1.0.9.pdf`, distilled (greppable) at
`docs/api/innbucks-merchant-api.md`.

Non-negotiables when touching this integration:

- **Amounts are in CENTS** on the Merchant API (booking totals are decimal
  dollars). `InnbucksPaymentService.toCents` is the single conversion point
  and the client cross-checks the generation response's amount echo — keep
  both, they are the 100x-charge guard.
- **Code generation is NEVER retried** (a retry can mint a second live code);
  the status inquiry (`POST /api/code/inquiry`, keyed by the code) is the only retried call. A row whose upstream status is
  UNKNOWN is never auto-expired — blocked slot beats double charge.
- The FE contract is the historical stub shape: `bookingId` in,
  SUCCESS/PROCESSING/FAILED out. `paymentCode`/`paymentCodeExpiresAt` are
  additive. The code reaches the customer ONLY via the response — the FE
  renders the code + QR on the checkout screen; there is no out-of-band
  delivery (no WhatsApp/SMS for the payment code). Trade-off accepted: if
  the FE drops the response, the code is lost — but that beats a
  notification-dependency that hides outages.
- Env vars deliberately keep their `BANK_API_*` names (same platform creds);
  the credentials must belong to a MERCHANT-type client allowed to generate
  PAYMENT codes.
- Refunds: real-time reversals are NOT available for code-based transactions
  (doc §10) — paid-but-unconfirmable bookings are an operator queue, watch
  `payment.payments.unconfirmed_retry{outcome=still_failing}`.

## ZimSwitch Online (COPYandPAY) — the card rail

**The second collection rail on `POST /payments`** (`paymentRail=ZIMSWITCH_CARD`,
additive — the InnBucks 2D code stays the default). Spec distilled at
`docs/api/zimswitch-copyandpay.md`; read it before touching the integration —
it pins the four wire facts that are easy to get wrong. Non-negotiables:

- **Amounts are MAJOR units** (`"92.00"`) on this rail — the OPPOSITE of the
  InnBucks Merchant API's cents. `ZimswitchCopyPayClient` owns the one
  cents→major rendering; `ZimswitchCardPaymentService.echoMismatch` is the
  100x guard (a paid status read whose amount/currency/merchantTransactionId
  echo disagrees with the ledger parks the row IN_DOUBT for an operator —
  never confirmed, never guessed).
- **Prepare-checkout is NEVER retried** (a retry can mint a second live
  checkout); the status GET is the only retried call. The status URL is
  ALWAYS rebuilt from OUR stored checkoutId — a browser-supplied
  `resourcePath` is never accepted (SSRF: it would splice attacker input
  into a Bearer-authenticated server-side URL).
- **The status read of a final outcome is ONE-SHOT** (the checkoutId dies
  after it), so on a paid read the money fact is persisted FIRST
  (`COMPLETED_UNCONFIRMED`) and the order confirm runs after — the inverse
  of the code rail's confirm-then-mark order. Status reads are throttled
  upstream to two per checkout per minute, enforced through the persisted
  `card_status_checked_at` stamp shared by poller and instant check.
- **A decline does not close the row**: the checkout stays alive upstream
  for a shopper retry (documented multi-transaction reuse); `200.300.404`
  ("no payment for this checkout") is the NORMAL pre-submission answer and
  only counts as positively-unpaid once the checkout's 30-minute ceiling has
  passed.
- Credentials (`ZIMSWITCH_ENTITY_ID` / `ZIMSWITCH_ACCESS_TOKEN`) are
  env-only secrets; blank = rail disabled (503 on card attempts). The
  Bearer token can create checkouts against the merchant — same custody
  rules as every other credential (A02).
- **Config must land in `deploy/cells/cell.<iso>.env`, not just
  `docker-compose.yml` / `.env.example`.** k3s services read their whole
  environment via `envFrom: [configMapRef: cell-zw, secretRef: cell-zw-secrets]`,
  so a key in neither source never reaches the pod — there is no per-service
  default to fall back on. Shipping only the credentials (which arrive via the
  gitignored `.local.env` → Secret) leaves the rail **half-provisioned**: it
  looks live but `ZIMSWITCH_SHOPPER_RESULT_URL` has no source and every card
  checkout is refused 503. That is exactly how the ZW cell shipped; the
  distinguishing signal is the boot-time `ZimSwitch card rail is
  HALF-PROVISIONED` ERROR and the `no_shopper_result_url` (vs `unconfigured`)
  metric tag. Blank credentials in the committed file are deliberate — blank
  fails SAFE (clean 503), whereas a `REPLACE_ME` placeholder is non-blank and
  would make the rail believe itself configured and fail at the gateway.
- Card-not-present refunds ARE supported by the platform
  (`paymentType=RF`) but are NOT modelled yet — refunds remain an operator
  procedure; see the spec doc's "Not yet modelled" list (also: webhooks,
  Transaction Reports, settlement recon for card rows).

## EcoCash Instant Payment (EIP) — the wallet rail

**The third collection rail on `POST /payments`** (`paymentRail=ECOCASH`,
additive). Spec distilled at `docs/api/ecocash-eip.md` — read it before
touching the integration; the PDF's own samples are unreliable in documented
ways (identity fields swapped, country code dropped, the merchant PIN echoed
back). Non-negotiables:

- **Amounts are decimal JSON NUMBERS in MAJOR units** (`3.00`) — a third
  convention (InnBucks: integer cents; ZimSwitch: major-unit strings).
  `EcocashEipClient` owns the one cents→major rendering; the echo guard on a
  COMPLETED read (`totalAmountCharged`/amount/currency vs ledger) parks
  mismatches IN_DOUBT for an operator.
- **The charge is NEVER retried** — `clientCorrelator` is the upstream
  idempotency key, and a fresh correlator on a blind retry could debit the
  customer twice. The Query (`GET .../{endUserId}/transactions/amount/
  {clientCorrelator}`) is the only retried call and the only truth source.
- **Ledger ordering is INVERTED vs the other rails**: PENDING →
  TOKEN_ISSUED (correlator persisted) happens BEFORE the upstream call,
  because the EIP "instrument" is a PIN prompt EcoCash delivers to the
  customer's phone — a crash mid-call can leave a payable prompt live. An
  AMBIGUOUS charge outcome therefore stays TOKEN_ISSUED for the poller,
  never markFailed.
- **`transactionOperationStatus` is the ONLY outcome field** (COMPLETED /
  FAILED / everything-else-is-open). Never gate on HTTP status or
  `responseCode`.
- **`EcocashCurrencies` is a hardcoded allow-list (`USD`, `ZWG`) and every
  charge resolves through it BEFORE the hold, the ledger row and the wire.**
  An unsupported `currencyCode` does not come back as a business rejection —
  measured on preprod it returns EcoCash's edge WAF `text/html` "Request
  Rejected" page, which the client correctly raises as transient (see the
  rule above), so the row would sit `TOKEN_ISSUED` forever holding the order's
  ONLY payment slot across all three rails. **The numeric ISO code is the
  quieter trap**: the charge echoes `840` back verbatim while the *query*
  normalises it to `USD`, and the query echo is what `echoMismatch` compares —
  so a numeric currency parks 100% of COMPLETED payments IN_DOUBT with no
  error at all. Hence also the `@PostConstruct` guard that logs an ERROR when
  `innbucks.currency` is unsupported and the rail is configured. **Do not make
  the set env-configurable** — that reintroduces exactly the footgun; adding a
  currency EcoCash later supports is a one-line reviewed code change.
  An order priced in an unsupported currency is a `422` (use another rail);
  an unsupported *cell* currency is a `503` (deployment fault, affects
  everyone). ZWG's query echo is still **unmeasured** — run one charge+query in
  ZWG before this cell transacts it, or those rows park IN_DOUBT.
- **A 2xx whose body is not a JSON object is INFRASTRUCTURE, not a status** —
  raise it as transient, never classify it. EcoCash sits behind Cloudflare and
  an F5 BIG-IP ASM, and the ASM serves its "Request Rejected / support ID"
  page with **HTTP 200** and `text/html`. Classifying that as `UNKNOWN` looks
  harmless but is not: `UNKNOWN` never closes a row, so a sustained block pins
  every charge in `TOKEN_ISSUED` holding the order's ONLY payment slot across
  all three rails — the customer can then never pay for that order by any
  method. Same trap by a second route: an unknown correlator answers **200
  with an all-null envelope** (NOT a 404), which is why "no status AND no
  echo" maps to `NOT_FOUND` while "no status WITH an echo" stays `UNKNOWN`.
  Both shapes are pinned verbatim in `EcocashEipClientContractTest`.
- **PREPROD IS `payonline.econet.co.zw` — the PDF's `payonline.ecocash.co.zw`
  is a trap.** The documented host sits behind a Cloudflare **bot challenge**,
  which a server-to-server client cannot pass by design (no browser to run the
  JS), so every charge is refused `403` there — with the `cf-mitigated:
  challenge` header proving it is Cloudflare mitigating, not EIP refusing.
  Confirmed by varying ONLY the User-Agent on an otherwise identical request:
  `ecocash.co.zw` answers `curl` 200 but our client 403; `econet.co.zw`
  answers **both** 200. This cost a full day of debugging while the
  credentials, merchant config, body and registered msisdns were all correct
  — so check the HOST first if EcoCash starts refusing. **The PRODUCTION host
  is NOT confirmed** (EcoCash said `.ecocash`, then `.econet`): get it in
  writing and probe it with our real User-Agent before go-live, or launch day
  reproduces this with real customers.
- **The client sends an honest `User-Agent`** (`EcocashEipClient.USER_AGENT`),
  because EcoCash's edge runs a UA **allow-list**: `curl/8.x` passes,
  `Java-http-client/21` (the JDK default) and even `Ticketize-Payments/1.0`
  are refused with a bodyless 403. It is therefore NOT a self-service fix —
  it is the stable identity EcoCash allow-lists. Never spoof a browser or
  another tool's UA to get through a payment partner's edge.
- **The notify webhook (`POST /payments/ecocash/notify`) is a TRIGGER, not
  a truth source** — unauthenticated body, so it only re-runs the Query
  resolver; constant 200; the poller stays authoritative. Public via the
  gateway's IP-keyed `ecocash-notify-write-route` (BEFORE the write
  catch-all, pinned by `GatewayRouteTableTest`).
- **`ECOCASH_NOTIFY_URL` must be the ABSOLUTE public edge URL WITH the
  `/foundry` prefix** (the QR-media lesson: EcoCash fetches from the public
  internet, where nginx strips the prefix). The shared `cell.zw.env`
  carries production's value; the staging box overrides it in
  `cell.zw.local.env`. Blank credentials fail SAFE (clean 503); credentials
  present but no notify URL = the half-provisioned boot ERROR.
- Refunds ARE supported upstream (`/transactions/refund` keyed by
  `originalEcocashReference` — persisted as `payment.ecocash_reference`)
  but are NOT modelled yet; operator procedure, same as the card rail.

## Veengu API reference — source of truth for payment integrations

**Two veengu API surfaces are pinned in-tree, and every veengu-backed
client MUST be modelled against the matching one** — not against ad-hoc
field names from a chat or a Postman export. If the wire shape we observe
in production diverges, update the JSON in-tree and ship the change in the
same PR as the client change so the contract test (per the WireMock
convention above) is anchored to a versioned source.

1. **`docs/api/veengu-openapi.json`** — the official **Veengu Platform
   Frontend API v3.1.0** (168 endpoints, 50 tags, contact `dev@veengu.com`,
   sandbox base URL `https://demo.veengu.cloud/api/`). The customer-session
   surface: what a logged-in app user does (P2P, purchase, top-up,
   statements, own KYC).
2. **`docs/api/veengu-integration-openapi.json`** — the official **Veengu
   Platform Integration API v3.1.0** (212 endpoints, 91 schemas, partner
   sandbox `https://veengu.cloud/integration/api`; instance base URL is
   provided by the platform tenant). The **B2B/server-to-server partner
   surface**: onboarding individuals/businesses, accounts/cards/
   beneficiaries, remittance & cash payout, incoming/outgoing/direct
   transfers, payroll, debit orders, reversals, callbacks. Auth is
   per-request headers — `V-Tenant` (tenant code) + `V-Access-Token`
   (partner API key; separate test/live keys), both provisioned by the
   platform tenant at partner onboarding (NOT self-service); optional
   `V-Profile` (act on behalf of a profile UUID discovered at
   registration/search) and `V-Api-Channel` (defaults to `MIDDLEWARE`).
   The access token can create financial transactions — env-var/secret
   custody only, never committed, per the A02 rules above.

Each spec is ~1 MB — too large to inline anywhere or grep usefully. Use
the snippets below
to inspect either file (swap the filename):

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

Do NOT try to model every field of every veengu DTO —
trim aggressively to only what we actually consume on the wire, and let
the unknown fields fall through as ignored JSON. The pinned spec exists
so anyone can re-derive the shape they need without rediscovering it
from runtime traces.
