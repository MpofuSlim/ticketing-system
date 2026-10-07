// Scenario 5 — lending back office, READ-ONLY. A credit officer working the
// lending portal (loans-service, MpofuSlim/innbucks-loans, routed at
// /lending/** by loans-service-route).
//
// STAGING ONLY. loans-service runs on staging alone: production's Service has
// no endpoints, so every /lending/** call there is a 500 from the gateway.
// refusal() keeps this scenario off any target classified as production —
// even with ALLOW_PRODUCTION=true, and whatever STAGING_URLS says about a
// known production host.
//
// Touches (every journey call is a GET; the scope is the account's groups):
//   GET /lending/v1/loans?page&size                  loan list (newest first)
//   GET /lending/v1/loans/{loanId}                   loan detail (no document content,
//                                                    so no document-view log row)
//   GET /lending/v1/work-queues                      queue summaries
//   GET /lending/v1/work-queues/{stage}/items        one visible stage's items
//   GET /lending/v1/work-queues/mine                 the caller's own items
//   GET /lending/v1/loans/pending-credit-decision    only when the account sees CREDIT_DECISION
//   GET /lending/v1/staff-members?status&q&page&size staff register search
//   GET /lending/v1/merchants/{code}/users           a merchant's users (CREDIT_MANAGER only;
//                                                    loans has no other user search for a
//                                                    non-admin)
// Not loaded: GET /lending/v1/dashboard is SUPER_ADMIN-only, and this suite
// never signs in as an admin.
//
// Auth: loans is its own identity provider (own users table, own HS256 key),
// so this scenario signs in to LOANS with POST /lending/v1/auth/login —
// exactly ONCE per run, in setup(), and every VU reuses that token (24 h by
// default, JWT_EXPIRATION_MS). Loans locks an account for 30 minutes after
// SEVEN consecutive wrong passwords, so a failed sign-in is never retried: the
// run aborts and says why. A temporary-password account is refused too —
// changing a password is a write. forgot-password is never called (it is
// edge-denied, and it would replace the account's password).
//
// It never calls a POST/PUT/DELETE other than that one sign-in: nothing is
// decided, assigned, lodged, booked, paid, messaged or reset. Leaves nothing
// behind but the sign-in itself (a successful one only clears the failed-login
// counter).

import http from 'k6/http';
import { check } from 'k6';
import { str, num, list, scenarioThresholds } from '../lib/config.js';
import { think, pick, params, data } from '../lib/util.js';

export const NAME = 'lending';
// Journeys (iterations) per second. One journey is 6-8 GETs with ONE bearer,
// so every VU shares one gateway limiter bucket (50/s): keep it far below.
// loans-service runs ONE replica with a 19-connection pool on staging.
export const LOAD_RATE = num('LENDING_RATE', 1);
export const SOAK_RATE = num('LENDING_SOAK_RATE', 0.5);
export const ITER_SECONDS = 25;

const API = '/lending/v1';
const PAGE_SIZE = 20;
const STAFF_STATUS = str('LENDING_STAFF_STATUS', 'ACTIVE');

// Groups that would make this a privileged or machine account. The scenario
// only reads, but a load test must never hold an admin's or a till's token.
const REFUSED_GROUPS = ['SUPER_ADMIN', 'MERCHANT_TILL'];
// Who may read the work queues (WorkQueueController) and the staff register
// (StaffRegisterController READERS) without being SUPER_ADMIN.
const QUEUE_GROUPS = ['CREDIT_MANAGER', 'FINANCE'];
const STAFF_GROUPS = ['HUMAN_CAPITAL', 'CREDIT_MANAGER', 'FINANCE'];

/**
 * Why this scenario cannot run here, or null when it can. Called in the INIT
 * context, before any request: an explicit SCENARIOS=lending throws this
 * reason; SCENARIOS=all skips the scenario and prints it.
 */
export function refusal(targetKind) {
  if (targetKind === 'production') {
    return 'lending is STAGING-ONLY (loans-service has no endpoints on production; every /lending/** ' +
      'call there is a 500), so it never runs against a target classified as production, ' +
      'ALLOW_PRODUCTION or not. Point BASE_URL at staging and list it in STAGING_URLS.';
  }
  const missing = ['LENDING_USERNAME', 'LENDING_PASSWORD'].filter((n) => !str(n, undefined));
  if (missing.length > 0) {
    return `lending needs ${missing.join(' and ')}: a dedicated read-only back-office test account ` +
      'on the staging loans portal (load-tests/README.md §6).';
  }
  return null;
}

