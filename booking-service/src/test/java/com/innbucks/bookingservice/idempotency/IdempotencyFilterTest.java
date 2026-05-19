package com.innbucks.bookingservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of())
        );
    }

    @Test
    void replaysCachedResponse_onDuplicateKey_forSameUser() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store);
        StoredResponse cached = new StoredResponse(
                201, "application/json", "{\"confirmation\":\"INN-123\"}".getBytes());
        when(store.get(contains("user:alice@example.com")))
                .thenReturn(Optional.of(new IdempotencyEntry.Completed(cached)));

        authenticate("alice@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(201, res.getStatus());
        assertEquals("application/json", res.getContentType());
        assertEquals("{\"confirmation\":\"INN-123\"}", res.getContentAsString());
    }

    @Test
    void differentUsers_withSameKey_doNotShareCache() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);

        // User A makes the call first.
        authenticate("alice@example.com");
        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/bookings");
        reqA.addHeader("Idempotency-Key", "shared-key");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        filter.doFilterInternal(reqA, resA, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(201);
            http.setContentType("application/json");
            http.getWriter().write("{\"owner\":\"alice\"}");
        });
        assertEquals(201, resA.getStatus());

        // User B presents the same key — must NOT see alice's cached response.
        SecurityContextHolder.clearContext();
        authenticate("bob@example.com");
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/bookings");
        reqB.addHeader("Idempotency-Key", "shared-key");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicInteger executed = new AtomicInteger();
        filter.doFilterInternal(reqB, resB, (req, res) -> {
            executed.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(201);
            http.setContentType("application/json");
            http.getWriter().write("{\"owner\":\"bob\"}");
        });

        assertEquals(1, executed.get(), "bob's request must execute fresh, not replay alice");
        assertEquals(201, resB.getStatus());
        assertEquals("{\"owner\":\"bob\"}", resB.getContentAsString());
    }

    @Test
    void guestRequests_areScopedByClientIp() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);

        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/bookings");
        reqA.setRemoteAddr("10.0.0.1");
        reqA.addHeader("Idempotency-Key", "guest-key");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        filter.doFilterInternal(reqA, resA, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(201);
            http.getWriter().write("{\"ip\":\"10.0.0.1\"}");
        });

        // Same key, different IP — must not replay.
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/bookings");
        reqB.setRemoteAddr("10.0.0.2");
        reqB.addHeader("Idempotency-Key", "guest-key");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicInteger executed = new AtomicInteger();
        filter.doFilterInternal(reqB, resB, (req, res) -> {
            executed.incrementAndGet();
            ((HttpServletResponse) res).setStatus(201);
        });

        assertEquals(1, executed.get());
    }

    @Test
    void xForwardedFor_takesPrecedenceOverRemoteAddr() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);

        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/bookings");
        reqA.setRemoteAddr("10.0.0.1"); // gateway IP
        reqA.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        reqA.addHeader("Idempotency-Key", "guest-key");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        filter.doFilterInternal(reqA, resA, (req, res) -> ((HttpServletResponse) res).setStatus(201));

        // Second guest with same key but different real IP behind the same gateway.
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/bookings");
        reqB.setRemoteAddr("10.0.0.1");
        reqB.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
        reqB.addHeader("Idempotency-Key", "guest-key");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicInteger executed = new AtomicInteger();
        filter.doFilterInternal(reqB, resB, (req, res) -> {
            executed.incrementAndGet();
            ((HttpServletResponse) res).setStatus(201);
        });

        assertEquals(1, executed.get(), "different real-client IPs must not share a scope");
    }

    @Test
    void cachesResponse_onFirstCallAndReplaysOnSecond() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        AtomicInteger callCount = new AtomicInteger();

        authenticate("alice@example.com");
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/bookings");
        req1.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, (req, res) -> {
            callCount.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(201);
            http.setContentType("application/json");
            http.getWriter().write("{\"id\":42}");
        });
        assertEquals(1, callCount.get());
        assertEquals(201, res1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/bookings");
        req2.addHeader("Idempotency-Key", "xyz-789");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, (req, res) ->
                fail("second call should have been replayed, not executed"));

        assertEquals(1, callCount.get(), "downstream should have been invoked exactly once");
        assertEquals(201, res2.getStatus());
        assertEquals("{\"id\":42}", res2.getContentAsString());
    }

    @Test
    void returns409_whenAnotherRequestWithSameKeyIsInProgress() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.of(new IdempotencyEntry.Reserved()));
        IdempotencyFilter filter = new IdempotencyFilter(store);

        authenticate("alice@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "race-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(409, res.getStatus());
        assertEquals("5", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("REQUEST_IN_PROGRESS"));
    }

    @Test
    void returns409_whenReservationLostBetweenGetAndTryReserve() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.empty());
        when(store.tryReserve(any(), anyLong())).thenReturn(false);
        IdempotencyFilter filter = new IdempotencyFilter(store);

        authenticate("alice@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "race-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(409, res.getStatus());
        assertTrue(res.getContentAsString().contains("REQUEST_IN_PROGRESS"));
    }

    @Test
    void doesNotCache_whenResponseIsNot2xx_andReleasesReservation() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.empty());
        when(store.tryReserve(any(), anyLong())).thenReturn(true);
        IdempotencyFilter filter = new IdempotencyFilter(store);

        authenticate("alice@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "fail-key");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (request, response) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(400);
            http.getWriter().write("{\"error\":\"bad\"}");
        });

        verify(store, never()).put(any(), any(), anyLong());
        verify(store).release(any());
    }

    @Test
    void releasesReservation_whenDownstreamThrows() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.get(any())).thenReturn(Optional.empty());
        when(store.tryReserve(any(), anyLong())).thenReturn(true);
        IdempotencyFilter filter = new IdempotencyFilter(store);

        authenticate("alice@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/bookings");
        req.addHeader("Idempotency-Key", "boom-key");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThrows(RuntimeException.class, () ->
                filter.doFilterInternal(req, res, (request, response) -> {
                    throw new RuntimeException("downstream blew up");
                }));

        verify(store, never()).put(any(), any(), anyLong());
        verify(store).release(any());
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
