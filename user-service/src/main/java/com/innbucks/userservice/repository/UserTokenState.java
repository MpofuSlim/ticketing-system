package com.innbucks.userservice.repository;

/**
 * The two columns that decide whether a session may still be used:
 * {@code users.token_version} (the session epoch every access token and
 * mfaToken is bound to) and {@code users.active}. Read together in one
 * projected query by {@link UserRepository#findTokenStateBySubject} and
 * {@link UserRepository#findTokenStateById}, so neither check costs a second
 * round-trip or an entity load.
 *
 * <p>Boxed components on purpose: the JPQL constructor expression hands the
 * constructor the attributes' wrapper types, and a boxed signature matches them
 * exactly rather than relying on primitive/wrapper equivalence. Both columns
 * are NOT NULL, so {@link #version()} and {@link #isActive()} never see a null.
 */
public record UserTokenState(Long tokenVersion, Boolean active) {

    /** The session epoch; 0 only for a row the database never populated. */
    public long version() {
        return tokenVersion == null ? 0L : tokenVersion;
    }

    /** True only for an explicitly active account — a null reads as inactive (fails closed). */
    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}
