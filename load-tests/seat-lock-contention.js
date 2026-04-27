// Seat-lock contention test for the ticketing system.
//
// Exercises the path that just got hardened: many concurrent requests
// racing for a small pool of seats. Validates that the server returns
// clean 200/400/409 responses under load (no 500s, no hangs) and that
// the optimistic-lock + atomic-counter changes actually surface
// conflicts as 409s instead of silently corrupting state.
//
// ---------------------------------------------------------------------
// Setup (run once against a fresh stack):
//
//   1. Start the stack:
//        JWT_SECRET=$(openssl rand -base64 48) docker compose up --build -d
//
//   2. Register + verify a user, then capture the JWT. Through the
//      gateway:
//        AUTH_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
//          -H 'Content-Type: application/json' \
//          -d '{"identifier":"loadtest@example.com","password":"..."}' \
//          | jq -r '.data.token')
//
//   3. Create an event + seat category as a tenant, then list a few
//      AVAILABLE seat IDs (need at least 5 — the test fans out across
//      them so we get genuine contention rather than serial waits):
//        SEAT_IDS=$(curl -s "http://localhost:8080/seat-categories/$CATEGORY_ID/seats?status=AVAILABLE" \
//          -H "Authorization: Bearer $AUTH_TOKEN" \
//          | jq -r '[.data[].id] | join(",")')
//
// Run:
//
//   Native k6:
//     BASE_URL=http://localhost:8080 \
//     AUTH_TOKEN=... SEAT_IDS=uuid1,uuid2,uuid3,uuid4,uuid5 \
//       k6 run load-tests/seat-lock-contention.js
//
//   Dockerised (uses the k6 service in the loadtest profile):
//     AUTH_TOKEN=... SEAT_IDS=uuid1,uuid2,uuid3,uuid4,uuid5 \
//       docker compose --profile loadtest run --rm k6
//
// What to look for in the output:
//
//   * lock_succeeded ≈ SEAT_IDS.length  (each seat locks exactly once,
//     all subsequent attempts on a locked seat return 400/409).
//   * lock_conflicted_409 > 0           (proves @Version is firing under
//     contention — if 0, you don't have enough VUs or the seats freed
//     up between requests).
//   * lock_unavailable_400 climbs as seats fill up — expected.
//   * lock_other_errors == 0            (any 5xx is a regression).
//   * http_req_duration p95 < 2s        (threshold; will fail the run
//     if breached).
// ---------------------------------------------------------------------

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const SEAT_IDS = (__ENV.SEAT_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);

if (!AUTH_TOKEN) {
  throw new Error('AUTH_TOKEN env var is required (Bearer JWT for the test user)');
}
if (SEAT_IDS.length === 0) {
  throw new Error('SEAT_IDS env var is required (comma-separated seat UUIDs)');
}

const lockSucceeded = new Counter('lock_succeeded');
const lockConflicted409 = new Counter('lock_conflicted_409');
const lockUnavailable400 = new Counter('lock_unavailable_400');
const lockOtherErrors = new Counter('lock_other_errors');

export const options = {
  // Ramp 0→200 VUs in 30s, hold 200 for 60s, ramp down in 10s.
  // Tune VUs via K6_VUS_PEAK env var if you want a heavier or lighter run.
  stages: [
    { duration: '30s', target: parseInt(__ENV.K6_VUS_PEAK || '200', 10) },
    { duration: '60s', target: parseInt(__ENV.K6_VUS_PEAK || '200', 10) },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    // 95th percentile of lock requests must complete under 2s
    'http_req_duration{name:lock}': ['p(95)<2000'],
    // 5xx-or-network failures should be rare; 4xx (400/409) are expected
    // and don't count toward http_req_failed by default
    http_req_failed: ['rate<0.05'],
    // Hard fail the run if we ever see a 5xx or unexpected status
    lock_other_errors: ['count==0'],
  },
};

export default function () {
  const seatId = SEAT_IDS[Math.floor(Math.random() * SEAT_IDS.length)];
  const res = http.post(`${BASE_URL}/seats/${seatId}/lock`, null, {
    headers: { Authorization: `Bearer ${AUTH_TOKEN}` },
    tags: { name: 'lock' },
  });

  if (res.status === 200) {
    lockSucceeded.add(1);
  } else if (res.status === 409) {
    lockConflicted409.add(1);
  } else if (res.status === 400) {
    lockUnavailable400.add(1);
  } else {
    lockOtherErrors.add(1);
  }

  check(res, {
    'status is 200/400/409': (r) => [200, 400, 409].includes(r.status),
  });
}
