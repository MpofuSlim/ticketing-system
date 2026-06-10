# Multi-Cell Deployment — Infrastructure Proposal

**Status:** DRAFT — open for review and amendment
**Audience:** InnBucks Platform Engineering, Operations, CTO
**Owner:** Ticketing Engineering
**Date:** 2026-06-09

---

## Document purpose

This document proposes the infrastructure setup required for the Simbisa /
InnBucks super-app to operate as one product across all African markets we
plan to enter, while keeping each country's data, payments, and customer
identity physically isolated as required by national banking regulators.

The application-layer work to support this is largely complete (status in
§2.2). What's needed now is platform-side: hosting environments per country,
InnBucks-side coordination (veengu, messenger-interface) per market, and
shared cross-cutting plumbing (DNS, edge routing, secrets, monitoring).

---

## 1. Executive summary

We are moving from a single Zimbabwe-rooted deployment to a **cell-based**
architecture where each of the ten African markets we plan to enter (current
home: Zimbabwe; next planned: Kenya) runs as its own fully-isolated stack:

- Its own database (no cross-country data mixing — satisfies residency)
- Its own InnBucks core integration (cell ZW → ZW veengu; cell KE → KE veengu)
- Its own SMS / WhatsApp / email provider
- Its own service instances and observability surface

A customer is anchored to **one** cell at registration based on their MSISDN
country code, and stays anchored there permanently. The same super-app works
from anywhere in the world; their requests are always routed to their home
cell.

**Application-layer status:** steps 1–5 of the multi-cell roadmap are merged
into `master`. The application no longer needs to know which country it's
running — it reads `INNBUCKS_COUNTRY` at boot and behaves accordingly.
Adding country #N+1 is now a configuration change, not a code change.

**Asks of the InnBucks platform team over the next quarter:**

1. One isolated hosting environment per cell, in or near the country
   (residency requirement TBC by Legal per market).
2. InnBucks core readiness per cell: veengu participant, messenger-interface
   configuration, Eureka registry access — one set per market.
3. A small edge-routing layer: one global URL that routes each request to
   the right cell based on the customer's MSISDN country code.
4. Standard platform plumbing (DNS, certs, secrets manager, central logging
   / monitoring) aligned with InnBucks' existing standards.

**Recommended sequence:** prove the model on **two cells** end-to-end
(Zimbabwe + one of Kenya / South Africa / Nigeria — Legal and Business to
choose) before batch-onboarding the rest.

---

## 2. Where we are today

### 2.1 Application

The ticketing-system fleet is a 7-service Spring Boot deployment:

- `api-gateway`, `discovery-server` (Eureka)
- `user-service`, `event-service`, `seat-service`, `booking-service`,
  `payment-service`, `loyalty-service`

Plus `innbucks-core-gateway` — a standalone Spring Boot adapter that bridges
ticketing services to InnBucks **veengu** (payments) and
**messenger-interface** (SMS / WhatsApp / email).

**Today:** ONE deployment, on ONE EC2 instance in Zimbabwe. The mobile FE
(`innvents.vercel.app`) hits the EC2's api-gateway; the stack talks to the
InnBucks Eureka cluster, veengu, and messenger-interface for payments and
notifications.

**Database topology:** 6 logical databases (one per service) inside a single
Postgres container on the EC2. Per-service Flyway migrations. Co-located
Redis (rate-limiting, idempotency, seat-locks), Kafka (booking domain
events), Eureka (service discovery).

### 2.2 Multi-cell readiness — what's already merged

The application layer is now multi-cell-aware:

| Capability | Status | PR |
|---|---|---|
| `homeCountry` routing key in customer JWT (derived from MSISDN) | ✅ merged | #188 |
| `country` MDC tag + `INNBUCKS_COUNTRY` startup pin (`user-service`) | ✅ merged | #189 |
| Same pattern mirrored across the other 5 reactor services | ✅ merged | #190 |
| `home_country` persisted on the `users` table; composite `UNIQUE(phone_number, home_country)` constraint | ✅ merged | #191 |
| Per-cell deploy template + wrapper script (`deploy/cell.sh`) | ✅ merged | #192 |
| RS256 + JWKS cross-cell trust fabric | ⏳ step 6, not started | — |
| Edge routing by MSISDN country code | ⏳ step 7, not started | — |

