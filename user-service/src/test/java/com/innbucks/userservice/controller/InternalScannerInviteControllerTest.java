package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.notification.ScannerInviteNotifier;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@code POST /users/internal/scanner-invites} (event-service V20): the
 * internal token first, then a valid phone. Each refusal is a specific status
 * (CLAUDE.md: never is4xxClientError), and no refusal reaches the notifier.
 */
class InternalScannerInviteControllerTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID SCANNER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");

    private final ScannerInviteNotifier notifier = mock(ScannerInviteNotifier.class);
    private final InternalScannerInviteController controller = new InternalScannerInviteController(
            new InternalTokenAuthorizer(TOKEN, mock(AuditService.class)), notifier, "ZW");

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/scanner-invites");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    private static InternalScannerInviteController.ScannerInviteRequest body(String phone) {
        return body(phone, null);
    }

    private static InternalScannerInviteController.ScannerInviteRequest body(String phone, List<String> roles) {
        return new InternalScannerInviteController.ScannerInviteRequest(phone, "Harare Arena Events",
                "Jazz on the Lawn", SCANNER, roles);
    }

    @Test
    void aValidRequest_isQueued_withThePhoneInE164() {
        ResponseEntity<?> resp = controller.send(TOKEN, body("0771234567"), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(((ApiResult<?>) resp.getBody()).getCode()).isEqualTo("202 ACCEPTED");
        // No roles = the V20 request = the gate-staff wording.
        verify(notifier).send("+263771234567", "Harare Arena Events", "Jazz on the Lawn", SCANNER,
                EnumSet.of(ScannerInviteNotifier.InviteRole.SCAN));
    }

    @Test
    void theRoles_pickTheWording() {
        controller.send(TOKEN, body("+263771234567", List.of("SELL")), request());
        verify(notifier).send("+263771234567", "Harare Arena Events", "Jazz on the Lawn", SCANNER,
                EnumSet.of(ScannerInviteNotifier.InviteRole.SELL));

        controller.send(TOKEN, body("+263771234567", List.of("SCAN", "SELL")), request());
        verify(notifier).send("+263771234567", "Harare Arena Events", "Jazz on the Lawn", SCANNER,
                EnumSet.of(ScannerInviteNotifier.InviteRole.SCAN, ScannerInviteNotifier.InviteRole.SELL));
    }

    @Test
    void noOrAWrongToken_is401_beforeAnythingElse() {
        assertThat(controller.send(null, body("+263771234567"), request()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.send("wrong", null, request()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notifier);
    }

    @Test
    void aMissingOrInvalidPhone_isA400_andSendsNothing() {
        for (InternalScannerInviteController.ScannerInviteRequest bad : new InternalScannerInviteController.ScannerInviteRequest[] {
                null, body(null), body(""), body("12345"), body("not a number")}) {
            ResponseEntity<?> resp = controller.send(TOKEN, bad, request());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((ApiResult<?>) resp.getBody()).getData()).isEqualTo(Map.of("errorCode", "invalid_phone"));
        }
        verifyNoInteractions(notifier);
    }
}
