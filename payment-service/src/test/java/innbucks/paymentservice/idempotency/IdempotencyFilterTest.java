package innbucks.paymentservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {

    @Test
    void replaysCachedResponse_onDuplicateKey() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store);
        StoredResponse cached = new StoredResponse(
                200, "application/json", "{\"transactionId\":\"f04f203f\"}".getBytes());
        when(store.get("POST /payments/shop-checkout#abc-123")).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        req.addHeader("Idempotency-Key", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        // Critical: the downstream handler must NOT run on a replay — that's
        // the whole point. Without this, a retried /payments/shop-checkout
        // would credit + debit loyalty twice.
        verify(chain, never()).doFilter(any(), any());
        assertEquals(200, res.getStatus());
        assertEquals("application/json", res.getContentType());
        assertEquals("{\"transactionId\":\"f04f203f\"}", res.getContentAsString());
    }

    @Test
    void cachesResponse_onFirstCallAndReplaysOnSecond() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        AtomicInteger callCount = new AtomicInteger();

        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        req1.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res1 = new MockHttpServletResponse();

        filter.doFilterInternal(req1, res1, (request, response) -> {
            callCount.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.setContentType("application/json");
            http.getWriter().write("{\"pointsEarned\":1300}");
        });
        assertEquals(1, callCount.get());
        assertEquals(200, res1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        req2.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res2 = new MockHttpServletResponse();

        filter.doFilterInternal(req2, res2, (request, response) ->
                fail("second call should have been replayed, not executed"));

        assertEquals(1, callCount.get(), "downstream should have been invoked exactly once");
        assertEquals(200, res2.getStatus());
        assertEquals("{\"pointsEarned\":1300}", res2.getContentAsString());
    }

    @Test
    void doesNotCache_whenResponseIsNot2xx() throws Exception {
        // 4xx/5xx responses aren't cached — clients retrying after a transient
        // failure should be allowed to actually re-execute and (hopefully) get
        // a 2xx the second time. Caching a 400 would lock the client out.
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.empty());
        IdempotencyFilter filter = new IdempotencyFilter(store);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        req.addHeader("Idempotency-Key", "fail-key");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(400);
            http.getWriter().write("{\"error\":\"bad\"}");
        });

        verify(store, never()).put(any(), any(), anyLong());
    }

    @Test
    void shouldNotFilter_forSafeMethodsOrMissingHeader() {
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/payments");
        get.addHeader("Idempotency-Key", "should-be-ignored");
        assertTrue(filter.shouldNotFilter(get), "GET must bypass idempotency");

        MockHttpServletRequest postNoHeader = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        assertTrue(filter.shouldNotFilter(postNoHeader), "POST without header must bypass");

        MockHttpServletRequest postWithHeader = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        postWithHeader.addHeader("Idempotency-Key", "k1");
        assertFalse(filter.shouldNotFilter(postWithHeader));
    }
}
