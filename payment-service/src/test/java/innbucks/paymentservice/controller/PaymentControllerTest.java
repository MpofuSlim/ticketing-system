package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient.LoyaltyCheckoutException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.dto.ShopCheckoutResponse;
import org.junit.jupiter.api.Test;
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

    // Fresh registry per controller so tests can assert counters without
    // bleeding state across @Test methods.
    private static PaymentMetrics newMetrics() {
        return new PaymentMetrics(new SimpleMeterRegistry());
    }

    private PaymentRequest paymentFor(UUID bookingId) {
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(bookingId);
        r.setCurrency("USD");
        r.setCardLast4("4242");
        return r;
    }

    @Test
    void processPayment_usesBookingTotalAmountForReceipt() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId))).thenReturn(Map.of(
                "id", bookingId.toString(),
                "status", "CONFIRMED",
                "totalAmount", 100.00,
                "confirmationNumber", "INN-20260502-AB12CD"
        ));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client, mock(innbucks.paymentservice.client.LoyaltyServiceClient.class), newMetrics())
                .processPayment(paymentFor(bookingId));

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
        verify(client).confirmBooking(eq(bookingId));
    }

    @Test
    void processPayment_defaultsCurrencyToUsdWhenRequestOmitsIt() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId))).thenReturn(Map.of(
                "totalAmount", 50.00,
                "confirmationNumber", "INN-Y"
        ));

        PaymentRequest req = new PaymentRequest();
        req.setBookingId(bookingId);

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client, mock(innbucks.paymentservice.client.LoyaltyServiceClient.class), newMetrics())
                .processPayment(req);

        assertEquals("USD", resp.getBody().getData().getCurrency());
    }

    @Test
    void processPayment_handlesBookingsWithFractionalTotalAmount() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId))).thenReturn(Map.of(
                "totalAmount", 75.50,
                "confirmationNumber", "INN-X"
        ));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client, mock(innbucks.paymentservice.client.LoyaltyServiceClient.class), newMetrics())
                .processPayment(paymentFor(bookingId));

        assertEquals(0, new BigDecimal("75.5").compareTo(resp.getBody().getData().getAmountPaid()));
    }

    @Test
    void processPayment_surfacesBookingServiceErrorMessageWhenConfirmRejected() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId)))
                .thenThrow(new BookingConfirmationException("Seat hold expired", 400));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client, mock(innbucks.paymentservice.client.LoyaltyServiceClient.class), newMetrics())
                .processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Seat hold expired", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    private ShopCheckoutRequest cashAndPoints(UUID shopId) {
        ShopCheckoutRequest r = new ShopCheckoutRequest();
        r.setShopId(shopId);
        r.setMsisdn("0712345678");
        r.setPaymentMethod(PaymentMethod.CASH_AND_POINTS);
        r.setCashAmount(new BigDecimal("10.00"));
        r.setPointsAmount(new BigDecimal("200.0000"));
        return r;
    }

    @Test
    void shopCheckout_cashOnly_returnsEarnedPoints() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(loyalty.shopCheckout(eq(shopId), eq("0712345678"),
                eq(new BigDecimal("10.00")), eq(null), eq(null)))
                .thenReturn(new LoyaltyServiceClient.CheckoutResult(
                        shopId, merchantId, UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("10.00"), BigDecimal.ZERO,
                        new BigDecimal("12.5000"), new BigDecimal("1812.5000"),
                        UUID.randomUUID(), null));

        ShopCheckoutRequest req = new ShopCheckoutRequest();
        req.setShopId(shopId);
        req.setMsisdn("0712345678");
        req.setPaymentMethod(PaymentMethod.CASH);
        req.setCashAmount(new BigDecimal("10.00"));

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(BookingServiceClient.class), loyalty, newMetrics()).shopCheckout(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ShopCheckoutResponse data = resp.getBody().getData();
        assertEquals(PaymentMethod.CASH, data.getPaymentMethod());
        assertEquals(0, new BigDecimal("12.5000").compareTo(data.getPointsEarned()));
        assertEquals(0, BigDecimal.ZERO.compareTo(data.getPointsRedeemed()));
        assertEquals(merchantId, data.getMerchantId());
    }

    @Test
    void shopCheckout_cashAndPoints_returnsBothLegs() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        UUID shopId = UUID.randomUUID();
        when(loyalty.shopCheckout(eq(shopId), eq("0712345678"),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("200.0000")), eq(null)))
                .thenReturn(new LoyaltyServiceClient.CheckoutResult(
                        shopId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("10.00"), new BigDecimal("200.0000"),
                        new BigDecimal("12.5000"), new BigDecimal("1612.5000"),
                        UUID.randomUUID(), UUID.randomUUID()));

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(BookingServiceClient.class), loyalty, newMetrics()).shopCheckout(cashAndPoints(shopId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ShopCheckoutResponse data = resp.getBody().getData();
        assertEquals(0, new BigDecimal("200.0000").compareTo(data.getPointsRedeemed()));
        assertEquals(0, new BigDecimal("12.5000").compareTo(data.getPointsEarned()));
        assertEquals(0, new BigDecimal("1612.5000").compareTo(data.getWalletBalanceAfter()));
    }

    @Test
    void shopCheckout_rejectsCashMethodWithPointsAmount() {
        ShopCheckoutRequest req = new ShopCheckoutRequest();
        req.setShopId(UUID.randomUUID());
        req.setMsisdn("0712345678");
        req.setPaymentMethod(PaymentMethod.CASH);
        req.setCashAmount(new BigDecimal("10.00"));
        req.setPointsAmount(new BigDecimal("200")); // illegal mix

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(BookingServiceClient.class), mock(LoyaltyServiceClient.class), newMetrics()).shopCheckout(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("CASH"));
    }

    @Test
    void shopCheckout_rejectsPointsMethodWithoutPointsAmount() {
        ShopCheckoutRequest req = new ShopCheckoutRequest();
        req.setShopId(UUID.randomUUID());
        req.setMsisdn("0712345678");
        req.setPaymentMethod(PaymentMethod.POINTS);
        // no pointsAmount → invalid

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(BookingServiceClient.class), mock(LoyaltyServiceClient.class), newMetrics()).shopCheckout(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void shopCheckout_surfacesLoyaltyServiceErrorVerbatim() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenThrow(new LoyaltyCheckoutException("merchant is not active; no loyalty operations will run", 400));

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(BookingServiceClient.class), loyalty, newMetrics()).shopCheckout(cashAndPoints(UUID.randomUUID()));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("merchant is not active"));
    }

    @Test
    void shopCheckout_successIncrementsSuccessOutcomeCounter() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        UUID shopId = UUID.randomUUID();
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenReturn(new LoyaltyServiceClient.CheckoutResult(
                        shopId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("10"), new BigDecimal("200"),
                        new BigDecimal("12.5"), new BigDecimal("1612.5"),
                        UUID.randomUUID(), UUID.randomUUID()));
        PaymentMetrics m = newMetrics();
        new PaymentController(mock(BookingServiceClient.class), loyalty, m)
                .shopCheckout(cashAndPoints(shopId));

        double success = m.shopCheckoutDuration().count() > 0
                ? counter(m, "outcome", "success", "mode", "mixed") : 0;
        assertEquals(1.0, success);
        assertEquals(1L, m.shopCheckoutDuration().count());
    }

    @Test
    void shopCheckout_loyaltyDownIncrementsUnavailableCounter() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenThrow(new LoyaltyCheckoutException("Unable to reach loyalty-service for checkout", 503));
        PaymentMetrics m = newMetrics();
        new PaymentController(mock(BookingServiceClient.class), loyalty, m)
                .shopCheckout(cashAndPoints(UUID.randomUUID()));

        assertEquals(1.0, counter(m, "outcome", "loyalty_unavailable", "mode", "mixed"));
    }

    @Test
    void shopCheckout_validationFailureIncrementsValidationFailedCounter() {
        ShopCheckoutRequest req = new ShopCheckoutRequest();
        req.setShopId(UUID.randomUUID());
        req.setMsisdn("0712345678");
        req.setPaymentMethod(PaymentMethod.CASH);
        req.setCashAmount(new BigDecimal("10"));
        req.setPointsAmount(new BigDecimal("200"));
        PaymentMetrics m = newMetrics();
        new PaymentController(mock(BookingServiceClient.class), mock(LoyaltyServiceClient.class), m)
                .shopCheckout(req);

        assertEquals(1.0, counter(m, "outcome", "validation_failed", "mode", "cash"));
    }

    private static double counter(PaymentMetrics m, String... tags) {
        // Drill into the SimpleMeterRegistry behind the PaymentMetrics to read
        // payment.shop_checkout{tags...} without going through Prometheus serialization.
        var registry = (io.micrometer.core.instrument.MeterRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(m, "registry");
        return registry.find("payment.shop_checkout").tags(tags).counter().count();
    }

    @Test
    void processPayment_returns503EquivalentWhenBookingServiceIsUnreachable() {
        BookingServiceClient client = mock(BookingServiceClient.class);
        UUID bookingId = UUID.randomUUID();
        when(client.confirmBooking(eq(bookingId)))
                .thenThrow(new BookingConfirmationException(
                        "Unable to reach booking-service to confirm the booking", 503));

        ResponseEntity<ApiResult<PaymentResponse>> resp = new PaymentController(client, mock(innbucks.paymentservice.client.LoyaltyServiceClient.class), newMetrics())
                .processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().toLowerCase().contains("booking-service"));
    }
}