export function thresholds(profile) {
  return scenarioThresholds(profile, NAME, {
    lending_loans_list: 800,
    lending_loan_detail: 800,
    lending_work_queues: 1200,
    lending_queue_items: 1200,
    lending_my_work: 800,
    lending_credit_pending: 1200,
    lending_staff_search: 1200,
    lending_merchant_users: 800,
  });
}

function bearer(token) {
  return { headers: { Authorization: `Bearer ${token}` } };
}

/**
 * Signs in once, checks what the account is, and discovers what to read.
 * Every refusal throws, which aborts the whole run before the first journey.
 */
export function prepare(baseUrl, profile, seconds, targetKind) {
  const reason = refusal(targetKind);
  if (reason) throw new Error(reason);

  // ONE sign-in for the whole run. Never in a loop, never retried.
  const login = http.post(`${baseUrl}${API}/auth/login`,
    JSON.stringify({ username: str('LENDING_USERNAME'), password: str('LENDING_PASSWORD') }),
    params('POST /lending/v1/auth/login', 'setup', 200, { headers: { 'Content-Type': 'application/json' } }));
  const session = data(login);
  if (login.status === 423) {
    const wait = session && session.retryAfterSeconds;
    throw new Error(`lending pre-flight: the account is LOCKED (seven wrong passwords)${wait ? ` for another ${wait}s` : ''}. ` +
      'Not retried. Wait it out or have a loans SUPER_ADMIN reset it, then fix LENDING_PASSWORD.');
  }
  if (login.status === 401) {
    throw new Error('lending pre-flight: sign-in refused (wrong LENDING_USERNAME or LENDING_PASSWORD). Not retried: ' +
      'every wrong password counts toward loans\' seven-strike lockout — fix the value before running again.');
  }
  if (login.status !== 200 || !session || !session.accessToken) {
    throw new Error(`lending pre-flight: POST ${API}/auth/login answered ${login.status}. Not retried. ` +
      'Is loans-service running on this cell (it is staging-only)?');
  }
  if (session.temporaryPassword === true) {
    throw new Error('lending pre-flight: the account still has a TEMPORARY password, so its token only works for ' +
      'PUT /me/password. This suite never changes a password: sign in to the portal once by hand, set one, ' +
      'and put it in LENDING_PASSWORD.');
  }
  const groups = Array.isArray(session.groups) ? session.groups : [];
  const refused = groups.filter((g) => REFUSED_GROUPS.includes(g));
  if (refused.length > 0) {
    throw new Error(`lending pre-flight: the account holds ${refused.join(', ')}. Use a dedicated back-office ` +
      'test account (CREDIT_MANAGER), never an admin or a till.');
  }
  const expiresIn = Number(session.expiresIn || 0);
  if (expiresIn < seconds + 60) {
    throw new Error(`lending pre-flight: the loans token lives ${expiresIn}s but this profile runs lending for ` +
      `${seconds}s; it is not refreshed (that would mean signing in again). Shorten the run.`);
  }
  const token = session.accessToken;
  console.log(`lending: signed in once (groups ${groups.join(', ') || 'none'}); token reused by every VU`);

  // Loans to open: fixed by LENDING_LOAN_IDS, else the first page of the list.
  const listed = http.get(`${baseUrl}${API}/loans?page=0&size=${PAGE_SIZE}`,
    params('GET /lending/v1/loans', 'setup', 200, bearer(token)));
  const page = data(listed);
  if (listed.status !== 200 || !page) {
    throw new Error(`lending pre-flight: GET ${API}/loans answered ${listed.status}`);
  }
  let loanIds = list('LENDING_LOAN_IDS', undefined);
  loanIds.forEach((id) => {
    if (!/^\d+$/.test(id)) throw new Error(`LENDING_LOAN_IDS must be numeric loan ids, got "${id}"`);
  });
  if (loanIds.length === 0) loanIds = (page.items || []).map((l) => l.id).filter((id) => id !== undefined && id !== null);
  const loanPages = Math.max(1, Math.min(3, page.totalPages || 1));
  if (loanIds.length === 0) console.warn('lending: no loans visible to this account; loan detail is not exercised');

  // Work queues: the stages this account may VIEW, as the summary lists them.
  let stages = [];
  let queues = false;
  if (groups.some((g) => QUEUE_GROUPS.includes(g))) {
    const wq = http.get(`${baseUrl}${API}/work-queues`, params('GET /lending/v1/work-queues', 'setup', 200, bearer(token)));
    const summaries = data(wq);
    if (wq.status !== 200 || !Array.isArray(summaries)) {
      throw new Error(`lending pre-flight: GET ${API}/work-queues answered ${wq.status}`);
    }
    queues = true;
    stages = summaries.map((s) => s.stage).filter(Boolean);
  } else {
    console.warn(`lending: groups [${groups.join(', ')}] cannot read the work queues; queue reads skipped`);
  }
  const creditQueue = stages.includes('CREDIT_DECISION');

  const staff = groups.some((g) => STAFF_GROUPS.includes(g));
  if (!staff) console.warn('lending: this account cannot read the staff register; staff search skipped');
  const staffQueries = list('LENDING_STAFF_QUERIES', undefined);

  // Merchant users: CREDIT_MANAGER only. Codes from the merchant list.
  let merchantCodes = [];
  if (groups.includes('CREDIT_MANAGER')) {
    const ml = http.get(`${baseUrl}${API}/merchants`, params('GET /lending/v1/merchants', 'setup', 200, bearer(token)));
    const merchants = data(ml);
    if (ml.status !== 200 || !Array.isArray(merchants)) {
      throw new Error(`lending pre-flight: GET ${API}/merchants answered ${ml.status}`);
    }
    merchantCodes = merchants.map((m) => m.merchantCode).filter(Boolean);
  }

  console.log(`lending: ${loanIds.length} loan(s) to open, stages [${stages.join(', ') || 'none'}], ` +
    `${merchantCodes.length} merchant(s), staff search ${staff ? 'on' : 'off'}`);
  return { token, loanIds, loanPages, queues, stages, creditQueue, staff, staffQueries, merchantCodes };
}

