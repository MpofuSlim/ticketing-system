package com.innbucks.eventservice.security;

import com.innbucks.eventservice.controller.EventController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event-side authorization contract for PRODUCT_OFFICER and PRODUCT_MANAGER.
 *
 * <p>The remit is deliberately lopsided and the asymmetry is the whole point:
 * a PRODUCT_MANAGER may <b>approve or reject</b> an event but may <b>not</b>
 * create, edit, activate, deactivate or delete one. A PRODUCT_OFFICER may do
 * none of those. Approving is a judgement about an event someone else built;
 * editing is building it.
 *
 * <p>See the sibling test in booking-service for why these assert the
 * annotation reflectively rather than through MockMvc.
 */
class PlatformStaffRoleMatrixTest {

    private static final String OFFICER = "PRODUCT_OFFICER";
    private static final String MANAGER = "PRODUCT_MANAGER";

    private static Authentication authWith(String... roles) {
        return new UsernamePasswordAuthenticationToken("someone@example.com", null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    @Test
    @DisplayName("isPlatformStaff matches booking-service exactly — the two must not drift")
    void isPlatformStaff_membership() {
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("SUPER_ADMIN"))).isTrue();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith(OFFICER))).isTrue();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith(MANAGER))).isTrue();

        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("EVENT_ORGANIZER"))).isFalse();
        assertThat(AuthenticatedCaller.isPlatformStaff(authWith("TEAM_MEMBER"))).isFalse();
        assertThat(AuthenticatedCaller.isPlatformStaff(null)).isFalse();

        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(AuthenticatedCaller.isPlatformStaff(anonymous)).isFalse();
    }

    private static String preAuthorizeFor(String path) {
        for (Method m : EventController.class.getDeclaredMethods()) {
            if (!mapsTo(m, path)) continue;
            PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
            if (pa != null) return pa.value();
            PreAuthorize classLevel = EventController.class.getAnnotation(PreAuthorize.class);
            return classLevel != null ? classLevel.value() : null;
        }
        throw new AssertionError("No handler found for EventController " + path
                + " — the route moved; update this matrix rather than deleting the case.");
    }

    private static boolean mapsTo(Method m, String path) {
        return contains(m.getAnnotation(PutMapping.class) == null ? null : m.getAnnotation(PutMapping.class).value(), path)
                || contains(m.getAnnotation(PostMapping.class) == null ? null : m.getAnnotation(PostMapping.class).value(), path)
                || contains(m.getAnnotation(DeleteMapping.class) == null ? null : m.getAnnotation(DeleteMapping.class).value(), path);
    }

    private static boolean contains(String[] values, String path) {
        if (values == null) return false;
        if (values.length == 0) return path.isEmpty();
        return Arrays.asList(values).contains(path);
    }

    @Test
    @DisplayName("GRANTED: the MANAGER may approve and reject events")
    void managerMayApproveAndReject() {
        for (String path : List.of("/{id}/approve", "/{id}/reject")) {
            assertThat(preAuthorizeFor(path))
                    .as("event %s", path)
                    .contains(MANAGER)
                    .contains("SUPER_ADMIN");
        }
    }

    @Test
    @DisplayName("DENIED: the OFFICER approves nothing — the role is read-only")
    void officerMayNotApproveOrReject() {
        for (String path : List.of("/{id}/approve", "/{id}/reject")) {
            assertThat(preAuthorizeFor(path))
                    .as("event %s must not name the read-only role", path)
                    .doesNotContain(OFFICER);
        }
    }

    @Test
    @DisplayName("DENIED: neither role may edit, activate, deactivate or delete an event")
    void neitherRoleMayMutateEvents() {
        for (String path : List.of("/{id}", "/{id}/activate", "/{id}/deactivate")) {
            assertThat(preAuthorizeFor(path))
                    .as("event write %s — approving is not editing", path)
                    .doesNotContain(OFFICER)
                    .doesNotContain(MANAGER);
        }
    }

    @Test
    @DisplayName("DENIED: neither role may create an event")
    void neitherRoleMayCreateEvents() {
        assertThat(preAuthorizeFor(""))
                .as("POST /events — creation stays with organizers and SUPER_ADMIN")
                .doesNotContain(OFFICER)
                .doesNotContain(MANAGER);
    }
}