**What this means concretely:** today, "add country #N+1 to the application
fleet" is `cp deploy/cells/cell.example.env deploy/cells/cell.<iso>.env`,
edit ~6 fields, run `deploy/cell.sh <iso> up`. No application code changes
required, ever.

What's missing is the **hosting** to actually run cell #2 — that's what this
document is asking the platform team to provide.

---

## 3. Target architecture

### 3.1 The cell model

Each country runs as an independent **cell**. A cell contains everything
needed to serve that country's customers end-to-end:

```
+--------------------------------------------------------+
|  Cell <COUNTRY>                                        |
|                                                        |
|   +--------+ +---------+ +---------+ +-------------+   |
|   |gateway | | service | | service | | innbucks-   |   |
|   |        | | ...     | | ...     | | core-gw     |   |
|   +--------+ +---------+ +---------+ +------+------+   |
|                                             |          |
|   +--------+ +---------+ +---------+        |          |
|   |postgres| | redis   | | kafka   |        |          |
|   +--------+ +---------+ +---------+        |          |
+---------------------------------------------+----------+
                                              |
                                              v
                          +-------------------+----------+
                          | <COUNTRY>'s InnBucks core    |
                          | (veengu + messenger-iface)   |
                          +------------------------------+
```

**Cells are fully independent.** Cell ZW's Postgres has no Kenyan data;
Cell KE's Postgres has no Zimbabwean data. If KE has an outage, ZW is
unaffected (and vice versa).

### 3.2 Customer routing — home-anchored

A customer is anchored to their **home cell** at registration based on their
MSISDN country code:

| MSISDN prefix | Home cell |
|---|---|
| `+263…` | ZW |
| `+254…` | KE |
| `+260…` | ZM |
| `+265…` | MW |
| `+27…` | ZA |
| `+267…` | BW |
| `+258…` | MZ |
| `+266…` | LS |
| `+268…` | SZ |
| `+234…` | NG |

The anchoring is **permanent and physical**. A Zimbabwean customer's wallet,
KYC, bookings, and transaction history live in the ZW cell's Postgres and
never leave Zimbabwe — satisfying any "data must remain in-country"
regulator stance.

When a Zimbabwean customer is physically in Kenya and opens the app, their
requests are routed back to their home (ZW) cell — exactly like a Standard
Chartered Zimbabwe account works for a customer traveling abroad.

### 3.3 Target topology

```
              Customer's phone (anywhere in the world)
                            |
                            v
                +---------------------------+
                |  app.innbucks.com         |
                |  (single global URL)      |
                +--------------+------------+
                               |
                  edge routing: inspect MSISDN /
                  homeCountry claim, forward to home cell
                               |
        +----------------+-----+-----+----------------+
        |                |           |                |
        v                v           v                v
   +---------+      +---------+ +---------+    +-----------+
   | Cell ZW |      | Cell KE | | Cell ZA |    | Cell N+1  |
   | (in ZW) |      | (in KE) | | (in ZA) |    | (in ...)  |
   +---------+      +---------+ +---------+    +-----------+
```

Each cell owns:

- Compute (EC2 / equivalent)
- Database (Postgres, in-region)
- Caching + queueing (Redis, Kafka)
- Service discovery (Eureka)
- InnBucks core integration (veengu + messenger-interface for that market)

The customer-facing FE talks to **one** URL. The edge routes per-request.
The FE never needs to know cells exist.

---

## 4. What the platform layer needs to provide

### 4.1 Per-cell hosting

| Resource | Recommended spec per cell | Notes |
|---|---|---|
| Compute | 1× EC2 `t3.xlarge` equivalent (4 vCPU, 16 GB RAM, 100 GB SSD) | Sized for current ZW load + 2× headroom |
| Region | In or near the country | ZW = af-south-1 (Cape Town) today; KE may need eu-west-1 or a Nairobi-region provider depending on residency |
| Network | Private VPC; only the cell's api-gateway port (18080) publicly exposed | Matches current ZW hardening |
| DNS | One subdomain per cell (`api-zw.example.com`, `api-ke.example.com`) plus the global `app.innbucks.com` | Edge URL points at the global; per-cell URLs used for internal smoke / fallback |
| TLS | Auto-renewing cert per cell subdomain + the global | Let's Encrypt or ACM |
| Backups | Nightly Postgres dumps, 30-day retention, off-site copy | The existing `scripts/backup-postgres.sh` runs per cell unchanged |

