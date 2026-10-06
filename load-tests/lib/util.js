// Small helpers shared by every scenario. No remote imports: the suite runs
// with nothing but the k6 binary.

import http from 'k6/http';
import { sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { num } from './config.js';

const THINK_SCALE = num('THINK_TIME_SCALE', 1);

/** Sleep a uniformly random time in [min, max] seconds — a person reading a screen. */
export function think(min, max) {
  const s = (min + Math.random() * (max - min)) * THINK_SCALE;
  if (s > 0) sleep(s);
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function uuidv4() {
  const b = new Uint8Array(crypto.randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function requireUuid(name, value) {
  if (!value || !UUID.test(value)) {
    throw new Error(`${name} must be a UUID, got "${value === undefined ? '' : value}"`);
  }
  return value.toLowerCase();
}

const E164 = /^\+[1-9]\d{8,14}$/;

export function requirePhone(name, value) {
  if (!value || !E164.test(value)) {
    throw new Error(`${name} must be an E.164 phone such as +263770000000`);
  }
  return value;
}

/** Last four digits only — phones never appear whole in k6 output. */
export function maskPhone(p) {
  return p ? `****${String(p).slice(-4)}` : '';
}

/**
 * Request params with a stable name (so URLs with ids group into one series),
 * an `endpoint` tag the thresholds key on, and the statuses that count as
 * success for http_req_failed.
 */
export function params(name, endpoint, expected, extra = {}) {
  const statuses = Array.isArray(expected) ? expected : [expected];
  return Object.assign(
    {
      tags: { name, endpoint },
      responseCallback: http.expectedStatuses(...statuses),
      timeout: '30s',
    },
    extra,
    { headers: Object.assign({ Accept: 'application/json' }, extra.headers || {}) },
  );
}

/** Parsed JSON body, or null when the body is missing or not JSON. */
export function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

/** `data` of the fleet's ApiResult envelope, or null. */
export function data(res) {
  const body = json(res);
  return body && typeof body === 'object' ? body.data : null;
}

/**
 * Claims of a JWT, decoded WITHOUT verifying it — only so the pre-flight can
 * say "this token expires in 3 minutes" or "this token is not a cashier's"
 * before the run instead of after it. The gateway and services verify it.
 */
export function jwtClaims(token) {
  const parts = String(token).split('.');
  if (parts.length !== 3) throw new Error('TILL_TOKEN is not a JWT (expected three dot-separated parts)');
  try {
    return JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's'));
  } catch (e) {
    throw new Error('TILL_TOKEN payload is not base64url JSON');
  }
}
