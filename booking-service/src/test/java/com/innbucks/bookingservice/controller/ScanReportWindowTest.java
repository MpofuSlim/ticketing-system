package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.scan.PageResponse;
import com.innbucks.bookingservice.dto.scan.ScanAttemptDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.ScanReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the window semantics that make the Scan Reports screen self-updating.
 *
 * <p><b>The bug these exist for.</b> An operator scanned a ticket and the
 * report did not show it, however often they hit refresh. Nothing was stale:
 * the audit row commits with the redemption and nothing caches the read. The
 * screen was simply asking a question that excluded the row — it computed
 * "now" once when it mounted, sent that as {@code to}, and re-sent the same
 * frozen value on every refresh. With a closed {@code BETWEEN}, every scan
 * after page-load is {@code > to} and invisible forever.
 *
 * <p>The fix is entirely server-side by design: no client change may be
 * required to see today's scans.
 */
class ScanReportWindowTest {

    private static final MarketTimeZone ZW = new MarketTimeZone("ZW");

    @Test
    void aScanMadeAfterTheScreenLoaded_isStillInsideTheQueriedWindow() {
        // The literal scenario from the report. The operator opened Scan
        // Reports at 09:44 Harare (07:44Z), so that is the `to` the screen
        // will keep sending. They then scan at 10:30 Harare (08:30Z).
        ScanReportService svc = mock(ScanReportService.class);
        Instant from = Instant.parse("2026-09-02T07:44:00Z");
        Instant frozenTo = Instant.parse("2026-09-09T07:44:00Z");
        Instant laterScan = Instant.parse("2026-09-09T08:30:00Z");
        when(svc.listMyScans(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        UUID userUuid = UUID.randomUUID();
        controller(svc).myScans(teamMemberAuth(userUuid), from, frozenTo, 0, 20);

        ArgumentCaptor<Instant> toArg = ArgumentCaptor.forClass(Instant.class);
        verify(svc).listMyScans(eq(userUuid), eq(from), toArg.capture(), eq(0), eq(20));
        assertThat(toArg.getValue())
                .as("the scan that happened after page-load must fall inside the window")
                .isAfterOrEqualTo(laterScan);
    }

    @Test
    void theWindowStretchesToTheEndOfTheMarketDay_notTheUtcDay() {
        // A 23:30 Harare scan is 21:30Z — still the same local day. Rounding
        // the bound up in UTC would stop at 21:59:59.999Z and cut the last two
        // hours off every single day.
        ScanReportService svc = mock(ScanReportService.class);
        when(svc.listMyScans(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        UUID userUuid = UUID.randomUUID();
        controller(svc).myScans(teamMemberAuth(userUuid),
                Instant.parse("2026-09-09T07:44:00Z"),
                Instant.parse("2026-09-09T07:44:00Z"), 0, 20);

        ArgumentCaptor<Instant> toArg = ArgumentCaptor.forClass(Instant.class);
        verify(svc).listMyScans(any(), any(), toArg.capture(), anyInt(), anyInt());
        assertThat(toArg.getValue()).isEqualTo(Instant.parse("2026-09-09T21:59:59.999999999Z"));
    }

    @Test
    void aHistoricalWindowKeepsItsMeaning_andIsNotSilentlyExtendedToNow() {
        // This is what makes the widening safe to apply unconditionally. If we
        // had simply clamped `to` up to "now", "what happened on the 3rd" would
        // start reporting everything since — an answer that looks authoritative
        // and is wrong.
        ScanReportService svc = mock(ScanReportService.class);
        when(svc.listMyScans(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-03T10:00:00Z"), 0, 20);

        ArgumentCaptor<Instant> toArg = ArgumentCaptor.forClass(Instant.class);
        verify(svc).listMyScans(any(), any(), toArg.capture(), anyInt(), anyInt());
        assertThat(toArg.getValue())
                .as("must still end on the 3rd, local")
                .isEqualTo(Instant.parse("2026-09-03T21:59:59.999999999Z"))
                .isBefore(Instant.now());
    }

    @Test
    void omittingTo_meansUpToThisInstant_evaluatedPerRequest() {
        // The clean way for a live view to ask the question: no frozen bound at
        // all, so not even the midnight-rollover edge can hide a scan.
        ScanReportService svc = mock(ScanReportService.class);
        when(svc.listMyScans(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        Instant before = Instant.now();
        controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                Instant.parse("2026-09-02T07:44:00Z"), null, 0, 20);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> toArg = ArgumentCaptor.forClass(Instant.class);
        verify(svc).listMyScans(any(), any(), toArg.capture(), anyInt(), anyInt());
        assertThat(toArg.getValue()).isBetween(before, after);
    }

    @Test
    void anInvertedRangeIsStillRejected() {
        // The widening must not swallow a genuinely nonsensical request.
        ScanReportService svc = mock(ScanReportService.class);

        assertThatThrownBy(() -> controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void aMissingFromIsStillRejected() {
        ScanReportService svc = mock(ScanReportService.class);

        assertThatThrownBy(() -> controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                null, Instant.parse("2026-09-01T00:00:00Z"), 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'from' is required");
    }

    @Test
    void aFutureFromWithNoTo_isRejectedRatherThanReturningAnEmptyReport() {
        ScanReportService svc = mock(ScanReportService.class);

        assertThatThrownBy(() -> controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                Instant.now().plusSeconds(3600), null, 0, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be in the future");
    }

    @Test
    void reportsAreNeverCacheable() {
        // These responses previously carried no cache header at all, leaving an
        // intermediary free to invent freshness — which would present as
        // exactly the "the report isn't updating" complaint.
        ScanReportService svc = mock(ScanReportService.class);
        when(svc.listMyScans(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> resp =
                controller(svc).myScans(teamMemberAuth(UUID.randomUUID()),
                        Instant.parse("2026-09-02T07:44:00Z"), null, 0, 20);

        assertThat(resp.getHeaders().getCacheControl()).contains("no-store");
    }

    // ---- helpers -----------------------------------------------------------

    private static ScanReportController controller(ScanReportService svc) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        return new ScanReportController(svc, ZW, provider);
    }

    private static UsernamePasswordAuthenticationToken teamMemberAuth(UUID userUuid) {
        var auth = new UsernamePasswordAuthenticationToken(
                "tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null,
                userUuid, UUID.randomUUID(), "Tariro", "Chikomo"));
        return auth;
    }
}
