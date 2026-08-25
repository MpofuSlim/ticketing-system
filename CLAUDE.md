# Ticketing System — Claude memory

Project-wide instructions for Claude when working in this repo.

> [!IMPORTANT]
> **Branch naming: always `feature/<short-kebab-description>`, cut from `master`.**
> NEVER commit or push feature work on the session's auto-assigned
> `claude/<random-words>` branch (e.g. `claude/happy-archimedes-4zz7fc`) — that
> name is a harness default, not our convention, and must never appear on a PR.
> Create the `feature/*` branch first. Full rules under [Branching](#branching).

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

3. **Wire format** — every `LocalDateTime` the four services serialize
   carries the explicit `Z` designator (`2026-07-27T07:19:00Z`), via each
   service's `UtcJsonTimeConfig` Jackson module. Browsers/FEs parse it as
   the UTC instant it is and render local time correctly (this fixed the
   "times show 2h behind in Harare" bug). Inbound stays permissive —
   `Z`-suffixed, `±HH:mm` offsets (normalized to UTC) and legacy zoneless
   strings all parse — so S2S calls and in-flight FE code survive rolling
   deploys. Contract pinned per service by `UtcJsonTimeConfigTest`. FE code
   parsing dates should still guard (`s.endsWith('Z') ? s : s + 'Z'`) rather
   than blindly appending.

The remaining long-term step (LocalDateTime → Instant + `timestamptz`
columns) is now invisible on the wire — the `Z` already ships — so it can
be done per-service without FE coordination whenever convenient.

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
`git push -u origin <branch>` and open a **draft** PR.

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
  images, so tests run on what production runs.
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
  set** across all seven guards (6 data services + discovery-server) —
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
**deploys are manual via `kubectl`**. (The Release workflow used to carry a
`Deploy to EC2` job — a legacy docker-compose-over-SSH deploy that predated the
k3s migration and failed on every run at "Prepare SSH". It has been **removed**;
the Release workflow now ends at build → scan → push → attest, which is all we
need since `kubectl` pulls the freshly-pushed `:latest` image.) After a merge,
the routine on the box is:

```sh
git -C ~/ticketing-system pull
# only matters if deploy/k8s manifests changed (harmless no-op otherwise):
kubectl apply -f ~/ticketing-system/deploy/k8s/
# restart ONLY the service(s) whose code changed — images are :latest with the
# default Always pull policy, so a restart re-pulls the freshly-built image:
kubectl -n ticketing rollout restart deployment/<service>
kubectl -n ticketing rollout status  deployment/<service>
```

- `<service>` = the owning module(s) of the merged diff (e.g. `loyalty-service`,
  `user-service`, `api-gateway`). Restart just those, not the whole fleet.
- **`kubectl rollout restart` has NO `--all` flag** — it errors
  `unknown flag: --all` and nothing restarts. To roll the whole fleet use:
  `kubectl -n ticketing get deploy -o name | xargs kubectl -n ticketing rollout restart`
- A restart only helps once the new image is pushed: confirm the merge commit's
  `Build, scan, push (<service>)` job in the **Release** workflow is green before
  rolling.
- Verify through the edge after the rollout — e.g. an unauthenticated call to a
  secured endpoint returns `401` (new image present) rather than `404` (old).

### Rolling back

There is no rollback workflow — a version rollback on k3s is a one-liner. The
Release build pushes every image as both `:latest` and `:sha-<commit>`, so roll
a service back by pinning it to a known-good SHA tag:

```sh
kubectl -n ticketing set image deployment/<service> \
  '*=ghcr.io/mpofuslim/<service>:sha-<good-commit>'
kubectl -n ticketing rollout status deployment/<service>
```

Return to the tip by re-pinning `:latest` (then restart to re-pull it):

```sh
kubectl -n ticketing set image deployment/<service> '*=ghcr.io/mpofuslim/<service>:latest'
kubectl -n ticketing rollout restart deployment/<service>
```

- `kubectl rollout undo` does **not** help here: every revision runs the mutable
  `:latest`, so undo reverts the pod spec but not the image version — pin the SHA
  tag instead.
- The old `Rollback` GitHub Action was a docker-compose-over-SSH deploy from
  before the k3s migration (it SSH'd to the box and ran `docker compose ... up`,
  failing at "Prepare SSH" the same way the retired `Deploy to EC2` job did). It
  never worked against the k3s cell and has been **removed**; use the `kubectl`
  procedure above.

## InnBucks Merchant API — the primary ticket-payment rail (2D code)

**Ticket payments (`POST /payments` in payment-service) run on TWO rails
since the ZimSwitch integration: the InnBucks 2D-code rail (the default —
`paymentRail` omitted) and the ZimSwitch COPYandPAY card rail (additive,
`paymentRail=ZIMSWITCH_CARD`; see the next section).** The earlier
"exclusively InnBucks" wording predates the card rail; what remains
non-negotiable is that the earlier server-side wallet debit
(`/bank/api/payment`) was removed at the InnBucks team's direction — do not
reintroduce it. The InnBucks canonical spec is
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
the snippets below (same shape as `OradianMiddleware/docs/oradian-swagger.json`)
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

As with Oradian, do NOT try to model every field of every veengu DTO —
trim aggressively to only what we actually consume on the wire, and let
the unknown fields fall through as ignored JSON. The pinned spec exists
so anyone can re-derive the shape they need without rediscovering it
from runtime traces.
