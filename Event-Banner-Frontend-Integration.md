# Event Banner — Frontend Integration Guide

**Change:** an event's banner image can now be **replaced or removed after
creation**. Previously it was write-once — set at `POST /events` and never
changeable, because `PUT /events/{id}` is JSON-only. An organizer who uploaded
the wrong poster had to delete the event, which takes its bookings and seat
categories with it.

Merged in **PR #557** (`event-service`). Anchored to the merged code.

---

## 1. Base URL, auth, headers

| | |
|---|---|
| **Base URL** | the API gateway, e.g. `https://<host>/foundry` |
| **Auth** | `Authorization: Bearer <JWT>` — required on **PUT** and **DELETE** |
| **Roles** | `EVENT_ORGANIZER` (own events only) or `SUPER_ADMIN` (any event) |
| **`X-Tenant-Id`** | **not used** by event-service. Do not send it. |
| **Gateway route** | none added — the existing `event-service-route` (`Path=/events/**`) already covers both methods. |

`GET /events/{id}/banner` stays **public** (no token) — it serves the poster on
the listing page.

---

## 2. Endpoints

### 2.1 Replace the banner

```
PUT /events/{id}/banner
Content-Type: multipart/form-data
Authorization: Bearer <JWT>
```

| part | type | required | notes |
|---|---|---|---|
| `eventBanner` | file | **yes** | JPG / PNG / WEBP. Max 10 MB. |

> The part name is **`eventBanner`** — the same name `POST /events` uses. A
> different name is rejected by Spring before the handler runs.

**200 response** — the full refreshed event:

```json
{
  "code": "200 OK",
  "message": "Event banner updated successfully",
  "data": {
    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "title": "Summer Concert (Updated)",
    "venue": "Harare Gardens",
    "country": "Zimbabwe",
    "category": "CONCERT",
    "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
    "startDateTime": "2026-06-15T19:00:00Z",
    "endDateTime": "2026-06-15T22:00:00Z",
    "totalCapacity": 600,
    "availableTickets": 520,
    "active": true
  }
}
```

### 2.2 Remove the banner

```
DELETE /events/{id}/banner
Authorization: Bearer <JWT>
```

No body. **Idempotent** — an event that already has no banner returns `200`,
not `404`, so a repeated tap is harmless.

```json
{
  "code": "200 OK",
  "message": "Event banner removed successfully",
  "data": {
    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "title": "Summer Concert (Updated)",
    "bannerUrl": null,
    "...": "rest of the event unchanged"
  }
}
```

### 2.3 Read the banner (unchanged, listed for completeness)

```
GET /events/{id}/banner            # public, no token
```

Returns the **raw image bytes** with the stored `Content-Type`, plus:

- `X-Content-Type-Options: nosniff`
- `Cache-Control: public, max-age=3600` ← **1 hour**. See the caching gotcha below.

---

## 3. `bannerUrl` is stable across replacements

The URL is **always** `/events/{id}/banner` — it does not change when the image
changes, and it is `null` only when there is no banner. So:

- **Do not** treat a returned `bannerUrl` as a new, cache-busting path.
- **Do** append your own cache-buster after a successful replace (below).

---

## 4. Error handling

All errors use the standard envelope: `{ "code", "message", "data": null }`.
There are no per-row/per-field errors on these endpoints — a banner upload is a
single atomic operation, so every failure is top-level.

| status | `message` (verbatim from the service) | when |
|---|---|---|
| `400` | `Please choose an image to upload.` | no file part, or an **empty** file |
| `400` | `That image is too large. Please use one under 10 MB.` | > 10 MB |
| `400` | `Please upload a JPG, PNG, or WEBP image.` | `Content-Type` not in the allow-list |
| `400` | `Please upload a valid image file (JPG, PNG, or WEBP).` | magic bytes don't match a real JPG/PNG/WEBP |
| `401` | `Invalid token` | missing / invalid JWT |
| `403` | `You are not authorized to update this event` | organizer who does not own the event |
| `404` | `Event not found` | unknown or soft-deleted event id |

