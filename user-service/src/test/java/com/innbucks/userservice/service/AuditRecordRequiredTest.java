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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @DisplayName("both write_failed series exist at 0 from construction — the alert can see the FIRST failure")
    void writeFailedSeriesAreRegisteredAtZero() {
        // increase() cannot see a series' first sample, so a counter created by
        // the failure it counts would start at 1 and AuditWriteFailed would miss
        // the first failure per pod.
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        new SecurityMetrics(fresh);

        for (String mode : new String[] {"best_effort", "required"}) {
            var counter = fresh.find("security.audit.write_failed").tag("mode", mode).counter();
            assertThat(counter).as("series mode=%s", mode).isNotNull();
            assertThat(counter.count()).isZero();
        }
        // The series set is closed: mode is the only tag, so nothing is created lazily.
        assertThat(fresh.find("security.audit.write_failed").counters()).hasSize(2);
        assertThat(fresh.find("security.audit.write_failed").counters())
                .allSatisfy(c -> assertThat(c.getId().getTags()).extracting(t -> t.getKey()).containsExactly("mode"));
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

    // -- the required audit is the LAST statement ------------------------------
    //
    // The row commits in its own transaction, so anything that could still fail
    // after it would leave a committed row describing a rolled-back change. These
    // pin the order: the change is made and flushed (and, for a PLATFORM removal,
    // the holders bumped) BEFORE the audit is asked for.

    @Test
    @DisplayName("setRoles: the roles are changed, the token bumped and the row flushed before the audit")
    void setRolesAuditsLast() throws Exception {
        AdminDispatchHarness h = new AdminDispatchHarness();
        h.account(1L, "admin@innbucks.co.zw", "SUPER_ADMIN");
        var target = h.eligibleStaff(h.account(40L, "agent@innbucks.co.zw", "FRAUD_DESK"));
        doAnswer(inv -> {
            // At the moment the audit is asked for, the change is already made.
            assertThat(target.getRoles()).containsExactlyInAnyOrder("FRAUD_DESK", "CALL_CENTER_AGENT");
            assertThat(target.getTokenVersion()).isEqualTo(4L);
            return null;
        }).when(h.audit).recordRequired(eq(AuditEventType.USER_ROLES_CHANGED), any(), any(), any(), any(), any(),
                any());

        h.mvc.perform(put("/admin/users/40/roles").principal(as("admin@innbucks.co.zw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"FRAUD_DESK\",\"CALL_CENTER_AGENT\"]}"))
                .andExpect(status().isOk());

        InOrder order = inOrder(h.users, h.audit);
        order.verify(h.users).save(target);
        order.verify(h.users).flush();
        order.verify(h.audit).recordRequired(eq(AuditEventType.USER_ROLES_CHANGED), any(), any(), eq("40"), any(),
                any(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("setPermissions: saved, flushed and every holder bumped before the audit")
    void setPermissionsAuditsLast() throws Exception {
        AdminDispatchHarness h = new AdminDispatchHarness();
        h.account(1L, "admin@innbucks.co.zw", "SUPER_ADMIN");
        h.role("PHONE_SUPPORT", "device-security:read", "device-security:manage");
        doAnswer(inv -> {
            assertThat(h.roleRows.get("PHONE_SUPPORT").getPermissions()).containsExactly("device-security:read");
            assertThat(h.bumper.bumpedRoles).as("holders are signed out before the audit").containsExactly(
                    "PHONE_SUPPORT");
            return null;
        }).when(h.audit).recordRequired(eq(AuditEventType.ROLE_PERMISSIONS_CHANGED), any(), any(), any(), any(),
                any(), any());

        h.mvc.perform(put("/admin/roles/PHONE_SUPPORT/permissions").principal(as("admin@innbucks.co.zw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"device-security:read\"]}"))
                .andExpect(status().isOk());

        InOrder order = inOrder(h.roles, h.audit);
        order.verify(h.roles).save(any(com.innbucks.userservice.entity.Role.class));
        order.verify(h.roles).flush();
        order.verify(h.audit).recordRequired(eq(AuditEventType.ROLE_PERMISSIONS_CHANGED), any(), any(),
                eq("PHONE_SUPPORT"), any(), any(), any());
        verify(h.audit, times(1)).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create and delete: saved or deleted, and flushed, before the audit")
    void createAndDeleteAuditLast() throws Exception {
        AdminDispatchHarness h = new AdminDispatchHarness();
        h.account(1L, "admin@innbucks.co.zw", "SUPER_ADMIN");

        h.mvc.perform(post("/admin/roles").principal(as("admin@innbucks.co.zw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PHONE_LOOKUP\",\"description\":\"d\","
                                + "\"permissions\":[\"device-security:read\"]}"))
                .andExpect(status().isCreated());
        InOrder created = inOrder(h.roles, h.audit);
        created.verify(h.roles).save(any(com.innbucks.userservice.entity.Role.class));
        created.verify(h.roles).flush();
        created.verify(h.audit).recordRequired(eq(AuditEventType.ROLE_CREATED), any(), any(), eq("PHONE_LOOKUP"),
                any(), any(), any());

        h.mvc.perform(delete("/admin/roles/PHONE_LOOKUP").principal(as("admin@innbucks.co.zw")))
                .andExpect(status().isOk());
        InOrder deleted = inOrder(h.roles, h.audit);
        deleted.verify(h.roles).delete(any(com.innbucks.userservice.entity.Role.class));
        deleted.verify(h.roles).flush();
        deleted.verify(h.audit).recordRequired(eq(AuditEventType.ROLE_DELETED), any(), any(), eq("PHONE_LOOKUP"),
                any(), any(), any());
    }
}
