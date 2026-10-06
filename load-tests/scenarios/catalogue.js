// Scenario 1 — public catalogue. Anonymous browsing of events and the
// marketplace, exactly what the app does before anyone signs in.
//
// Touches (all GET, all public, no writes, no side effects):
//   event-service        GET /events?page&size       (event-service-route)
//                        GET /events/{id}
//   seat-service         GET /seat-categories?eventId= (seat-service-category-route)
//   marketplace-service  GET /marketplace/catalog?page&size (marketplace-service-route)
//                        GET /marketplace/catalog/{id}
//                        GET /marketplace/catalog/{id}/image?w=480
//
// One iteration = 6 requests. Anonymous calls are rate-limited at the gateway
// PER CLIENT IP AND PER ROUTE (50/s, burst 100), and the marketplace route
// takes 3 of the 6, so ONE load generator caps out near 15 iterations/s.

import http from 'k6/http';
import { check } from 'k6';
import { bool, num, list, scenarioThresholds } from '../lib/config.js';
import { think, pick, params, data } from '../lib/util.js';

export const NAME = 'catalogue';
export const LOAD_RATE = num('CATALOGUE_RATE', 10);
export const SOAK_RATE = num('CATALOGUE_SOAK_RATE', 5);
export const ITER_SECONDS = 15;

const MARKETPLACE = !bool('SKIP_MARKETPLACE');
const PAGE_SIZE = 10;
const IMAGE_WIDTH = 480; // one of 120, 240, 480, 960

export function thresholds(profile) {
  const budgets = {
    events_list: 800,
    event_detail: 800,
    seat_categories: 800,
  };
  if (MARKETPLACE) {
    budgets.mkt_list = 800;
    budgets.mkt_detail = 800;
    budgets.mkt_image = 1500;
  }
  return scenarioThresholds(profile, NAME, budgets);
}

/** Collects ids to browse: from env, else from the first catalogue pages. */
export function prepare(baseUrl) {
  let eventIds = list('CATALOGUE_EVENT_IDS', undefined);
  let eventPages = 1;
  if (eventIds.length === 0) {
    const res = http.get(`${baseUrl}/events?page=0&size=20`, params('GET /events', 'setup', 200));
    const page = data(res);
    if (res.status !== 200 || !page) {
      throw new Error(`catalogue pre-flight: GET /events answered ${res.status}; is BASE_URL right?`);
    }
    eventIds = (page.content || []).map((e) => e.eventId).filter(Boolean);
    // Browse the first three pages at most, as people do.
    eventPages = Math.max(1, Math.min(3, Math.ceil((page.totalElements || 0) / PAGE_SIZE)));
  }

  let listingIds = [];
  let listingPages = 1;
  if (MARKETPLACE) {
    listingIds = list('CATALOGUE_LISTING_IDS', undefined);
    if (listingIds.length === 0) {
      const res = http.get(`${baseUrl}/marketplace/catalog?page=0&size=20`,
        params('GET /marketplace/catalog', 'setup', 200));
      const page = data(res);
      if (res.status !== 200 || !page) {
        throw new Error(
          `catalogue pre-flight: GET /marketplace/catalog answered ${res.status}. ` +
            'Set SKIP_MARKETPLACE=true if marketplace-service is not running on this cell.',
        );
      }
      listingIds = (page.items || []).map((l) => l.id).filter(Boolean);
      listingPages = Math.max(1, Math.min(3, page.totalPages || 1));
    }
  }

  if (eventIds.length === 0) console.warn('catalogue: no events on this cell; only the list page is exercised');
  if (MARKETPLACE && listingIds.length === 0) {
    console.warn('catalogue: no marketplace listings; only the catalogue page is exercised');
  }
  return { eventIds, eventPages, listingIds, listingPages };
}

export function run(baseUrl, d) {
  // Land on the events tab and page through a little.
  const page = Math.floor(Math.random() * d.eventPages);
  const list = http.get(`${baseUrl}/events?page=${page}&size=${PAGE_SIZE}`,
    params('GET /events', 'events_list', 200));
  check(list, { 'events list 200': (r) => r.status === 200 });
  think(1, 3);

  if (d.eventIds.length > 0) {
    const id = pick(d.eventIds);
    const ev = http.get(`${baseUrl}/events/${id}`, params('GET /events/{id}', 'event_detail', 200));
    check(ev, { 'event detail 200': (r) => r.status === 200 });
    const cats = http.get(`${baseUrl}/seat-categories?eventId=${id}`,
      params('GET /seat-categories', 'seat_categories', 200));
    check(cats, { 'seat categories 200': (r) => r.status === 200 && Array.isArray(data(r)) });
    think(2, 5);
  }

  if (!MARKETPLACE) return;

  const mpage = Math.floor(Math.random() * d.listingPages);
  const mlist = http.get(`${baseUrl}/marketplace/catalog?page=${mpage}&size=20`,
    params('GET /marketplace/catalog', 'mkt_list', 200));
  check(mlist, { 'catalog list 200': (r) => r.status === 200 });
  think(1, 3);

  if (d.listingIds.length === 0) return;
  const lid = pick(d.listingIds);
  const detail = http.get(`${baseUrl}/marketplace/catalog/${lid}`,
    params('GET /marketplace/catalog/{id}', 'mkt_detail', 200));
  check(detail, { 'listing detail 200': (r) => r.status === 200 });
  const listing = data(detail);
  // The listing's own imageUrl is root-relative to the gateway, so it takes
  // the BASE_URL prefix (e.g. /foundry) like every other path here.
  if (listing && listing.imageUrl) {
    const img = http.get(`${baseUrl}${listing.imageUrl}?w=${IMAGE_WIDTH}`,
      params('GET /marketplace/catalog/{id}/image', 'mkt_image', 200,
        { responseType: 'none', headers: { Accept: 'image/*' } }));
    check(img, {
      'listing image 200 image/*': (r) => r.status === 200 && /^image\//.test(r.headers['Content-Type'] || ''),
    });
  }
  think(2, 5);
}
