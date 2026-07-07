package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.EventChangeNotificationRequest;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private BookingService bookingService;
    private BookingController controller;

    @BeforeEach
    void setUp() {
        notifications = mock(EventChangeNotificationService.class);
        bookingService = mock(BookingService.class);
        controller = new BookingController(
                bookingService, mock(UserServiceClient.class), notifications);
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

    @Test
    void internalGet_validToken_returnsBooking_ownershipBypassed() {
        UUID id = UUID.randomUUID();
        BookingResponseDTO dto = new BookingResponseDTO();
        dto.setId(id);
        dto.setPhoneNumber("+263782606983");
        dto.setTotalAmount(new BigDecimal("40.00"));
        // isAdmin=true + null userEmail = no ownership check (trusted S2S caller).
        when(bookingService.getBookingById(eq(id), isNull(), eq(true))).thenReturn(dto);

        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller.getBookingByIdInternal(id, TOKEN);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getPhoneNumber()).isEqualTo("+263782606983");
        assertThat(resp.getBody().getData().getTotalAmount()).isEqualByComparingTo("40.00");
        verify(bookingService).getBookingById(id, null, true);
    }

    @Test
    void internalGet_missingToken_returns401_andNeverReadsTheBooking() {
        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller.getBookingByIdInternal(UUID.randomUUID(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void internalGet_wrongToken_returns401_andNeverReadsTheBooking() {
        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller.getBookingByIdInternal(UUID.randomUUID(), "not-the-secret");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void extendHold_validToken_delegatesWithTheRequestedDeadline() {
        UUID id = UUID.randomUUID();
        java.time.LocalDateTime until = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(13);
        BookingResponseDTO dto = new BookingResponseDTO();
        dto.setId(id);
        when(bookingService.extendHold(id, until)).thenReturn(dto);

        ResponseEntity<ApiResult<BookingResponseDTO>> resp = controller.extendHoldInternal(
                id, TOKEN, new com.innbucks.bookingservice.dto.ExtendHoldRequestDTO(until));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).extendHold(id, until);
    }

    @Test
    void extendHold_missingToken_401_neverTouchesTheBooking() {
        ResponseEntity<ApiResult<BookingResponseDTO>> resp = controller.extendHoldInternal(
                UUID.randomUUID(), null,
                new com.innbucks.bookingservice.dto.ExtendHoldRequestDTO(
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void extendHold_wrongToken_401() {
        ResponseEntity<ApiResult<BookingResponseDTO>> resp = controller.extendHoldInternal(
                UUID.randomUUID(), "not-the-secret",
                new com.innbucks.bookingservice.dto.ExtendHoldRequestDTO(
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void extendHold_missingHoldUntil_400() {
        ResponseEntity<ApiResult<BookingResponseDTO>> resp = controller.extendHoldInternal(
                UUID.randomUUID(), TOKEN, new com.innbucks.bookingservice.dto.ExtendHoldRequestDTO(null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(bookingService);
    }

    // --- A01: active-counts reads are now internal-token gated (were public) --

    @Test
    void activeCounts_validToken_returnsCounts() {
        when(bookingService.getActiveItemCountsByEvents(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new com.innbucks.bookingservice.dto.EventActiveCountDTO()));

        ResponseEntity<ApiResult<java.util.List<com.innbucks.bookingservice.dto.EventActiveCountDTO>>> resp =
                controller.getActiveCounts(java.util.List.of(UUID.randomUUID()), TOKEN);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).getActiveItemCountsByEvents(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeCounts_missingToken_returns401_andNeverReads() {
        ResponseEntity<ApiResult<java.util.List<com.innbucks.bookingservice.dto.EventActiveCountDTO>>> resp =
                controller.getActiveCounts(java.util.List.of(UUID.randomUUID()), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void activeCounts_wrongToken_returns401_andNeverReads() {
        ResponseEntity<ApiResult<java.util.List<com.innbucks.bookingservice.dto.EventActiveCountDTO>>> resp =
                controller.getActiveCounts(java.util.List.of(UUID.randomUUID()), "not-the-secret");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }

    @Test
    void categoryActiveCounts_validToken_returnsCounts() {
        when(bookingService.getActiveItemCountsByCategories(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new com.innbucks.bookingservice.dto.CategoryActiveCountDTO()));

        ResponseEntity<ApiResult<java.util.List<com.innbucks.bookingservice.dto.CategoryActiveCountDTO>>> resp =
                controller.getCategoryActiveCounts(java.util.List.of(UUID.randomUUID()), TOKEN);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).getActiveItemCountsByCategories(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void categoryActiveCounts_missingToken_returns401_andNeverReads() {
        ResponseEntity<ApiResult<java.util.List<com.innbucks.bookingservice.dto.CategoryActiveCountDTO>>> resp =
                controller.getCategoryActiveCounts(java.util.List.of(UUID.randomUUID()), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(bookingService);
    }
}
