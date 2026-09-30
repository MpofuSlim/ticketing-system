package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.exception.SupportPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The call-center reads of {@code GET /admin/device-security/**} under the
 * support rules (T4): the limit is taken BEFORE the read runs (a limited agent
 * learns nothing), a read that returns data writes a REQUIRED row (a read that
 * can't be recorded isn't shown), and a DTX refusal writes a best-effort row
 * and is rethrown unchanged.
 */
class DeviceSecurityReadAccessTest {

    private static final UUID AGENT = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f");

    private SupportLookupLimiter limiter;
    private SupportAccessLogWriter accessLog;
    private DeviceSecurityReadAccess access;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        SupportAgentResolver agents = mock(SupportAgentResolver.class);
        when(agents.resolve(any())).thenReturn(new SupportAgent("agent.one@innbucks.co.zw", 7L, AGENT,
                "agent.one@innbucks.co.zw", null, null, Set.of("device-security:read")));
        limiter = mock(SupportLookupLimiter.class);
        accessLog = mock(SupportAccessLogWriter.class);
        access = new DeviceSecurityReadAccess(agents, limiter, accessLog, SupportMetrics.none(),
                Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC), "ZW");
    }

    private String call() {
        calls.incrementAndGet();
        return "+263771234567";
    }

    private String read() {
        return access.read(mock(Authentication.class), "41.221.147.12", DeviceSecurityReadAccess.OP_OVERVIEW,
                DeviceSecurityReadAccess.Subject.phone("0771234567"), this::call,
                msisdn -> new SupportCustomerKeys(List.of(msisdn), List.of(), List.of(), List.of(), null));
    }

    @Test
    @DisplayName("over the limit: 429 BEFORE the read runs, and nothing is logged as a read")
    void limitedBeforeTheCall() {
        doThrow(SupportPolicyException.rateLimited(60, "10m")).when(limiter).acquire(any());
        assertThatThrownBy(this::read).isInstanceOf(SupportPolicyException.class);
        assertThat(calls.get()).isZero();
        verifyNoInteractions(accessLog);
    }

    @Test
    @DisplayName("a read that returns data writes a REQUIRED row with the keys it reached, masked query and all")
    void successWritesARequiredRow() {
        assertThat(read()).isEqualTo("+263771234567");
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).required(row.capture());
        verify(accessLog, never()).bestEffort(any());
        assertThat(row.getValue().getOp()).isEqualTo("DEVICE_SECURITY_OVERVIEW");
        assertThat(row.getValue().getOutcome()).isEqualTo("OK");
        assertThat(row.getValue().getAgentUserUuid()).isEqualTo(AGENT);
        assertThat(row.getValue().getQueryMasked()).isEqualTo("****4567");
        assertThat(row.getValue().getCustomerKeys()).contains("+263771234567");
        assertThat(row.getValue().getSections()).isEqualTo("innbucksApp");
    }

    @Test
    @DisplayName("a read whose row can't be written is refused 503 — fail-closed")
    void successButNoRowIsRefused() {
        doThrow(SupportPolicyException.logUnavailable()).when(accessLog).required(any());
        assertThatThrownBy(this::read)
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode())
                        .isEqualTo("support_log_unavailable"));
    }

    @Test
    @DisplayName("a DTX refusal writes a best-effort row with its errorCode and is rethrown unchanged")
    void dtxRefusalIsLoggedBestEffort() {
        DeviceSecurityException refusal = new DeviceSecurityException(HttpStatus.NOT_FOUND, "support_ref_not_found",
                "No block, ban or unlock carries reference SEC-8F2KQ9.");
        assertThatThrownBy(() -> access.read(mock(Authentication.class), null, DeviceSecurityReadAccess.OP_SUPPORT_REF,
                DeviceSecurityReadAccess.Subject.reference("sec-8f2kq9"), () -> {
                    throw refusal;
                }, x -> null)).isSameAs(refusal);
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        verify(accessLog, never()).required(any());
        assertThat(row.getValue().getOutcome()).isEqualTo("support_ref_not_found");
        assertThat(row.getValue().getQueryMasked()).isEqualTo("SEC-8F2KQ9");
        assertThat(row.getValue().getCustomerKeys()).isNull();
        verify(limiter).acquire(any());
    }
}
