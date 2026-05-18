package innbucks.paymentservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {

    /** SHA-256 hex of the bytes — same algorithm the filter uses internally. */
    private static String sha256(String body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(body.getBytes()));
    }

    private static MockHttpServletRequest postWithBody(String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContentType("application/json");
        req.setContent(body.getBytes());
        return req;
    }

    @Test
    void replaysCachedResponse_whenKeyAndBodyMatch() throws Exception {
        String body = "{\"shopId\":\"s1\",\"amount\":\"10.00\"}";
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store);
        StoredResponse cached = new StoredResponse(
                200, "application/json",
                "{\"transactionId\":\"f04f203f\"}".getBytes(),
                sha256(body));
        when(store.get("POST /payments/shop-checkout#abc-123")).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = postWithBody("/payments/shop-checkout", body);
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
    void returns422IdempotencyConflict_whenKeyReusedWithDifferentBody() throws Exception {
        // The Stripe-style contract: same key + different body must NOT
        // replay the cached response — that would let a careless client
        // reuse one key for "$1 transfer" and "$1000 transfer" and silently
        // get the $1 response back. 422 with errorCode=idempotency_conflict
        // matches the OradianMiddleware/IdempotencyService contract.
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store);
        StoredResponse cached = new StoredResponse(
                200, "application/json", "{\"x\":1}".getBytes(),
                sha256("{\"amount\":\"1.00\"}"));
        when(store.get(any())).thenReturn(Optional.of(cached));

        MockHttpServletRequest req = postWithBody("/payments/deposit", "{\"amount\":\"1000.00\"}");
        req.addHeader("Idempotency-Key", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(422, res.getStatus());
        assertTrue(res.getContentAsString().contains("idempotency_conflict"),
                "response body must carry errorCode=idempotency_conflict; was: " + res.getContentAsString());
    }

    @Test
    void cachesResponse_onFirstCallAndReplaysOnSecond_whenBodyMatches() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        AtomicInteger callCount = new AtomicInteger();

        String body = "{\"shopId\":\"s1\"}";
        MockHttpServletRequest req1 = postWithBody("/payments/shop-checkout", body);
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

        MockHttpServletRequest req2 = postWithBody("/payments/shop-checkout", body);
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

        MockHttpServletRequest req = postWithBody("/payments/shop-checkout", "{}");
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
    void shouldNotFilter_forSafeMethods() {
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/payments");
        get.addHeader("Idempotency-Key", "should-be-ignored");
        assertTrue(filter.shouldNotFilter(get), "GET must bypass idempotency");
    }

    @Test
    void shouldNotFilter_bypassesOptionalPath_whenHeaderIsMissing() {
        // /payments/shop-checkout is not in REQUIRED_PATHS — a missing
        // header is still legal there (existing FE behavior). Only the
        // money-movement paths (/payments/deposit, /payments/withdraw)
        // require the header.
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/payments/shop-checkout");
        assertTrue(filter.shouldNotFilter(req),
                "optional path POST without header must bypass");
    }

    @Test
    void shouldNotFilter_runsForRequiredPath_evenWhenHeaderIsMissing() {
        // Required paths always go through the filter so the missing-header
        // case can be 400'd. shouldNotFilter must NOT short-circuit them.
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));

        for (String path : IdempotencyFilter.REQUIRED_PATHS) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            assertFalse(filter.shouldNotFilter(req),
                    "required path " + path + " must run the filter even without the header");
        }
    }

    @Test
    void returns400_whenIdempotencyKeyMissingOnDepositPath() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));
        MockHttpServletRequest req = postWithBody("/payments/deposit", "{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(400, res.getStatus());
        assertTrue(res.getContentAsString().contains("idempotency_key_required"),
                "response must carry errorCode=idempotency_key_required");
    }

    @Test
    void returns400_whenIdempotencyKeyMissingOnWithdrawPath() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));
        MockHttpServletRequest req = postWithBody("/payments/withdraw", "{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(400, res.getStatus());
        assertTrue(res.getContentAsString().contains("idempotency_key_required"));
    }

    @Test
    void returns400_whenIdempotencyKeyIsBlankString() throws Exception {
        // A header present-but-blank is treated the same as missing — the
        // FE shouldn't get a free pass by sending "Idempotency-Key: ".
        IdempotencyFilter filter = new IdempotencyFilter(mock(IdempotencyStore.class));
        MockHttpServletRequest req = postWithBody("/payments/deposit", "{}");
        req.addHeader("Idempotency-Key", "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, mock(FilterChain.class));

        assertEquals(400, res.getStatus());
    }

    @Test
    void controllerCanStillReadBody_afterFilterHashesIt() throws Exception {
        // The filter wraps the request in CachedBodyHttpServletRequest so
        // its body read for SHA-256 doesn't consume the InputStream from
        // the downstream controller's @RequestBody Jackson reader. This
        // test pins that contract by asserting the body is still readable
        // from the wrapped request inside the doFilter callback.
        IdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store);
        String body = "{\"accountID\":\"A000001\",\"amount\":\"10.00\"}";

        MockHttpServletRequest req = postWithBody("/payments/withdraw", body);
        req.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicInteger reads = new AtomicInteger();
        filter.doFilterInternal(req, res, (request, response) -> {
            // Downstream reads the body — this would fail with empty bytes
            // if the filter had consumed the original InputStream without
            // the cached-body wrapper.
            String forwarded = new String(request.getInputStream().readAllBytes());
            assertEquals(body, forwarded);
            reads.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) response;
            http.setStatus(200);
            http.setContentType("application/json");
            http.getWriter().write("{\"ok\":true}");
        });

        assertEquals(1, reads.get());
    }
}
