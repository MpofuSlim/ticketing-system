package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.UserContactDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.notification.UserNotificationDispatcher;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Security + behaviour tests for the internal contact lookup consumed by
 * loyalty-service's tenant-attach notifier.
 *
 * <p>Mirrors {@link InternalMerchantAssignmentControllerTest}: the token check
 * must reject with a SPECIFIC code (401) — asserted directly on the returned
 * status, never via a vague {@code is4xxClientError()} (per CLAUDE.md) — and the
 * repository is only queried once the shared X-Internal-Token matches. An
 * unknown uuid returns a specific 404.
 */
class InternalUserLookupControllerTest {

    private static final String TOKEN = "the-shared-secret";

    private InternalUserLookupController controller(UserRepository repo,
                                                    String expectedToken,
                                                    AuditService audit) {
        InternalTokenAuthorizer authorizer = new InternalTokenAuthorizer(expectedToken, audit);
        return new InternalUserLookupController(repo, authorizer, mock(UserNotificationDispatcher.class));
    }

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/contact");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    private static User user(UUID uuid) {
        return User.builder()
                .userUuid(uuid)
                .firstName("Alice")
                .lastName("Moyo")
                .phoneNumber("+263771234567")
                .email("alice@example.com")
                .password("x")
                .build();
    }

    @Test
    void validToken_returnsContact_withPhoneEmailFirstName() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        UUID uuid = UUID.randomUUID();
        when(repo.findByUserUuid(uuid)).thenReturn(Optional.of(user(uuid)));

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .getContact(TOKEN, uuid, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserContactDTO data = (UserContactDTO) ((ApiResult<?>) resp.getBody()).getData();
        assertThat(data.getUserUuid()).isEqualTo(uuid);
        assertThat(data.getPhoneNumber()).isEqualTo("+263771234567");
        assertThat(data.getEmail()).isEqualTo("alice@example.com");
        assertThat(data.getFirstName()).isEqualTo("Alice");
        // Audit fires ONLY on failure — the happy path stays quiet.
        verify(audit, never()).recordFailure(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingToken_returns401_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .getContact(null, UUID.randomUUID(), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_INTERNAL_TOKEN_FAILURE),
                any(), any(), any(), any(),
                eq("token_missing"), any(), any());
    }

    @Test
    void blankToken_returns401_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .getContact("   ", UUID.randomUUID(), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void wrongToken_returns401_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .getContact("not-the-secret", UUID.randomUUID(), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
        verify(audit).recordFailure(
                eq(AuditEventType.AUTH_INTERNAL_TOKEN_FAILURE),
                any(), any(), any(), any(),
                eq("token_mismatch"), any(), any());
    }

    @Test
    void unknownUuid_returns404() {
        UserRepository repo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        UUID uuid = UUID.randomUUID();
        when(repo.findByUserUuid(uuid)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller(repo, TOKEN, audit)
                .getContact(TOKEN, uuid, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiResult<?> body = (ApiResult<?>) resp.getBody();
        assertThat(body.getData()).isNull();
        assertThat(body.getMessage()).isEqualTo("User not found");
    }
}
