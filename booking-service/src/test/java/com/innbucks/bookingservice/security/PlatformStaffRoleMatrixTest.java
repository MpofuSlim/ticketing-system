package com.innbucks.bookingservice.security;

import com.innbucks.bookingservice.controller.BookingController;
import com.innbucks.bookingservice.controller.InvoiceController;
import com.innbucks.bookingservice.controller.OrganizerReportController;
import com.innbucks.bookingservice.controller.ScanReportController;
import com.innbucks.bookingservice.controller.TicketResendController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization contract for the two internal reporting roles,
 * PRODUCT_OFFICER and PRODUCT_MANAGER, pinned in ONE place.
 *
 * <p><b>Why reflection rather than MockMvc.</b> {@code @PreAuthorize} is
 * enforced by a Spring proxy, so calling a controller method directly in a
 * unit test does not evaluate it — a plain unit test would pass no matter
 * what the annotation said. A {@code @SpringBootTest} + MockMvc suite WOULD
 * evaluate it but needs a Docker daemon for Testcontainers (see CLAUDE.md),
 * so it cannot run everywhere. Asserting the annotation itself is the
 * honest middle: it cannot prove Spring enforces the expression, but it DOES
 * fail the build the moment someone widens or drops a role, which is the
 * regression that actually matters here.
 *
 * <p><b>Read the denials first.</b> A missing {@code @PreAuthorize} fails
 * OPEN, so the negative cases below are load-bearing: a positive-only suite
 * would still be green with the whole gate deleted.
 */
class PlatformStaffRoleMatrixTest {

    private static final String OFFICER = "PRODUCT_OFFICER";
    private static final String MANAGER = "PRODUCT_MANAGER";

    // ---------- isPlatformStaff: the single definition of "sees everything" ----------

