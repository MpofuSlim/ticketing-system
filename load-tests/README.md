# Fleet load tests (k6)

Load, soak and smoke tests for the public surface of the fleet, run through
the **public gateway** exactly as the apps reach it. Tool: [k6](https://k6.io),
the same tool the booking write-path campaign used
([`docs/booking-capacity-and-scaling.md`](../docs/booking-capacity-and-scaling.md) §5,
`booking-flat.js`). That script sweeps `POST /bookings` alone to find the write
ceiling; this suite covers the rest of the fleet: catalogue reads, a checkout
that never pays, a loyalty till, the gateway rate limiter, and (staging only)
the lending back office.

No dependencies beyond the k6 binary — no remote `jslib` imports, no Node, no JVM.

```
load-tests/
  fleet.js               entry point; SCENARIOS picks what runs
  lib/config.js          env, target guard, profiles, thresholds
  lib/util.js            think time, uuid, request params, JWT pre-flight
  scenarios/catalogue.js events, seat categories, marketplace catalogue
  scenarios/checkout.js  POST /bookings on a test event, then abandon (no payment)
  scenarios/till.js      a SHOP_USER cashier; writes behind ENABLE_WRITES
  scenarios/ratelimit.js 429 correctness check on the 2FA route
  scenarios/lending.js   loans back office, read-only, STAGING ONLY
```

---

## 1. Safety rules (read first)

- **Production is refused by default.** `BASE_URL` is classified before any
  request: a private or local host is `local`; a URL listed in `STAGING_URLS`
  (no default; you must name staging) is `staging`; **everything else is
  `production`** and the run aborts unless `ALLOW_PRODUCTION=true`.
  The guard fails closed: a typo or an unknown host counts as production.
  Plain `http://` to a public host is refused too.
- **Writes on production need a second key.** Even with `ALLOW_PRODUCTION=true`,
  `checkout` and till writes are refused without `ALLOW_PRODUCTION_WRITES=true`.
- **Checkout books only on a marked test event.** The event title or the
  category name must contain `LOADTEST` (`CHECKOUT_MARKER`), so a pasted id can
  never hold seats on a real show.
- **Till writes go to one named test merchant.** With `ENABLE_WRITES=true`,
  `TILL_TOKEN`'s `merchantId` claim must equal `LOYALTY_TEST_MERCHANT_ID`.
- **Every credential, id and phone comes from an env var.** Nothing real is
  committed; examples use placeholders such as `+263770000000`. Phones are
  printed masked (`****0000`). Never put a token on a shared command line you
  then paste into a ticket.
- **Never `/payments`.** No scenario calls payment-service.
- **Lending is staging-only, whatever the flags say.** loans-service runs on
  staging alone (production's Service has no endpoints: every `/lending/**`
  call there is a 500), so `lending` refuses any target classified as
  production — `ALLOW_PRODUCTION=true` does not unlock it. It is read-only:
  its one non-GET is a single sign-in, in `setup()`, shared by every VU and
  **never retried** (loans locks an account for 30 minutes after seven wrong
  passwords). It never calls loans' `forgot-password` (edge-denied, and it
  replaces the password) and refuses an account with a temporary password
  rather than change it. Never run it with `--http-debug=full`: that prints the
  sign-in body, password included.
- **Not loaded, on purpose:** `POST /auth/login` (limited to 5/min per
  identifier in user-service) and `POST /auth/refresh` (rotates the token and
  is reuse-detected — a replay revokes the whole session family).

> **`https://dtx.innbucks.co.zw/foundry` is PRODUCTION** (the ZW gateway
> origin in `deploy/cells/cell.zw.env`). It is hard-listed as production in
> `lib/config.js` (`KNOWN_PRODUCTION_HOSTS`), so naming it in `STAGING_URLS`
> does not make it staging. Run against the staging host's own URL and pass the
> same value in `STAGING_URLS`.

---

## 2. Running it

From the repo root. With a k6 binary (v1.x, tested on 1.8.1):

```sh
k6 run \
  -e BASE_URL=$STAGING_URL -e STAGING_URLS=$STAGING_URL \
  -e PROFILE=smoke \
  -e SCENARIOS=catalogue,ratelimit \
  load-tests/fleet.js
```

