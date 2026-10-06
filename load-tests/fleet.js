// Fleet load tests — one entry point, scenarios chosen with SCENARIOS.
//
//   k6 run -e BASE_URL=<staging base url> -e STAGING_URLS=<same url> -e PROFILE=smoke \
//          -e SCENARIOS=catalogue,ratelimit load-tests/fleet.js
//
// SCENARIOS=all runs every scenario whose inputs are configured and prints the
// ones it skipped (checkout without CHECKOUT_EVENT_ID, till without TILL_TOKEN,
// lending without its credentials or off staging). A scenario NAMED in
// SCENARIOS is never skipped: it refuses with the variable it is missing.
//
// See load-tests/README.md for every variable, what each scenario touches and
// what it leaves behind.

import { resolveTarget, resolveProfile, executorFor, scenarioSeconds, list, str } from './lib/config.js';
import * as catalogueScenario from './scenarios/catalogue.js';
import * as checkoutScenario from './scenarios/checkout.js';
import * as tillScenario from './scenarios/till.js';
import * as ratelimitScenario from './scenarios/ratelimit.js';
import * as lendingScenario from './scenarios/lending.js';

const ALL = {
  catalogue: catalogueScenario,
  checkout: checkoutScenario,
  till: tillScenario,
  ratelimit: ratelimitScenario,
  lending: lendingScenario,
};

const target = resolveTarget();
const profile = resolveProfile();

// Why SCENARIOS=all leaves a scenario out, or null to include it. Only the
// inputs a scenario cannot start without; everything else is still checked by
// its own pre-flight.
const SKIP_REASON = {
  catalogue: () => null,
  ratelimit: () => null,
  checkout: () => (str('CHECKOUT_EVENT_ID', undefined) ? null : 'CHECKOUT_EVENT_ID is not set'),
  till: () => (str('TILL_TOKEN', undefined) ? null : 'TILL_TOKEN is not set'),
  lending: () => lendingScenario.refusal(target.kind),
};

const requested = list('SCENARIOS', 'catalogue');
const skipped = [];
let selected = requested;
if (requested.includes('all')) {
  if (requested.length > 1) throw new Error('SCENARIOS=all cannot be combined with named scenarios');
  selected = [];
  for (const s of Object.keys(ALL)) {
    const why = SKIP_REASON[s]();
    if (why) skipped.push(`${s}: ${why}`);
    else selected.push(s);
  }
}
for (const s of selected) {
  if (!ALL[s]) throw new Error(`Unknown scenario "${s}"; choose from ${Object.keys(ALL).join(', ')}, or all`);
}
if (selected.length === 0) throw new Error('SCENARIOS is empty');
// Lending is staging-only and needs its own credentials: when it is named,
// refuse here, in init, before any scenario has sent a request.
if (selected.includes('lending')) {
  const why = lendingScenario.refusal(target.kind);
  if (why) throw new Error(why);
}

const scenarios = {};
let thresholds = {};
for (const s of selected) {
  const mod = ALL[s];
  scenarios[s] = s === 'ratelimit'
    ? mod.scenario()
    : executorFor(profile, s, mod.LOAD_RATE, mod.SOAK_RATE, mod.ITER_SECONDS);
  thresholds = Object.assign(thresholds, mod.thresholds(profile));
}

export const options = {
  scenarios,
  thresholds,
  // A run never follows a redirect off the target host silently.
  maxRedirects: 0,
  userAgent: 'innbucks-fleet-load-test/1.0 (k6)',
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // The tags thresholds key on; drop the high-cardinality URL tag.
  systemTags: ['status', 'method', 'name', 'scenario', 'check', 'error_code', 'expected_response'],
};

export function setup() {
  console.log(`target ${target.baseUrl} (${target.kind}), profile ${profile}, scenarios ${selected.join(', ')}`);
  for (const s of skipped) console.log(`skipped ${s}`);
  const seconds = scenarioSeconds(profile);
  const data = {};
  if (selected.includes('catalogue')) data.catalogue = catalogueScenario.prepare(target.baseUrl);
  if (selected.includes('checkout')) {
    data.checkout = checkoutScenario.prepare(target.baseUrl, profile, seconds, target.kind);
  }
  if (selected.includes('till')) data.till = tillScenario.prepare(target.baseUrl, profile, seconds, target.kind);
  if (selected.includes('lending')) {
    data.lending = lendingScenario.prepare(target.baseUrl, profile, seconds, target.kind);
  }
  return data;
}

// k6 calls the exported function named by each scenario's `exec`.
export function catalogue(data) {
  catalogueScenario.run(target.baseUrl, data.catalogue);
}

export function checkout(data) {
  checkoutScenario.run(target.baseUrl, data.checkout);
}

export function till(data) {
  tillScenario.run(target.baseUrl, data.till);
}

export function ratelimit() {
  ratelimitScenario.run(target.baseUrl);
}

export function lending(data) {
  lendingScenario.run(target.baseUrl, data.lending);
}

export default function () {
  // Every scenario names its own exec function; nothing runs here.
}
