package com.innbucks.userservice.testsupport;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.service.TokenVersionBumper;

/**
 * A {@link TokenVersionBumper} for plain unit tests with no database: applies
 * each bump to the in-memory entity — the value the real one reads back from
 * {@code UPDATE ... RETURNING} — with the same guards (active, expected
 * version), and publishes through the given publisher exactly as production
 * does ({@link TokenVersionPublisher#publishAfterCommit}), so a test can verify
 * the published value on a mock publisher.
 *
 * <p>The SQL itself is pinned by {@code TokenVersionBumperTest} and the
 * Postgres-backed ITs; this double exists so the many service tests built
 * around mocked repositories can keep asserting "the version went from 3 to 4".
 */
public class InMemoryTokenVersionBumper extends TokenVersionBumper {

    private final TokenVersionPublisher publisher;

    public InMemoryTokenVersionBumper(TokenVersionPublisher publisher) {
        super(null, publisher);
        this.publisher = publisher;
    }

    @Override
    public long bump(User user) {
        return apply(user);
    }

    @Override
    public boolean bumpIfActive(User user) {
        if (!user.isActive()) return false;
        apply(user);
        return true;
    }

    @Override
    public boolean bumpIfCurrent(User user, long expected) {
        if (!user.isActive() || user.getTokenVersion() != expected) return false;
        apply(user);
        return true;
    }

    @Override
    public long deactivateAndBump(User user) {
        user.setActive(false);
        return apply(user);
    }

    private long apply(User user) {
        long next = user.getTokenVersion() + 1;
        user.setTokenVersion(next);
        if (publisher != null) {
            publisher.publishAfterCommit(user.getUserUuid(), next);
        }
        return next;
    }
}
