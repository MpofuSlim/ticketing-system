package com.innbucks.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the gateway route table (application.yaml) down as an executable spec.
 *
 * That table is not just plumbing — it encodes security-critical, order-
 * sensitive invariants whose regression is a SILENT hole:
 *   - the edge-deny routes must be matched BEFORE their catch-all (reorder
 *     them and /events/{id}/availability/** and /loyalty/internal/** become
 *     publicly reachable again);
 *   - /payments/** is split by HTTP method into differently-sized rate-limit
 *     buckets (the write verbs get the tight money-path limit);
 *   - the /auth/** catch-all deliberately carries NO rate limiter so logins
 *     survive a Redis outage, while the abuse-prone /auth sub-routes (customer
 *     lookup/registration) and every other authenticated route keep one — and
 *     those sub-routes must be matched BEFORE the catch-all;
 *   - the api-docs proxies are scoped to /v3/api-docs ONLY — a historical /**
 *     here let callers tunnel past edge-deny and the limiter.
 * A missing/renamed route is the "added an endpoint, forgot the gateway route
 * = 404 for clients" failure CLAUDE.md warns about.
 *
 * Pure introspection of the parsed RouteDefinitions — no request is issued,
 * so this needs neither Redis nor Docker and runs in every environment.
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayRouteTableTest {

    private static final List<String> EXPECTED_ROUTE_IDS = List.of(
            "auth-customer-lookup-route", "auth-customer-route", "auth-register-route",
            "user-auth-route", "cells-lookup-route", "user-admin-route", "user-event-organizer-route",
            "user-internal-deny", "user-self-route",
            "event-availability-deny", "event-service-route",
            "seat-service-seat-route", "seat-service-category-route",
            "booking-internal-deny", "booking-service-route", "booking-tickets-route", "brand-assets-route",
            "payment-internal-deny", "payment-service-read-route",
            "payments-innbucks-write-route", "payment-service-write-route",
            "loyalty-internal-deny", "loyalty-service-route",
            "user-service-proxy-route", "event-service-proxy-route",
            "seat-service-proxy-route", "booking-service-proxy-route",
            "payment-service-proxy-route", "loyalty-service-proxy-route");

    // Single-prefix routes whose loss or typo = a silent 404 for real clients.
    // Map.of caps at 10 entries — switched to Map.ofEntries when we added
    // user-event-organizer-route and booking-tickets-route.
    private static final Map<String, String> SERVICE_PREFIXES = Map.ofEntries(
            Map.entry("user-auth-route", "/auth/**"),
            Map.entry("user-admin-route", "/admin/**"),
            Map.entry("user-event-organizer-route", "/event-organizer/**"),
            Map.entry("user-self-route", "/users/**"),
            Map.entry("event-service-route", "/events/**"),
            Map.entry("seat-service-seat-route", "/seats/**"),
            Map.entry("seat-service-category-route", "/seat-categories/**"),
            Map.entry("booking-service-route", "/bookings/**"),
            Map.entry("booking-tickets-route", "/tickets/**"),
            Map.entry("loyalty-service-route", "/loyalty/**"));

    private static final List<String> RATE_LIMITED_ROUTES = List.of(
            "auth-customer-lookup-route", "auth-customer-route", "auth-register-route",
            "user-admin-route", "user-event-organizer-route", "user-self-route", "event-service-route",
            "seat-service-seat-route", "seat-service-category-route",
            "booking-service-route", "booking-tickets-route", "brand-assets-route",
            "payment-service-read-route", "payments-innbucks-write-route", "payment-service-write-route",
            "loyalty-service-route");

    private static final List<String> API_DOCS_PROXY_ROUTES = List.of(
            "user-service-proxy-route", "event-service-proxy-route",
            "seat-service-proxy-route", "booking-service-proxy-route",
            "payment-service-proxy-route", "loyalty-service-proxy-route");

    @Autowired
    RouteDefinitionLocator locator;

    private List<RouteDefinition> routes() {
        return Objects.requireNonNull(locator.getRouteDefinitions().collectList().block());
    }

    private RouteDefinition route(String id) {
        return routes().stream().filter(r -> id.equals(r.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("route not defined: " + id));
    }

    private List<String> orderedIds() {
        return routes().stream().map(RouteDefinition::getId).toList();
    }

    private List<String> predicateArgs(String id, String predicateName) {
        return route(id).getPredicates().stream()
                .filter(p -> predicateName.equals(p.getName()))
                .flatMap(p -> p.getArgs().values().stream())
                .toList();
    }

    private List<String> filterNames(String id) {
        return route(id).getFilters().stream().map(FilterDefinition::getName).toList();
    }

    @Test
    void exactlyTheExpectedRoutesAreDefined() {
        // containsExactlyInAnyOrder fails on BOTH a missing route (silent 404)
        // and an unexpected new one (forces a conscious update here).
        assertThat(orderedIds()).containsExactlyInAnyOrderElementsOf(EXPECTED_ROUTE_IDS);
    }

    @Test
    void serviceRoutesTargetTheirExpectedPrefix() {
        SERVICE_PREFIXES.forEach((id, prefix) ->
                assertThat(predicateArgs(id, "Path"))
                        .as("Path predicate for %s", id)
                        .containsExactly(prefix));
        // Both payment routes share the prefix; the Method predicate splits them.
        assertThat(predicateArgs("payment-service-read-route", "Path")).containsExactly("/payments/**");
        assertThat(predicateArgs("payment-service-write-route", "Path")).containsExactly("/payments/**");
    }

    @Test
    void edgeDenyRoutesPrecedeTheirCatchAll() {
        List<String> order = orderedIds();
        assertThat(order.indexOf("event-availability-deny"))
                .as("event-availability-deny must match before /events/**")
                .isBetween(0, order.indexOf("event-service-route") - 1);
        assertThat(order.indexOf("loyalty-internal-deny"))
                .as("loyalty-internal-deny must match before /loyalty/**")
                .isBetween(0, order.indexOf("loyalty-service-route") - 1);
        assertThat(order.indexOf("user-internal-deny"))
                .as("user-internal-deny must match before /users/**")
                .isBetween(0, order.indexOf("user-self-route") - 1);
        assertThat(order.indexOf("booking-internal-deny"))
                .as("booking-internal-deny must match before /bookings/**")
                .isBetween(0, order.indexOf("booking-service-route") - 1);
        assertThat(order.indexOf("payment-internal-deny"))
                .as("payment-internal-deny must match before all /payments/** routes")
                .isBetween(0, Math.min(order.indexOf("payment-service-read-route"),
                        Math.min(order.indexOf("payments-innbucks-write-route"),
                                order.indexOf("payment-service-write-route"))) - 1);
        // The dedicated /payments/innbucks bucket only kicks in if SCG matches
        // it BEFORE the broader payment-service-write-route catch-all (both
        // accept POST on a /payments path). A reordering that flips them
        // silently merges /payments/innbucks back into the bearer-keyed bucket
        // and reopens the harvest/abuse window we just closed.
        assertThat(order.indexOf("payments-innbucks-write-route"))
                .as("payments-innbucks-write-route must match before the /payments/** write catch-all")
                .isBetween(0, order.indexOf("payment-service-write-route") - 1);
    }

    @Test
    void edgeDenyRoutesForwardToTheDenyHandler() {
        assertThat(route("event-availability-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(route("loyalty-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(route("user-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(route("payment-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(predicateArgs("event-availability-deny", "Path")).containsExactly("/events/*/availability/**");
        assertThat(predicateArgs("loyalty-internal-deny", "Path")).containsExactly("/loyalty/internal/**");
        assertThat(predicateArgs("user-internal-deny", "Path")).containsExactly("/users/internal/**");
        assertThat(route("booking-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(predicateArgs("booking-internal-deny", "Path")).containsExactly("/bookings/internal/**");
    }

    @Test
    void brandAssetsRouteTargetsBookingServiceAtBrandPrefix() {
        // /brand/** is a public static-asset path served by booking-service from
        // classpath:/static/brand/. Pinned so a typo in the predicate (or the
        // route silently being deleted) surfaces as a route-table test failure
        // instead of a runtime 404 the FE only notices in WhatsApp / email.
        assertThat(route("brand-assets-route").getUri()).hasToString("lb://booking-service");
        assertThat(predicateArgs("brand-assets-route", "Path")).containsExactly("/brand/**");
    }

    @Test
    void authSkipsRateLimiterButAuthenticatedRoutesEnforceIt() {
        assertThat(filterNames("user-auth-route"))
                .as("/auth must stay usable when Redis is down")
                .doesNotContain("RequestRateLimiter");
        // /cells/lookup is a pre-login surface (no token exists yet) — same
        // no-limiter treatment as /auth/**, and it targets /cells/**.
        assertThat(filterNames("cells-lookup-route"))
                .as("/cells lookup is pre-login and must skip the token-keyed limiter")
                .doesNotContain("RequestRateLimiter");
        assertThat(predicateArgs("cells-lookup-route", "Path")).containsExactly("/cells/**");
        RATE_LIMITED_ROUTES.forEach(id ->
                assertThat(filterNames(id)).as("rate limiter on %s", id).contains("RequestRateLimiter"));
    }

    @Test
    void authSensitiveSubRoutesPrecedeTheAuthCatchAll() {
        List<String> order = orderedIds();
        int catchAll = order.indexOf("user-auth-route");
        List.of("auth-customer-lookup-route", "auth-customer-route", "auth-register-route")
                .forEach(id -> assertThat(order.indexOf(id))
                        .as("%s must match before the /auth/** catch-all", id)
                        .isBetween(0, catchAll - 1));
        // /auth/customer/send-money/** is a subset of /auth/customer/** — it
        // must be listed first or the broader route would swallow it.
        assertThat(order.indexOf("auth-customer-lookup-route"))
                .as("send-money lookup must precede the broader /auth/customer/**")
                .isLessThan(order.indexOf("auth-customer-route"));
    }

    @Test
    void authSensitiveSubRoutesTargetTheirPaths() {
        assertThat(predicateArgs("auth-customer-lookup-route", "Path"))
                .containsExactly("/auth/customer/send-money/**");
        assertThat(predicateArgs("auth-customer-route", "Path"))
                .containsExactly("/auth/customer/**");
        assertThat(predicateArgs("auth-register-route", "Path"))
                .containsExactly("/auth/register");
    }

    @Test
    void paymentRoutesAreSplitByHttpMethod() {
        assertThat(predicateArgs("payment-service-read-route", "Method")).containsExactly("GET");
        assertThat(predicateArgs("payment-service-write-route", "Method"))
                .containsExactlyInAnyOrder("POST", "PUT", "PATCH", "DELETE");
    }

    @Test
    void paymentsInnbucksWriteRouteIsIpKeyedAtPaymentsInnbucksOnly() {
        // The route exists to give the public POST /payments/innbucks call
        // (no bearer) its own IP-keyed bucket — separate from the broader
        // /payments/** bearer-keyed write route. Pin the path, the method
        // AND the resolver bean so a refactor that silently widens the path
        // or swaps the resolver fails CI instead of reopening the abuse window.
        assertThat(predicateArgs("payments-innbucks-write-route", "Path"))
                .containsExactly("/payments/innbucks");
        assertThat(predicateArgs("payments-innbucks-write-route", "Method"))
                .containsExactly("POST");
        boolean usesIpResolver = route("payments-innbucks-write-route").getFilters().stream()
                .filter(f -> "RequestRateLimiter".equals(f.getName()))
                .flatMap(f -> f.getArgs().values().stream())
                .anyMatch(v -> v != null && v.contains("paymentsInnbucksIpKeyResolver"));
        assertThat(usesIpResolver)
                .as("the IP-only key resolver bean is what closes the bearer-fallback ambiguity")
                .isTrue();
    }

    @Test
    void apiDocsProxiesAreScopedToApiDocsOnly() {
        // Guards the documented regression: a /** predicate here would let
        // /user-service/loyalty/internal/** etc. bypass edge-deny + limiter.
        API_DOCS_PROXY_ROUTES.forEach(id -> {
            assertThat(predicateArgs(id, "Path"))
                    .as("api-docs proxy %s must expose only /v3/api-docs paths", id)
                    .isNotEmpty()
                    .allSatisfy(p -> assertThat(p).contains("/v3/api-docs"));
            assertThat(filterNames(id)).as("StripPrefix on %s", id).contains("StripPrefix");
        });
    }
}