    private static Authentication authWith(String... roles) {
        // The 3-arg constructor already marks the token authenticated; calling
        // setAuthenticated(true) on it throws.
        return new UsernamePasswordAuthenticationToken("someone@example.com", null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    @Test
    @DisplayName("isPlatformStaff: exactly SUPER_ADMIN, PRODUCT_OFFICER and PRODUCT_MANAGER")
    void isPlatformStaff_membership() {
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("SUPER_ADMIN"))).isTrue();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith(OFFICER))).isTrue();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith(MANAGER))).isTrue();

        // An organizer is scoped to their OWN events — widening this would let
        // one organizer read every other organizer's bookings and revenue.
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("EVENT_ORGANIZER"))).isFalse();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("TEAM_MEMBER"))).isFalse();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("CUSTOMER"))).isFalse();
    }

    @Test
    @DisplayName("isPlatformStaff: null and unauthenticated callers are never platform staff")
    void isPlatformStaff_deniesUnauthenticated() {
        assertThat(AuthenticatedCaller.isPlatformStaff(null)).isFalse();

        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(AuthenticatedCaller.isPlatformStaff(anonymous)).isFalse();

        var notAuthenticated = new UsernamePasswordAuthenticationToken(
                "someone@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        notAuthenticated.setAuthenticated(false);
        assertThat(AuthenticatedCaller.isPlatformStaff(notAuthenticated)).isFalse();
    }

    // ---------- the endpoint matrix ----------

    /**
     * The effective {@code @PreAuthorize} for the handler serving {@code path}
     * — method-level if present, else the controller's class-level annotation.
     * Keyed by ROUTE rather than method name so a rename does not silently
     * skip an assertion.
     */
    private static String preAuthorizeFor(Class<?> controller, String path) {
        for (Method m : controller.getDeclaredMethods()) {
            if (!mapsTo(m, path)) continue;
            PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
            if (pa != null) return pa.value();
            PreAuthorize classLevel = controller.getAnnotation(PreAuthorize.class);
            if (classLevel != null) return classLevel.value();
            return null; // mapped but ungated — fails open
        }
        throw new AssertionError("No handler found for " + controller.getSimpleName() + " " + path
                + " — the route moved; update this matrix rather than deleting the case.");
    }

    private static boolean mapsTo(Method m, String path) {
        return contains(m.getAnnotation(GetMapping.class) == null ? null : m.getAnnotation(GetMapping.class).value(), path)
                || contains(m.getAnnotation(PostMapping.class) == null ? null : m.getAnnotation(PostMapping.class).value(), path)
                || contains(m.getAnnotation(PutMapping.class) == null ? null : m.getAnnotation(PutMapping.class).value(), path)
                || contains(m.getAnnotation(PatchMapping.class) == null ? null : m.getAnnotation(PatchMapping.class).value(), path)
                || contains(m.getAnnotation(DeleteMapping.class) == null ? null : m.getAnnotation(DeleteMapping.class).value(), path);
    }

    /**
     * A bare {@code @GetMapping} carries an EMPTY value array rather than
     * {@code {""}}, so the controller-root route is matched by the empty
     * string against a present-but-empty mapping.
     */
    private static boolean contains(String[] values, String path) {
        if (values == null) return false;
        if (values.length == 0) return path.isEmpty();
        return Arrays.asList(values).contains(path);
    }

    @Test
    @DisplayName("REPORTS: both reporting roles can read bookings, invoices, scan and organizer reports")
    void reportEndpoints_grantBothRoles() {
        List<String> bookingReports = List.of("/by-category/{categoryId}", "/by-event/{eventId}");
        for (String path : bookingReports) {
            assertThat(preAuthorizeFor(BookingController.class, path))
                    .as("bookings report %s", path)
                    .contains(OFFICER).contains(MANAGER);
        }

        for (String path : List.of("", "/{id}")) {
            assertThat(preAuthorizeFor(InvoiceController.class, path))
                    .as("invoices %s", path)
                    .contains(OFFICER).contains(MANAGER);
        }

        for (String path : List.of("/events/{eventId}", "/events/{eventId}/stats", "/team-stats")) {
            assertThat(preAuthorizeFor(ScanReportController.class, path))
                    .as("scan report %s", path)
                    .contains(OFFICER).contains(MANAGER);
        }

        assertThat(OrganizerReportController.class.getAnnotation(PreAuthorize.class).value())
                .as("organizer reports (class-level)")
                .contains(OFFICER).contains(MANAGER);
    }

    @Test
    @DisplayName("DENIED: the OFFICER is read-only — no ticket resend, which is a write")
    void officerCannotResendTickets() {
        String resend = preAuthorizeFor(TicketResendController.class, "/{id}/resend-ticket");
        assertThat(resend).as("resend is a WRITE: manager yes, officer never")
                .contains(MANAGER)
                .doesNotContain(OFFICER);
    }

    @Test
    @DisplayName("DENIED: neither role may cancel or reverse a booking — those stay SUPER_ADMIN")
    void neitherRoleMayMutateBookings() {
        for (String path : List.of("/{id}/reverse")) {
            String expr = preAuthorizeFor(BookingController.class, path);
            assertThat(expr).as("booking write %s", path)
                    .doesNotContain(OFFICER)
                    .doesNotContain(MANAGER)
                    .contains("SUPER_ADMIN");
        }
    }

    @Test
    @DisplayName("DENIED: neither role may generate, mark-paid or cancel an invoice")
    void neitherRoleMayMutateInvoices() {
        for (String path : List.of("/generate", "/{id}/mark-paid", "/{id}/cancel")) {
            String expr = preAuthorizeFor(InvoiceController.class, path);
            assertThat(expr).as("invoice write %s", path)
                    .doesNotContain(OFFICER)
                    .doesNotContain(MANAGER)
                    .contains("SUPER_ADMIN");
        }
    }

    @Test
    @DisplayName("every endpoint in this matrix is actually gated — an ungated route fails open")
    void everyMatrixedEndpointIsGated() {
        record Route(Class<?> controller, String path) {}
        List<Route> all = List.of(
                new Route(BookingController.class, "/by-category/{categoryId}"),
                new Route(BookingController.class, "/by-event/{eventId}"),
                new Route(BookingController.class, "/{id}/reverse"),
                new Route(InvoiceController.class, ""),
                new Route(InvoiceController.class, "/{id}"),
                new Route(InvoiceController.class, "/generate"),
                new Route(ScanReportController.class, "/events/{eventId}"),
                new Route(TicketResendController.class, "/{id}/resend-ticket"));
        for (Route r : all) {
            assertThat(preAuthorizeFor(r.controller(), r.path()))
                    .as("%s %s must carry a @PreAuthorize", r.controller().getSimpleName(), r.path())
                    .isNotNull().isNotBlank();
        }
    }
}
