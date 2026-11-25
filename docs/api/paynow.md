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
| `returnurl` | ✓ | ✓ | Web: the website's return page, as configured. **Never with our payment id on it**: an id in a page URL reaches browser history, the site's analytics and its host's logs, and `GET /payments/{id}` turns it into the booking. The website keeps the `transactionId` across the redirect. Express: the same page (or the result URL). |
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
The query picks them, least recently checked first, so a page of payments that
are not due never hides one that is. A run stops starting checks after
`paynow.sweep-run-budget` (25 s): every rail's poller shares the scheduler, and
a Paynow that hangs costs up to three reads at the read timeout per check.

**After we close a payment unpaid** (EXPIRED), the sweep looks at it again for
`paynow.closed-recheck-for` (24 h): every 15 minutes in the first hour, then
every 3 hours. Paynow's card page can outlive our deadline, and its update
about a late payment can be lost. A paid status there is an operator's case
(below).

A customer is never answered from a stale "unpaid" either: before
`GET /payments/{transactionId}` answers a card attempt we closed, and before
`POST /payments` starts anything new for its order, we look at that attempt
once more (one read, at most every `paynow.check-min-gap`, for the same 24 h).
The return page reads right after a late payment, usually before Paynow's
update has reached us. Card only: a OneMoney prompt or an O'mari code cannot be
paid after its deadline. If the look fails, the answer is the row as it is.

## O'mari code

POST to `remoteotpurl`: `id`, `otp`, `status=Message`, `hash`. A status update
back on success; `status=Error&error=Invalid+OTP` on a wrong code. **Five wrong
codes cancel the payment.** Never retried (each try spends an attempt).

After an accepted code we read the payment's status ONCE (no retries): the
customer waits on that reply behind the gateway's 60 s, and the result update
or the sweep confirms the payment if the read fails. A code sent again for a
payment that already went through (or is being confirmed) answers 200 with that
payment, and O'mari is not asked again; so does an attempt we had closed that
Paynow then reported paid (stage VERIFYING, "don't pay again"). A code
for a payment that is over is a 409 whose `data` says how it ended.

A code that never left our server (our breaker was open, or no connection
opened) is a **503** "…your code was not sent. Enter it again in a moment.": it
was not used and O'mari counted no attempt, so the customer sends it again. A
code that was sent with no usable answer stays the **502** "wait, then check
your payment": it may have been used, so it is not asked for again.

## Our rules (PaynowPaymentService)

- The order's hold is extended to deadline + 2 min before anything else
  (card 30 min, phone 10 min).
- The row is opened with the trace on it, and moved TOKEN_ISSUED **before** the
  call (EcoCash's order): a OneMoney prompt can be live even when the reply is
  lost.
- No answer to a start: OneMoney stays open (the trace resolves it). A card or
  O'mari start closes FAILED at once: without Paynow's reply the customer has no
  page to pay on and nowhere to send the code.
- A start that never reached Paynow (our circuit breaker was open, or no
  connection could be opened) closes FAILED on every rail, OneMoney included:
  nothing was sent, so no prompt can be live. A read timeout or a reset
  connection can come after the request was written, and stays "no answer".
- A second tap while a card or O'mari start waits for Paynow's reply is
  answered IN_PROGRESS ("retry in a moment"), never "expired".
- A paid status must echo our amount and reference, or the payment is parked
  IN_DOUBT.
- **Paid is recorded before the booking is confirmed**, and only while the row
  is still open, under the row's lock: TOKEN_ISSUED becomes
  COMPLETED_UNCONFIRMED, then the booking is confirmed, then the payment is
  SUCCEEDED. A row another trigger closed meanwhile is never confirmed (it is
  an operator's case), and a failed confirm is left COMPLETED_UNCONFIRMED for
  the confirm-retry.
- An unknown status never closes a payment.
- **A decline holds that option for 2 minutes** (`paynow.decline-hold`, EcoCash's
  rule): after a OneMoney or O'mari attempt the customer cancelled or their
  wallet refused, a new attempt for the same order on the same option is a 400
  carrying the declined attempt (stage DECLINED, `retryAfterSeconds`). The
  website re-sends `POST /payments` every few seconds; without the hold each
  re-send after a cancel started a new prompt or texted a new code. Another
  option works at once.
- **Paynow's OneMoney test numbers** (below) are refused unless
  `PAYNOW_TEST_NUMBERS_ALLOWED=true`. While an integration is in test mode
  Paynow reports them paid with no money moved, so on a host that sells real
  tickets they would be free tickets. On a live integration they are Econet
  numbers, never OneMoney wallets. Only a staging host allows them.
- Money that moves after we closed or confirmed a payment changes nothing
  automatically: it is journalled and counted (alert `PaynowPaymentNeedsOperator`),
  once per payment (`paynow_flagged_at`):
  - paid after we closed it: the payment stays closed, and its answer is stage
    VERIFYING ("we received your payment … don't pay again"), never "nothing
    was charged". Every later `POST /payments` for the order answers that
    payment too, on every rail, and starts nothing: the order is paid for;
  - refunded or disputed before its booking was confirmed: parked IN_DOUBT, so
    the confirm-retry (which re-reads each row before its confirm) issues no
    tickets for money that went back; an earlier confirm that timed out may
    still have reached the booking, so the operator checks it;
  - refunded or disputed after the booking was confirmed: the tickets are the
    operator's call.
- **A refund or dispute after confirmation is seen only through Paynow's
  update.** Nothing polls a confirmed payment again. So the gateway's
  `paynow-result-write-route` bucket is sized for all of Paynow (50/s, burst
  500, one bucket for Paynow's own address), not like EcoCash's notify (5/s,
  burst 20): a 429 there never reaches payment-service, and Paynow stops
  resending after ten tries.
- Paynow's URLs are pinned to `PAYNOW_ALLOWED_HOSTS`; we never call or redirect
  to any other host.

**Not yet modelled:** settlement reconciliation of Paynow payments against
Paynow's records (like ZimSwitch and EcoCash: `SettlementReconciliationJob`
reads InnBucks code rows only), and refunds (Paynow has no refund API; an
operator refunds in Paynow's portal).

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

  We refuse these four numbers for OneMoney unless the host sets
  `PAYNOW_TEST_NUMBERS_ALLOWED=true` (staging only; see "Our rules").
- **O'mari:** the test page lists no numbers.
- Express card tokens (not used by us): `vmc` and `zimswitch` test tokens are on
  Paynow's test-mode page.
