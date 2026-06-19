package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Security + behaviour tests for the internal merchant-assignment lookup.
 *
 * <p>Two concerns are pinned here: (1) per CLAUDE.md, the token check must
 * reject with a SPECIFIC code (401) and the lookup must run only when the
 * shared X-Internal-Token matches — asserted directly on the returned status,
 * not via a vague 4xx check; (2) every rejection must persist exactly one
 * {@code AUTH_INTERNAL_TOKEN_FAILURE} audit row with the right
 * {@code failure_reason}, so probing of the S2S trust boundary stops being a
 * silent 401.
 */
class InternalMerchantAssignmentControllerTest {

    private static final String TOKEN = "the-shared-secret";

    @SuppressWarnings("unchecked")
    private static List<String> data(ResponseEntity<?> resp) {
        return (List<String>) ((ApiResult<?>) resp.getBody()).getData();
    }

    private InternalMerchantAssignmentController controller(UserRepository repo,
                                                            String expectedToken,
                                                            AuditService audit) {
        InternalTokenAuthorizer authorizer = new InternalTokenAuthorizer(expectedToken, audit);
        return new InternalMerchantAssignmentController(repo, authorizer);
    }

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/merchants/assigned");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    @Test
    void validToken_returnsAssignedMerchantIdsForDefaultRole_andNoAuditRow() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findDistinctLoyaltyMerchantIdsByRole(User.Role.MERCHANT_ADMIN))
                .thenReturn(List.of(a, b));

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .assignedMerchantIds(TOKEN, "MERCHANT_ADMIN", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp)).containsExactlyInAnyOrder(a.toString(), b.toString());
        // Audit fires ONLY on failure — the happy path stays quiet.
        verify(audit, never()).recordFailure(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingToken_returns401_persistsTokenMissingAuditRow_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .assignedMerchantIds(null, "MERCHANT_ADMIN", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
        assertAuditFailureReason(audit, "token_missing", 0);
    }

    @Test
    void wrongToken_returns401_persistsTokenMismatchAuditRow_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .assignedMerchantIds("not-the-secret", "MERCHANT_ADMIN", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
        assertAuditFailureReason(audit, "token_mismatch", "not-the-secret".length());
    }

    @Test
    void unconfiguredToken_returns401_persistsTokenNotConfiguredAuditRow_andDoesNotQuery() {
        // A blank expectedToken is a misconfiguration — the controller must
        // fail-CLOSED rather than fail-open (it never matches an attacker's
        // empty string by accident). The audit row distinguishes this from
        // active probing so on-call sees the prod-misconfig signal.
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, "", audit)
                .assignedMerchantIds(TOKEN, "MERCHANT_ADMIN", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
        assertAuditFailureReason(audit, "token_not_configured", TOKEN.length());
    }

    @Test
    void unknownRole_returns400() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .assignedMerchantIds(TOKEN, "MAYOR_OF_MARS", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(repo);
        verify(audit, never()).recordFailure(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emptyResult_returns200_withEmptyList() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        when(repo.findDistinctLoyaltyMerchantIdsByRole(User.Role.MERCHANT_ADMIN))
                .thenReturn(List.of());

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .assignedMerchantIds(TOKEN, "MERCHANT_ADMIN", request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp)).isEmpty();
    }

    /**
     * Verifies that exactly one AUTH_INTERNAL_TOKEN_FAILURE was persisted with
     * the expected {@code failure_reason}, ANONYMOUS actor, and metadata
     * carrying the path + the presented-token length (never the token itself).
     */
    @SuppressWarnings("unchecked")
    private static void assertAuditFailureReason(AuditService audit, String expectedReason,
                                                 int expectedPresentedLength) {
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_INTERNAL_TOKEN_FAILURE),
                eq(null),
                eq(AuditService.ACTOR_TYPE_ANONYMOUS),
                eq(null),
                eq(null),
                reasonCaptor.capture(),
                metadataCaptor.capture(),
                any());
        assertThat(reasonCaptor.getValue()).isEqualTo(expectedReason);
        assertThat(metadataCaptor.getValue()).containsEntry("path",
                "/users/internal/merchants/assigned");
        assertThat(metadataCaptor.getValue()).containsEntry("presentedTokenLength",
                expectedPresentedLength);
        // Critical safety check: the metadata must NEVER carry the token itself.
        assertThat(metadataCaptor.getValue().values()).doesNotContain(TOKEN, "not-the-secret");
        // Avoid an unused-stub IDE warning by referencing the matcher import.
        verify(audit, never()).recordFailure(eq(AuditEventType.AUTH_LOGIN_FAILURE),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }
}
