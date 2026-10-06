// Fleet load tests — one entry point, scenarios chosen with SCENARIOS.
//
//   k6 run -e BASE_URL=https://dtx.innbucks.co.zw/foundry -e PROFILE=smoke \
//          -e SCENARIOS=catalogue,ratelimit load-tests/fleet.js
//
// See load-tests/README.md for every variable, what each scenario touches and
// what it leaves behind.

import { resolveTarget, resolveProfile, executorFor, scenarioSeconds, list } from './lib/config.js';
import * as catalogueScenario from './scenarios/catalogue.js';
import * as checkoutScenario from './scenarios/checkout.js';
import * as tillScenario from './scenarios/till.js';
import * as ratelimitScenario from './scenarios/ratelimit.js';

const ALL = {
  catalogue: catalogueScenario,
  checkout: checkoutScenario,
  till: tillScenario,
  ratelimit: ratelimitScenario,
};

const target = resolveTarget();
const profile = resolveProfile();
const selected = list('SCENARIOS', 'catalogue');
for (const s of selected) {
  if (!ALL[s]) throw new Error(`Unknown scenario "${s}"; choose from ${Object.keys(ALL).join(', ')}`);
}
if (selected.length === 0) throw new Error('SCENARIOS is empty');

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
  const seconds = scenarioSeconds(profile);
  const data = {};
  if (selected.includes('catalogue')) data.catalogue = catalogueScenario.prepare(target.baseUrl);
  if (selected.includes('checkout')) {
    data.checkout = checkoutScenario.prepare(target.baseUrl, profile, seconds, target.kind);
  }
  if (selected.includes('till')) data.till = tillScenario.prepare(target.baseUrl, profile, seconds, target.kind);
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

export default function () {
  // Every scenario names its own exec function; nothing runs here.
}
