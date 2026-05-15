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

### `OtelExportFailing`

Spans can't reach the OTel collector. App keeps running but you lose
trace visibility — every alert that says "look at the trace" becomes
guesswork until this is fixed.

1. Check `OTLP_ENDPOINT` env var on the pod matches the live collector
   address.
2. Curl the collector from inside the pod: `curl -v $OTLP_ENDPOINT`.
3. If the collector is up but rejecting: check its receive logs for
   schema/protocol mismatch (usually a major OTel version skew between
   SDK and collector).
