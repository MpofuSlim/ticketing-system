package com.innbucks.userservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.config.SecurityMetrics;
import com.innbucks.userservice.entity.AuditChainHead;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.exception.AuditUnavailableException;
import com.innbucks.userservice.repository.AuditChainHeadRepository;
import com.innbucks.userservice.repository.AuditEventRepository;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.Optional;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit of a change to who can do what is REQUIRED: if its row cannot be
 * written the change is refused ({@code 503 audit_unavailable}) and rolls back.
 * Every other audit row stays best-effort — a login is never broken by the audit
 * path — and every failure of either kind moves {@code security.audit.write_failed}.
 */
class AuditRecordRequiredTest {

    private AuditEventRepository repo;
    private AuditService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        when(repo.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        AuditChainHeadRepository head = mock(AuditChainHeadRepository.class);
        when(head.lockHead()).thenReturn(Optional.of(new AuditChainHead(1, null, null)));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AuditService(repo, head, new ObjectMapper(), tx, "test-audit-hmac-secret");
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "securityMetrics", new SecurityMetrics(registry));
    }

    private double failed(String mode) {
        var c = registry.find("security.audit.write_failed").tag("mode", mode).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("a required row is written like any other SUCCESS row")
    void requiredRowIsWritten() {
        service.recordRequired(AuditEventType.ROLE_CREATED, "admin@innbucks.co.zw", AuditService.ACTOR_TYPE_USER,
                "PHONE_LOOKUP", AuditService.TARGET_TYPE_ROLE, Map.of("role", "PHONE_LOOKUP"),
                AuditContext.none());

        ArgumentCaptor<AuditEvent> row = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("ROLE_CREATED");
        assertThat(row.getValue().getOutcome()).isEqualTo(AuditService.OUTCOME_SUCCESS);
        assertThat(row.getValue().getRowHmac()).isNotBlank();
        assertThat(row.getValue().getChainHmac()).isNotBlank();
    }

    @Test
    @DisplayName("a required row that cannot be written THROWS — the change must not go ahead")
    void requiredFailureThrows() {
        when(repo.save(any(AuditEvent.class))).thenThrow(new DataIntegrityViolationException("simulated outage"));

        assertThatThrownBy(() -> service.recordRequired(AuditEventType.USER_ROLES_CHANGED, "admin@innbucks.co.zw",
                AuditService.ACTOR_TYPE_USER, "42", AuditService.TARGET_TYPE_USER, Map.of(), AuditContext.none()))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessage("We couldn't record this change, so it wasn't made. Try again.")
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(failed("required")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a best-effort row that cannot be written is swallowed — but counted")
    void bestEffortFailureIsCounted() {
        when(repo.save(any(AuditEvent.class))).thenThrow(new DataIntegrityViolationException("simulated outage"));

        assertDoesNotThrow(() -> service.recordSuccess(AuditEventType.AUTH_LOGIN_SUCCESS, "42",
                AuditService.ACTOR_TYPE_USER, "42", AuditService.TARGET_TYPE_USER, null, AuditContext.none()));
        assertThat(failed("best_effort")).isEqualTo(1.0);
        assertThat(failed("required")).isZero();
    }

    @Test
    @DisplayName("through real dispatch: a role change whose audit fails is a 503 audit_unavailable")
    void dispatchRendersA503() throws Exception {
        AdminDispatchHarness h = new AdminDispatchHarness();
        h.account(1L, "admin@innbucks.co.zw", "SUPER_ADMIN");
        doThrow(new AuditUnavailableException(new IllegalStateException("db down")))
                .when(h.audit).recordRequired(eq(AuditEventType.ROLE_CREATED), any(), any(), any(), any(), any(), any());

        h.mvc.perform(post("/admin/roles").principal(as("admin@innbucks.co.zw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PHONE_LOOKUP\",\"description\":\"d\",\"permissions\":[\"device-security:read\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"));
    }
}
