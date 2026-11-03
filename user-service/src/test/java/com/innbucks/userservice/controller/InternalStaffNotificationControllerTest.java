package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.notification.StaffNotificationService;
import com.innbucks.userservice.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code POST /users/internal/staff-notifications} (event-service V17): the
 * internal token first, then a known type, then the text. Each refusal is a
 * specific status (CLAUDE.md: never is4xxClientError).
 */
class InternalStaffNotificationControllerTest {

    private static final String TOKEN = "the-shared-secret";

    private final StaffNotificationService service = mock(StaffNotificationService.class);
    private final InternalStaffNotificationController controller = new InternalStaffNotificationController(
            new InternalTokenAuthorizer(TOKEN, mock(AuditService.class)), service);

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/staff-notifications");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    private static InternalStaffNotificationController.StaffNotifyRequest body(String type, String subject,
                                                                              String message) {
        return new InternalStaffNotificationController.StaffNotifyRequest(type, subject, message, "WARNING",
                "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", "Harare Runners Club", "EVENT",
                "4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11", "/ticketing/events/4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11");
    }

    @Test
    void aKnownNotice_isPassedOn_andAnswers202WithTheCount() {
        when(service.notifyStaff(any())).thenReturn(3);

        ResponseEntity<?> resp = controller.notifyStaff(TOKEN,
                body("EVENT_SUBMITTED", "  Event awaiting approval: Pink Fun Run ", "Submitted for review."),
                request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(((ApiResult<?>) resp.getBody()).getData()).isEqualTo(Map.of("recipients", 3));
        ArgumentCaptor<StaffNotificationService.StaffNotice> notice =
                ArgumentCaptor.forClass(StaffNotificationService.StaffNotice.class);
        verify(service).notifyStaff(notice.capture());
        assertThat(notice.getValue().subject()).isEqualTo("Event awaiting approval: Pink Fun Run");
        assertThat(notice.getValue().subjectId()).isEqualTo("4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11");
    }

    @Test
    void noOrAWrongToken_is401_beforeAnythingElse() {
        assertThat(controller.notifyStaff(null, body("EVENT_SUBMITTED", "s", "m"), request()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.notifyStaff("wrong", null, request()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(service);
    }

    @Test
    void anUnknownType_isA400_soNoServiceCanMessageStaffAboutAnything() {
        assertThat(controller.notifyStaff(TOKEN, body("GENERAL", "s", "m"), request()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.notifyStaff(TOKEN, null, request()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(service);
    }

    @Test
    void aBlankOrOverlongSubjectOrMessage_isA400() {
        assertThat(controller.notifyStaff(TOKEN, body("EVENT_PUBLISHED", " ", "m"), request()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.notifyStaff(TOKEN, body("EVENT_PUBLISHED", "s", null), request()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.notifyStaff(TOKEN, body("EVENT_PUBLISHED", "x".repeat(201), "m"), request())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.notifyStaff(TOKEN, body("EVENT_PUBLISHED", "s", "x".repeat(2001)), request())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(service);
    }
}
