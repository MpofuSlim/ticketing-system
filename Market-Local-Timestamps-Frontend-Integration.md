# Market-local timestamps — frontend integration

**What changed:** every timestamp a person reads now arrives in the **market's
wall clock with an explicit offset**, not UTC.

```
Before:  "createdAt": "2026-07-27T07:19:03Z"
Now:     "createdAt": "2026-07-27T09:19:03+02:00"
```

Same instant. Same ISO-8601. The difference is that the leading characters are
already the clock your user is standing in, so **you print the string and stop**.

This is the treatment `/scans/**` has had since the gate-dashboard fix. It now
applies to booking, event, user and seat services.

---

## 1. What you have to do

**Delete your timezone code.** Anything that converts, appends a `Z`, or guesses
a zone is now wrong — not subtly wrong, actively wrong.

```js
// DELETE — appending Z to a value that already carries +02:00 is garbage
const d = new Date(s.endsWith('Z') ? s : s + 'Z');

// DELETE — re-renders in the BROWSER's zone, undoing the whole point
new Date(row.createdAt).toLocaleString();

// KEEP — the string is the answer
row.createdAt.slice(11, 16);   // "09:19"
row.createdAt.slice(0, 10);    // "2026-07-27"
```

If you need a `Date` object for arithmetic (sorting, "3 days from now"),
`new Date(s)` still parses correctly — the offset is explicit. Just don't use
the result to *display* a wall clock, because that re-renders in the viewer's
zone.

### Why not just let the browser localise it?

Because the browser localises to the **viewer**, and we want the **market**.
Those coincide for a Harare user on a Harare phone, and diverge the moment
someone opens the app from another country, on a laptop with a stale zone, or in
a CI screenshot runner. The gate dashboard already shipped that bug once: every
scan read two hours early. The backend knows which market the cell serves; the
browser does not.

---

## 2. Which offset

Whatever the **cell's market** is — not the viewer's, not a constant.

| Market | Offset |
|---|---|
| ZW, ZM, MW, ZA, BW, MZ, LS, SZ | `+02:00` |
| KE | `+03:00` |
| NG | `+01:00` |

**Don't hardcode `+02:00`.** Read the offset from the string, or just don't look
at it — printing the leading characters verbatim is correct in every market.

None of the ten supported markets observes DST, so the offset for a given market
is stable year-round.

---

## 3. What did NOT change

- **Requests you send.** Inbound parsing is unchanged and permissive: `Z`,
  `±HH:mm` and zoneless strings all still parse. You can keep sending exactly
  what you send today.
- **Event create/update.** Still send the wall clock the organizer typed; the
  backend converts it to the stored instant. Unchanged.
- **Service-to-service payloads.** Still `Z`. Irrelevant to you, but it means a
  timestamp you see in a backend log may look different from the one in your
  response — that is intentional, not a bug.
- **Storage.** Everything is still stored and compared in UTC. This is a
  presentation change at the response edge only.

---

## 4. Checklist

- [ ] **Remove every `+ 'Z'` / `endsWith('Z')` guard.** These now corrupt the value.
- [ ] **Remove `toLocaleString()` / `toLocaleTimeString()` on our timestamps.**
      They re-render in the viewer's zone.
- [ ] **Don't hardcode `+02:00`** — it is the cell's market offset.
- [ ] **Don't double-convert.** The value is already local. A second shift
      double-shifts it.
- [ ] **Check your test fixtures and snapshots** — anything asserting a trailing
      `Z` on a user-facing response will now fail.
- [ ] `new Date(s)` for *arithmetic* is fine; for *display*, use the string.