### 4.2 Per-cell InnBucks core readiness

For each country, the InnBucks platform team needs to configure:

| InnBucks side | Per-cell requirement |
|---|---|
| **veengu** | A merchant participant for the cell's `innbucks-core-gateway`, with `validateDuplicates=true` (required for our idempotency contract). Settlement wallet for ticketing payouts in the country's currency. |
| **messenger-interface** | Country-appropriate SMS sender ID registered with the local regulator; cell's `innbucks-core-gateway` registered as an allowed `X-Source-Component`. |
| **Eureka** | The cell's `innbucks-core-gateway` needs network access to the InnBucks Eureka cluster. **Open question — see §6.3:** one shared Eureka across all cells, or per-country Eureka clusters? |
| **WhatsApp** | Per-country WhatsApp Business API account, or shared account with country-specific sender IDs. |
| **Compliance docs** | Per-country KYC tier definitions / transaction limits (these vary materially — RBZ vs CBK vs CBN have very different ceilings). |

### 4.3 Edge routing

A small routing layer sits in front of the cells and inspects each request
to decide which cell handles it. Two implementation options:

| Option | Stack | Pros | Cons |
|---|---|---|---|
| **A: Cloudflare Workers / Lambda@Edge** | ~50 lines of JS/TS | Lowest operational cost; auto-scales; geo-distributed | Requires Cloudflare or AWS account at the edge |
| **B: nginx in a single small VM** | nginx config | Familiar; one more thing to monitor | Single point of failure unless HA-pair |

**Recommendation: Option A** unless InnBucks already operates nginx
ingresses everywhere.

The routing logic is:

```
1. Pre-login (POST /auth/login):
   - Parse MSISDN from request body
   - Look up country code in the same 10-market table the app uses
   - Forward to the corresponding cell's api-gateway
2. Post-login (Authorization: Bearer <jwt>):
   - Decode the JWT's `homeCountry` claim (no signature verification needed
     at the edge — just routing)
   - Forward to the matching cell
3. Fallback: return 400 with `unsupported_country` if neither yields a
   known cell
```

### 4.4 Secrets management

Per cell needs ~10 secrets (Postgres password, Redis password, Eureka
password, JWT secret, 2× shared service-to-service tokens, 2× loyalty
signing secrets, WhatsApp API key, optional bootstrap admin password). At
10 cells that's ~100 secrets total.

**Recommendation:** centralised secrets manager (AWS Secrets Manager,
HashiCorp Vault, Doppler, or whatever InnBucks already standardises on).
The cell deploy template
(`deploy/cells/cell.<iso>.local.env`) is the integration point — secrets
are pulled from the secrets manager into that file on the host at deploy
time and **never committed to git**.

### 4.5 Monitoring & observability

The application already emits everything needed:

- **Logs**: JSON-structured, with `country` and `homeCountry` tagged on
  every line. Cross-cell queries are slice-able by country out of the box.
- **Metrics**: Micrometer instrumentation throughout.
- **Traces**: OTLP endpoint configurable per cell (off by default).

What the platform needs to provide:

| Need | Recommended tool | Why |
|---|---|---|
| Log aggregation | Loki / CloudWatch Logs / ELK | One pane to slice by country |
| Metrics aggregation | Prometheus + central Grafana | Per-cell scrape, global dashboard |
| Tracing | Tempo / Jaeger / Honeycomb | Optional but useful for cross-service debugging |
| Alerting | Grafana Alertmanager | Per-cell on health/latency/errors; plus a global "wrong-cell" alert (a request with `homeCountry=X` hit cell `Y`) for post-step-7 |

---

## 5. Phased rollout

### Phase 0 — done (today)

- Zimbabwe cell live on legacy `scripts/deploy.sh` + `.env` workflow
- Application code is multi-cell-ready (steps 1–5 merged)
- Cell template + deploy script in repo (`deploy/cell.sh`)

### Phase 1 — operationalize Zimbabwe as a cell (1–2 weeks)

Migrate the existing ZW EC2 to the new `deploy/cell.sh zw` workflow. No
behavioural change — same containers, same data, same images — just the
deploy ergonomics.

