// Scenario 2 — ticket checkout, read-mostly, NO PAYMENT.
//
// A guest opens a dedicated test event, picks a category, fills in the form
// and submits — then walks away from the payment screen. The 5-minute hold
// (BOOKING_HOLD_TTL_MINUTES) lapses and booking-service's expiry sweep
// cancels the booking and returns the ticket to the category.
//
// Touches:
//   event-service    GET  /events/{id}
//   seat-service     GET  /seat-categories?eventId=
//   booking-service  POST /bookings              -> 201, status PENDING   (WRITE)
//                    GET  /bookings/public/{id}  -> the checkout screen's status read
//
// It NEVER calls /payments. What it leaves behind:
//   - one PENDING booking per iteration, CANCELLED by the sweep ~5-5.5 min later
//     (rows stay as history on the test event);
//   - ONE CANCELLATION SMS PER BOOKING to CHECKOUT_PHONE when the hold lapses
//     (BookingCancelledNotificationListener; WhatsApp only if the SMS fails).
//     Billable, and the phone receives every one of them. Size the rate with that
//     in mind.

import http from 'k6/http';
import { check } from 'k6';
import { str, num, list, bool, scenarioThresholds } from '../lib/config.js';
import { think, pick, params, data, requireUuid, requirePhone, maskPhone } from '../lib/util.js';

export const NAME = 'checkout';
export const LOAD_RATE = num('CHECKOUT_RATE', 1);
export const SOAK_RATE = num('CHECKOUT_SOAK_RATE', 0.2);
export const ITER_SECONDS = 18;

const QTY = Math.max(1, Math.min(4, Math.round(num('CHECKOUT_QTY', 1))));
const MARKER = str('CHECKOUT_MARKER', 'LOADTEST');

export function thresholds(profile) {
  return scenarioThresholds(profile, NAME, {
    checkout_event: 800,
    checkout_categories: 800,
    booking_create: 1500,
    booking_status: 800,
  });
}

/** Validates the env and the test event before the first booking is made. */
export function prepare(baseUrl, profile, seconds, targetKind) {
  if (targetKind === 'production' && !bool('ALLOW_PRODUCTION_WRITES')) {
    throw new Error('checkout creates bookings and sends SMS; refused on production without ALLOW_PRODUCTION_WRITES=true');
  }
  const eventId = requireUuid('CHECKOUT_EVENT_ID', str('CHECKOUT_EVENT_ID', undefined));
  const categoryId = requireUuid('CHECKOUT_CATEGORY_ID', str('CHECKOUT_CATEGORY_ID', undefined));
  const phones = list('CHECKOUT_PHONE', undefined);
  if (phones.length === 0) {
    throw new Error('CHECKOUT_PHONE is required: a test phone you control (comma list allowed). ' +
      'Every booking texts it a cancellation notice when its hold lapses.');
  }
  phones.forEach((p) => requirePhone('CHECKOUT_PHONE', p));

  const ev = http.get(`${baseUrl}/events/${eventId}`, params('GET /events/{id}', 'setup', 200));
  const event = data(ev);
  if (ev.status !== 200 || !event) {
    throw new Error(`checkout pre-flight: GET /events/${eventId} answered ${ev.status}`);
  }
  const cats = http.get(`${baseUrl}/seat-categories?eventId=${eventId}`,
    params('GET /seat-categories', 'setup', 200));
  const category = (data(cats) || []).find((c) => String(c.seatCategoryId).toLowerCase() === categoryId);
  if (!category) {
    throw new Error(`checkout pre-flight: category ${categoryId} is not a live category of event ${eventId}`);
  }

  // The dedicated-test-event guard: book only on something visibly marked as a
  // load-test fixture, so a copy-pasted id can never hold seats on a real show.
  const marked = [event.title, category.name].some(
    (s) => String(s || '').toUpperCase().includes(MARKER.toUpperCase()),
  );
  if (!marked) {
    throw new Error(`checkout pre-flight: neither the event title nor the category name contains "${MARKER}". ` +
      'Book only on a dedicated test event (or set CHECKOUT_MARKER to its marker).');
  }

  // Seats held at once ~= rate x (hold 5 min + sweep 30 s) x qty. Below that the
  // category sells out mid-run and every booking after it is a 409.
  const rate = profile === 'smoke' ? 1 / ITER_SECONDS : profile === 'load' ? LOAD_RATE : SOAK_RATE;
  const heldAtPeak = Math.ceil(rate * Math.min(seconds, 330) * QTY);
  const available = Number(category.availableSeats);
  if (Number.isFinite(available) && available < heldAtPeak * 1.5) {
    throw new Error(`checkout pre-flight: category has ${available} seats left but the run holds up to ` +
      `~${heldAtPeak} at once. Seed a bigger LOADTEST category (see README).`);
  }
  const bookings = Math.ceil(rate * seconds);
  console.log(`checkout: event "${event.title}", category "${category.name}" (${available} available); ` +
    `~${bookings} bookings, ~${bookings} cancellation SMS to ${phones.map(maskPhone).join(', ')}`);
  return { eventId, categoryId, phones };
}

export function run(baseUrl, d) {
  const ev = http.get(`${baseUrl}/events/${d.eventId}`, params('GET /events/{id}', 'checkout_event', 200));
  check(ev, { 'event 200': (r) => r.status === 200 });
  const cats = http.get(`${baseUrl}/seat-categories?eventId=${d.eventId}`,
    params('GET /seat-categories', 'checkout_categories', 200));
  check(cats, { 'categories 200': (r) => r.status === 200 });
  think(3, 8); // choosing tickets and typing a name and number

  const seats = [];
  for (let i = 0; i < QTY; i++) seats.push({ categoryId: d.categoryId });
  const res = http.post(`${baseUrl}/bookings`, JSON.stringify({
    eventId: d.eventId,
    customerName: 'Load Test',
    phoneNumber: pick(d.phones),
    seats,
  }), params('POST /bookings', 'booking_create', 201, { headers: { 'Content-Type': 'application/json' } }));
  const booking = data(res);
  const ok = check(res, {
    'booking 201': (r) => r.status === 201,
    'booking PENDING': () => !!booking && booking.status === 'PENDING',
  });
  if (!ok || !booking || !booking.id) {
    if (res.status === 409) console.warn('checkout: 409 — the LOADTEST category is sold out or held');
    return;
  }

  // The payment screen reads the booking back; the user then abandons it.
  think(2, 4);
  const status = http.get(`${baseUrl}/bookings/public/${booking.id}`,
    params('GET /bookings/public/{id}', 'booking_status', 200));
  check(status, { 'booking status 200': (r) => r.status === 200 });
  think(2, 4);
}
