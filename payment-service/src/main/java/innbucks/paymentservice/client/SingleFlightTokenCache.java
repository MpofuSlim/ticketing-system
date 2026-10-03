package innbucks.paymentservice.client;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A cached upstream access token whose refresh is SINGLE-FLIGHT and never held
 * under a lock across the network call.
 *
 * <p>It replaces {@code private synchronized String currentToken(boolean force)},
 * which held the client's monitor for the whole login: one slow login (about
 * 19 s measured against the InnBucks notification API) blocked every sender in
 * the service, including the ones holding a perfectly good token, and it would
 * pin a carrier thread if virtual threads were ever enabled. The rules:
 *
 * <ol>
 *   <li><b>Fast path, no lock.</b> A token before its {@code refreshAt} is
 *       returned straight from an {@link AtomicReference}.</li>
 *   <li><b>Single flight.</b> At most one login runs per cache. The
 *       {@link ReentrantLock} is held only to start or join that login's
 *       {@link CompletableFuture}, never while it runs. The caller that starts
 *       it runs it on its own thread.</li>
 *   <li><b>Stale-while-refreshing.</b> Past {@code refreshAt} but before
 *       {@code expiresAt}, a caller that does not start the refresh returns the
 *       current token at once.</li>
 *   <li><b>Bounded wait.</b> A caller with no usable token joins the login for at
 *       most {@code maxWait} (the login's connect + read timeout + a margin, see
 *       {@link #waitBound}) and then gets the client's own transient exception,
 *       the same failure it sees today when the upstream is down.</li>
 *   <li><b>No stampede after a 401.</b> {@link #refreshAfterRejection} logs in
 *       again only if the cached token is still the one that was refused;
 *       otherwise it returns the newer one. N concurrent 401s cost one login.</li>
 *   <li><b>A failed login</b> fails its future (every joiner gets the same
 *       exception the starter got), frees the slot so the next caller retries,
 *       and caches nothing.</li>
 * </ol>
 *
 * <p>The login supplier owns all logging of the login itself; this class never
 * logs a token or a credential ({@link CachedToken#toString} withholds the value).
 * Services share no code, so each module that needs this keeps its own copy.
 *
 * @param <T> the token type; compared with {@code equals} to recognise the one
 *            a 401 refused
 */
@Slf4j
public final class SingleFlightTokenCache<T> {

    /** Allowance on top of the login's own connect + read timeouts. */
    public static final Duration WAIT_MARGIN = Duration.ofSeconds(2);

    /**
     * A token and its two deadlines: {@code refreshAt} is where a refresh
     * starts, {@code expiresAt} the last instant it may still be handed out.
     */
    public record CachedToken<T>(T value, Instant refreshAt, Instant expiresAt) {
        public CachedToken {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(refreshAt, "refreshAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (refreshAt.isAfter(expiresAt)) {
                refreshAt = expiresAt;
            }
        }

        @Override
        public String toString() {
            return "CachedToken[refreshAt=" + refreshAt + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final String name;
    private final Supplier<CachedToken<T>> login;
    private final Duration maxWait;
    private final Function<String, ? extends RuntimeException> unavailable;
    private final Clock clock;

    private final AtomicReference<CachedToken<T>> cached = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** The login in flight, if any. Guarded by {@link #lock}. */
    private CompletableFuture<CachedToken<T>> inFlight;

    /**
     * @param name        upstream name for log lines and exception messages
     * @param login       performs one login; throws the client's own exception types
     * @param maxWait     how long a caller with no usable token waits for someone
     *                    else's login (see {@link #waitBound})
     * @param unavailable builds the client's transient exception for a wait that
     *                    runs out
     * @param clock       time source for both deadlines
     */
    public SingleFlightTokenCache(String name, Supplier<CachedToken<T>> login, Duration maxWait,
                                  Function<String, ? extends RuntimeException> unavailable, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.login = Objects.requireNonNull(login, "login");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
        this.unavailable = Objects.requireNonNull(unavailable, "unavailable");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The wait bound for a login whose HTTP client has these timeouts. */
    public static Duration waitBound(long connectTimeoutMs, long readTimeoutMs) {
        return Duration.ofMillis(Math.max(0, connectTimeoutMs) + Math.max(0, readTimeoutMs)).plus(WAIT_MARGIN);
    }

    /** A usable token: the cached one, or one from a (possibly shared) login. */
    public T get() {
        CachedToken<T> current = cached.get();
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        return acquire(null);
    }

    /**
     * A token to replay with after the upstream refused {@code rejected} (a
     * 401). Logs in again only if {@code rejected} is still the cached token.
     */
    public T refreshAfterRejection(T rejected) {
        return acquire(Objects.requireNonNull(rejected, "rejected"));
    }

    /** Drops the cached token; the next caller logs in. */
    public void invalidate() {
        cached.set(null);
    }

    /** The cached entry, for tests. */
    CachedToken<T> peek() {
        return cached.get();
    }

    private T acquire(T rejected) {
        CompletableFuture<CachedToken<T>> flight;
        CachedToken<T> current;
        boolean leader = false;
        lock.lock();
        try {
            Instant now = clock.instant();
            current = cached.get();
            if (rejected == null) {
                if (current != null && now.isBefore(current.refreshAt())) {
                    return current.value();
                }
            } else if (current != null && !current.value().equals(rejected)) {
                // Someone already replaced the refused token: use theirs.
                if (now.isBefore(current.expiresAt())) {
                    return current.value();
                }
            } else if (current != null) {
                // The cached token is the one upstream just refused: never hand it out again.
                cached.compareAndSet(current, null);
                current = null;
            }
            flight = inFlight;
            if (flight == null) {
                flight = new CompletableFuture<>();
                inFlight = flight;
                leader = true;
            }
        } finally {
            lock.unlock();
        }

        if (leader) {
            return lead(flight).value();
        }
        if (rejected == null && current != null && clock.instant().isBefore(current.expiresAt())) {
            return current.value();
        }
        return await(flight).value();
    }

    private CachedToken<T> lead(CompletableFuture<CachedToken<T>> flight) {
        CachedToken<T> fresh;
        try {
            fresh = Objects.requireNonNull(login.get(), "login returned no token");
        } catch (RuntimeException | Error e) {
            release(flight);
            flight.completeExceptionally(e);
            throw e;
        }
        cached.set(fresh);
        release(flight);
        flight.complete(fresh);
        return fresh;
    }

    private void release(CompletableFuture<CachedToken<T>> flight) {
        lock.lock();
        try {
            if (inFlight == flight) {
                inFlight = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private CachedToken<T> await(CompletableFuture<CachedToken<T>> flight) {
        try {
            return flight.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw unavailable.apply(name + " login failed");
        } catch (TimeoutException e) {
            log.warn("{} login still in flight after {} ms; failing this call instead of waiting longer",
                    name, maxWait.toMillis());
            throw unavailable.apply(name + " login did not complete within " + maxWait.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable.apply("Interrupted while waiting for the " + name + " login");
        }
    }
}
