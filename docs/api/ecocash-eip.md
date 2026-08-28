# EcoCash Instant Payment (EIP) — the third collection rail

Distilled from `EcoCash_Instant_Payments_APIs_V3.pdf` (pinned in this
directory) — the **customer-pays-merchant** API behind
`paymentRail=ECOCASH` on `POST /payments`. Read this before touching the
integration: like the other two rails, the wire facts that are easy to get
wrong are pinned here, and several of this doc's own samples are unreliable
in ways called out below.

> Do NOT confuse this with the EcoCash **EPG** `serviceRequest` API
> (`MERCHTOSUB` / `EXTERNALTRANSACTIONLOOKUP` / `TXNREVERSAL`). That is a
> separate, DISBURSEMENT-side surface (merchant wallet → customer) with
> different auth (`x-client-id`/`x-client-secret`), different envelopes and
> its own traps (`responseCode "00"` on a Declined lookup). EIP is the
> collection rail; EPG is not integrated.

## The three operations

| Op | Method + path | Notes |
|---|---|---|
| Charge | `POST {base}/payment/v1/transactions/amount` | **Never retried** |
| Query | `GET {base}/payment/v1/{endUserId}/transactions/amount/{clientCorrelator}` | The only retried call |
| Refund | `POST {base}/payment/v1/transactions/refund` | Keyed by `originalEcocashReference`; not yet modelled |

- Preprod base: `https://payonline.ecocash.co.zw/ecocashGateway-preprod`
  (test basic-auth creds + test merchant 8003/789111401/PIN 1234 are in the
  PDF; test customer msisdns must be registered with the EcoCash POC by
  email first).
- Auth: **HTTP Basic** on every call. Same credentials for USD and ZWG.
- Merchant identity travels in the **body**: `merchantCode`,
  `merchantNumber`, `merchantPin` on every charge/refund.

## The payment lifecycle

1. Charge accepted → response `transactionOperationStatus:
   "PENDING SUBSCRIBER VALIDATION"`, `totalAmountCharged: 0.0`. EcoCash
   pushes a PIN prompt to the customer's phone.
2. Customer approves → `COMPLETED` / rejects → `FAILED`. The final status
   arrives BOTH as an HTTP POST of the amountTransaction body to our
   `notifyUrl` (mandatory request field) AND via the Query endpoint.
3. The doc's parameter table also names `CHARGED` as "the initial status" —
   the sample shows `PENDING SUBSCRIBER VALIDATION` instead. Treat the
   still-pending set as open-ended: anything that is not `COMPLETED` or
   `FAILED` is "not finished", never a terminal verdict.

## Non-negotiables (mirror of the other rails' rules)

- **The charge is NEVER retried.** `clientCorrelator` is the upstream
  idempotency key — a duplicate is rejected with a ServiceError, and a
  FRESH correlator on a blind retry could debit the customer twice. An
  ambiguous outcome goes to Query, which is the only retried call.
- **Ledger write ordering is INVERTED vs the code rail, deliberately.** On
  the InnBucks rail the instrument is delivered by OUR response, so a
  PENDING row means nothing was ever payable and the stale sweep may close
  it FAILED. On EIP the instrument is a PIN prompt delivered by ECOCASH to
  the customer's phone — a crash mid-call can leave a payable prompt live.
  So the row transitions PENDING → TOKEN_ISSUED (correlator persisted)
  BEFORE the charge call: PENDING then provably means "no upstream call was
  attempted" (stale-sweep-safe), and TOKEN_ISSUED means "a charge may
  exist, keyed by this correlator" (query-resolvable).
- **`transactionOperationStatus` is the ONLY outcome field.** `COMPLETED` =
  money moved; `FAILED` = subscriber rejected = positively no money moved
  (terminal FAILED, slot freed). Do not gate on HTTP status alone.
- **Echo guard:** a `COMPLETED` read whose `totalAmountCharged` /
  `charginginformation.amount` / `currency` disagrees with the ledger parks
  the row IN_DOUBT for an operator — never confirmed, never guessed.