With Docker (no install; `-e` on `docker run` reaches k6 as an env var):

```sh
docker run --rm -i -v "$PWD/load-tests:/scripts:ro" \
  -e BASE_URL=$STAGING_URL -e STAGING_URLS=$STAGING_URL \
  -e PROFILE=smoke -e SCENARIOS=catalogue,ratelimit \
  grafana/k6:1.8.1 run /scripts/fleet.js
```

**Full smoke against staging, every scenario** (after provisioning §6):

```sh
k6 run \
  -e BASE_URL=$STAGING_URL -e STAGING_URLS=$STAGING_URL \
  -e PROFILE=smoke \
  -e SCENARIOS=catalogue,checkout,till,ratelimit \
  -e CHECKOUT_EVENT_ID=<loadtest event uuid> \
  -e CHECKOUT_CATEGORY_ID=<loadtest category uuid> \
  -e CHECKOUT_PHONE=+263770000000 \
  -e TILL_TOKEN="$TILL_TOKEN" \
  -e LOYALTY_TENANT_ID=<test tenant uuid> \
  -e TILL_LOOKUP_PHONE=+263770000000 \
  load-tests/fleet.js
```

**Everything that is configured:** `SCENARIOS=all` runs every scenario whose
inputs are set and prints the ones it skipped (`checkout` without
`CHECKOUT_EVENT_ID`, `till` without `TILL_TOKEN`, `lending` without
`LENDING_USERNAME`/`LENDING_PASSWORD` or off staging). A scenario you NAME is
never skipped: it refuses with the variable it is missing.

**Lending on staging** (after provisioning §6):

```sh
k6 run \
  -e BASE_URL=$STAGING_URL -e STAGING_URLS=$STAGING_URL \
  -e PROFILE=smoke -e SCENARIOS=lending \
  -e LENDING_USERNAME="$LENDING_USERNAME" -e LENDING_PASSWORD="$LENDING_PASSWORD" \
  load-tests/fleet.js
```

(Prefer exporting the two variables in the shell over typing the password on
the command line, where it lands in shell history.)

Add `--summary-export load-tests/results/summary.json` to keep the numbers
(`results/` is git-ignored).

Exit codes: `0` all thresholds held; `99` a threshold failed; `107` the script
refused to start (guard, missing variable, failed pre-flight) — the message
says which.

---

## 3. Profiles

| `PROFILE` | Shape per scenario | Default length |
|---|---|---|
| `smoke` (default) | 1 VU looping with think time | `SMOKE_DURATION` = 1m |
| `load` | arrival rate ramps 0 → target, holds, ramps down | `RAMP_DURATION` 2m + `HOLD_DURATION` 5m + 1m |
| `soak` | constant arrival rate at the soak target | `SOAK_DURATION` = 30m |

Rates are **journeys (iterations) per second**, not requests: one catalogue
journey is 6 requests. The `ratelimit` check has one fixed shape under every
profile (1 VU, 1 iteration, ~10 s).

Thresholds fail the run under every profile. Each scenario has its own: a p95
per endpoint, a p99 per endpoint on soak (2.5 × the p95 budget), an error
ceiling, and a checks floor, all keyed on the `scenario` tag so one scenario
never spends another's budget.

| Scenario | Endpoint (`endpoint` tag) | p95 budget | Errors | Load rate | Soak rate |
|---|---|---|---|---|---|
| `catalogue` | `events_list`, `event_detail`, `seat_categories`, `mkt_list`, `mkt_detail` | 800 ms | < 1 % | `CATALOGUE_RATE` 10/s | `CATALOGUE_SOAK_RATE` 5/s |
| | `mkt_image` | 1500 ms | | | |
| `checkout` | `checkout_event`, `checkout_categories`, `booking_status` | 800 ms | < 1 % | `CHECKOUT_RATE` 1/s | `CHECKOUT_SOAK_RATE` 0.2/s |
| | `booking_create` | 1500 ms | | | |
| `till` | `till_my_shop`, `till_vouchers_by_phone`, `till_redemption_rate`, `till_qr_status` | 800 ms | < 1 % | `TILL_RATE` 5/s | `TILL_SOAK_RATE` 2/s |
| | `till_earn`, `till_burn` (writes only) | 1500 ms | | | |
| `lending` | `lending_loans_list`, `lending_loan_detail`, `lending_my_work`, `lending_merchant_users` | 800 ms | < 1 % | `LENDING_RATE` 1/s | `LENDING_SOAK_RATE` 0.5/s |
| | `lending_work_queues`, `lending_queue_items`, `lending_credit_pending`, `lending_staff_search` | 1200 ms | | | |
| `ratelimit` | `ratelimit_429` count > 0, `ratelimit_recovered` count > 0, `ratelimit_unexpected` count == 0 | — | — | fixed | fixed |

