package innbucks.paymentservice.exception;

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
    void illegalArgument_returnsApiResultWithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handle(new IllegalArgumentException("bad mix"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("400 BAD_REQUEST", resp.getBody().getCode());
        assertEquals("bad mix", resp.getBody().getMessage());
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

    // Dummy controller-method signature so MethodParameter has something to
    // bind to. The body is never invoked.
    @SuppressWarnings("unused")
    private static class Probe {
        void accept(ShopCheckoutRequest req) {}
    }
}
