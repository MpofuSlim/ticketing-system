// Scenario 3 — loyalty till. A cashier (SHOP_USER) serving customers.
//
// Reads (always):
//   GET  /loyalty/transactions/my-shop?page&size       the till's own feed
//   GET  /loyalty/vouchers/users/by-phone/{phone}/active  customer at the counter
//                                                      (a SHOP_USER gets every code as null)
//   GET  /loyalty/redemption-rate?currency=           what a point is worth
//   POST /loyalty/qr/status {token}                    an UNKNOWN token -> 404, by design:
//                                                      the poll path without minting a QR
// Writes (only with ENABLE_WRITES=true, against a dedicated test merchant):
//   POST /loyalty/transactions  PURCHASE earn, reference LOADTEST-<uuid>
//   POST /loyalty/redeem        burn of 1 point (needs LOYALTY_TEST_USER_ID)
//
// Auth: a pre-minted access token in TILL_TOKEN. Till accounts are 2FA
// accounts, so k6 never logs in and never holds a TOTP secret. The token is
// not refreshed (refresh rotates and is reuse-detected), so it must outlive
// the run — access tokens last 15 minutes by default.

import http from 'k6/http';
import { check } from 'k6';
import { str, num, bool, scenarioThresholds } from '../lib/config.js';
import { think, params, data, requireUuid, requirePhone, maskPhone, uuidv4, jwtClaims } from '../lib/util.js';

export const NAME = 'till';
export const LOAD_RATE = num('TILL_RATE', 5);
export const SOAK_RATE = num('TILL_SOAK_RATE', 2);
export const ITER_SECONDS = 14;

const WRITES = bool('ENABLE_WRITES');
const EARN_RATIO = Math.min(1, num('TILL_EARN_RATIO', 0.3));
const BURN_RATIO = Math.min(1, num('TILL_BURN_RATIO', 0.1));
const EARN_AMOUNT = num('TILL_EARN_AMOUNT', 1);
const CURRENCY = str('TILL_CURRENCY', 'USD');

export function thresholds(profile) {
  const budgets = {
    till_my_shop: 800,
    till_vouchers_by_phone: 800,
    till_redemption_rate: 800,
    till_qr_status: 800,
  };
  if (WRITES) {
    budgets.till_earn = 1500;
    if (str('LOYALTY_TEST_USER_ID', undefined)) budgets.till_burn = 1500;
  }
  return scenarioThresholds(profile, NAME, budgets);
}

export function prepare(baseUrl, profile, seconds, targetKind) {
  const token = str('TILL_TOKEN', undefined);
  if (!token) {
    throw new Error('TILL_TOKEN is required: a fresh access token of a SHOP_USER test account ' +
      '(sign in through the console, complete 2FA, copy the token). k6 never logs in.');
  }
  const claims = jwtClaims(token);
  const roles = Array.isArray(claims.roles) ? claims.roles : [];
  if (!roles.includes('SHOP_USER')) {
    console.warn(`till: TILL_TOKEN roles are [${roles.join(', ')}], not SHOP_USER; results describe that role`);
  }
  const left = Math.floor(Number(claims.exp || 0) - Date.now() / 1000);
  if (left < seconds + 60) {
    throw new Error(`till pre-flight: TILL_TOKEN expires in ${left}s but this profile runs the till for ` +
      `${seconds}s. Mint a fresh token right before the run; for the 30-minute soak the token must ` +
      'outlive it (or run the till soak as two SOAK_DURATION=14m runs).');
  }

  const tenantId = requireUuid('LOYALTY_TENANT_ID', str('LOYALTY_TENANT_ID', undefined));
  const lookupPhone = requirePhone('TILL_LOOKUP_PHONE', str('TILL_LOOKUP_PHONE', undefined));

  const d = { token, tenantId, lookupPhone, writes: false };
  if (!WRITES) return d;

  if (targetKind === 'production' && !bool('ALLOW_PRODUCTION_WRITES')) {
    throw new Error('ENABLE_WRITES earns and burns real points; refused on production without ALLOW_PRODUCTION_WRITES=true');
  }
  // Writes go to ONE named test merchant, and the token must be that
  // merchant's till: a SHOP_USER's merchant comes from its token claim, so this
  // is the only way to be sure the earns do not land on a real business.
  const merchantId = requireUuid('LOYALTY_TEST_MERCHANT_ID', str('LOYALTY_TEST_MERCHANT_ID', undefined));
  if (String(claims.merchantId || '').toLowerCase() !== merchantId) {
    throw new Error('till pre-flight: ENABLE_WRITES needs TILL_TOKEN to belong to LOYALTY_TEST_MERCHANT_ID ' +
      `(token merchantId claim is "${claims.merchantId || 'absent'}")`);
  }
  const earnPhone = requirePhone('LOYALTY_TEST_PHONE', str('LOYALTY_TEST_PHONE', lookupPhone));
  if (claims.phoneNumber && claims.phoneNumber === earnPhone) {
    throw new Error('till pre-flight: LOYALTY_TEST_PHONE is the cashier\'s own phone; the earn would be refused SELF_EARN');
  }
  const burnUserId = str('LOYALTY_TEST_USER_ID', undefined);
  if (burnUserId) requireUuid('LOYALTY_TEST_USER_ID', burnUserId);
  console.log(`till: WRITES ON — earns of ${EARN_AMOUNT} ${CURRENCY} to ${maskPhone(earnPhone)} ` +
    `(${EARN_RATIO * 100}% of visits)` +
    (burnUserId ? `, 1-point burns (${BURN_RATIO * 100}% of visits)` : ', no burns (LOYALTY_TEST_USER_ID unset)'));
  return Object.assign(d, { writes: true, merchantId, earnPhone, burnUserId });
}