Every scenario also requires `checks` > 99 %.

A `lending` endpoint the account cannot read (see §4) is not called, and its
budget then shows in the summary as a passing `p(95)=0s`: read it as "not
exercised", not as fast.

The budgets assume a generator close to the cell. From far away (a GitHub
runner, ~250 ms round trip to ZW), raise all of them at once with
`THRESHOLD_SCALE` (e.g. `2`) rather than editing numbers.

---

## 4. What each scenario touches

### `catalogue` — anonymous browsing, read-only

Per journey: `GET /events?page&size=10` → `GET /events/{id}` →
`GET /seat-categories?eventId=` → `GET /marketplace/catalog?page&size=20` →
`GET /marketplace/catalog/{id}` → `GET /marketplace/catalog/{id}/image?w=480`,
with 1–5 s of think time between screens. Event and listing ids are discovered
from the first catalogue pages in `setup()` (or fixed with
`CATALOGUE_EVENT_IDS` / `CATALOGUE_LISTING_IDS`). `SKIP_MARKETPLACE=true`
drops the marketplace half on a cell without marketplace-service.
**Leaves nothing behind.**

### `checkout` — book, then abandon (no payment)

Per journey: `GET /events/{CHECKOUT_EVENT_ID}` → `GET /seat-categories` →
3–8 s filling the form → `POST /bookings` (guest: `customerName` "Load Test",
`phoneNumber` from `CHECKOUT_PHONE`, `CHECKOUT_QTY` tickets, default 1) →
expects **201 PENDING** → `GET /bookings/public/{id}` (the payment screen's
read) → leaves. **No `/payments` call.** The pre-flight refuses if the
category cannot absorb the seats the run holds at once.

### `till` — a cashier (SHOP_USER) at the counter

Reads, every journey: `GET /loyalty/transactions/my-shop?page&size=20`,
`GET /loyalty/vouchers/users/by-phone/{TILL_LOOKUP_PHONE}/active` (a SHOP_USER
gets every voucher `code` as null — that is the contract, not a fault),
`GET /loyalty/redemption-rate?currency=USD`, and `POST /loyalty/qr/status`
with a random **unknown** token, which answers the documented **404**
(counted as success).

Writes, only with `ENABLE_WRITES=true`: on `TILL_EARN_RATIO` (0.3) of
journeys a PURCHASE earn — `POST /loyalty/transactions`
`{assigneePhone: LOYALTY_TEST_PHONE, type: PURCHASE, amount: TILL_EARN_AMOUNT,
currency, reference: "LOADTEST-<uuid>"}` → 201; on `TILL_BURN_RATIO` (0.1) a
1-point burn — `POST /loyalty/redeem {userId: LOYALTY_TEST_USER_ID, points: 1,
reference}` → 200 (skipped when `LOYALTY_TEST_USER_ID` is unset).

Every call carries `Authorization: Bearer $TILL_TOKEN` and
`X-Tenant-Id: $LOYALTY_TENANT_ID`.

### `ratelimit` — the gateway limiter answers 429 (correctness, not load)

`POST /auth/login/mfa` with a body that is not a real `mfaToken`. That route
(`auth-mfa-route`) is limited at the gateway to **1/s, burst 3, per client IP**;
user-service refuses the bogus token with a 400 before looking up any account
(no audit row, no lockout, nothing sent). One VU sends 10 probes at ~4/s,
expects at least one **429 with `X-RateLimit-*` headers**, waits 5 s, and
expects the next probe to get through (400) again. A 5xx or 404 anywhere fails
it. It also catches a limiter that has quietly **failed open** (this route uses
the stock Redis limiter, which lets everything through when Redis is down).

