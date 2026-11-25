# Paynow — the PAYNOW_CARD, ONEMONEY and OMARI rails

Distilled from Paynow's developer docs (`https://developers.paynow.co.zw/docs/paynow/`,
read 2026-11-25: initiate transaction, express checkout, status update, polling,
generating and validating the hash, complete transaction, test mode). Update
this file, `PaynowClient` and `PaynowClientContractTest` together when Paynow's
real replies differ.

## What we use Paynow for

Only the methods our other rails do not carry (owner's decision, 2026-11-25),
and for **tickets only** for now:

| Rail | Paynow flow | Customer |
|---|---|---|
| `PAYNOW_CARD` | Web checkout (`/interface/initiatetransaction`) | Pays on Paynow's page: Visa/Mastercard, and ZimSwitch cards, whatever the merchant's integration has switched on. Back to the website's return page afterwards. |
| `ONEMONEY` | Express checkout (`/interface/remotetransaction`, `method=onemoney`) | A prompt on their phone, like EcoCash. |
| `OMARI` | Express checkout (`method=omari`) | O'mari texts a code; they type it into our checkout; we POST it to `remoteotpurl`. |

EcoCash, InnBucks and ZimSwitch stay on our own rails. A customer on Paynow's
card page may still choose another method the integration offers; the payment
then works and confirms the same way.

A first card payment cannot use express checkout: express card payments take a
saved card token only (`tokenize=true` on an earlier web payment, a permission
Paynow grants on request).

## Credentials

Per integration, from the merchant's Paynow account ("Receive Payment Links"):

- **Integration ID** (`id` on every request).
- **Integration Key**, a SECRET: it signs every message both ways. Whoever holds
  it can sign messages that pass as Paynow's.

The initiate request has **no currency field**: an integration is in one
currency. We hold one per currency (`PAYNOW_USD_*`, `PAYNOW_ZWG_*`) and the
order's currency picks it.

## The hash

Every message to or from Paynow carries `hash`: every other value of the
message, **in message order**, joined with nothing between them, then the
integration key, UTF-8, SHA-512, upper-case hex. Outbound values are hashed as
sent (before URL-encoding); inbound values after URL-decoding (`+` is a space).
Paynow's two published examples reproduce exactly (`PaynowHashTest`).

Order matters, and Paynow's own examples do not keep one field order, so a
reply is parsed in the order received. Key case varies too (the initiate example
answers `Status=Ok&BrowserUrl=…`), so keys are read in any case.

`status=Error` replies carry no hash in the docs. They arrive on the TLS
connection we opened, which is what makes them Paynow's.

## Initiate (web) and express checkout

POST, `application/x-www-form-urlencoded`. Fields we send, in this order:

| Field | Web | Express | Notes |
|---|---|---|---|
| `id` | ✓ | ✓ | Integration ID |
| `reference` | ✓ | ✓ | Our payment reference (`TKZ-<tag>-<hex>`), unique per transaction |
| `amount` | ✓ | ✓ | Major units, two decimals: `"12.50"` |
| `additionalinfo` | ✓ | ✓ | Shown to the customer; no confidential data. We send "Ticketize tickets". |
| `returnurl` | ✓ | ✓ | Web: the website's return page + `transactionId=<id>`. Express: the same page (or the result URL). |
| `resulturl` | ✓ | ✓ | Our `POST /payments/paynow/result`, absolute, with `/foundry` |
| `authemail` | — | ✓ | Required on express. **Test mode: must be a login email of the merchant.** Not sent on web (in test mode any other email blocks the payment). |
| `phone` | — | ✓ | `0771234567` form |
| `method` | — | ✓ | `onemoney`, `omari` (also `ecocash`, `innbucks`, `vmc`, `zimswitch`, not used) |
| `merchanttrace` | ✓ | ✓ | Ours, 32 hex, unique per merchant. Lets us look a payment up after a timeout. |
| `status` | ✓ | ✓ | `Message` |
| `hash` | ✓ | ✓ | |

Replies:

- `status=Ok` + `browserurl` (web), `pollurl`, `paynowreference`, `hash`; express
  may add `instructions`; O'mari adds `otpreference` and `remoteotpurl`.
  **Verify the hash before sending the customer to `browserurl`.**
- `status=Error&error=…`: refused, nothing created (e.g. `Invalid amount field`,
  or `Insufficient balance` on express).

**Initiate is never retried**: there is no idempotency key. A timeout is
resolved with the trace instead.

## Status: result URL, poll, trace

**Result URL.** Paynow POSTs `reference`, `amount`, `paynowreference`,
`pollurl`, `status`, `hash` whenever the status changes. It resends up to ten
times if we answer an HTTP error, and recommends polling to confirm any
important update. We act on an update only when its hash verifies under the
payment's own integration, and then only by polling.

**Poll.** An empty POST to `pollurl`; the reply has the status-update fields.

**Trace.** POST `/interface/trace` with `id`, `merchanttrace`, `status=Message`,
`hash`: a status update when found; `status=NotFound` (hashed) when not;
`status=Error` does **not** mean not found.

Statuses:

| Status | Meaning | Ours |
|---|---|---|
| Paid | Paid | PAID |
| Awaiting Delivery | Paid; funds held until delivery is confirmed | PAID |
| Delivered | Paid; funds held for the 24-hour window | PAID |
| Created, Sent | Not paid yet | OPEN |
| Cancelled | Cancelled; must be recreated | CANCELLED |
| Failed | Test mode's "user cancelled" sends it | CANCELLED |
| Disputed, Refunded | Money held or returned | REVERSED (operator) |

Paynow asks merchants to poll only to confirm an update or before discarding an
old payment, so our sweep checks only payments whose reply was lost, payments
past their deadline, and ones not checked for `paynow.recheck-every` (2 min).

## O'mari code

POST to `remoteotpurl`: `id`, `otp`, `status=Message`, `hash`. A status update
back on success; `status=Error&error=Invalid+OTP` on a wrong code. **Five wrong
codes cancel the payment.** Never retried (each try spends an attempt).

## Our rules (PaynowPaymentService)

- The order's hold is extended to deadline + 2 min before anything else
  (card 30 min, phone 10 min).
- The row is opened with the trace on it, and moved TOKEN_ISSUED **before** the
  call (EcoCash's order): a OneMoney prompt can be live even when the reply is
  lost.
- No answer to a start: OneMoney stays open (the trace resolves it). A card or
  O'mari start closes FAILED at once: without Paynow's reply the customer has no
  page to pay on and nowhere to send the code.
- A paid status must echo our amount and reference, or the payment is parked
  IN_DOUBT.
- An unknown status never closes a payment.
- Money that moves after we closed or confirmed a payment changes nothing
  automatically: it is journalled and counted (alert `PaynowPaymentNeedsOperator`).
- Paynow's URLs are pinned to `PAYNOW_ALLOWED_HOSTS`; we never call or redirect
  to any other host.

## Test mode

A new integration starts in test mode; Paynow sets it live on request, after at
least one successful test payment.

- **Web checkout:** only the merchant account that created the integration can
  log in on Paynow's page and choose **[TESTING: Faked Success]**. No real card
  is used, so test mode proves our integration, not that real cards work.
- **Express mobile money** (EcoCash, OneMoney; `authemail` = a merchant login):

  | Phone | Result |
  |---|---|
  | 0771111111 | Success after 5 s |
  | 0772222222 | Success after 30 s |
  | 0773333333 | User cancelled (FAILED) after 30 s |
  | 0774444444 | Refused at initiate: Insufficient balance |

- **O'mari:** the test page lists no numbers.
- Express card tokens (not used by us): `vmc` and `zimswitch` test tokens are on
  Paynow's test-mode page.
