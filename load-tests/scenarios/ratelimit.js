// Scenario 4 — auth: the gateway rate limiter answers 429. A CORRECTNESS check
// at a gentle rate, not load.
//
// Why this route. Login is limited in user-service per identifier (5/min) and
// /auth/refresh rotates and is reuse-detected, so neither is loaded. The 2FA
// step, POST /auth/login/mfa, sits on the gateway's auth-mfa-route: 1/s with a
// burst of 3, keyed on the client IP. Sending it a token that is not a token
// is refused by user-service with a plain 400 before any account is looked up
// (no audit row, no lockout, no message sent), so the only thing exercised is
// the limiter in front of it.
//
// Shape: one VU sends PROBES (10) requests ~4 per second, then waits for the
// bucket to refill and sends one more. Passes when at least one probe got a
// 429 with the limiter's headers, the request after the pause did NOT, and
// nothing else (5xx, 404) came back.
//
// Side effect worth knowing: for ~5 s the generator's public IP has no 2FA
// budget left on this cell. Anyone signing in from the SAME IP (an office NAT)
// in that window would see one 429 on their code step and can just retry.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { params } from '../lib/util.js';

export const NAME = 'ratelimit';

const limited = new Counter('ratelimit_429');
const recovered = new Counter('ratelimit_recovered');
const unexpected = new Counter('ratelimit_unexpected');

const PROBES = 10;
const BODY = JSON.stringify({ mfaToken: 'loadtest-not-a-token', code: '000000' });

export function scenario() {
  return {
    executor: 'per-vu-iterations',
    exec: 'ratelimit',
    vus: 1,
    iterations: 1,
    maxDuration: '1m',
  };
}

export function thresholds() {
  return {
    ratelimit_429: ['count>0'],
    ratelimit_recovered: ['count>0'],
    ratelimit_unexpected: ['count==0'],
  };
}

function probe(baseUrl) {
  return http.post(`${baseUrl}/auth/login/mfa`, BODY,
    params('POST /auth/login/mfa', 'ratelimit_probe', [400, 429], {
      headers: { 'Content-Type': 'application/json' },
    }));
}

export function run(baseUrl) {
  let seen429 = false;
  for (let i = 0; i < PROBES; i++) {
    const r = probe(baseUrl);
    if (r.status === 429) {
      seen429 = true;
      limited.add(1);
      check(r, {
        '429 carries X-RateLimit headers': (x) => x.headers['X-Ratelimit-Burst-Capacity'] !== undefined
          || x.headers['X-RateLimit-Burst-Capacity'] !== undefined,
      });
    } else if (r.status !== 400) {
      unexpected.add(1);
      console.warn(`ratelimit: probe ${i + 1} answered ${r.status}, expected 400 or 429`);
    }
    sleep(0.25);
  }
  check(null, { 'limiter answered 429 inside the burst': () => seen429 });

  // Burst 3 refills at 1/s: after 5 s the bucket has room again.
  sleep(5);
  const after = probe(baseUrl);
  if (after.status === 400) {
    recovered.add(1);
  } else if (after.status !== 429) {
    unexpected.add(1);
  }
  check(after, { 'limiter lets traffic through again after the pause': (r) => r.status === 400 });
}