function headers(d, json) {
  const h = { Authorization: `Bearer ${d.token}`, 'X-Tenant-Id': d.tenantId };
  if (json) h['Content-Type'] = 'application/json';
  return { headers: h };
}

export function run(baseUrl, d) {
  const page = Math.floor(Math.random() * 2);
  const feed = http.get(`${baseUrl}/loyalty/transactions/my-shop?page=${page}&size=20`,
    params('GET /loyalty/transactions/my-shop', 'till_my_shop', 200, headers(d)));
  check(feed, { 'my-shop 200': (r) => r.status === 200 });
  think(2, 5); // next customer walks up

  const phone = encodeURIComponent(d.lookupPhone);
  const vouchers = http.get(`${baseUrl}/loyalty/vouchers/users/by-phone/${phone}/active?page=0&size=20`,
    params('GET /loyalty/vouchers/users/by-phone/{phone}/active', 'till_vouchers_by_phone', 200, headers(d)));
  check(vouchers, { 'vouchers by phone 200': (r) => r.status === 200 });

  const rate = http.get(`${baseUrl}/loyalty/redemption-rate?currency=${encodeURIComponent(CURRENCY)}`,
    params('GET /loyalty/redemption-rate', 'till_redemption_rate', 200, headers(d)));
  check(rate, { 'redemption rate 200': (r) => r.status === 200 });
  think(1, 3);

  // The till polls a QR the customer is about to scan. An unknown token is the
  // documented 404 ("invalid or has expired"), the same answer as a real one
  // the caller may not see — counted as success here.
  const qr = http.post(`${baseUrl}/loyalty/qr/status`,
    JSON.stringify({ token: `loadtest-${uuidv4().slice(0, 18)}` }),
    params('POST /loyalty/qr/status', 'till_qr_status', [200, 404], headers(d, true)));
  check(qr, { 'qr status 404 for unknown token': (r) => r.status === 404 });

  if (d.writes) {
    if (Math.random() < EARN_RATIO) {
      think(1, 2);
      const earn = http.post(`${baseUrl}/loyalty/transactions`, JSON.stringify({
        assigneePhone: d.earnPhone,
        type: 'PURCHASE',
        amount: EARN_AMOUNT,
        currency: CURRENCY,
        reference: `LOADTEST-${uuidv4()}`,
      }), params('POST /loyalty/transactions', 'till_earn', 201, headers(d, true)));
      check(earn, { 'earn 201': (r) => r.status === 201 });
    }
    if (d.burnUserId && Math.random() < BURN_RATIO) {
      think(1, 2);
      const burn = http.post(`${baseUrl}/loyalty/redeem`, JSON.stringify({
        userId: d.burnUserId,
        points: 1,
        reason: 'Load test burn',
        reference: `LOADTEST-${uuidv4()}`,
      }), params('POST /loyalty/redeem', 'till_burn', 200, headers(d, true)));
      check(burn, { 'burn 200': (r) => r.status === 200 });
    }
  }
  think(2, 4);
}
