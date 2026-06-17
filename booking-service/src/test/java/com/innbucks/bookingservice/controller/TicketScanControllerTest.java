package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanTicketRequestDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.TicketScanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link TicketScanController} resolves the scanner's display name —
 * the value that lands in {@code booking_items.redeemed_by_name} and surfaces
 * on the rejection toast ("already scanned by Tariro Chikomo at 19:42") that
 * the FE shows to a colleague on a second scan.
 *
 * <p>Prefer the JWT firstName/lastName claims (emitted on TEAM_MEMBER /
 * EVENT_ORGANIZER tokens by user-service); fall back to the JWT subject
 * (email) when the claims are absent, so legacy tokens and any future
 * claim-less staff role still produce a non-null audit row.
 */
class TicketScanControllerTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static TicketScanController controller(TicketScanService service) {
        return new TicketScanController(service);
    }

    private static UsernamePasswordAuthenticationToken authWith(
            String email, String firstName, String lastName) {
        var auth = new UsernamePasswordAuthenticationToken(email, null);
        auth.setDetails(new JwtAuthDetails(email, null, UUID.randomUUID(), UUID.randomUUID(),
                firstName, lastName));
        return auth;
    }

    private static ScanTicketResponseDTO okResponse(String ticketNumber) {
        return ScanTicketResponseDTO.builder()
                .status(ScanTicketResponseDTO.Status.ALLOWED)
                .ticketNumber(ticketNumber)
                .bookingItemId(UUID.randomUUID())
                .redeemedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void scan_usesFirstAndLastNameFromJwtClaims_notTheEmail() {
        TicketScanService service = mock(TicketScanService.class);
        when(service.scan(eq("TKT-1"), any())).thenReturn(okResponse("TKT-1"));

        ScanTicketRequestDTO req = new ScanTicketRequestDTO();
        req.setTicketNumber("TKT-1");

        ResponseEntity<ApiResult<ScanTicketResponseDTO>> resp = controller(service).scan(req,
                authWith("tariro@harare-arena.co.zw", "Tariro", "Chikomo"));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(service).scan(eq("TKT-1"), name.capture());
        // The rejection-toast contract: human name, not the staff login email.
        assertThat(name.getValue()).isEqualTo("Tariro Chikomo");
    }

    @Test
    void scan_fallsBackToEmail_whenJwtCarriesNoNameClaims() {
        // Legacy tokens minted before this PR (and any future staff role that
        // intentionally omits display-name claims) must still produce a non-
        // null audit row — the fallback is the JWT subject (email).
        TicketScanService service = mock(TicketScanService.class);
        when(service.scan(eq("TKT-2"), any())).thenReturn(okResponse("TKT-2"));

        ScanTicketRequestDTO req = new ScanTicketRequestDTO();
        req.setTicketNumber("TKT-2");

        controller(service).scan(req, authWith("legacy@x.co", null, null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(service).scan(eq("TKT-2"), name.capture());
        assertThat(name.getValue()).isEqualTo("legacy@x.co");
    }

    @Test
    void scan_usesAvailableNameField_whenOnlyOneIsPresent() {
        // Some users only have a first name on file; don't blank out the
        // audit just because lastName is null.
        TicketScanService service = mock(TicketScanService.class);
        when(service.scan(eq("TKT-3"), any())).thenReturn(okResponse("TKT-3"));

        ScanTicketRequestDTO req = new ScanTicketRequestDTO();
        req.setTicketNumber("TKT-3");

        controller(service).scan(req, authWith("rumbi@x.co", "Rumbi", null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(service).scan(eq("TKT-3"), name.capture());
        assertThat(name.getValue()).isEqualTo("Rumbi");
    }
}
