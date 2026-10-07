# SLOs & alert runbook

Every page-able alert in [`alerts.yaml`](alerts.yaml) anchors here. When a
page fires, the responder lands on the matching `#section` and gets:

1. What the alert means in plain words.
2. The first thing to check.
3. The escalation path.

## SLO targets

| Surface                             | SLI                                                              | SLO    | Window |
| ----------------------------------- | ---------------------------------------------------------------- | ------ | ------ |
| Customer-facing HTTP (gateway out)  | `non-5xx` ratio over all `http_server_requests_seconds_count`    | 99.9%  | 30d    |
| `/payments/shop-checkout`           | p95 of `payment_shop_checkout_duration_seconds_bucket`            | < 1 s  | 30d    |
| Loyalty earn (PURCHASE)             | non-error rate of `loyalty_transaction_posted_total{type=PURCHASE}` | 99.95% | 30d    |
| Each service                        | `up{job="<svc>"}` == 1                                            | 99.9%  | 30d    |

Error budget burn (>2× normal rate for 1h) trips the ticket-tier alerts;
catastrophic burn (>14.4× — exhausts a 30d budget in 2 days) trips the
page-tier alerts. Tune the multipliers in `alerts.yaml` once you have a
month of real traffic to baseline against.

The current rules use threshold-based alerts (e.g. p95 > 1s) rather than
budget-burn alerts to keep the entry-level setup readable. Once a real
SRE workflow is in place, switch to multi-window multi-burn-rate
(<https://sre.google/workbook/alerting-on-slos/>).

## Severity legend

- **page** — wake someone up. Customer impact is happening now.
- **ticket** — create a ticket for the next business day. Symptom isn't
  customer-visible yet, but the trend will be if ignored.

---

## Runbook entries

### `ServiceDown`

Prometheus can't scrape `<service>` for 2 minutes.

1. Check the pod / container status. If it's in `CrashLoopBackOff`,
   pull the last 200 lines of logs (the JSON pipeline now lets you
   filter by `service` + `level=ERROR` in Loki/CloudWatch Insights).
2. If the process is up but `/actuator/health` is `DOWN`, look at the
   `components` block in the response — the health indicator that's
   red points at the root cause (DB, Redis, etc.).
3. Restart only after capturing a heap dump if memory looked high
   beforehand (see `JvmHeapPressure`).

### `HighHttp5xxRate`

A backend service is throwing > 1% 5xx for 5 minutes.

1. Open Sentry → filter by service. Top issue is almost always the
   cause.
2. If Sentry shows nothing new, check the matching trace in
   Tempo/Jaeger via the `traceId` MDC on a recent error log.
3. Cross-reference against `HikariPoolExhausted`, `JvmHeapPressure`,
   and any infrastructure pages that fired in the same window.

### `ShopCheckoutP95Slow`

The end-to-end shop payment p95 has crossed 1 second. Customers will
notice — POS terminals appear "stuck".

1. Check `payment_shop_checkout_total{outcome="loyalty_unavailable"}`
   — if non-zero, loyalty-service is the cause; see
   `LoyaltyUnavailableFromPayment` below.
2. If loyalty is fine, look at the trace breakdown: `payment-service →
   loyalty-service → DB`. The slowest span is the cause. Common ones:
   - JPA N+1 on the loyalty side (look for repeated SELECTs in the span)
   - DB lock contention (look at PgBouncer queue depth)
   - GC pause (correlate with `JvmHeapPressure`)
3. If everything individually looks fast but the total is slow,
   suspect interceptor / filter overhead.

### `LoyaltyUnavailableFromPayment`

payment-service tried to call loyalty-service and got a 503 or network
timeout. **Customers cannot pay** while this is firing.

1. Check `ServiceDown{job="loyalty-service"}` — if firing, treat that
   as the root cause and follow its runbook.
2. If loyalty-service is up, the issue is between the two pods: DNS,
   network policy, service mesh. Curl from a payment-service pod:
   `curl -v http://loyalty-service:8086/actuator/health`.
3. Fall-back: route shop checkouts through a degraded mode that
   bypasses loyalty entirely (record cash-only, no points awarded).
   That mode does not exist yet — adding it is the post-mortem action.

### `ShopCheckoutRejectionSpike`

Loyalty rejected 5× its baseline number of checkouts. The `reason`
label tells you which:

- `MERCHANT_INACTIVE` — someone deactivated a merchant. Likely
  intentional but worth confirming with merchant ops.
- `SHOP_INACTIVE` — same, but at shop level.
- `USER_BLOCKED` — a single fraud rule is suddenly matching a lot of
  customers; cross-check `FraudRejectionsHigh`.
- `BAD_AMOUNT` / `RECIPIENT_REQUIRED` — a POS integration is sending
  malformed requests. Find the merchant_id in the spans and call them.
- `INSUFFICIENT_BALANCE` — many customers tried to redeem more than
  they had. Likely a frontend / app regression that mis-displays the
  balance.

### `FraudRejectionsHigh`

`loyalty_fraud_rejected_total` is firing > 0.5/s sustained. Either an
attack or a regression in our signing/QR generation. Look at the
`reason` tag:

- `BAD_SIGNATURE` — someone is replaying / forging vouchers. Rotate
  `LOYALTY_VOUCHER_SECRET` if confirmed external.
- `EXPIRED` at high rate — clock skew between pods, or the issuer
  service is stamping wrong expiry timestamps.
- `VELOCITY_LIMIT` — a single customer/merchant pair is hitting the
  per-window cap; investigate before relaxing.

### `PointsEarnedFlatlined`

PURCHASE transactions keep posting but the rules engine returns 0
points for all of them. This is almost always a config error:

1. `GET /loyalty/rules` for the affected tenant. Is there an active
   PURCHASE rule with a positive `pointsPerUnit`?
2. If yes, check whether a recent campaign override set the multiplier
   to 0 (a UI bug allowed it once before).
3. If no, restore the rule from the audit log.

### `HttpP99Slow`

A service's HTTP p99 has been above 2s for 15 minutes. The buckets stop at
5s, so a value of `+Inf` means "slower than 5s", not a broken query.

1. Break it down by endpoint:
   `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{job="<svc>"}[5m])))`.
   One `uri` dominating is the usual case — a CSV export or report is
   expected to be slow; a hot read path is not.
2. Cross-check `HikariPoolExhausted` (waiting for a connection shows up as
   latency, not errors) and `JvmGcTimeHigh`.
3. If every endpoint is slow at once, look at the dependency they share:
   Postgres, Redis, or an upstream rail.

### `HikariPoolExhausted`

All DB connections held for > 1 minute. Requests are queueing and will
time out.

1. Find the slow query: `SELECT * FROM pg_stat_activity WHERE state =
   'active' ORDER BY query_start;` on Postgres.
2. If a single slow statement is the cause, `SELECT pg_cancel_backend(
   <pid>);` to free a connection while investigating.
3. Long-term fix is almost never "increase pool size" — it's an
   uncached query or a transaction that forgot to commit.

### `JvmHeapPressure`

Heap > 85% for 10 minutes. Next major GC will pause the app for
seconds.

1. Capture a heap dump *before* restarting:
   `jmap -dump:format=b,file=/tmp/heap.hprof <pid>`.
2. Rolling-restart the affected pod (k8s deployment patch with a
   no-op env var change works).
3. Analyse the dump after the incident. Common offenders: caches with
   no eviction, log appenders queueing under back-pressure, Hibernate
   first-level cache on a long-running transaction.

### `JvmGcTimeHigh`

The JVM has spent more than 5% of wall time in GC pauses for 10 minutes.
Usually the heap is too small for the live set, or something allocates
heavily (a large export, a bulk issue).

1. Check `JvmHeapPressure` for the same pod — high GC time with a nearly
   full heap means the live set no longer fits.
2. Confirm the collector: `kubectl -n ticketing exec deploy/<svc> -- java
   -XX:+PrintFlagsFinal -version | grep -E ' Use(Serial|G1)GC '`. The pods
   set `-XX:+UseG1GC`; SerialGC here means `JAVA_TOOL_OPTIONS` was lost.
3. If one endpoint correlates (see `HttpP99Slow`), fix that allocation
   before raising the memory limit.

### `OtelExportFailing`

Spans can't reach the OTel collector. App keeps running but you lose
trace visibility — every alert that says "look at the trace" becomes
guesswork until this is fixed.

1. Check `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` env var on the pod matches the
   live collector address.
2. Curl the collector from inside the pod:
   `curl -v $OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.
3. If the collector is up but rejecting: check its receive logs for
   schema/protocol mismatch (usually a major OTel version skew between
   SDK and collector).

### `RedisDown`

`redis-exporter` reports `redis_up == 0`: Redis itself is not answering.

What that does to the fleet, by family (CLAUDE.md, "Redis — noeviction"):
revocation reads (`auth:tokenver:*`, `auth:revoked:*`) fail OPEN until each
access token expires (≤ 15 min); booking/payment requests carrying an
`Idempotency-Key`, and seat holds, fail CLOSED (5xx); `/auth/exchange` answers
503; the login, support and voucher-guess limiters and the gateway's fail-safe
routes fall back to per-replica windows; the gateway's other routes lose their
limiter.

1. `kubectl -n ticketing get pod redis-0` and its events / logs. A pod
   OOM-killed by the container limit (not Redis's own maxmemory) shows as
   `OOMKilled` — the limit is too close to `maxmemory`; see `RedisMemoryHigh`.
2. Redis runs with `appendonly yes` on a PVC, so a restarted pod replays its AOF
   and comes back with every session-revocation key it had.

### `RedisExporterDown`

Prometheus cannot scrape `redis-exporter:9121`. Redis may be fine; what is
lost is every other alert in the `redis` group.
`kubectl -n ticketing get pod -l app=redis-exporter` and its logs (an auth
error means `REDIS_PASSWORD` in `cell-zw-secrets` changed — restart the
exporter).

### `RedisMemoryHigh`

Redis memory is above 75% (ticket) / 90% (page) of `maxmemory`. The policy is
`noeviction`, so at 100% Redis refuses every write (`RedisRefusingWrites`)
rather than dropping keys.

1. See where it goes: `kubectl -n ticketing exec redis-0 -- sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" INFO memory; redis-cli --no-auth-warning -a "$REDIS_PASSWORD" INFO keyspace'`.
   The expected bulk is the booking/payment idempotency entries (24 h TTL,
   ~2 KB each); count them with
   `kubectl -n ticketing exec redis-0 -- sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --scan --pattern "POST /*" --count 1000 | wc -l'`.
2. Every key carries a TTL, so memory drains on its own as they lapse; a
   sale spike recedes within 24 h. If it will not wait, raise `maxmemory`
   live — **only while it stays ≤ ~2/3 of the container memory limit**
   (`kubectl -n ticketing get sts redis -o jsonpath='{.spec.template.spec.containers[0].resources.limits.memory}'`):
   `kubectl -n ticketing exec redis-0 -- sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" CONFIG SET maxmemory <n>mb'`. Beyond that, raise the limit and
   `maxmemory` together in `deploy/k8s/01-infra.yaml` (restarts `redis-0`).
3. **Never** switch the policy to an evicting one to make room — that is the
   silent fail-open this setup exists to prevent.

### `RedisRefusingWrites`

Redis answered writes with `-OOM command not allowed when used memory >
'maxmemory'`. Each caller is on its Redis-outage path (see `RedisDown` for the
list) — the loud, fail-closed-where-it-matters outcome `noeviction` was chosen
for. Act as for `RedisMemoryHigh`: make room by raising `maxmemory`
within the container limit, then find what grew.

### `RedisEvictingKeys`

Redis evicted keys, or reports a `maxmemory-policy` other than `noeviction`.
Under `noeviction` this never happens, so the policy drifted — usually a pod
restarted from a manifest without the change, or a manual `CONFIG SET`.
Evicted keys may include session revocations (a signed-out or deactivated
account's tokens accepted again downstream) and idempotency keys (a retried
payment or booking processed twice).

1. Put it back: `kubectl -n ticketing exec redis-0 -- sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" CONFIG SET maxmemory-policy noeviction'`.
2. Check `deploy/k8s/01-infra.yaml` on the host says `noeviction` too, or the
   next restart reverts it.
3. Evicted revocations stop mattering once the access tokens they covered
   expire (≤ 15 min). An account that must be cut off downstream sooner gets
   its version re-published by hand:
   `SET auth:tokenver:<userUuid> <users.token_version> PX 604800000`.

### `RedisKeysWithoutTtl`

`redis_db_keys - redis_db_keys_expiring > 0` for an hour: something stored a
key with no TTL. Under `noeviction` such a key never leaves memory, and a
counter without a TTL (the old `auth:rl:*` INCR-then-EXPIRE race) is a
permanent lockout.

1. List them (O(keys) — run off-peak):
   ```sh
   kubectl -n ticketing exec redis-0 -- sh -c 'R="redis-cli --no-auth-warning -a $REDIS_PASSWORD"
     $R --scan --count 1000 | while read -r k; do [ "$($R PTTL "$k")" = "-1" ] && echo "$k"; done'
   ```
2. `auth:rl:*` leftovers are stale counters: `DEL` them (the current limiter
   heals one on its next hit anyway). Anything else: find the writer, fix it
   to set the TTL in the same command or script, then `DEL` or `PEXPIRE` the
   strays.

### `AuditIntegrityBroken`

A row in `audit_events` failed its `row_hmac` recompute — its **content**
was altered after write. The HMAC key lives in app config/env
(`AUDIT_HMAC_SECRET`), never in the DB, so an attacker with only DB write
access can't forge a matching tag. Page severity: treat as an incident.

1. Find the row(s): the verifier logs `AUDIT_ROW_TAMPERED id=… eventType=…`
   on the service that scanned (user-service or payment-service).
2. Rule out the benign cause first: was `AUDIT_HMAC_SECRET` rotated without
   re-sealing existing rows? A rotation invalidates every prior tag at once —
   you'll see a large `checked`-sized spike, not one or two rows. If so, this
   is a process failure, not an intrusion; re-seal or accept the gap and
   document it.
3. If it's a handful of rows: pull the DB write/audit log for those `id`s,
   diff against any replica/backup, and open a security incident — someone
   with DB write access edited the forensic log.

### `AuditChainBroken`

A `chain_hmac` link failed to recompute — a whole row was **deleted,
reordered, or truncated** from the append-only log (V32 hash-chaining).
`row_hmac` can't see this because the surviving rows are individually
intact; the chain catches it because each row is bound to its predecessor.
Ticket severity (surviving content is intact; can also be a benign
rotation).

1. Find the break point: the scanning service logs `AUDIT_CHAIN_BROKEN
   id=… eventType=…` at the row *after* the gap — the deleted/moved row sat
   immediately before it.
2. Cross-check `AuditIntegrityBroken`: a chain break with **no** content-tamper
   alert points at deletion/reordering specifically (content edits would trip
   both). A chain break across *many* rows at once is the rotation signature —
   `AUDIT_HMAC_SECRET` changed without re-chaining.
3. If it's a genuine gap: reconstruct the missing row from the gateway access
   log / OTel spans for that window, pull the DB write log to see who deleted
   it, and escalate to a security incident.

### `AuditWriteFailed`

**Trigger:** `security_audit_write_failed_total` moved — user-service could not
append a row to `audit_events` (the REQUIRES_NEW write threw: a database
outage, a lock timeout on `audit_chain_head`, a constraint violation).

- `mode="best_effort"`: the action went ahead **unrecorded**. Logins, logouts,
  MFA steps and most admin actions are deliberately never blocked by the audit
  path. Grep user-service for `AUDIT_WRITE_FAILED` for the event type and actor,
  and reconstruct the gap from the gateway access log / OTel spans.
- `mode="required"`: a change to who can do what — `USER_ROLES_CHANGED`,
  `ROLE_CREATED`, `ROLE_PERMISSIONS_CHANGED`, `ROLE_DELETED` — was **refused**
  with `503 audit_unavailable` and rolled back, because it may not happen
  unrecorded. Nothing to reconcile; the administrator retries once the audit
  path is healthy. A sustained run means role administration is down.

In both cases treat the cause as a database incident first (connectivity, pool
exhaustion, `audit_chain_head` lock contention).

### `TokenVersionPublishFailing`

**Trigger:** `user_tokenver_publish_failed_total` moved — user-service bumped a
`users.token_version` (deactivation, role change, password change or reset,
logout, MFA reset) but could not write `auth:tokenver:<userUuid>` to the shared
Redis. The publish runs after the database commit, so Postgres and
user-service's own JwtFilter are already correct; the gap is every OTHER
service, which fails open on a Redis miss and keeps accepting the ended
session's access token until it expires (at most the access-token TTL).

**Action:** treat as a Redis incident (connectivity, auth, memory). Grep
user-service for `Failed to publish token version` to see which users were
affected; nothing is retried, so if a specific deactivation must bite
downstream before the TTL runs out, `SET auth:tokenver:<userUuid>
<current token_version> PX 604800000` by hand once Redis is back — always with
the `PX` (the refresh-token lifetime): under `noeviction` a key without a TTL
never leaves (`RedisKeysWithoutTtl`).

The publish only ever RAISES the stored value (a Lua compare-and-set, so two
bumps whose after-commit writes land out of order cannot move it backwards). The
one time that bites: after restoring user-service's Postgres to an earlier
point, the published values can be higher than the restored ones, and
downstream would refuse those users' fresh tokens until their versions overtake.
Delete the keys as part of any such restore:
`redis-cli --scan --pattern 'auth:tokenver:*' | xargs -r -n 500 redis-cli DEL`.

### `PaymentAuditIntegrityBroken`

Same as `AuditIntegrityBroken` above, but on **payment-service**'s
`audit_events` — the money-movement log (code generation, confirmation,
failure, UNKNOWN status, settlement discrepancies). A content edit here is a
**money-movement incident**: someone altered the record of who was charged or
which settlement disagreed. Follow the `AuditIntegrityBroken` steps; the
verifier logs `AUDIT_ROW_TAMPERED` from payment-service, and the metric is
`payment_audit_integrity_broken_total`.

### `PaymentAuditChainBroken`

Same as `AuditChainBroken` above, but on **payment-service**'s `audit_events`
(V10 hash-chaining). A deleted/reordered money-movement row — e.g. the record
of a fraudulent confirmation removed to hide it. Follow the `AuditChainBroken`
steps; the verifier logs `AUDIT_CHAIN_BROKEN` from payment-service, and the
metric is `payment_audit_chain_broken_total`. Cross-check
`PaymentAuditIntegrityBroken`: a chain break with no content-tamper alert
points at deletion specifically.

### `DeviceSecurityFraudDeskSignal`

**Trigger:** `device_security_fraud_desk_alerts_total{kind}` moved in 15m. Kinds:
`otp_velocity` (a number crossed 5 OTP challenges an hour / 20 a day — someone is
working that number, or bombing it), `sim_without_pin` (a correct OTP followed by
wrong PINs until staging locked the PIN — the SIM is in someone else's hands),
`otp_relay` (a code typed on a different phone than the one that asked for it).

**Action:** grep user-service logs for `FRAUD_DESK_ALERT` to get the masked number
and device id, then open `GET /admin/device-security/customers/{msisdn}`. The
phone is usually already paused (TEMP_BLOCKED) when blocks are enforced; decide
whether to ban it (`POST .../devices/{id}/ban`, FRAUD_SUSPECTED) and whether to
call the customer. In watch mode nothing was blocked — the signal is the only record.

### `DeviceSecurityPartnerKeyProbing`

**Trigger:** the broker or the *569# USSD service is presenting a missing or wrong
`x-api-key` on `/auth/client-service/**` or `/device-security/**`.

**Action:** check the `DEVICE_SECURITY_PARTNER_KEY_FAILURE` rows in `audit_events`
(path, presented key LENGTH, IP). A single IP with the right key length = a
partner running a stale key after a rotation; roll it. Many IPs or odd lengths =
probing; the edge limiter (`device-security-partner-route`) is holding, but confirm
the keys have not leaked and rotate if unsure.

### `DeviceSecurityNotificationsUndelivered`

**Trigger:** security notices (new phone bound, paused, blocked, unlocked,
removed, PIN set) failed on SMS and WhatsApp alike.

**Action:** treat as an SMS-gateway plus WhatsApp-gateway incident (see the OTP
delivery path). Nothing is retried: the decision log and the admin console remain
the record of what happened to each phone.
