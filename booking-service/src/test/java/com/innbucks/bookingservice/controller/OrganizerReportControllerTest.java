package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.report.RevenueSummaryDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.OrganizerReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the controller's organizer-scoping guard: every report is scoped to the
 * caller's {@code organizerUuid} JWT claim, read from {@link JwtAuthDetails}.
 * A token without the claim must 400 (re-login) rather than silently scope to
 * null and leak/empty another organizer's data.
 */
class OrganizerReportControllerTest {

    private static UsernamePasswordAuthenticationToken authWithOrganizer(UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken("organizer@x.co", null);
        auth.setDetails(new JwtAuthDetails("organizer@x.co", null,
                UUID.randomUUID(), organizerUuid, "Rumbi", "Sibanda"));
        return auth;
    }

    @Test
    void revenue_scopesToCallersOrganizerUuid() {
        OrganizerReportService svc = mock(OrganizerReportService.class);
        UUID org = UUID.randomUUID();
        RevenueSummaryDTO dto = new RevenueSummaryDTO(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null,
                1, 1, new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO,
                0, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("100.00"), "USD");
        when(svc.revenueSummary(eq(org), isNull(), isNull(), isNull())).thenReturn(dto);

        ResponseEntity<ApiResult<RevenueSummaryDTO>> resp =
                new OrganizerReportController(svc).revenue(authWithOrganizer(org), null, null, null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isSameAs(dto);
        // The service was called with the caller's org uuid, not anything else.
        verify(svc).revenueSummary(eq(org), isNull(), isNull(), isNull());
    }

    @Test
    void revenue_rejectsTokenMissingOrganizerClaim() {
        OrganizerReportService svc = mock(OrganizerReportService.class);
        // Legacy token: organizerUuid claim absent.
        assertThatThrownBy(() ->
                new OrganizerReportController(svc).revenue(authWithOrganizer(null), null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing organizer identity");
    }
}
