package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventChangeNotificationRequest;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Security tests for the internal event-change-notification endpoint. Per
 * CLAUDE.md, internal endpoints must reject an unauthenticated call with a
 * SPECIFIC code (401) and only run the work when the shared X-Internal-Token
 * matches — asserted here directly on the returned status, not via a vague
 * 4xx check that a Spring-Security 401 could mask.
 */
class BookingControllerInternalEndpointTest {

    private static final String TOKEN = "the-shared-secret";

    private EventChangeNotificationService notifications;
    private BookingController controller;

    @BeforeEach
    void setUp() {
        notifications = mock(EventChangeNotificationService.class);
        controller = new BookingController(
                mock(BookingService.class), mock(UserServiceClient.class), notifications);
        ReflectionTestUtils.setField(controller, "expectedInternalToken", TOKEN);
    }

    private EventChangeNotificationRequest body() {
        return new EventChangeNotificationRequest("CANCELLED", "Jazz Night", null, null);
    }

    @Test
    void validToken_accepts202_andTriggersBroadcast() {
        UUID eventId = UUID.randomUUID();

        ResponseEntity<ApiResult<Void>> resp =
                controller.notifyEventChange(eventId, TOKEN, body());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(notifications).broadcast(eventId, "CANCELLED", "Jazz Night", null, null);
    }

    @Test
    void missingToken_returns401_andDoesNotBroadcast() {
        ResponseEntity<ApiResult<Void>> resp =
                controller.notifyEventChange(UUID.randomUUID(), null, body());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notifications);
    }

    @Test
    void wrongToken_returns401_andDoesNotBroadcast() {
        ResponseEntity<ApiResult<Void>> resp =
                controller.notifyEventChange(UUID.randomUUID(), "not-the-secret", body());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notifications);
    }
}
