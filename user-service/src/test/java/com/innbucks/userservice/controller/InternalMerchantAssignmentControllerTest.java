package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Security + behaviour tests for the internal merchant-assignment lookup.
 * Per CLAUDE.md, the token check must reject with a SPECIFIC code (401) and
 * the lookup must run only when the shared X-Internal-Token matches — asserted
 * directly on the returned status here, not via a vague 4xx check.
 */
class InternalMerchantAssignmentControllerTest {

    private static final String TOKEN = "the-shared-secret";

    @SuppressWarnings("unchecked")
    private static List<String> data(ResponseEntity<?> resp) {
        return (List<String>) ((ApiResult<?>) resp.getBody()).getData();
    }

    @Test
    void validToken_returnsAssignedMerchantIdsForDefaultRole() {
        UserRepository repo = mock(UserRepository.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findDistinctLoyaltyMerchantIdsByRole(User.Role.MERCHANT_ADMIN))
                .thenReturn(List.of(a, b));
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, TOKEN);

        ResponseEntity<?> resp = controller.assignedMerchantIds(TOKEN, "MERCHANT_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp)).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    @Test
    void missingToken_returns401_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, TOKEN);

        ResponseEntity<?> resp = controller.assignedMerchantIds(null, "MERCHANT_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void wrongToken_returns401_andDoesNotQuery() {
        UserRepository repo = mock(UserRepository.class);
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, TOKEN);

        ResponseEntity<?> resp = controller.assignedMerchantIds("not-the-secret", "MERCHANT_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void unconfiguredToken_returns401_andDoesNotQuery() {
        // A blank expectedToken is a misconfiguration — the controller must
        // fail-CLOSED rather than fail-open (it never matches an attacker's
        // empty string by accident).
        UserRepository repo = mock(UserRepository.class);
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, "");

        ResponseEntity<?> resp = controller.assignedMerchantIds(TOKEN, "MERCHANT_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(repo);
    }

    @Test
    void unknownRole_returns400() {
        UserRepository repo = mock(UserRepository.class);
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, TOKEN);

        ResponseEntity<?> resp = controller.assignedMerchantIds(TOKEN, "MAYOR_OF_MARS");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(repo);
    }

    @Test
    void emptyResult_returns200_withEmptyList() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findDistinctLoyaltyMerchantIdsByRole(User.Role.MERCHANT_ADMIN))
                .thenReturn(List.of());
        InternalMerchantAssignmentController controller =
                new InternalMerchantAssignmentController(repo, TOKEN);

        ResponseEntity<?> resp = controller.assignedMerchantIds(TOKEN, "MERCHANT_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(resp)).isEmpty();
    }
}
