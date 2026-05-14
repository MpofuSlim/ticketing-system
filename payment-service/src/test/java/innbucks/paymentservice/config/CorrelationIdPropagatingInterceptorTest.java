package innbucks.paymentservice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdPropagatingInterceptorTest {

    private final CorrelationIdPropagatingInterceptor interceptor = new CorrelationIdPropagatingInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void copiesTraceIdFromMdcOntoOutboundRequest() throws IOException {
        String trace = UUID.randomUUID().toString();
        MDC.put(CorrelationIdFilter.MDC_KEY, trace);

        StubRequest req = new StubRequest();
        interceptor.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse(new byte[0], 200));

        assertEquals(trace, req.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    }

    @Test
    void noTraceInMdc_leavesHeaderUnset() throws IOException {
        StubRequest req = new StubRequest();
        interceptor.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse(new byte[0], 200));
        assertNull(req.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    }

    @Test
    void respectsExplicitlyPresetHeader() throws IOException {
        MDC.put(CorrelationIdFilter.MDC_KEY, "mdc-value");
        StubRequest req = new StubRequest();
        req.getHeaders().add(CorrelationIdFilter.HEADER, "pre-set");

        interceptor.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse(new byte[0], 200));

        assertEquals("pre-set", req.getHeaders().getFirst(CorrelationIdFilter.HEADER));
    }

    private static class StubRequest implements HttpRequest {
        private final HttpHeaders headers = new HttpHeaders();
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public java.net.URI getURI() { return URI.create("http://test/local"); }
        @Override public org.springframework.http.HttpMethod getMethod() { return org.springframework.http.HttpMethod.GET; }
        @Override public java.util.Map<String, Object> getAttributes() { return new java.util.HashMap<>(); }
    }

}
