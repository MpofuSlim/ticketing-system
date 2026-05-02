package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentControllerTest {

    private PaymentRequest paymentFor(UUID bookingId) {
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(bookingId);
        r.setAmount(new BigDecimal("100.00"));
        r.setCurrency("USD");
        r.setCardLast4("4242");
        return r;
    }

    private HttpServletRequest reqWithAuth(String header) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(header);
        return req;
    }

    @Test
    void processPayment_forwardsAuthHeaderToBookingServiceAndReturnsSuccessReceipt() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId), eq("Bearer abc.def.ghi"))).thenReturn(Map.of(
                "id", bookingId.toString(),
                "status", "CONFIRMED",
                "totalAmount", 100.00,
                "confirmationNumber", "INN-20260502-AB12CD"
        ));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client)
                .processPayment(paymentFor(bookingId), reqWithAuth("Bearer abc.def.ghi"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.SUCCESS, data.getStatus());
        assertEquals(bookingId, data.getBookingId());
        assertEquals(0, new BigDecimal("100.00").compareTo(data.getAmountPaid()));
        assertEquals("USD", data.getCurrency());
        assertEquals("4242", data.getCardLast4());
        assertEquals("INN-20260502-AB12CD", data.getConfirmationNumber());
        assertNotNull(data.getTransactionId());
        assertNotNull(data.getProcessedAt());
        verify(client).confirmBooking(eq(bookingId), eq("Bearer abc.def.ghi"));
    }

    @Test
    void processPayment_fallsBackToBookingTotalAmountWhenRequestOmitsAmount() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId), any())).thenReturn(Map.of(
                "totalAmount", 75.50,
                "confirmationNumber", "INN-X"
        ));

        PaymentRequest req = new PaymentRequest();
        req.setBookingId(bookingId);
        // amount intentionally null

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client)
                .processPayment(req, reqWithAuth(null));

        assertEquals(0, new BigDecimal("75.5").compareTo(resp.getBody().getData().getAmountPaid()));
    }

    @Test
    void processPayment_surfacesBookingServiceErrorMessageWhenConfirmRejected() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId), any()))
                .thenThrow(new BookingConfirmationException("Seat hold expired", 400));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client)
                .processPayment(paymentFor(bookingId), reqWithAuth("Bearer x"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Seat hold expired", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void processPayment_returns503EquivalentWhenBookingServiceIsUnreachable() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId), any()))
                .thenThrow(new BookingConfirmationException(
                        "Unable to reach booking-service to confirm the booking", 503));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client)
                .processPayment(paymentFor(bookingId), reqWithAuth(null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().toLowerCase().contains("booking-service"));
    }
}