- **Still-pending past the local TTL + grace expires the row locally** —
  the code rail's "still New" rule: upstream POSITIVELY reports unapproved,
  so no money can be in flight at the moment of the read, and the prompt
  itself is long dead. `UNKNOWN`/error answers never expire a row.
- **A query that finds no transaction** is normal for a beat after issuing
  (and is the crash-before-call signature); past the TTL + grace it is the
  positive "no charge exists" answer that frees the slot.
- **Amounts are DECIMAL JSON NUMBERS in MAJOR units** (`3.00`) — the third
  convention (InnBucks: integer cents; ZimSwitch: major-unit strings).
  `EcocashEipClient` owns the one cents→major rendering.
- **The webhook is a TRIGGER, not a truth source.** `notifyUrl` receives an
  unauthenticated POST from outside; its body is never trusted for money
  facts. The handler extracts the correlator, finds the row, and runs the
  SAME query-based resolver the poller uses. Losing a webhook loses
  nothing — the poller remains the authority.
- **Responses echo `merchantPin`.** Raw request/response bodies are never
  logged on this rail; the client maps trimmed DTOs and error paths log
  status codes only.

## Doc samples that must NOT be trusted

Verified against the PDF's own examples — anchor on the reliable fields
only (`clientCorrelator`, `transactionOperationStatus`, `ecocashReference`,
`serverReferenceCode`, `paymentAmount`):

- The charge response echoes `endUserId` WITHOUT the country code
  (`777222093` for a `263777222093` request).
- The query sample returns `endUserId: "8003"` (the MERCHANT code) and
  `merchantNumber: "263777222093"` (the CUSTOMER) — identity fields come
  back swapped. Nothing keys on them.
- The refund sample echoes a `merchantPin` different from the one sent.
- Timestamps are epoch millis in some fields and `0`/null in others on the
  same response.

## Config (cell env; blank fails SAFE — rail disabled, clean 503)

| Key | Secret? | Notes |
|---|---|---|
| `ECOCASH_BASE_URL` | no | preprod URL committed for the ZW cell |
| `ECOCASH_API_USERNAME` / `ECOCASH_API_PASSWORD` | yes | HTTP Basic pair |
| `ECOCASH_MERCHANT_CODE` / `ECOCASH_MERCHANT_NUMBER` | no | blank in the committed file |
| `ECOCASH_MERCHANT_PIN` | yes | body credential — same custody as every A02 secret |
| `ECOCASH_NOTIFY_URL` | no | OUR public edge URL (`https://…/foundry/payments/ecocash/notify`). The committed `cell.zw.env` carries the PRODUCTION value; the staging box overrides it in `cell.zw.local.env` (the Secret wins over the ConfigMap). A charge is REFUSED while this is blank/relative — half-provisioned, same class as `ZIMSWITCH_SHOPPER_RESULT_URL`. |
| `ECOCASH_CHARGE_TTL` | no | local prompt deadline, default `PT5M` |
| `ECOCASH_POLL_INTERVAL` | no | sweep cadence, default `PT20S` |

Rows already open keep resolving on credentials alone (`isConfigured()`);
starting a NEW charge additionally requires the notify URL
(`canStartCharge()`) — the ZimSwitch provisioning split, for the same
reason: a half-provisioned rail must refuse at the gate, not strand a row
holding the order's only payment slot.

## Not yet modelled

- **Refunds** (`/transactions/refund`, `tranType` + `originalEcocashReference`)
  — the platform supports them (unlike the InnBucks code rail); operator
  procedure for now.
- ZWG currency flow (the API supports it on the same endpoints; the cell is
  USD).
- Settlement reconciliation for ECOCASH rows (the nightly recon covers the
  InnBucks statement only).

## THE HOST TRAP — the PDF names the wrong one for preprod

