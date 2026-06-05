<div align="center">

<img src="https://logo.clearbit.com/innbucks.co.zw?size=200" alt="InnBucks" height="60">

# Booking Write Path — Capacity & Scaling Hand-off

**Prepared for the InnBucks IT / Infrastructure Team**

</div>

---

**Scope:** Guest booking write path (`POST /bookings`) on the InnBucks Ticketing fleet
**Date:** 2026-06-05
**Status:** Application bottlenecks fixed (PRs #167–#171, all merged). Remaining
items below are **infrastructure / operational** and are this team's to action.

---

## 1. Executive summary

A load-testing campaign against the booking write path uncovered and fixed five
application-level bottlenecks (merged in PRs #167–#171). After those fixes, the
**measured capacity on a single 8-core / 30 GB EC2 box** is:

| Metric | Value |
|---|---|
| **Usable write throughput** (p95 < 20 ms, 0 errors) | **~300 req/s** |
| **Saturation throughput** (hard max the box will push) | **~400–450 req/s** |
| Behaviour past saturation | graceful — latency climbs, **no 5xx** until ~2× overload |
| Equivalent daily volume at the usable rate | **~26 million bookings/day** |

To scale **beyond ~450 req/s**, application changes are done — what remains is
**horizontal scaling + the Postgres connection budget**, detailed in §4.

There are also **three infrastructure issues** found along the way that are not
yet fixed and need this team's attention: a broken public login route (§4.5), an
un-codified Postgres tuning change (§4.2), and a connection over-subscription
(§4.1).

---

## 2. Measured capacity (current state)

Single box, all services + Postgres + Redis + nginx + gateway **co-resident**.
Test: constant arrival rate, guest bookings, 2-minute runs, 200k-seat category
(so results reflect compute, not inventory contention).

| Target rate | Achieved | p95 latency | 5xx | State |
|---|---|---|---|---|
| 150 req/s | 150 | 16 ms | 0% | coasting (3 VUs) |
| **300 req/s** | **300** | **19 ms** | **0%** | **comfortable — recommended ceiling** |
| 600 req/s | ~392 | 6.1 s | 0% | saturated, still no errors |
| 1000 req/s | ~446 | 14.1 s | 56% | overloaded — errors appear |

**How to read it:** throughput plateaus at ~400–450 req/s regardless of offered
load (600→392, 1000→446). Below ~300 req/s the box is fast and clean. Between
300 and 450 it still serves every request but latency climbs into seconds.
Above ~600 req/s offered, queuing exceeds the 5 s connection / circuit-breaker
timeouts and requests start returning 5xx.

**Bottleneck at saturation:** the single shared **Postgres**. Each booking drives
~4 DB operations (booking insert + active-seat cross-check + 2 seat-service
queries), so ~450 req/s ≈ ~1,800 DB ops/s against one instance with 20-connection
pools per service. Latency climbs from connection-pool queuing, not CPU on the
app tier (the app services stay near-idle — k6 needed only 3 VUs at 300 req/s).

---

## 3. What was fixed (application — already merged)

Context for *why* the numbers are what they are. Each PR removed the bottleneck
the previous one exposed.

| PR | Bottleneck | Fix |
|---|---|---|
| #167 | A pooled DB connection was held across 3 blocking upstream HTTP calls inside the booking transaction (capped throughput at `poolSize / callDuration` ≈ 8/s). | Resolve seats/tenant first with no connection held; commit only the DB writes in a short `TransactionTemplate`. |
| #168 | event-service tenant lookup ran on every guest booking; upstream Feign read-timeout was 5 s. | Skip tenant lookup for guests; tighten user/event Feign read-timeout to 300 ms (both are best-effort with fallbacks). |
| #169 | user-service tier lookup ran on every guest booking (always a miss for guests) → saturated user-service, tripped its circuit breaker. | Skip the tier lookup entirely for unauthenticated (guest) bookings. |
| #170 | booking-service fetched the **entire** available-seat pool per booking to pick one seat → ~7 MB / >1 s on a 50k-seat category → tripped the 1 s circuit breaker (503 storm). | seat-service returns a bounded random **sample** (`?limit=N`); booking requests 50. |
| #171 | #170's `ORDER BY random()` was still **O(N)** (sorts the whole pool) → re-tripped the breaker under concurrency. | Indexed random sampling via a random UUID pivot over the seats' PK + a `(category_id, status, id)` index → **O(log N)**, independent of inventory size. |

**Net effect:** the per-booking path went from multi-second, breaker-storming,
and effectively unusable under load to **~12 ms median, 0 errors at 300 req/s**.

---

## 4. Infrastructure action items

Ordered by priority. Items 4.1, 4.2 and 4.5 are **latent issues that exist
today** regardless of scaling plans.

### 4.1 Postgres connection budget — over-subscribed (do this first)

The six data services' Hikari pools sum to **150** max connections, but Postgres
default `max_connections` is **100**:

| Service | `DB_POOL_MAX` |
|---|---|
| loyalty | 50 |
| booking, seat, user, payment, event | 20 each |
| **Total** | **150** |

Under load this can produce `FATAL: sorry, too many clients already` — a failure
mode unrelated to the application. **Pick one:**

- **Raise `max_connections`** to comfortably exceed the sum of pools (e.g. 200),
  **and/or**
- **Introduce PgBouncer** (transaction pooling) so the services multiplex onto a
  small fixed set of real Postgres backends — required anyway before horizontal
  scaling (§4.3). The loyalty-service config already anticipates PgBouncer.

> Note: a Postgres connection is a full OS process (~5–10 MB). Do **not** treat
> `max_connections` as a throughput dial — past ~`2 × cores` *active* queries,
> more connections reduce throughput. Keep app pools modest (10–20) and let
> PgBouncer fan them in.

### 4.2 Postgres tuning — applied on the test box, NOT yet in IaC

These were set on the running container during testing and **will be lost on the
next rebuild** unless codified in `docker-compose.yml` / your IaC. Confirmed
applied (via `SHOW`): `max_connections=200`, `shared_buffers=5GB`, `work_mem=8MB`.
Recommended full set for an 8-core / 30 GB host:

```yaml
postgres:
  image: postgres:16
  command: >
    postgres
    -c max_connections=200
    -c shared_buffers=5GB
    -c effective_cache_size=15GB
    -c work_mem=8MB
    -c maintenance_work_mem=512MB
    -c max_worker_processes=8
    -c max_parallel_workers=8
    -c max_parallel_workers_per_gather=4
```

`shared_buffers` (default 128 MB → 5 GB) is the change that matters most for
performance — it keeps the hot booking/seat indexes in RAM. **Action: move these
into version-controlled config.**

### 4.3 Horizontal scaling — the path past ~450 req/s

booking-service and seat-service are **stateless** (coordination is via the
Postgres unique index + Redis idempotency), so they scale horizontally cleanly.
The gateway resolves replicas via Eureka + Spring Cloud LoadBalancer
automatically — no gateway change needed to add instances.

- Each additional booking-service replica adds **~300 req/s** of clean capacity,
  **provided** the Postgres connection budget (§4.1) is solved first — otherwise
  N replicas × 20 connections will exhaust Postgres.
- Scale **seat-service alongside** booking-service: each booking makes ~2
  seat-service calls, so seat-service sees ~2× the booking rate.
- **Recommended target architecture for >1k req/s:** 3–4 replicas each of
  booking + seat behind PgBouncer, with per-instance pools of ~10–15.

### 4.4 Gateway rate limiter — confirm production policy

`POST /bookings` is rate-limited at the gateway: **50 req/s replenish, 100
burst**, keyed per bearer-token (authenticated) or **per client IP** (guests).
This is a per-client DoS guard, **not** the system ceiling — but note all traffic
from a single IP (e.g. behind a shared NAT, or a single load generator) shares
one bucket. Confirm the replenish/burst values (`RATE_LIMIT_REPLENISH_PER_SECOND`,
`RATE_LIMIT_BURST_CAPACITY`) match expected real-world client distribution.

### 4.5 Public login is broken at the edge (nginx) — BUG, needs fixing

`POST $PUBLIC_URL/auth/login` returns **`405 Not Allowed` from nginx** — the
public reverse proxy forwards `/bookings/**` and `/seats/**` but not `/auth/**`,
so the request never reaches the gateway. **No one can log in through the public
URL.** The gateway itself routes `/auth/**` correctly (direct calls to the
gateway port succeed). Action: add an nginx location block forwarding `/auth/**`
(and audit for any other unrouted public paths).

### 4.6 Monitoring — what to watch in production

The load test showed the failure modes are quiet until they aren't. Alert on:

- **HikariCP pool utilisation / wait time** (booking + seat) — the saturation
  signal; latency climbs here first.
- **Circuit-breaker state** (`SeatServiceClient`, etc.) — an open breaker = 503s.
- **seat-service `GET /seats/available` p95** — should stay single-digit ms.
- **Postgres**: active connections vs `max_connections`, and slow-query log.
- **booking-service 5xx rate** and **p95 latency** — the user-facing SLO.

---

## 5. How to reproduce the load test

Single-file k6 script (`booking-flat.js`), constant arrival rate, guest path:

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
export const options = { scenarios: { w: {
  executor:'constant-arrival-rate', rate:Number(__ENV.RATE||150), timeUnit:'1s',
  duration:__ENV.DUR||'2m', preAllocatedVUs:500, maxVUs:2000,
}}};
export default function () {
  const phone = `+26377${Math.floor(1000000+Math.random()*9000000)}`;
  const res = http.post(`${__ENV.BASE_URL}/bookings`,
    JSON.stringify({eventId:__ENV.EVENT_ID, phoneNumber:phone, seats:[{categoryId:__ENV.CATEGORY_ID}]}),
    {headers:{'Content-Type':'application/json','Idempotency-Key':uuidv4()}});
  check(res,{'no 5xx':r=>r.status<500});
}
```

Setup + sweep:

```bash
# 1. Seed a category large enough that the run measures COMPUTE, not inventory
#    contention (rate × duration × ~0.6 << seatCount). 200k covers up to ~1k rps.
#    Requires an EVENT_ORGANIZER or SUPER_ADMIN token.
curl -sS -X POST "$BASE_URL/seat-categories" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"eventId":"<EVENT_ID>","name":"LOADTEST","price":1.00,
       "sections":[{"section":"L1","seatCount":100000},{"section":"L2","seatCount":100000}]}'

# 2. Sweep the arrival rate until `no 5xx` drops below 100% or p95 crosses SLO.
for r in 150 300 600 1000; do
  k6 run -e BASE_URL=$BASE_URL -e EVENT_ID=$EVENT_ID -e CATEGORY_ID=$BIG_CAT -e RATE=$r booking-flat.js
done
```

**Reading the results — important:**

- **`no 5xx` check** = the capacity marker. 100% → headroom remains; first dip → ceiling.
- **`http_req_duration{expected_response:true} p95`** = real latency of successful
  bookings. Watch it leave single-digit ms.
- **`http_req_failed`** is **misleading** here — it counts the **HTTP 409 "seat
  already booked"** responses, which are *correct* double-booking prevention
  under inventory contention, **not** errors or capacity limits. Ignore it for
  ceiling-finding; use `no 5xx` instead.
- **`dropped_iterations` / "Insufficient VUs"** = k6 itself can't keep up because
  server latency exploded → you're past saturation.

---

## 6. Known limitations & application follow-ups (not yet done)

Not blocking, but they cap *successful* throughput on a single hot event and are
worth scheduling:

1. **Seat availability has two sources of truth.** seat-service `seat.status`
   only flips on lock/confirm; a guest PENDING booking is **not** reflected in
   seat availability. So seat-service keeps offering seats booking-service then
   rejects with 409. Random sampling (#170/#171) mitigates the symptom; the real
   fix is to reconcile the two (e.g. filter the sample against active bookings,
   or hold the seat in seat-service on booking). This is the main driver of 409
   contention on a single heavily-booked category.
2. **event-service `getEvent` does synchronous seat-category enrichment** — the
   original reason upstream calls were slow. Worth making lazy / cached.
3. **Verify an index on `booking_item.seat_id`** — the per-booking
   `findActiveBySeatIds` cross-check scans by seat_id; confirm it's indexed
   before the table grows large in production.
4. **Composite index follow-up** in seat-service is in place (V6); revisit if
   categories exceed ~1M seats.

---

## 7. Appendix — bottleneck-discovery diagnostics

Useful one-liners for diagnosing the booking path in production (adjust service
names to your orchestrator):

```bash
# What is booking-service actually returning under load? (grouped messages)
docker compose logs --since 2m booking-service \
  | grep -ioE '"message":"[^"]*"' \
  | sed -E 's/"message":"//; s/"$//; s/[0-9a-f]{8}-[0-9a-f-]{27}/UUID/g' \
  | sort | uniq -c | sort -rn | head

# seat-service available-call latency buckets (should be <50ms)
docker compose logs --since 2m seat-service \
  | grep -oE '/seats/available[^"]*duration=[0-9]+ms' | grep -oE '[0-9]+' \
  | awk '{b=($1<50?"<50ms":$1<200?"50-200ms":$1<1000?"200ms-1s":">1s"); c[b]++} END{for(k in c) print c[k],k}'

# Hikari connection-timeout = pool exhaustion (the saturation smoking gun)
docker compose logs --since 5m booking-service | grep -i "Connection is not available"
```
