// Environment, target guard, profiles and thresholds for the fleet load tests.
//
// Everything here runs in k6's INIT context, so a refusal (a production target
// without ALLOW_PRODUCTION, a missing id) aborts the run before a single
// request is sent.

const env = __ENV;

export function str(name, fallback) {
  const v = env[name];
  return v === undefined || v === null || String(v).trim() === '' ? fallback : String(v).trim();
}

export function bool(name) {
  return str(name, 'false').toLowerCase() === 'true';
}

export function num(name, fallback) {
  const raw = str(name, undefined);
  if (raw === undefined) return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) {
    throw new Error(`${name} must be a non-negative number, got "${raw}"`);
  }
  return n;
}

export function list(name, fallback) {
  const raw = str(name, fallback);
  return raw === undefined ? [] : raw.split(',').map((s) => s.trim()).filter((s) => s !== '');
}

// --- Target guard ------------------------------------------------------------

// Hosts that can only be a developer machine, a container network or a private
// address. Anything else is a public host.
const LOCAL_HOST = /^(localhost|127(\.\d{1,3}){3}|::1|host\.docker\.internal|[a-z0-9-]+|[a-z0-9.-]+\.local|10(\.\d{1,3}){3}|192\.168(\.\d{1,3}){2}|172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2})$/;

// The public gateway origins this suite treats as STAGING. Every other public
// URL counts as production and is refused unless ALLOW_PRODUCTION=true.
// Override with STAGING_URLS (comma list) if staging moves.
export const DEFAULT_STAGING_URLS = 'https://dtx.innbucks.co.zw/foundry';