- Migrate ZW secrets into the secrets manager
- Stand up the central log aggregator; confirm `country=ZW` and
  `homeCountry=ZW` appear on every log line
- Document the runbook for ops

**Outcome:** ZW is the reference implementation. Procedures documented.

### Phase 2 — first non-Zimbabwe cell (4–8 weeks)

- Business + Legal: pick country #2 (Kenya recommended — large market,
  OradianMiddleware roadmap targets KE first per their docs)
- Stand up KE infrastructure (EC2, DNS, certs, monitoring, secrets)
- Coordinate with InnBucks platform team for KE veengu participant and
  messenger-interface configuration
- Land step 6 (RS256 + JWKS) and step 7 (edge routing) in the codebase
- Smoke ZW + KE end-to-end with real customer traffic (limited to internal
  staff + selected pilot users first)

**Outcome:** the multi-cell model is proven with two live cells.

### Phase 3 — batch onboard 3 more cells (4–6 weeks per cell)

- Order per business / Legal priority (likely ZA, NG, ZM or similar)
- Mostly mechanical: copy the cell template, fill in the values, provision
  the EC2 + DB + secrets, coordinate the InnBucks core side
- ~1 week of engineering work per cell once the template is proven; the
  long pole is regulator filings and InnBucks core readiness

### Phase 4 — remaining 4 markets (3–6 months)

- One country every 4–6 weeks based on business demand
- Each onboarding follows the Phase 3 playbook

**Realistic full timeline:** 9–12 months from today to 8 cells live.

---

## 6. Open decisions for the InnBucks platform team

These are the calls that need a decision from your side before we commit to
a specific Phase 2 plan.

### 6.1 Hosting topology — EC2 vs Kubernetes

| Option | Pros | Cons |
|---|---|---|
| **EC2-per-cell** (today's model extended) | Simple; matches current operational comfort; cheap | No autoscaling; manual rolling deploys |
| **Kubernetes** (per-cell namespace or per-cell cluster) | Rolling deploys, autoscaling, self-healing | Real operational learning curve; team needs k8s on-call experience |

**Our recommendation:** EC2 per cell for cells 1–3. Migrate to k8s when
the team has someone with k8s on-call experience, or when cell count or
traffic justifies the operational complexity. The cell template translates
1:1 to Helm values when that switch happens (`kompose` covers ~70% of the
docker-compose → k8s conversion automatically).

### 6.2 Data residency per country

Each country's banking regulator (RBZ, CBK, SARB, CBN, …) has its own
stance on whether customer data may leave national borders. We are
**currently designing for the strictest interpretation** (data physically
resident in-country).

If a country's regulator accepts logical isolation in a regional cloud
(e.g., AWS Cape Town hosting all of Southern Africa), that cell can be
significantly cheaper.

**Action:** Innbucks Legal / Compliance to provide written residency
requirement per market before we commit hosting region per cell.

### 6.3 InnBucks Eureka topology

Today our `innbucks-core-gateway` connects to a single InnBucks Eureka
cluster (the 5-node HA cluster, IPs in our compose config). For multi-cell:

| Option | Description |
|---|---|
| **A: One shared Eureka** | All cells' gateways connect to it. veengu / messenger-interface multi-tenant by `X-Source-Component`. Simpler. |
| **B: Per-country Eureka cluster** | Each cell connects only to its country's cluster. Stricter isolation; better fault containment. |

The OradianMiddleware sibling repo's docs suggest InnBucks is moving toward
per-country deployment (which implies per-country Eureka — option B).

**Decision needed from InnBucks Platform Lead.**

### 6.4 SMS / WhatsApp / Email provider per country

- **SMS sender-ID registration**: per-country, with each market's regulator
  (POTRAZ for ZW, CA Kenya for KE, NCC for NG, …). Each takes 2–6 weeks
  lead time.
- **WhatsApp**: which Business API gateway per market? (wasenda for ZW
  today; Kenya likely Africa's Talking, etc.)
- **Email**: SES / SendGrid / Mailgun — does each country need its own
  sender domain for deliverability?

**Decision needed per country.**

### 6.5 Currency support in ticketing

Today the ticketing services (booking / seat / event) assume a single
currency (USD). Payment-service accepts a `currency` parameter but
defaults to USD.

If cell #2 transacts in a non-USD currency (KES for Kenya, ZAR for South
Africa), step 3 of the application roadmap (currency parameterisation in
ticketing) must land before the cell goes live. It's a small PR — additive
column on the booking / seat / event entities, defaulted to the cell's
currency. No FE impact.

**Decision needed:** which currency for cell #2? (drives whether step 3 is
on the critical path for Phase 2 or can be deferred).

