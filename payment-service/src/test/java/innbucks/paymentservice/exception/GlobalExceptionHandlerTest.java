package innbucks.paymentservice.exception;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit test against the handler — no Spring context. Proves:
 *   - bean-validation failures get rewrapped into the project's ApiResult
 *     envelope (the regression that motivated the handler: payment-service
 *     was falling through to Boot's /error page and shipping a stack trace),
 *   - field-level messages land in data.fields so the client can map them,
 *   - generic exceptions are reduced to a generic 500 body (no internals).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void methodArgumentNotValid_returnsApiResultEnvelopeWithFieldMap() throws Exception {
        // Build a real MethodArgumentNotValidException — its constructor needs
        // a MethodParameter and a BindingResult. Cheaper than booting MockMvc.
        var bindingResult = new BeanPropertyBindingResult(new ShopCheckoutRequest(), "shopCheckoutRequest");
        bindingResult.rejectValue("pointsAmount", "PositiveOrZero", "pointsAmount must be >= 0");
        bindingResult.rejectValue("shopId", "NotNull", "shopId is required");
        Method method = Probe.class.getDeclaredMethod("accept", ShopCheckoutRequest.class);
        var param = new org.springframework.core.MethodParameter(method, 0);
        var ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ApiResult<Map<String, String>>> resp = handler.handle(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        ApiResult<Map<String, String>> body = resp.getBody();
        assertNotNull(body);
        assertEquals("400 BAD_REQUEST", body.getCode());
        assertEquals("validation failed", body.getMessage());
        assertEquals("pointsAmount must be >= 0", body.getData().get("pointsAmount"));
        assertEquals("shopId is required", body.getData().get("shopId"));
    }

    @Test
    void badRequestException_returnsApiResultWithMessage() {
        // Typed BadRequestException replaces the previous IllegalArgumentException
        // 400-handler: only deliberate 4xx text from our code is echoed back to
        // the client. An accidental IAE from the JDK / a library no longer goes
        // through this handler — it falls to the sanitised 500 catch-all (see
        // accidentalIllegalArgument_fallsThroughToInternalError_noLeak below).
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new innbucks.paymentservice.exception.BadRequestException("bad mix"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("400 BAD_REQUEST", resp.getBody().getCode());
        assertEquals("bad mix", resp.getBody().getMessage());
    }

    @Test
    void accidentalIllegalArgument_fallsThroughToInternalError_noLeak() {
        // Old behaviour: any IllegalArgumentException (deliberate OR accidental
        // — e.g. Map.of with a null value, Objects.requireNonNull failures) was
        // mapped to 400 with the raw exception message, leaking internal text
        // to clients. After the typed-exception refactor, an accidental IAE
        // falls through to the generic Exception catch-all, which sanitises.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                (Exception) new IllegalArgumentException("internal: cannot put null into Map.of"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("internal error", resp.getBody().getMessage());
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("map.of"));
    }

    @Test
    void uncaughtException_returnsGenericInternalError_noLeak() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new RuntimeException("NullPointerException at com.foo.Bar.x line 42"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("500 INTERNAL_SERVER_ERROR", resp.getBody().getCode());
        // Critically: the body must NOT echo the underlying message, otherwise
        // it leaks internals (package names, frame info, etc.) to the client.
        assertEquals("internal error", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
        assertTrue(!resp.getBody().getMessage().contains("NullPointerException"));
    }

    // ---- audit #9 — typed-exception handlers for downstream-client failures ----

    @Test
    void loyaltyCheckoutException_preservesUpstream4xxStatus() {
        // A 422 "insufficient points" from loyalty-service must surface as 422
        // to our client, not a generic 500. Old behaviour (fall-through to
        // catch-all) would have hidden this in the 5xx bucket.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new LoyaltyServiceClient.LoyaltyCheckoutException("Insufficient points", 422));
        assertEquals(422, resp.getStatusCode().value());
        assertTrue(resp.getBody().getCode().startsWith("422 "),
                "code starts with status int; reason phrase is HttpStatus-version-dependent");
        assertEquals("Insufficient points", resp.getBody().getMessage());
    }

    @Test
    void loyaltyCheckoutException_preservesUpstream503Status() {
        // The "Unable to reach loyalty-service" path carries 503 — that must
        // come through to the client unchanged so LBs / clients can react.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new LoyaltyServiceClient.LoyaltyCheckoutException("Unable to reach loyalty-service", 503));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("Unable to reach loyalty-service", resp.getBody().getMessage());
    }

    @Test
    void loyaltyCheckoutException_unrecognisedStatusCodeFallsBackTo502() {
        // Synthetic / unknown codes (e.g. 0 from a connect-refused with no
        // HTTP semantics) must not crash HttpStatus.resolve — fall back to
        // 502 as the canonical "upstream is having a moment" code.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new LoyaltyServiceClient.LoyaltyCheckoutException("weird", 0));
        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
    }

    @Test
    void bookingConfirmationException_preservesUpstream4xxStatus() {
        // booking-service returns 400 for "Seat hold expired" / "Only PENDING
        // bookings can be confirmed" — those must reach our client as 400, not
        // 500. Used by /payments to surface the actual reason confirm failed.
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new BookingServiceClient.BookingConfirmationException("Seat hold expired", 400));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Seat hold expired", resp.getBody().getMessage());
    }

    @Test
    void bookingConfirmationException_503OnUnreachable_isPreserved() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(
                new BookingServiceClient.BookingConfirmationException(
                        "Unable to reach booking-service to confirm the booking", 503));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    // Dummy controller-method signature so MethodParameter has something to
    // bind to. The body is never invoked.
    @SuppressWarnings("unused")
    private static class Probe {
        void accept(ShopCheckoutRequest req) {}
    }
}
