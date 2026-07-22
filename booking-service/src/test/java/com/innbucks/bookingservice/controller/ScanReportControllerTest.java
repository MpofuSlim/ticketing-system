package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.scan.EventScanStatsDTO;
import com.innbucks.bookingservice.dto.scan.PageResponse;
import com.innbucks.bookingservice.dto.scan.ScanAttemptDTO;
import com.innbucks.bookingservice.dto.scan.ScannerStatsDTO;
import com.innbucks.bookingservice.dto.scan.TeamMemberStatsDTO;
import com.innbucks.bookingservice.dto.scan.TeamStatsResponseDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.ScanReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the wiring in {@link ScanReportController}: who can call what, how the
 * caller's identity is read from the JWT, how per-event endpoints verify
 * ownership against event-service, and that the team-stats leaderboard is
 * surfaced in the order the service produced (DESC by total).
 *
 * <p>Pure controller-instance tests — no {@code @SpringBootTest}. The
 * Spring-Security 401/403 paths are covered indirectly via the {@code
 * @PreAuthorize} annotations + the per-event {@code requireEventOwnership}
 * helper; for the full end-to-end role-rejection round trip the existing
 * security-config tests (BookingControllerEmptyTest etc.) are the model.
 */
class ScanReportControllerTest {

    private static UsernamePasswordAuthenticationToken organizerAuth(UUID organizerUuid, UUID userUuid) {
        var auth = new UsernamePasswordAuthenticationToken(
                "organizer@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("organizer@example.com", null,
                userUuid, organizerUuid, "Rumbi", "Sibanda"));
        return auth;
    }

    private static UsernamePasswordAuthenticationToken teamMemberAuth(UUID organizerUuid, UUID userUuid) {
        var auth = new UsernamePasswordAuthenticationToken(
                "tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null,
                userUuid, organizerUuid, "Tariro", "Chikomo"));
        return auth;
    }

    private static UsernamePasswordAuthenticationToken superAdminAuth() {
        // No organizerUuid in the details — an admin token carries no
        // organizer claim, which is exactly what the endpoints must tolerate.
        var auth = new UsernamePasswordAuthenticationToken(
                "admin@innbucks.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new JwtAuthDetails("admin@innbucks.co.zw", null,
                UUID.randomUUID(), null, "Super", "Admin"));
        return auth;
    }

    private static ScanReportController controller(ScanReportService svc, EventServiceClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new ScanReportController(svc, provider);
    }

    private static Map<String, Long> emptyOutcomes() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("ALLOWED", 0L);
        m.put("ALREADY_REDEEMED", 0L);
        m.put("WRONG_ORGANIZER", 0L);
        m.put("NOT_ASSIGNED_TO_EVENT", 0L);
        m.put("TICKET_NOT_FOUND", 0L);
        m.put("BOOKING_NOT_CONFIRMED", 0L);
        return m;
    }

    // --------------------------------------------------------------
    // /scans/me — happy paths for organizer + team member, security
    // negative paths (no userUuid, bad range).
    // --------------------------------------------------------------

    @Test
    void myScans_returnsPagedListForOrganizer() {
        ScanReportService svc = mock(ScanReportService.class);
        UUID userUuid = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");
        PageResponse<ScanAttemptDTO> page = new PageResponse<>(List.of(), 0, 20, 0L, 0);
        when(svc.listMyScans(eq(userUuid), eq(from), eq(to), anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> resp =
                controller(svc, null).myScans(organizerAuth(UUID.randomUUID(), userUuid),
                        from, to, 0, 20);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isSameAs(page);
        verify(svc).listMyScans(eq(userUuid), eq(from), eq(to), eq(0), eq(20));
    }

    @Test
    void myScans_returnsPagedListForTeamMember() {
        // Same /me path must work for TEAM_MEMBER — they're the primary
        // gate-staff persona.
        ScanReportService svc = mock(ScanReportService.class);
        UUID userUuid = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");
        PageResponse<ScanAttemptDTO> page = new PageResponse<>(List.of(), 0, 20, 0L, 0);
        when(svc.listMyScans(eq(userUuid), any(), any(), anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> resp =
                controller(svc, null).myScans(teamMemberAuth(UUID.randomUUID(), userUuid),
                        from, to, 0, 20);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(svc).listMyScans(eq(userUuid), eq(from), eq(to), eq(0), eq(20));
    }

    @Test
    void myScans_rejectsTokenMissingUserUuidClaim() {
        // Legacy token with no userUuid claim: scoping to "whose scans?" is
        // impossible — the controller must 400 instead of silently scoping to
        // null and returning an empty page.
        ScanReportService svc = mock(ScanReportService.class);
        var auth = new UsernamePasswordAuthenticationToken("legacy@x.co", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("legacy@x.co", null, null, null, null, null));

        assertThatThrownBy(() -> controller(svc, null).myScans(auth,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z"), 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing user identity");
        verify(svc, never()).listMyScans(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void myScans_rejectsInvertedRange() {
        ScanReportService svc = mock(ScanReportService.class);
        assertThatThrownBy(() -> controller(svc, null).myScans(
                organizerAuth(UUID.randomUUID(), UUID.randomUUID()),
                Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"), 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void myScans_rejectsPageSizeOverCap() {
        ScanReportService svc = mock(ScanReportService.class);
        assertThatThrownBy(() -> controller(svc, null).myScans(
                organizerAuth(UUID.randomUUID(), UUID.randomUUID()),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z"), 0, 101))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'size' must be between 1 and 100");
    }

    // --------------------------------------------------------------
    // /scans/me/stats — happy path, display-name resolution.
    // --------------------------------------------------------------

    @Test
    void myStats_passesScannerIdentityFromJwtClaimsToService() {
        ScanReportService svc = mock(ScanReportService.class);
        UUID userUuid = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T23:59:59Z");
        ScannerStatsDTO dto = new ScannerStatsDTO(userUuid, "tariro@harare-arena.co.zw",
                "Tariro Chikomo", from, to, 0L, emptyOutcomes());
        when(svc.myStats(eq(userUuid), eq("tariro@harare-arena.co.zw"),
                eq("Tariro Chikomo"), eq(from), eq(to))).thenReturn(dto);

        ResponseEntity<ApiResult<ScannerStatsDTO>> resp = controller(svc, null)
                .myStats(teamMemberAuth(UUID.randomUUID(), userUuid), from, to);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isSameAs(dto);
    }

    // --------------------------------------------------------------
    // /scans/events/{eventId} — owner check.
    // --------------------------------------------------------------

    @Test
    void eventScans_allowedWhenOrganizerOwnsTheEvent() {
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID organizerUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventLookupDTO eventDto = new EventLookupDTO();
        eventDto.setTenantUserUuid(organizerUuid);
        when(eventClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok("ok", eventDto));
        Instant from = Instant.parse("2026-06-19T17:00:00Z");
        Instant to = Instant.parse("2026-06-20T02:00:00Z");
        PageResponse<ScanAttemptDTO> page = new PageResponse<>(List.of(), 0, 20, 0L, 0);
        when(svc.listEventScans(eq(eventId), eq(from), eq(to), anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> resp =
                controller(svc, eventClient).eventScans(
                        organizerAuth(organizerUuid, UUID.randomUUID()),
                        eventId, from, to, 0, 20);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(svc).listEventScans(eq(eventId), eq(from), eq(to), eq(0), eq(20));
    }

    @Test
    void eventScans_rejects403WhenCallerDoesNotOwnTheEvent() {
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID callerOrganizer = UUID.randomUUID();
        UUID otherOrganizer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventLookupDTO eventDto = new EventLookupDTO();
        // Event is owned by someone else.
        eventDto.setTenantUserUuid(otherOrganizer);
        when(eventClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok("ok", eventDto));

        assertThatThrownBy(() -> controller(svc, eventClient).eventScans(
                organizerAuth(callerOrganizer, UUID.randomUUID()),
                eventId,
                Instant.parse("2026-06-19T17:00:00Z"),
                Instant.parse("2026-06-20T02:00:00Z"), 0, 20))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("do not own this event");
        verify(svc, never()).listEventScans(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void eventScans_returns404WhenEventLookupReturnsNoData() {
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID eventId = UUID.randomUUID();
        when(eventClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok("ok", null));

        assertThatThrownBy(() -> controller(svc, eventClient).eventScans(
                organizerAuth(UUID.randomUUID(), UUID.randomUUID()),
                eventId,
                Instant.parse("2026-06-19T17:00:00Z"),
                Instant.parse("2026-06-20T02:00:00Z"), 0, 20))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Event not found");
    }

    // --------------------------------------------------------------
    // /scans/events/{id}/stats — owner check on the stats endpoint too.
    // --------------------------------------------------------------

    @Test
    void eventStats_rejects403WhenCallerDoesNotOwnTheEvent() {
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID callerOrganizer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventLookupDTO eventDto = new EventLookupDTO();
        eventDto.setTenantUserUuid(UUID.randomUUID());  // someone else
        when(eventClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok("ok", eventDto));

        assertThatThrownBy(() -> controller(svc, eventClient).eventStats(
                organizerAuth(callerOrganizer, UUID.randomUUID()), eventId,
                Instant.parse("2026-06-19T17:00:00Z"),
                Instant.parse("2026-06-20T02:00:00Z")))
                .isInstanceOf(AccessDeniedException.class);
        verify(svc, never()).eventStats(any(), any(), any());
    }

    // --------------------------------------------------------------
    // /scans/team-stats — the leaderboard must surface what the
    // service returned, in service order (DESC by total).
    // --------------------------------------------------------------

    @Test
    void teamStats_returnsMembersInServiceProvidedOrderTopScannerFirst() {
        ScanReportService svc = mock(ScanReportService.class);
        UUID organizerUuid = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-19T17:00:00Z");
        Instant to = Instant.parse("2026-06-20T02:00:00Z");
        UUID topUserUuid = UUID.randomUUID();
        UUID midUserUuid = UUID.randomUUID();
        UUID lowUserUuid = UUID.randomUUID();
        TeamStatsResponseDTO response = new TeamStatsResponseDTO(from, to, List.of(
                new TeamMemberStatsDTO(topUserUuid, "tariro@x.co", "Tariro Chikomo",
                        412L, emptyOutcomes()),
                new TeamMemberStatsDTO(midUserUuid, "rufaro@x.co", "Rufaro Moyo",
                        287L, emptyOutcomes()),
                new TeamMemberStatsDTO(lowUserUuid, "rumbi@x.co", "Rumbi Sibanda",
                        53L, emptyOutcomes())
        ));
        when(svc.teamStats(eq(organizerUuid), eq(from), eq(to))).thenReturn(response);

        ResponseEntity<ApiResult<TeamStatsResponseDTO>> resp = controller(svc, null)
                .teamStats(organizerAuth(organizerUuid, UUID.randomUUID()), from, to);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
        TeamStatsResponseDTO data = resp.getBody().getData();
        assertThat(data.members()).extracting(TeamMemberStatsDTO::scannerUserUuid)
                .containsExactly(topUserUuid, midUserUuid, lowUserUuid);
        assertThat(data.members().get(0).total())
                .as("top scanner first")
                .isEqualTo(412L);
    }

    @Test
    void eventScans_superAdmin_bypassesOwnershipAndNeedsNoOrganizerClaim() {
        // SUPER_ADMIN sees any event's scans: no ownership lookup is made
        // (their token has no organizer claim to compare with anyway).
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID eventId = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-19T17:00:00Z");
        Instant to = Instant.parse("2026-06-20T02:00:00Z");
        PageResponse<ScanAttemptDTO> page = new PageResponse<>(List.of(), 0, 20, 0L, 0);
        when(svc.listEventScans(eq(eventId), eq(from), eq(to), anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> resp =
                controller(svc, eventClient).eventScans(superAdminAuth(), eventId, from, to, 0, 20);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(eventClient, never()).getEventInternal(any(), any());
        verify(svc).listEventScans(eq(eventId), eq(from), eq(to), eq(0), eq(20));
    }

    @Test
    void eventStats_superAdmin_bypassesOwnership() {
        ScanReportService svc = mock(ScanReportService.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        UUID eventId = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-19T17:00:00Z");
        Instant to = Instant.parse("2026-06-20T02:00:00Z");
        EventScanStatsDTO dto = new EventScanStatsDTO(eventId, from, to, 0L, emptyOutcomes());
        when(svc.eventStats(eq(eventId), eq(from), eq(to))).thenReturn(dto);

        ResponseEntity<ApiResult<EventScanStatsDTO>> resp =
                controller(svc, eventClient).eventStats(superAdminAuth(), eventId, from, to);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(eventClient, never()).getEventInternal(any(), any());
    }

    @Test
    void teamStats_superAdmin_getsFleetWideNullScope() {
        // Null organizer scope = every organizer's gate staff. Pinned so a
        // future refactor can't quietly turn the admin view into a 400.
        ScanReportService svc = mock(ScanReportService.class);
        Instant from = Instant.parse("2026-06-19T17:00:00Z");
        Instant to = Instant.parse("2026-06-20T02:00:00Z");
        TeamStatsResponseDTO response = new TeamStatsResponseDTO(from, to, List.of());
        when(svc.teamStats(eq(null), eq(from), eq(to))).thenReturn(response);

        ResponseEntity<ApiResult<TeamStatsResponseDTO>> resp = controller(svc, null)
                .teamStats(superAdminAuth(), from, to);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(svc).teamStats(eq(null), eq(from), eq(to));
    }

    @Test
    void teamStats_rejectsTokenMissingOrganizerClaim() {
        ScanReportService svc = mock(ScanReportService.class);
        var auth = new UsernamePasswordAuthenticationToken("legacy@x.co", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        // organizerUuid claim absent
        auth.setDetails(new JwtAuthDetails("legacy@x.co", null,
                UUID.randomUUID(), null, null, null));

        assertThatThrownBy(() -> controller(svc, null).teamStats(auth,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing organizer identity");
    }

    // Compile-time pin: the unused-otherwise EventScanStatsDTO import + its
    // single direct construction make a refactor that breaks the stats DTO's
    // public shape fail the controller test, not just a hypothetical FE.
    @SuppressWarnings("unused")
    private static EventScanStatsDTO zeroStats(UUID eventId, Instant from, Instant to) {
        return new EventScanStatsDTO(eventId, from, to, 0L, emptyOutcomes());
    }
}
