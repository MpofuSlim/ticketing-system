package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The per-number mutex: the number's {@code customer_security_profiles} row, locked
 * for the rest of the caller's transaction. One phone per number makes a phone's
 * bind write the number's OTHER phones, so the transactions that bind, verify a
 * code or change the profile take it, and two phones finishing a sign-in together
 * serialise instead of both winning.
 *
 * <p><b>Call-site rules.</b> Take it as the transaction's FIRST statement that reads
 * the number's profile, challenges, tickets or phones, so nothing read afterwards is
 * stale: a profile already loaded is never refreshed by a lock (Hibernate throws on
 * the version-checked upgrade instead). Read the msisdn with a scalar query, never by
 * loading the entity first. Inside a locked transaction, use the returned entity and
 * never {@code DeviceSignInService.profile()}. Lock order is always the profile, then
 * tickets and challenges, then phones.
 *
 * <p><b>Bounded.</b> The wait is capped ({@code SET LOCAL lock_timeout}, which also
 * bounds every later lock wait in the transaction), so a burst on one number can never
 * queue the shared connection pool: a wait that runs out is a
 * {@code ConcurrencyFailureException}, answered 503 "try again shortly". The sign-in
 * decision itself ({@code POST /auth/client-service}) takes no lock on purpose: it is
 * unauthenticated, and its own row write ({@code touch()}) plus the device's
 * {@code @Version} already refuse a decision made on a row a bind just changed.
 */
@Component
public class NumberLock {

    /** OTP verify: the customer is waiting, and a concurrent bind is rare. */
    static final Duration VERIFY = Duration.ofMillis(500);
    /** The broker's login result: retried once on a timeout. */
    static final Duration BROKER = Duration.ofSeconds(2);
    /** Call-center changes to the profile. */
    static final Duration STAFF = Duration.ofSeconds(2);
    /** Best-effort bookkeeping (the channel a customer chose). */
    static final Duration CUSTOMER = Duration.ofSeconds(1);

    private final CustomerSecurityProfileRepository profiles;

    public NumberLock(CustomerSecurityProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** Creates the number's profile if needed and returns it locked. Requires an open transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerSecurityProfile lock(String msisdn, LocalDateTime now, Duration maxWait) {
        profiles.setLockTimeout(maxWait.toMillis() + "ms");
        profiles.insertIfAbsent(msisdn, now);
        return profiles.lockByMsisdn(msisdn).orElseThrow();
    }
}