For ~5 s the generator's public IP has no 2FA budget on that cell: someone
signing in from the same IP (an office NAT) would get one 429 on the code step
and can retry.

### `lending` — a credit officer in the lending portal, read-only, STAGING ONLY

loans-service (`MpofuSlim/innbucks-loans`, `loans-service-route`, `/lending/**`)
is its own identity provider, so `setup()` signs in to **loans** once —
`POST /lending/v1/auth/login {username, password}` — and every VU reuses that
bearer (24 h by default, `JWT_EXPIRATION_MS`; the pre-flight refuses one that
would expire mid-run). A refused sign-in aborts the run with the reason (401
wrong password, 423 locked, temporary password, an admin or till account) and
is not retried.

Per journey, all GET, with 2–8 s of reading between screens:

| Request | `endpoint` tag | Who can read it (loans' own `@PreAuthorize`) |
|---|---|---|
| `/lending/v1/loans?page&size=20` (first 1–3 pages) | `lending_loans_list` | any signed-in user (CREDIT_MANAGER / FINANCE see every merchant's loans) |
| `/lending/v1/loans/{loanId}` | `lending_loan_detail` | same scope; lists documents WITHOUT content, so no document-view log row |
| `/lending/v1/work-queues` | `lending_work_queues` | CREDIT_MANAGER, FINANCE |
| `/lending/v1/work-queues/{stage}/items` | `lending_queue_items` | whoever may VIEW the stage; stages come from the summary |
| `/lending/v1/work-queues/mine` | `lending_my_work` | CREDIT_MANAGER, FINANCE |
| `/lending/v1/loans/pending-credit-decision?page=0&size=20` | `lending_credit_pending` | only when the summary lists `CREDIT_DECISION` |
| `/lending/v1/staff-members?status=ACTIVE[&q=]&page=0&size=20` | `lending_staff_search` | HUMAN_CAPITAL, CREDIT_MANAGER, FINANCE |
| `/lending/v1/merchants/{merchantCode}/users` | `lending_merchant_users` | CREDIT_MANAGER (loans' only user listing a non-admin can read) |

Loan ids come from the first list page (or `LENDING_LOAN_IDS`), merchant codes
from `GET /lending/v1/merchants`, stages from `GET /lending/v1/work-queues`,
all in `setup()`. Reads the account's groups cannot reach are skipped with a
warning, never sent to collect 403s. **Not loaded:** `GET /lending/v1/dashboard`
(SUPER_ADMIN only — this suite never uses an admin account) and every write:
nothing is decided, assigned, lodged, booked, paid, messaged or reset.
**Leaves nothing behind** but the sign-in.

---

## 5. Environment variables

| Variable | Needed by | Default | Meaning |
|---|---|---|---|
| `BASE_URL` | all | — (required) | Public gateway base, with the edge prefix, e.g. the staging host's `https://<host>/foundry` |
| `PROFILE` | all | `smoke` | `smoke`, `load` or `soak` |
| `SCENARIOS` | all | `catalogue` | Comma list of `catalogue`, `checkout`, `till`, `ratelimit`, `lending`; or `all` (every configured one) |
| `STAGING_URLS` | guard | — (none) | Comma list of URLs treated as staging |
| `ALLOW_PRODUCTION` | guard | `false` | `true` to run against a non-staging public URL |
| `ALLOW_PRODUCTION_WRITES` | guard | `false` | `true` to also allow checkout / till writes there |
| `THINK_TIME_SCALE` | all | `1` | Multiplier on think time (`0` = none) |
| `THRESHOLD_SCALE` | all | `1` | Multiplier on every latency budget |
| `SMOKE_DURATION` / `RAMP_DURATION` / `HOLD_DURATION` / `SOAK_DURATION` | profiles | `1m` / `2m` / `5m` / `30m` | Profile lengths |
| `CATALOGUE_RATE` / `CATALOGUE_SOAK_RATE` | catalogue | `10` / `5` | Journeys per second |
| `CATALOGUE_EVENT_IDS` / `CATALOGUE_LISTING_IDS` | catalogue | discovered | Fixed ids to browse (comma lists) |
| `SKIP_MARKETPLACE` | catalogue | `false` | Skip the marketplace half |
| `CHECKOUT_EVENT_ID` | checkout | — | The dedicated LOADTEST event |
| `CHECKOUT_CATEGORY_ID` | checkout | — | Its LOADTEST seat category |
| `CHECKOUT_PHONE` | checkout | — | Test phone(s) you control, E.164, comma list allowed |
| `CHECKOUT_QTY` | checkout | `1` | Tickets per booking (1–4) |
| `CHECKOUT_MARKER` | checkout | `LOADTEST` | Text the event title or category name must contain |
| `CHECKOUT_RATE` / `CHECKOUT_SOAK_RATE` | checkout | `1` / `0.2` | Bookings per second |
| `TILL_TOKEN` | till | — | Fresh access token of a SHOP_USER test account |
| `LOYALTY_TENANT_ID` | till | — | The test tenant (`X-Tenant-Id`) |
| `TILL_LOOKUP_PHONE` | till | — | Customer phone the till looks up |
| `TILL_CURRENCY` | till | `USD` | Currency of the rate read and of earns |
| `TILL_RATE` / `TILL_SOAK_RATE` | till | `5` / `2` | Journeys per second |
| `ENABLE_WRITES` | till | `false` | `true` turns on earns and burns |
| `LOYALTY_TEST_MERCHANT_ID` | till writes | — | Must equal `TILL_TOKEN`'s `merchantId` claim |
| `LOYALTY_TEST_PHONE` | till writes | `TILL_LOOKUP_PHONE` | Phone that earns |
| `LOYALTY_TEST_USER_ID` | till burns | unset (no burns) | Loyalty user id of that phone in the test tenant |
| `TILL_EARN_RATIO` / `TILL_BURN_RATIO` | till writes | `0.3` / `0.1` | Share of journeys that earn / burn |
| `TILL_EARN_AMOUNT` | till writes | `1` | Earn amount in `TILL_CURRENCY` |
| `LENDING_USERNAME` / `LENDING_PASSWORD` | lending | — | The dedicated loans back-office test account (§6). Both unset: `SCENARIOS=all` skips lending |
| `LENDING_RATE` / `LENDING_SOAK_RATE` | lending | `1` / `0.5` | Journeys per second (~7–8 GETs each) |
| `LENDING_LOAN_IDS` | lending | discovered | Fixed numeric loan ids to open (comma list) |
| `LENDING_STAFF_STATUS` | lending | `ACTIVE` | Employment status the staff search filters on |
| `LENDING_STAFF_QUERIES` | lending | unset (no `q`) | Search texts for the staff register (comma list): test employee numbers, never a real person's name or phone |

---

## 6. Test accounts and data to provision

**catalogue** — nothing. It browses what the cell already lists.

**checkout**

1. A dedicated event whose title contains `LOADTEST`, dated in the future and
   created by a test organizer. It is public: it will show in the app's event
   list on that cell.
2. A seat category on it large enough that the run never sells out. The run
   holds about `rate × 330 s × CHECKOUT_QTY` seats at once (5-min hold + the
   30-s sweep); the pre-flight wants 1.5× that free. At the defaults 1,000
   seats is ample; the capacity doc's seeding call works:

   ```sh
   curl -sS -X POST "$BASE_URL/seat-categories" \
     -H "Authorization: Bearer $ORGANIZER_TOKEN" -H 'Content-Type: application/json' \
     -d '{"eventId":"<EVENT_ID>","name":"LOADTEST","price":1.00,
          "sections":[{"section":"L1","seatCount":5000}]}'
   ```
   (The event's `totalCapacity` must be at least the categories' sum — the
   oversell guard refuses otherwise.)
3. `CHECKOUT_PHONE`: a phone the team controls. It receives every cancellation
   SMS (see §7).

**till**

1. A test tenant, a test merchant in it and a shop of that merchant.
2. A **SHOP_USER** account on that shop. Till accounts belong to staff and
   need **2FA**, so k6 never logs in: sign in through the console, complete the
   code step, and copy the access token into `TILL_TOKEN` (from the login
   response's `token`, or the browser's network tab). k6 never sees a password
   or a TOTP secret.
   **Access tokens live 15 minutes** (`JWT_EXPIRATION_MS`), and the pre-flight
   refuses a token that will not outlive the run, so mint it just before.
   `load` (8 min) fits; the 30-minute `soak` does not — run the till soak as
   two `SOAK_DURATION=14m` runs with a fresh token each.
3. `TILL_LOOKUP_PHONE`: a customer phone with (ideally) a voucher in the test
   tenant. Not the cashier's own phone.
4. A redemption rate in force for `TILL_CURRENCY` (USD by default):
   `GET /loyalty/redemption-rate` answers 404 without one, and the till read
   counts that as a failure.
5. For writes: `LOYALTY_TEST_MERCHANT_ID` = the token's merchant;
   `LOYALTY_TEST_PHONE` a **registered** test customer phone (OTP-verified, so
   burns are not refused `USER_PENDING`) that is not a staff phone of that
   merchant (`STAFF_RECIPIENT`) nor the cashier's (`SELF_EARN`);
   `LOYALTY_TEST_USER_ID` its loyalty user id in that tenant
   (`GET /loyalty/users/me`, signed in as that customer, lists one `userId`
   per tenant; the row exists after its first earn).

**ratelimit** — nothing.

**lending** — staging only.

1. A **dedicated** user in the staging loans portal, created by a loans
   SUPER_ADMIN (`POST /lending/v1/merchants/{merchantCode}/users`): a test
   account, named as one (e.g. `loadtest-credit`), never a real person's.
   Group **`CREDIT_MANAGER`** — the one group that reaches every read in §4.
   Give it an email and mobile number the team controls: loans sends the
   username and a temporary password there. Never `SUPER_ADMIN` or `MERCHANT_TILL` (the pre-flight refuses both).
   Give it **no credit authority level**, so that even by hand it could not
   approve a loan once approval limits are configured. Loans has no read-only
   group: CREDIT_MANAGER can decide credit and assign queue items, so the
   password lives only in the secret store and the account is used by nothing
   but this suite. (`FINANCE` also works, without the merchant-users read;
   `HUMAN_CAPITAL` gets the loan list and the staff search only.)
2. Sign in to the portal once by hand and set a permanent password: a new
   account's is temporary, and the suite refuses to change it.
3. Some loans on staging the account can see. Without any, only the list page
   is read. `LENDING_LOAN_IDS` pins particular ones.
4. Optional: `LENDING_STAFF_QUERIES` with test employee numbers on the staging
   staff register.

If a run aborts with **423**, the account is locked (seven wrong passwords):
wait 30 minutes or have a loans SUPER_ADMIN reset it, and fix the secret
before the next run.

### CI secrets (for the workflow in §10)

`LOADTEST_CHECKOUT_PHONE`, `LOADTEST_TILL_TOKEN`, `LOADTEST_TENANT_ID`,
`LOADTEST_TILL_LOOKUP_PHONE`, `LOADTEST_LENDING_USERNAME`,
`LOADTEST_LENDING_PASSWORD` — each optional; a scenario that needs a missing
one refuses with its name (`all` skips a scenario whose main input is unset).

---

## 7. What a run leaves behind

| Scenario | Rows | Messages |
|---|---|---|
| `catalogue` | none | none |
| `checkout` | One booking per journey on the LOADTEST event: **PENDING**, then **CANCELLED** by the expiry sweep ~5–5.5 min later; the seats return to the category. The rows stay as history (organizer guest list and CSV for that event). | **One cancellation SMS per booking to `CHECKOUT_PHONE`** when its hold lapses (WhatsApp only if the SMS send fails). Billable. Expect ~5 per smoke minute, ~390 per `load` run at the default rate, ~360 per soak. Bookings carry no email, so no email. |
| `till` reads | none (an unknown QR token is not recorded as fraud) | none |
| `till` writes | PURCHASE earns (`LOADTEST-<uuid>` references) and 1-point REDEMPTIONs on the test merchant; the test phone's wallet grows; the merchant's next invoice period counts these points. | An SMS/WhatsApp per earn and per burn to `LOYALTY_TEST_PHONE` (sent after commit; dropped and counted on `loyalty.notify.rejected` if loyalty's notification pool is saturated). |
| `ratelimit` | none | none |
| `lending` | none (one successful sign-in; it only clears the account's failed-login counter) | none |

The booking rows and earns are real data on that cell. Run against staging.

---

## 8. The gateway limiter caps one generator

Every public route is rate-limited at the gateway, **per route and per key**:
the bearer token when present, else the client IP. The catch-all routes allow
**50 req/s, burst 100**. So from one machine:

- `catalogue` sends 2 requests per journey to the events route, 1 to the seat
  route and **3 to the marketplace route** — one IP tops out around
  **15 journeys/s**. The default `CATALOGUE_RATE=10` keeps every route under 50.
- `till` sends all of its ~4–6 requests per journey with ONE token, so all VUs
  share one bucket: about **10 journeys/s** per token.
- `checkout` is far below its limits at any sane rate; the SMS bill is its real
  limit.
- `lending` sends its ~7–8 GETs per journey with ONE loans token (signed in
  once for the run), so every VU shares one `loans-service-route` bucket: about
  **6 journeys/s** caps it. The default `LENDING_RATE=1` (~8 req/s) is set by
  the backend instead: loans-service runs **one replica** with a 19-connection
  Hikari pool on staging. Raise it in steps while watching §9.1.

Above those rates the gateway answers **429**, which this suite counts as a
failed request — it says "the generator is mis-sized", not "the fleet is
slow". To test beyond them, run several generators from different IPs (and
divide the rates), or raise `RATE_LIMIT_REPLENISH_PER_SECOND` /
`RATE_LIMIT_BURST_CAPACITY` on staging for the run. Do not spoof
`X-Forwarded-For`.

---

## 9. Reading the results

k6 prints a summary at the end:

- **Thresholds** come first: `✓`/`✗` per budget. Any `✗` = exit code 99.
- **`http_req_duration{scenario:…,endpoint:…}`** is the latency of each
  endpoint, measured at the client — it includes the round trip to the cell.
  Compare p95 with the same series on the server (§9.1) to split network from
  server time.
- **`http_req_failed{scenario:…}`** counts responses outside each request's
  expected status (201 for a booking, 404 for the unknown QR, …).
- **`checks`** name what went wrong (`booking PENDING`, `qr status 404 …`).
- **`dropped_iterations`** means k6 had no free VU when a journey was due: the
  server is slow enough that journeys pile up — past saturation, or raise
  `maxVUs` (it is 4 × the pre-allocated pool).
- A `checkout` **409** is a sold-out or fully-held LOADTEST category, not a
  fleet fault: seed more seats (§6).
- A burst of **429** in a load run is the gateway limiter (§8).

The booking capacity doc (§2) is the baseline for the write path: about
200 bookings/s clean on one box, with saturation showing first as Hikari wait.

### 9.1 What to watch in Prometheus while it runs

The cell runs Prometheus (no Grafana): `kubectl -n ticketing port-forward
svc/prometheus 9090:9090` and paste these into the graph page (or into a
Grafana pointed at it). `job` is the service name.

**`http_server_requests` — server-side latency and errors**

```promql
# p95 per service and endpoint (fixed SLO buckets 50ms..5s)
histogram_quantile(0.95, sum by (le, job, uri)
  (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[1m])))

# request rate and 5xx rate per service
sum by (job) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))
sum by (job) (rate(http_server_requests_seconds_count{status=~"5.."}[1m]))

# 429s from the gateway limiter
sum (rate(http_server_requests_seconds_count{job="api-gateway",status="429"}[1m]))
```

**Hikari — the first thing to saturate** (capacity doc §2)

```promql
hikaricp_connections_active{job=~"event-service|seat-service|booking-service|user-service"}
  / hikaricp_connections_max
hikaricp_connections_pending                       # anything > 0 sustained = queueing
rate(hikaricp_connections_timeout_total[1m])       # > 0 = callers got "Connection is not available"
rate(hikaricp_connections_acquire_seconds_sum[1m])
  / rate(hikaricp_connections_acquire_seconds_count[1m])   # mean wait for a connection
```

loyalty-service and marketplace-service export the same series under their own
`job`.

**loans-service (`lending`)** — the `loans-service` scrape job
(`prometheus/prometheus.yml`) reads `/actuator/prometheus` on 8088. It is
**staging only**: on production it reads `up=0` by design, so read these on
staging's Prometheus. Its `http_server_requests` carries the same fixed SLO
buckets (50ms … 5s); `uri` is the full template, e.g.
`/lending/v1/loans/{loanId}`.

```promql
up{job="loans-service"}                            # 1 on staging, before you start
histogram_quantile(0.95, sum by (le, uri)
  (rate(http_server_requests_seconds_bucket{job="loans-service",uri=~"/lending/.*"}[1m])))
sum by (uri, status) (rate(http_server_requests_seconds_count{job="loans-service"}[1m]))
hikaricp_connections_active{job="loans-service"} / hikaricp_connections_max{job="loans-service"}  # max 19
hikaricp_connections_pending{job="loans-service"}
rate(hikaricp_connections_timeout_total{job="loans-service"}[1m])  # loans fails a wait after 5s
sum(jvm_memory_used_bytes{job="loans-service",area="heap"}) / sum(jvm_memory_max_bytes{job="loans-service",area="heap"})
rate(jvm_gc_pause_seconds_sum{job="loans-service"}[1m])
process_cpu_usage{job="loans-service"}
```

A 401 or 423 on `/lending/v1/auth/login` in the gateway's series means the
sign-in failed and the run aborted; a 500 on every `/lending/**` call means the
target has no loans-service (production).

**JVM and GC**

```promql
sum by (job) (jvm_memory_used_bytes{area="heap"}) / sum by (job) (jvm_memory_max_bytes{area="heap"})
sum by (job) (rate(jvm_gc_pause_seconds_sum[1m]))           # fraction of time paused
sum by (job) (rate(jvm_gc_pause_seconds_count[1m]))
process_cpu_usage
jvm_threads_live_threads
```

**Cache hit ratio and HTTP client pools — if present.** On master at the time
this suite was written no service registers either, so these return nothing
until the change that adds them lands. Once they exist:

```promql
# Spring caches (Micrometer cache binder)
sum by (job, cache) (rate(cache_gets_total{result="hit"}[1m]))
  / sum by (job, cache) (rate(cache_gets_total[1m]))

# Apache HttpClient 5 pools (inter-service RestClients)
httpcomponents_httpclient_pool_total_connections{state="leased"}
httpcomponents_httpclient_pool_total_pending

# Gateway upstream pool (Reactor Netty)
reactor_netty_connection_provider_active_connections
reactor_netty_connection_provider_pending_connections
```

**Alerts that should stay quiet** under a sane run: `HikariPoolExhausted`,
`HighHttp5xxRate`, `HttpP99Slow`, `JvmHeapPressure`, `JvmGcTimeHigh`
(`prometheus/alerts.yaml`). Postgres has no exporter; for connections, run
`select count(*), state from pg_stat_activity group by state;` on `postgres-0`.

---

## 10. CI

`.github/workflows/load-test-smoke.yml` — **manual only** (`workflow_dispatch`;
never on push, pull request or schedule). It runs the **smoke** profile against
the `base_url` input with the scenarios you name, and uploads the k6 summary as
an artifact. `ALLOW_PRODUCTION`, `ALLOW_PRODUCTION_WRITES` and `ENABLE_WRITES`
are not inputs and are pinned `false`, so CI can never target production or
write loyalty data. `lending` takes its account from the
`LOADTEST_LENDING_USERNAME` / `LOADTEST_LENDING_PASSWORD` secrets and, being
staging-only, refuses any URL not listed in `LOADTEST_STAGING_URLS`. A GitHub runner is far from the cell: use the
`threshold_scale` input (e.g. `2`) if latency budgets fail on round-trip time
alone. `load` and `soak` are run by hand from a machine close to the cell.