function parseUrl(raw) {
  const m = /^(https?):\/\/(\[[0-9a-f:]+\]|[^/:?#]+)(?::(\d+))?(\/[^?#]*)?$/i.exec(raw);
  if (!m) {
    throw new Error(`BASE_URL "${raw}" is not an http(s) URL without query or fragment`);
  }
  const host = m[2].toLowerCase().replace(/^\[|\]$/g, '');
  const path = (m[4] || '').replace(/\/+$/, '');
  return { scheme: m[1].toLowerCase(), host, port: m[3] || '', path };
}

function normalised(u) {
  return `${u.scheme}://${u.host}${u.port ? `:${u.port}` : ''}${u.path}`;
}

/**
 * Classifies BASE_URL as 'local', 'staging' or 'production' and refuses a
 * production target unless ALLOW_PRODUCTION=true. "production" is everything
 * that is neither local nor listed in STAGING_URLS — the guard fails closed,
 * so a typo or a new host is treated as production, never as staging.
 */
export function resolveTarget() {
  const raw = str('BASE_URL', undefined);
  if (!raw) {
    throw new Error('BASE_URL is required, e.g. -e BASE_URL=https://dtx.innbucks.co.zw/foundry');
  }
  const url = parseUrl(raw);
  const baseUrl = normalised(url);
  const staging = list('STAGING_URLS', DEFAULT_STAGING_URLS).map((s) => normalised(parseUrl(s)));

  let kind;
  if (LOCAL_HOST.test(url.host)) {
    kind = 'local';
  } else if (staging.includes(baseUrl)) {
    kind = 'staging';
  } else {
    kind = 'production';
  }

  if (kind !== 'local' && url.scheme !== 'https') {
    throw new Error(`Refusing plain http to the public host ${url.host}; use https.`);
  }
  if (kind === 'production' && !bool('ALLOW_PRODUCTION')) {
    throw new Error(
      `Refusing to run: ${baseUrl} is not a listed staging URL (${staging.join(', ')}) ` +
        'so it is treated as PRODUCTION. Point BASE_URL at staging, add the URL to ' +
        'STAGING_URLS if it really is staging, or set ALLOW_PRODUCTION=true on purpose.',
    );
  }
  return { baseUrl, kind };
}

// --- Profiles ----------------------------------------------------------------

export const PROFILES = ['smoke', 'load', 'soak'];

export function resolveProfile() {
  const p = str('PROFILE', 'smoke').toLowerCase();
  if (!PROFILES.includes(p)) {
    throw new Error(`PROFILE must be one of ${PROFILES.join(', ')}, got "${p}"`);
  }
  return p;
}

// Longest a k6 duration string ("90s", "5m", "1h30m") can mean, in seconds.
export function durationSeconds(d) {
  let total = 0;
  const re = /(\d+(?:\.\d+)?)(ms|h|m|s)/g;
  let m;
  let matched = '';
  while ((m = re.exec(d)) !== null) {
    matched += m[0];
    const v = Number(m[1]);
    total += m[2] === 'h' ? v * 3600 : m[2] === 'm' ? v * 60 : m[2] === 's' ? v : v / 1000;
  }
  if (matched !== d) throw new Error(`Bad duration "${d}"`);
  return total;
}

/**
 * The k6 executor for one load scenario under the chosen profile.
 *
 * - smoke: 1 VU looping for SMOKE_DURATION (1m).
 * - load:  ramping arrival rate, 0 -> target over RAMP_DURATION (2m), held for
 *          HOLD_DURATION (5m), then down over 1m.
 * - soak:  constant arrival rate at the soak target for SOAK_DURATION (30m).
 *
 * Rates are ITERATIONS per second (one iteration = one simulated user journey
 * with several requests); they are converted to per-minute so fractional rates
 * such as 0.2/s work.
 *
 * @param {string} exec          exported function name in fleet.js
 * @param {number} loadRate      iterations/s at the top of the load ramp
 * @param {number} soakRate      iterations/s for the soak
 * @param {number} iterSeconds   rough length of one iteration incl. think time
 */
export function executorFor(profile, exec, loadRate, soakRate, iterSeconds) {
  if (profile === 'smoke') {
    return { executor: 'constant-vus', exec, vus: 1, duration: str('SMOKE_DURATION', '1m') };
  }
  const perMinute = (r) => Math.max(1, Math.round(r * 60));
  const vusFor = (r) => Math.max(2, Math.ceil(r * iterSeconds * 1.5));
  if (profile === 'load') {
    const target = perMinute(loadRate);
    return {
      executor: 'ramping-arrival-rate',
      exec,
      startRate: 0,
      timeUnit: '1m',
      preAllocatedVUs: vusFor(loadRate),
      maxVUs: vusFor(loadRate) * 4,
      stages: [
        { target, duration: str('RAMP_DURATION', '2m') },
        { target, duration: str('HOLD_DURATION', '5m') },
        { target: 0, duration: '1m' },
      ],
    };
  }
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate: perMinute(soakRate),
    timeUnit: '1m',
    duration: str('SOAK_DURATION', '30m'),
    preAllocatedVUs: vusFor(soakRate),
    maxVUs: vusFor(soakRate) * 4,
  };
}

/** Upper bound, in seconds, on how long a scenario can run under a profile. */
export function scenarioSeconds(profile) {
  if (profile === 'smoke') return durationSeconds(str('SMOKE_DURATION', '1m'));
  if (profile === 'load') {
    return durationSeconds(str('RAMP_DURATION', '2m')) + durationSeconds(str('HOLD_DURATION', '5m')) + 60;
  }
  return durationSeconds(str('SOAK_DURATION', '30m'));
}

// Latency budgets are written for a load generator close to the cell. A
// generator far away (a GitHub runner in the US, ~250 ms round trip to ZW)
// should raise them with THRESHOLD_SCALE rather than editing the numbers.
const SCALE = num('THRESHOLD_SCALE', 1);

export function p95(ms) {
  return `p(95)<${Math.round(ms * SCALE)}`;
}

export function p99(ms) {
  return `p(99)<${Math.round(ms * SCALE)}`;
}

/**
 * Threshold map for one scenario: a p95 per endpoint, a p99 per endpoint on
 * soak, an error-rate ceiling and a checks floor. Every key is a submetric on
 * the scenario tag k6 sets automatically, so scenarios never share a budget.
 */
export function scenarioThresholds(profile, scenario, budgets, errorRate = 0.01) {
  const t = {};
  for (const [endpoint, ms] of Object.entries(budgets)) {
    const key = `http_req_duration{scenario:${scenario},endpoint:${endpoint}}`;
    t[key] = profile === 'soak' ? [p95(ms), p99(ms * 2.5)] : [p95(ms)];
  }
  t[`http_req_failed{scenario:${scenario}}`] = [{ threshold: `rate<${errorRate}`, abortOnFail: false }];
  t[`checks{scenario:${scenario}}`] = ['rate>0.99'];
  return t;
}
