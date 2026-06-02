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
            "user-auth-route", "user-admin-route", "user-internal-deny", "user-self-route",
            "event-availability-deny", "event-service-route",
            "seat-service-seat-route", "seat-service-category-route",
            "booking-service-route",
            "payment-service-read-route", "payment-service-write-route",
            "loyalty-internal-deny", "loyalty-service-route",
            "user-service-proxy-route", "event-service-proxy-route",
            "seat-service-proxy-route", "booking-service-proxy-route",
            "payment-service-proxy-route", "loyalty-service-proxy-route");

    // Single-prefix routes whose loss or typo = a silent 404 for real clients.
    private static final Map<String, String> SERVICE_PREFIXES = Map.of(
            "user-auth-route", "/auth/**",
            "user-admin-route", "/admin/**",
            "user-self-route", "/users/**",
            "event-service-route", "/events/**",
            "seat-service-seat-route", "/seats/**",
            "seat-service-category-route", "/seat-categories/**",
            "booking-service-route", "/bookings/**",
            "loyalty-service-route", "/loyalty/**");

    private static final List<String> RATE_LIMITED_ROUTES = List.of(
            "auth-customer-lookup-route", "auth-customer-route", "auth-register-route",
            "user-admin-route", "user-self-route", "event-service-route",
            "seat-service-seat-route", "seat-service-category-route",
            "booking-service-route", "payment-service-read-route",
            "payment-service-write-route", "loyalty-service-route");

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
    }

    @Test
    void edgeDenyRoutesForwardToTheDenyHandler() {
        assertThat(route("event-availability-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(route("loyalty-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(route("user-internal-deny").getUri()).hasToString("forward:/__edge_deny__");
        assertThat(predicateArgs("event-availability-deny", "Path")).containsExactly("/events/*/availability/**");
        assertThat(predicateArgs("loyalty-internal-deny", "Path")).containsExactly("/loyalty/internal/**");
        assertThat(predicateArgs("user-internal-deny", "Path")).containsExactly("/users/internal/**");
    }

    @Test
    void authSkipsRateLimiterButAuthenticatedRoutesEnforceIt() {
        assertThat(filterNames("user-auth-route"))
                .as("/auth must stay usable when Redis is down")
                .doesNotContain("RequestRateLimiter");
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
