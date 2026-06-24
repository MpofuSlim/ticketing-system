package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.dto.invoice.PageResponse;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the controller's audience-scoping: SUPER_ADMIN spans all organizers
 * (scope null), an EVENT_ORGANIZER is pinned to their own {@code organizerUuid}
 * claim, and a token missing the claim 400s rather than silently scoping to
 * null. (The {@code @PreAuthorize} role gate itself is enforced declaratively by
 * Spring and is not exercised by these direct-call unit tests.)
 */
class InvoiceControllerTest {

    private InvoiceService service;
    private InvoiceController controller;

    @BeforeEach
    void setUp() {
        service = mock(InvoiceService.class);
        controller = new InvoiceController(service);
        when(service.list(any(), any(), any(), any())).thenReturn(emptyPage());
    }

    @Test
    void list_asSuperAdmin_usesNullScope_soAllOrganizersAreVisible() {
        UUID filter = UUID.randomUUID();
        controller.list(admin(), filter, null, 0, 20);
        // scope == null (admin); the request's organizerUuid is passed through as the filter.
        verify(service).list(isNull(), eq(filter), isNull(), any(Pageable.class));
    }

    @Test
    void list_asOrganizer_pinsScopeToCallersClaim() {
        UUID organizer = UUID.randomUUID();
        controller.list(organizer(organizer), null, null, 0, 20);
        verify(service).list(eq(organizer), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void getOne_asOrganizer_pinsScopeToCallersClaim() {
        UUID organizer = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        controller.getOne(organizer(organizer), invoiceId);
        verify(service).getById(invoiceId, organizer);
    }

    @Test
    void getOne_asSuperAdmin_usesNullScope() {
        UUID invoiceId = UUID.randomUUID();
        controller.getOne(admin(), invoiceId);
        verify(service).getById(invoiceId, null);
    }

    @Test
    void organizerWithoutClaim_isRejected() {
        Authentication legacy = new UsernamePasswordAuthenticationToken("org@x.co", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        // no JwtAuthDetails -> organizerUuid claim absent
        assertThatThrownBy(() -> controller.list(legacy, null, null, 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing organizer identity");
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.co", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    private static Authentication organizer(UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken("org@x.co", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("org@x.co", null, UUID.randomUUID(), organizerUuid, "Ta", "Moyo"));
        return auth;
    }

    private static PageResponse<InvoiceResponse> emptyPage() {
        return new PageResponse<>(List.of(), 0, 20, 0, 0);
    }
}
