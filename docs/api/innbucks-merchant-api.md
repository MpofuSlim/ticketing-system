# InnBucks Merchant API — distilled integration notes

Source of truth: `docs/api/InnBucks_Merchant_Api_Doc_v1.0.9.pdf` (the official
"Merchant Api Document v1.0.9" provided by the InnBucks team). This file
distills the parts ticketing consumes so they're greppable; if the wire shape
observed in staging/production diverges, update BOTH files and the
`InnbucksApiClientContractTest` stubs in the same PR.

**This API is the primary (and only) ticket-payment rail** — the InnBucks
team designated 2D-code payment as the priority; the earlier server-side
debit (`/bank/api/payment`) integration was removed.

## Platform conventions

- `{{baseUrl}}` is environment-specific; staging is `https://staging.innbucks.co.zw`.
- Every request carries header `X-Api-Key: <api key>` (issued with the account).
- Secured endpoints also need `Authorization: Bearer <accessToken>`.
- **All amounts are in CENTS** (minor units). The 100x footgun: the client
  sends `long amountCents` and cross-checks the response's `amount` echo.
- Response envelope: `responseCode` `0` (number, code endpoints) or `"00"`
  (string, auth/reversal) means success; anything else failed, reason in
  `responseMsg` / `responseDescription`.
- Every response carries an `X-Trace-Id` header — quote it to InnBucks support.
- Full response-code list: `GET {{baseUrl}}/api/file/response-codes`.

## Auth

`POST /auth/third-party` — body `{"username","password"}` →
`{"accessToken","responseCode":"00","responseDescription"}`.
Token expires after ~15 minutes; our client caches it, parses the JWT `exp`,
refreshes 30s early, and replays exactly once on a 401.

> The credentials must belong to a **merchant-type** API client allowed to
> generate `PAYMENT` codes ("The type of code you can generate depends on the
> merchant type and the allowed transactions").

## 2D-code payment flow (what ticketing uses)

### 1. `POST /api/code/generate`

Request: `{"reference", "narration", "amount": <cents>, "type": "PAYMENT"}`
(`reference` = our `TKT-PMT-<uuid>` paymentReference).

Response (success): `{"stan", "authNumber", "processedDateTime",
"responseCode": 0, "responseMsg", "code", "qrCode": "<base64>", "amount",
"description"}`.

- `code` — what the customer pays: shown in their InnBucks app under
  **Pay by Code** (or USSD). We deliver it via WhatsApp→SMS and echo it on
  the `POST /payments` response (`paymentCode`, additive field).
- `authNumber` — the handle for all status queries (`originalReference`).
- `qrCode` — base64 QR image; not consumed today (text code + deep link
  cover app + USSD). A future FE can render it or use the deep link
  `com.innbucks.customer://purchase?paymentToken=<code>`.
- **Never retried** by our client: a retry after an ambiguous failure could
  mint a second live code. Generation moves no money, so failures close the
  ledger row FAILED and the customer just taps pay again.

### 2. `POST /api/code/query/originalReference`

Request: `{"reference": "<unique per query>", "originalReference": "<authNumber>"}`.

Response: `{... "responseCode": 0, "code", "amount", "status", "timeToLive",
"description"}` where `status` ∈:

| status      | meaning                              | our action                          |
|-------------|--------------------------------------|-------------------------------------|
| `New`       | generated, not yet approved          | wait; expire after local TTL+grace  |
| `Claimed`   | "finalised by the customer" (doc)    | treat as paid → confirm booking     |
| `Paid`      | finalised by the customer            | confirm booking → SUCCEEDED         |
| `Expired`   | validity period elapsed              | EXPIRED (slot freed)                |
| `Timed Out` | transaction window exceeded          | EXPIRED (slot freed)                |

Anything else → UNKNOWN: the poller leaves the row alone (auto-expiring a
code the customer may have paid is the double-charge path).

(`POST /api/code/query` — same thing keyed by the code itself — exists but is
not used.)

## Other endpoints (not consumed today, candidates later)

- `GET /api/code/{accountId}/miniStatement` — recent code transactions; the
  natural source for a daily code-payment reconciliation report.
- `POST /api/account/fullStatement` — full statement (calendar-month window).
- `GET /api/account/msisdn/{msisdn}/details` — MSISDN → account lookup
  (MSISDN format `263772123123`, no `+`).
- `POST /api/transaction/deposit` + `POST /bank/api/transaction/inquiry` —
  deposits and deposit-status inquiry.
- `POST /api/transaction/reversal/v2` — **deposits only**. ⚠️ "Real-time
  reversals are not available for any code-based transactions" — refunding a
  paid ticket code is a manual/ops flow until InnBucks provides a rail.
- `POST /api/transaction/bankChange`, `POST /api/utility/provider/payment` —
  out of scope.