> [!IMPORTANT]
> **Preprod is `payonline.econet.co.zw`, NOT the `payonline.ecocash.co.zw`
> this PDF documents.** The documented host sits behind a Cloudflare **bot
> challenge**, which a server-to-server client cannot pass by design — it has
> no browser to run the JavaScript. Every charge from our backend was refused
> `403` there while the credentials, merchant config, request body and
> registered msisdns were all correct the whole time.

Confirmed 2026-08-28 from the ZW cell by varying **only** the `User-Agent` on
an otherwise identical request (same URL path, credentials, source IP):

| Host | `curl/8.x` | `Java-http-client/21` | `Ticketize-Payments/1.0` |
|---|---|---|---|
| `payonline.ecocash.co.zw` (PDF) | 200 | **403** | **403** |
| `payonline.econet.co.zw` | 200 | **200** | **200** |

The `403` carried Cloudflare's own `cf-mitigated: challenge` header — proof
it is Cloudflare mitigating, not the EIP application refusing. The `POST`
path additionally returned an F5 BIG-IP ASM block page ("Request Rejected /
Your support ID is: …") served with **HTTP 200** and `text/html`, even for an
empty `{}` body — which is what proved the request *content* was never the
problem.

- **Which host serves PRODUCTION is NOT yet confirmed.** EcoCash said
  `.ecocash` first, then `.econet`. Before go-live, get the production base
  URL in writing and run one non-mutating GET against it with our real
  `User-Agent`; if production is on `payonline.ecocash.co.zw`, the same
  challenge will refuse every charge on launch day unless they whitelist us
  first.
- Do NOT "fix" this by spoofing a browser or `curl` User-Agent. The rail
  sends an honest identity (`EcocashEipClient.USER_AGENT`); a payment partner
  is entitled to know what is calling it.

## Verified wire facts (preprod, 2026-08-27, from the ZW cell)

Observed directly against the preprod gateway; each is pinned by a case in
`EcocashEipClientContractTest`.

- **An unknown correlator answers HTTP 200 with an ALL-NULL envelope**, NOT
  the 404 this doc previously assumed. Every field — `id`,
  `clientCorrelator`, `transactionOperationStatus`, `paymentAmount` — is
  `null`. `classify()` reads "no status AND no echo" as the positive
  no-such-transaction answer (`NOT_FOUND`); a null status *with* an echoed
  correlator stays `UNKNOWN`, because EcoCash knowing the transaction while
  we cannot read its state is exactly when guessing would free a slot the
  customer may have paid for. The 404 branch is kept — it costs nothing and
  may still be what production returns.
- **The edge can answer 200 with an HTML body.** EcoCash sits behind
  Cloudflare and then an F5 BIG-IP ASM, and the ASM serves its "Request
  Rejected / Your support ID is: …" page with **HTTP 200** and `text/html`.
  Any non-JSON 2xx is therefore infrastructure answering for EIP, and
  `classify()` raises it as transient rather than classifying it — see the
  non-negotiable below.
- **The edge runs a User-Agent allow-list.** Same URL, same credentials,
  varying only the UA: `curl/8.x` → 200, `Java-http-client/21` → 403,
  `Ticketize-Payments/1.0` → 403. So no UA we can choose fixes it on our
  own; the client sends a stable honest identity (`EcocashEipClient.USER_AGENT`)
  for EcoCash to allow-list. Basic auth itself works (wrong credentials give
  a clean 401), and the source IP is not blocked.

## Open questions for the EcoCash POC

- How long does the subscriber PIN prompt live upstream, and does an
  unanswered prompt eventually flip the transaction to `FAILED` on Query?
  (Local TTL default PT5M is a guess; tighten once answered.)
- The exact ServiceError envelope for a duplicate `clientCorrelator`.
- Whether the F5 rejects the charge POST on content grounds independently of
  the UA allow-list — an empty `{}` body from an allowed UA was also blocked
  (support ID `11686949056897540070`), so **no** POST has yet reached EIP.
  Until one does, nothing about our charge body, merchant provisioning or
  msisdn handling has been validated upstream.