---

## 7. Costs — rough order of magnitude

These are placeholders for the platform team to refine with real numbers
from contracted providers.

### Per cell, recurring monthly (USD, estimated)

| Item | Cost |
|---|---|
| EC2 t3.xlarge (or equivalent) | $150 |
| EBS storage + snapshots | $30 |
| Postgres backups to S3 | $10 |
| DNS + TLS cert | $5 |
| Log forwarding to central | $20 |
| **Sub-total per cell** | **~$215** |
| × 8 cells | **~$1,720 / month** |

### One-off per cell

| Item | Cost / effort |
|---|---|
| Engineering setup (provisioning + smoke) | ~1 engineering-week |
| Compliance / regulator filing | varies materially per country |
| WhatsApp Business API registration | one-off provider fee per country |

### Shared (paid once across all cells)

| Item | Monthly |
|---|---|
| Edge router (Cloudflare Workers / Lambda@Edge) | $20 |
| Central log aggregator (Loki / CloudWatch) | $200 |
| Central Grafana + alerting | $50 |
| Secrets manager (AWS Secrets Manager scale) | $50 |
| **Sub-total shared** | **~$320 / month** |

### Realistic infrastructure budget at 8 cells live

**~$2,000 / month** for the ticketing-system's hosting, **excluding**:

- InnBucks-side costs (veengu transaction fees, SMS per-message charges)
  — these scale with traffic, not cell count
- Engineering effort for the rollout
- Regulator filing fees per country

---

## 8. Risks & dependencies