function get(url, name, endpoint, token) {
  return http.get(url, params(name, endpoint, 200, bearer(token)));
}

export function run(baseUrl, d) {
  const base = `${baseUrl}${API}`;

  // The portal's landing screen: the loan list, a page or two in.
  const p = Math.floor(Math.random() * d.loanPages);
  const loans = get(`${base}/loans?page=${p}&size=${PAGE_SIZE}`, 'GET /lending/v1/loans', 'lending_loans_list', d.token);
  check(loans, { 'loans list 200': (r) => r.status === 200 && Array.isArray((data(r) || {}).items) });
  think(2, 5);

  if (d.loanIds.length > 0) {
    const detail = get(`${base}/loans/${pick(d.loanIds)}`, 'GET /lending/v1/loans/{loanId}', 'lending_loan_detail', d.token);
    check(detail, { 'loan detail 200': (r) => r.status === 200 });
    think(3, 8); // reading the application
  }

  if (d.queues) {
    const wq = get(`${base}/work-queues`, 'GET /lending/v1/work-queues', 'lending_work_queues', d.token);
    check(wq, { 'work queues 200': (r) => r.status === 200 && Array.isArray(data(r)) });
    if (d.stages.length > 0) {
      const stage = encodeURIComponent(pick(d.stages));
      const items = get(`${base}/work-queues/${stage}/items`, 'GET /lending/v1/work-queues/{stage}/items',
        'lending_queue_items', d.token);
      check(items, { 'queue items 200': (r) => r.status === 200 && Array.isArray(data(r)) });
    }
    const mine = get(`${base}/work-queues/mine`, 'GET /lending/v1/work-queues/mine', 'lending_my_work', d.token);
    check(mine, { 'my work 200': (r) => r.status === 200 && Array.isArray(data(r)) });
    think(2, 5);
  }

  if (d.creditQueue) {
    const pending = get(`${base}/loans/pending-credit-decision?page=0&size=${PAGE_SIZE}`,
      'GET /lending/v1/loans/pending-credit-decision', 'lending_credit_pending', d.token);
    check(pending, { 'credit pending 200': (r) => r.status === 200 });
    think(2, 5);
  }

  if (d.staff) {
    let q = '';
    if (d.staffQueries.length > 0) q = `&q=${encodeURIComponent(pick(d.staffQueries))}`;
    const staff = get(`${base}/staff-members?status=${encodeURIComponent(STAFF_STATUS)}${q}&page=0&size=${PAGE_SIZE}`,
      'GET /lending/v1/staff-members', 'lending_staff_search', d.token);
    check(staff, { 'staff search 200': (r) => r.status === 200 });
    think(2, 4);
  }

  if (d.merchantCodes.length > 0) {
    const code = encodeURIComponent(pick(d.merchantCodes));
    const users = get(`${base}/merchants/${code}/users`, 'GET /lending/v1/merchants/{merchantCode}/users',
      'lending_merchant_users', d.token);
    check(users, { 'merchant users 200': (r) => r.status === 200 && Array.isArray(data(r)) });
  }
  think(2, 4);
}
