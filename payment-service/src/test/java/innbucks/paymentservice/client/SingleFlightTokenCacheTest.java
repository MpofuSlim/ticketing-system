package innbucks.paymentservice.client;

import innbucks.paymentservice.client.SingleFlightTokenCache.CachedToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the single-flight token cache that replaced {@code synchronized
 * currentToken(boolean)}: callers holding a usable token never wait on a slow
 * login, concurrent cold callers and concurrent 401s each cost ONE login, a
 * waiter is bounded, and a failed login is retried by the next caller.
 */
class SingleFlightTokenCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-03T08:00:00Z");
    private static final int THREADS = 16;

    private final MutableClock clock = new MutableClock(T0);
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /** A login that hands out tok-1, tok-2, … and can be held on a latch. */
    private static final class FakeLogin implements Supplier<CachedToken<String>> {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate;
        volatile RuntimeException failNext;
        private final Clock clock;

        FakeLogin(Clock clock) {
            this.clock = clock;
        }

        @Override
        public CachedToken<String> get() {
            int n = calls.incrementAndGet();
            entered.countDown();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NotificationDeliveryException("interrupted");
                }
            }
            RuntimeException fail = failNext;
            if (fail != null) {
                failNext = null;
                throw fail;
            }
            Instant now = clock.instant();
            return new CachedToken<>("tok-" + n, now.plusSeconds(270), now.plusSeconds(300));
        }
    }

    private SingleFlightTokenCache<String> cache(FakeLogin login, Duration maxWait) {
        return new SingleFlightTokenCache<>("Test API", login, maxWait, NotificationDeliveryException::new, clock);
    }

    private List<Future<String>> concurrently(int n, java.util.concurrent.Callable<String> call) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        return results;
    }

    @Test
    @DisplayName("callers holding a usable token never wait while another caller's refresh is blocked")
    void usableToken_neverWaitsOnABlockedRefresh() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofSeconds(30));
        assertThat(cache.get()).isEqualTo("tok-1");

        // Into the stale-while-refreshing window: past refreshAt, before expiresAt.
        clock.advance(Duration.ofSeconds(280));
        login.gate = new CountDownLatch(1);
        CountDownLatch leaderEntered = new CountDownLatch(1);
        Future<String> leader = pool.submit(() -> {
            leaderEntered.countDown();
            return cache.get();
        });
        leaderEntered.await();
        // wait until the leader is inside the (blocked) login
        while (login.calls.get() < 2) {
            Thread.onSpinWait();
        }

        long started = System.nanoTime();
        for (Future<String> f : concurrently(THREADS, cache::get)) {
            assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("tok-1");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(leader.isDone()).isFalse();

        login.gate.countDown();
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(cache.get()).isEqualTo("tok-2");
        assertThat(login.calls).hasValue(2);
    }

    @Test
    @DisplayName("N concurrent callers with no token cause exactly one login")
    void coldCallers_shareOneLogin() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        login.gate = new CountDownLatch(1);
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofSeconds(30));

        List<Future<String>> results = concurrently(THREADS, cache::get);
        login.entered.await();
        Thread.sleep(200); // let the others pile up behind the in-flight login
        login.gate.countDown();

        for (Future<String> f : results) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("tok-1");
        }
        assertThat(login.calls).hasValue(1);
    }

    @Test
    @DisplayName("N concurrent refreshes after a 401 cause exactly one login, and a late one reuses it")
    void concurrent401s_shareOneLogin() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofSeconds(30));
        String refused = cache.get();
        assertThat(refused).isEqualTo("tok-1");

        login.gate = new CountDownLatch(1);
        List<Future<String>> results = concurrently(THREADS, () -> cache.refreshAfterRejection(refused));
        while (login.calls.get() < 2) {
            Thread.onSpinWait();
        }
        // While the re-login runs, the refused token is no longer handed out.
        Future<String> plain = pool.submit(cache::get);
        Thread.sleep(200);
        assertThat(plain.isDone()).isFalse();
        login.gate.countDown();

        for (Future<String> f : results) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        }
        assertThat(plain.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(cache.refreshAfterRejection(refused)).isEqualTo("tok-2");
        assertThat(login.calls).hasValue(2);
    }

    @Test
    @DisplayName("a caller with no token waiting on a hung login gets the client's transient exception within the bound")
    void hungLogin_waiterIsBounded() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        login.gate = new CountDownLatch(1);
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofMillis(300));

        Future<String> leader = pool.submit(cache::get);
        login.entered.await();

        long started = System.nanoTime();
        assertThatThrownBy(cache::get)
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("Test API login did not complete within 300 ms");
        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(250)).isLessThan(Duration.ofSeconds(2));

        login.gate.countDown();
        assertThat(leader.get(2, TimeUnit.SECONDS)).isEqualTo("tok-1");
        assertThat(login.calls).hasValue(1);
    }

    @Test
    @DisplayName("a failed login reaches every waiter with its own type, caches nothing, and the next caller retries")
    void failedLogin_isRetriedByTheNextCaller() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        login.gate = new CountDownLatch(1);
        login.failNext = new NotificationDeliveryException("Notification API login failed: HTTP 503");
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofSeconds(30));

        Future<String> leader = pool.submit(cache::get);
        login.entered.await();
        Future<String> joiner = pool.submit(cache::get);
        Thread.sleep(100);
        login.gate.countDown();

        for (Future<String> f : List.of(leader, joiner)) {
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(NotificationDeliveryException.class)
                    .hasMessageContaining("HTTP 503");
        }
        assertThat(cache.peek()).isNull();

        login.gate = null;
        assertThat(cache.get()).isEqualTo("tok-2");
        assertThat(login.calls).hasValue(2);
    }

    @Test
    @DisplayName("a token past its hard expiry is never handed out, even while a refresh runs")
    void expiredToken_waitsForTheRefresh() throws Exception {
        FakeLogin login = new FakeLogin(clock);
        SingleFlightTokenCache<String> cache = cache(login, Duration.ofSeconds(30));
        cache.get();
        clock.advance(Duration.ofSeconds(301));

        login.gate = new CountDownLatch(1);
        Future<String> leader = pool.submit(cache::get);
        while (login.calls.get() < 2) {
            Thread.onSpinWait();
        }
        Future<String> joiner = pool.submit(cache::get);
        Thread.sleep(200);
        assertThat(joiner.isDone()).isFalse();
        login.gate.countDown();
        assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
        assertThat(joiner.get(5, TimeUnit.SECONDS)).isEqualTo("tok-2");
    }

    @Test
    void waitBound_isConnectPlusReadPlusMargin() {
        assertThat(SingleFlightTokenCache.waitBound(3000, 20000)).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void cachedToken_neverPrintsTheToken() {
        assertThat(new CachedToken<>("secret-token", T0, T0.plusSeconds(1)).toString())
                .doesNotContain("secret-token");
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