| Risk | Likelihood | Mitigation |
|---|---|---|
| A target country's regulator requires data residency we can't meet on shared cloud | Medium | Engage Legal early per country; our design assumes worst case |
| InnBucks platform team can't deliver per-country veengu participants on our timeline | Medium | Sequence cell rollout around their readiness; don't block on theoretical countries |
| WhatsApp / SMS provider unavailable in a market | Low | Per-cell config: swap provider via env file, no code change |
| Cross-cell purchase becomes a product requirement (Zimbabwean buys a Kenyan event ticket) | High over 12 months | The architecture **explicitly defers** this. Surfacing it now lets Product decide whether to fund the cross-cell saga work. |
| Team capacity to run 8 production environments | High | Operational cost (alerting, on-call, runbooks per cell) must be budgeted before Phase 3 |
| Currency support outside USD | High for non-USD cells | Step 3 of application roadmap (currency parameterisation) is a small PR; blocker only for first non-USD cell |
| MSISDN portability (a customer who's KE-registered moves to ZW and changes their number) | Low–Medium | Need a "transfer cell" operational procedure; not in scope for v1 |

---

## 9. Specific asks of the InnBucks platform team

These are the calls / commitments needed to unblock Phase 2.

1. **Confirm hosting topology preference** (§6.1) — EC2-per-cell or
   Kubernetes? *Owner: InnBucks Platform Lead. Target: 1 week.*
2. **Provide residency stance per market** (§6.2) — or formally commit to
   the strict interpretation. *Owner: Innbucks Legal / Compliance.
   Target: 2 weeks.*
3. **Commit to delivering veengu + messenger-interface readiness for
   cell #2** (§4.2) by a target date. *Owner: InnBucks Platform Lead.
   Target: confirm in next sprint; ready ~6 weeks.*
4. **Approve the edge-routing implementation** (§4.3) — Workers /
   Lambda@Edge vs nginx. *Owner: InnBucks CTO. Target: 1 week.*
5. **Confirm Eureka topology** (§6.3) — shared vs per-country. *Owner:
   InnBucks Platform Lead. Target: 1 week.*
6. **Provision the secrets manager** (§4.4) — or confirm which existing one
   we should use. *Owner: InnBucks Platform Lead. Target: 2 weeks.*
7. **Confirm budget envelope** of ~$2,000/month at 8 cells (§7), plus the
   InnBucks-side recurring costs (transactional veengu / SMS fees).
   *Owner: Finance. Target: 2 weeks.*
8. **Identify the on-call rotation owner per cell** before Phase 2 goes
   live. *Owner: InnBucks Engineering Manager. Target: before Phase 2.*

---

## Appendix A — Cell readiness checklist

Before declaring a cell "production-ready":

- [ ] EC2 / compute provisioned to the agreed spec, in the agreed region
- [ ] DNS record + TLS cert created and validated
- [ ] Secrets pulled from the secrets manager into
      `deploy/cells/cell.<iso>.local.env` on the host (never committed)
- [ ] Postgres / Redis / Kafka containers start cleanly
- [ ] All 6 reactor services boot with `country=<ISO>` in their startup logs
      (`grep "pinned to country" logs`)
- [ ] `deploy/cell.sh <iso> status` shows all services `(healthy)`
- [ ] InnBucks veengu merchant participant registered, settlement wallet
      created, `validateDuplicates=true` confirmed
- [ ] messenger-interface SMS / WhatsApp routing validated end-to-end with
      a test message to a real number
- [ ] Backup job scheduled, first backup successful, restore tested
- [ ] Logs flowing to central aggregator with `country=<ISO>` and
      `homeCountry=<ISO>` visible
- [ ] Metrics scraped by central Prometheus; cell appears on global
      Grafana dashboard
- [ ] At least one alert tested and fires (e.g. high error rate)
- [ ] **Smoke test 1**: tier-1 customer registration with a real MSISDN of
      the target country materialises a `users` row with
      `home_country=<ISO>`
- [ ] **Smoke test 2**: a payment debit + reversal succeeds against the
      country's veengu
- [ ] **Smoke test 3**: an SMS or WhatsApp test message lands on a real
      handset in the country
- [ ] Runbook reviewed by at least 2 engineers; on-call owner named

---

## Appendix B — Glossary

| Term | Meaning |
|---|---|
| **Cell** | A complete, isolated stack for one country. Own database, own services, own integrations. |
| **Home-anchored** | The model where a customer's account lives in exactly one cell (their home country), regardless of where they physically are. |
| **`INNBUCKS_COUNTRY`** | Env var that pins each running JVM to one ISO 3166-1 alpha-2 country code; validated at startup against the InnBucks markets allowlist. |
| **`homeCountry` (JWT claim)** | ISO routing key derived from the customer's MSISDN at token mint time. |
| **MSISDN** | A customer's phone number in international format (e.g. `+263782606983`). |
| **veengu** | InnBucks' core banking engine; the payment rails. |
| **messenger-interface** | InnBucks' notification service (SMS, WhatsApp, email). |
| **`innbucks-core-gateway`** | The standalone Spring Boot adapter in this repo that bridges ticketing services to veengu + messenger-interface. |
| **Cross-cell purchase** | The scenario where a customer in cell A transacts on inventory in cell B (e.g. a Zimbabwean buys a Kenyan event ticket). Explicitly deferred from v1. |

---

## Appendix C — Reference: the multi-cell roadmap (application side)

For traceability — these are the application-layer PRs that build up to and
beyond this proposal:

| Step | What it does | Status |
|---|---|---|
| 1 | `homeCountry` JWT claim derived from MSISDN at token-mint time | ✅ #188 |
| 2 | `INNBUCKS_COUNTRY` startup pin + country MDC on every log line across all 6 reactor services | ✅ #189 + #190 |
| 3 | Currency parameterisation in booking / seat / event (additive response field) | ⏳ not started — needed before first non-USD cell |
| 4 | `users.home_country` column + composite `UNIQUE(phone_number, home_country)` | ✅ #191 |
| 5 | Per-cell deploy template (`deploy/cells/`) + wrapper script (`deploy/cell.sh`) | ✅ #192 |
| 6 | RS256 + JWKS — replace per-cell shared JWT secret with public-key trust fabric so cells trust each other's tokens | ⏳ not started — needed for Phase 2 |
| 7 | Edge router (Cloudflare Worker / Lambda@Edge) — routes requests to the home cell by MSISDN / claim | ⏳ not started — needed for Phase 2 |

---

*End of document. Comments and amendments welcome via PR review.*