**The last two 400s are different failures — say so in the UI.** "Wrong type"
means the browser's declared MIME type was rejected; "not a valid image" means
the declared type was fine but the *bytes* aren't really that format (a renamed
file, a truncated upload, or a smuggled payload). Both are user-fixable but the
remedy differs.

**GIF is deliberately not accepted.** Banners are static marketing images. A
`.gif` gets `Please upload a JPG, PNG, or WEBP image.` — filter it out of your
file picker's `accept` attribute so the user never gets that far:

```html
<input type="file" accept="image/jpeg,image/png,image/webp">
```

### An empty file is a 400, not a silent success

Worth calling out because it differs from create. On `POST /events` the banner
is optional, so an empty file is quietly ignored. On a **replace** that would
return `200` while changing nothing — so it's a `400` instead. If your form
serialises an empty `File` when the user didn't pick one, you'll get this;
guard on the client instead of sending an empty part.

### A rejected upload leaves the previous banner intact

Every 400/403 path throws before anything is written. The old image is still
there, byte-for-byte. Your optimistic UI can roll back to the previous
preview with confidence.

---

## 5. Realistic examples

### Replace (fetch)

```js
const form = new FormData();
form.append('eventBanner', file);            // the part name matters

const res = await fetch(`${BASE}/events/${eventId}/banner`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${token}` },  // do NOT set Content-Type —
  body: form,                                     // the browser adds the boundary
});

const body = await res.json();
if (!res.ok) throw new Error(body.message);

// bannerUrl is unchanged; bust the 1-hour cache yourself:
setBannerSrc(`${BASE}${body.data.bannerUrl}?v=${Date.now()}`);
```

> **Never set `Content-Type` manually on a multipart request.** The browser must
> generate the `boundary=` parameter; hardcoding `multipart/form-data` without it
> makes the server unable to parse the parts.

### Remove

```js
const res = await fetch(`${BASE}/events/${eventId}/banner`, {
  method: 'DELETE',
  headers: { Authorization: `Bearer ${token}` },
});
const body = await res.json();
setBannerSrc(null);            // body.data.bannerUrl is null — render the placeholder
```

### curl

```sh
# replace
curl -X PUT "$BASE/events/$EVENT_ID/banner" \
  -H "Authorization: Bearer $TOKEN" \
  -F "eventBanner=@poster.png;type=image/png"

# remove
curl -X DELETE "$BASE/events/$EVENT_ID/banner" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 6. Gotchas checklist

- [ ] **Part name is `eventBanner`** — not `file`, not `banner`, not `image`.
- [ ] **Don't set `Content-Type`** on the PUT; let the browser add the boundary.
- [ ] **`bannerUrl` never changes** on a replace. Append `?v=<timestamp>` (or the
      event's `updatedAt`) yourself, or the browser serves the **old image for
      up to an hour** from the `max-age=3600` cache and the organizer will
      report the upload as "not working".
- [ ] **`bannerUrl: null`** after a DELETE, and on any event that never had one
      — render your placeholder, don't request the URL.
- [ ] **Empty file → 400**, not a silent no-op. Guard the form.
- [ ] **GIF is refused.** Set `accept="image/jpeg,image/png,image/webp"`.
- [ ] **10 MB per file** (gateway/service limit is 10 MB per file, 15 MB per
      request). Check `file.size` client-side for a faster, friendlier error.
- [ ] **403 vs 404**: 403 means the event exists but isn't theirs; 404 means no
      such event. Don't collapse them into one "couldn't save" toast — the
      organizer's next action differs.
- [ ] **DELETE is idempotent** — a double-tap is safe, no need to disable the
      button after the first call (though you still should, for clarity).
- [ ] **No `X-Tenant-Id`** on any event-service call.
- [ ] The response is the **full event object**, so you can replace your local
      event state from it rather than re-fetching.

---

## 7. What did *not* change

- `POST /events` — unchanged, banner still optional at creation.
- `PUT /events/{id}` — still JSON-only. It does **not** accept a banner; use the
  new `PUT /events/{id}/banner` for that. The two are independent calls, so an
  "edit event" screen that changes both text and image makes two requests.
- `GET /events/{id}/banner` — unchanged shape, headers and public access.
- No new gateway route, no schema migration, no change to `EventResponseDTO`'s
  fields.
