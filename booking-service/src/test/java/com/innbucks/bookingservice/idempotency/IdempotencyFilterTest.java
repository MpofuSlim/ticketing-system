package com.innbucks.bookingservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {

    @Test
    void replaysCachedResponse_onDuplicateKey() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store);
        StoredResponse cached = new StoredResponse(
                201, "application/json", "{\"confirmation\":\"INN-123\"}".getBytes());
        when(store.get("POST /bookings#abc-123")).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any()); // downstream skipped
        assertEquals(201, res.getStatus());
        assertEquals("application/json", res.getContentType());
        assertEquals("{\"confirmation\":\"INN-123\"}", res.getContentAsString());
    }

    @Test
    void cachesResponse_onFirstCallAndReplaysOnSecond() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        AtomicInteger callCount = new AtomicInteger();

        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/bookings");
        req1.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res1 = new MockHttpServletResponse();

        filter.doFilterInternal(req1, res1, (request, response) -> {
            callCount.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(201);
            http.setContentType("application/json");
            http.getWriter().write("{\"id\":42}");
        });
        assertEquals(1, callCount.get());
        assertEquals(201, res1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/bookings");
        req2.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res2 = new MockHttpServletResponse();

        filter.doFilterInternal(req2, res2, (request, response) ->
                fail("second call should have been replayed, not executed"));

        assertEquals(1, callCount.get(), "downstream should have been invoked exactly once");
        assertEquals(201, res2.getStatus());
        assertEquals("{\"id\":42}", res2.getContentAsString());
    }

    @Test
    void doesNotCache_whenResponseIsNot2xx() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.empty());
        IdempotencyFilter filter = new IdempotencyFilter(store);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
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

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/bookings/my");
        get.addHeader("Idempotency-Key", "should-be-ignored");
        assertTrue(filter.shouldNotFilter(get), "GET must bypass idempotency");

        MockHttpServletRequest postNoHeader = new MockHttpServletRequest("POST", "/bookings");
        assertTrue(filter.shouldNotFilter(postNoHeader), "POST without header must bypass");

        MockHttpServletRequest postWithHeader = new MockHttpServletRequest("POST", "/bookings");
        postWithHeader.addHeader("Idempotency-Key", "k1");
        assertFalse(filter.shouldNotFilter(postWithHeader));
    }
}
