# Fleet load tests (k6)

Load, soak and smoke tests for the public surface of the fleet, run through
the **public gateway** exactly as the apps reach it. Tool: [k6](https://k6.io),
the same tool the booking write-path campaign used
([`docs/booking-capacity-and-scaling.md`](../docs/booking-capacity-and-scaling.md) §5,
`booking-flat.js`). That script sweeps `POST /bookings` alone to find the write
ceiling; this suite covers the rest of the fleet: catalogue reads, a checkout
that never pays, a loyalty till, and the gateway rate limiter.

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
```

---

## 1. Safety rules (read first)

- **Production is refused by default.** `BASE_URL` is classified before any
  request: a private or local host is `local`; a URL listed in `STAGING_URLS`
  (default `https://dtx.innbucks.co.zw/foundry`) is `staging`; **everything
  else is `production`** and the run aborts unless `ALLOW_PRODUCTION=true`.
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
- **Not loaded, on purpose:** `POST /auth/login` (limited to 5/min per
  identifier in user-service) and `POST /auth/refresh` (rotates the token and
  is reuse-detected — a replay revokes the whole session family).

> **Confirm the staging URL before the first run.** The default staging entry
> follows the request that commissioned this suite. `deploy/cells/cell.zw.env`
> describes its `https://dtx.innbucks.co.zw/foundry/...` `ECOCASH_NOTIFY_URL`
> as *production's* value (staging overrides it locally). If
> `dtx.innbucks.co.zw/foundry` serves production, set `STAGING_URLS` to
> staging's real URL — and change the default in `lib/config.js` — before
> anyone runs this.

---

## 2. Running it

From the repo root. With a k6 binary (v1.x, tested on 1.8.1):

```sh
k6 run \
  -e BASE_URL=https://dtx.innbucks.co.zw/foundry \
  -e PROFILE=smoke \
  -e SCENARIOS=catalogue,ratelimit \
  load-tests/fleet.js
```

With Docker (no install; `-e` on `docker run` reaches k6 as an env var):

```sh
docker run --rm -i -v "$PWD/load-tests:/scripts:ro" \
  -e BASE_URL=https://dtx.innbucks.co.zw/foundry \
  -e PROFILE=smoke -e SCENARIOS=catalogue,ratelimit \
  grafana/k6:1.8.1 run /scripts/fleet.js
```

**Full smoke against staging, every scenario** (after provisioning §6):

```sh
k6 run \
  -e BASE_URL=https://dtx.innbucks.co.zw/foundry \
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
| `ratelimit` | `ratelimit_429` count > 0, `ratelimit_recovered` count > 0, `ratelimit_unexpected` count == 0 | — | — | fixed | fixed |

Every scenario also requires `checks` > 99 %.

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

---

## 5. Environment variables

| Variable | Needed by | Default | Meaning |
|---|---|---|---|
| `BASE_URL` | all | — (required) | Public gateway base, with the edge prefix, e.g. `https://dtx.innbucks.co.zw/foundry` |
| `PROFILE` | all | `smoke` | `smoke`, `load` or `soak` |
| `SCENARIOS` | all | `catalogue` | Comma list of `catalogue`, `checkout`, `till`, `ratelimit` |
| `STAGING_URLS` | guard | `https://dtx.innbucks.co.zw/foundry` | Comma list of URLs treated as staging |
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

### CI secrets (for the workflow in §10)

`LOADTEST_CHECKOUT_PHONE`, `LOADTEST_TILL_TOKEN`, `LOADTEST_TENANT_ID`,
`LOADTEST_TILL_LOOKUP_PHONE` — each optional; a scenario that needs a missing
one refuses with its name.

---

## 7. What a run leaves behind

| Scenario | Rows | Messages |
|---|---|---|
| `catalogue` | none | none |
| `checkout` | One booking per journey on the LOADTEST event: **PENDING**, then **CANCELLED** by the expiry sweep ~5–5.5 min later; the seats return to the category. The rows stay as history (organizer guest list and CSV for that event). | **One cancellation SMS per booking to `CHECKOUT_PHONE`** when its hold lapses (WhatsApp only if the SMS send fails). Billable. Expect ~5 per smoke minute, ~390 per `load` run at the default rate, ~360 per soak. Bookings carry no email, so no email. |
| `till` reads | none (an unknown QR token is not recorded as fraud) | none |
| `till` writes | PURCHASE earns (`LOADTEST-<uuid>` references) and 1-point REDEMPTIONs on the test merchant; the test phone's wallet grows; the merchant's next invoice period counts these points. | An SMS/WhatsApp per earn and per burn to `LOYALTY_TEST_PHONE` (sent after commit; dropped and counted on `loyalty.notify.rejected` if loyalty's notification pool is saturated). |
| `ratelimit` | none | none |

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
write loyalty data. A GitHub runner is far from the cell: use the
`threshold_scale` input (e.g. `2`) if latency budgets fail on round-trip time
alone. `load` and `soak` are run by hand from a machine close to the cell.
