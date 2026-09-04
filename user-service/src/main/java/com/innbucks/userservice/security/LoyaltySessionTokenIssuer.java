package com.innbucks.userservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mints the token a customer gets for proving, by OTP, that they hold a phone
 * number — the app customer's route to loyalty's AUTHENTICATED endpoints.
 *
 * <h2>Why this exists</h2>
 * The customer app authenticates against InnBucks, not against us, so its
 * customers have no user-service account and no password to log in with. Until
 * now their only route into loyalty was {@code /loyalty/public/**}: endpoints
 * that take a phone number in the body and never ask who is calling, so anyone
 * who guesses a number can spend that number's points. An OTP proves possession
 * of the SIM, which is the strongest phone-ownership proof available to us and
 * needs no cooperation from any third party.
 *
 * <h2>The token is INERT everywhere except loyalty — by construction</h2>
 * It is minted with an <b>EMPTY roles list</b>. Every other service in the fleet
 * gates customer endpoints on {@code hasRole('CUSTOMER')}, which a role-less
 * token fails, so booking, payment, event and seat reject it without needing a
 * single line of change. Loyalty alone recognises the {@code services} marker
 * below and grants {@code ROLE_CUSTOMER} for it.
 *
 * <p>That asymmetry is the whole safety argument, and it is why the roles list
 * must stay empty. Minting this with {@code roles: [CUSTOMER]} would silently
 * turn one SMS into a full passwordless login for the entire platform —
 * a far larger change than "let app customers spend their points", and not one
 * an OTP flow should make on its own.
 *
 * <p>Claims are deliberately minimal: no {@code tier}, no {@code verified}, no
 * {@code userId}. Loyalty reads none of them (nothing there gates on TIER_* or
 * VERIFIED), and an OTP proves phone possession — not KYC, not a tier, not an
 * identity in our own user table. The phone claim is the only thing that
 * matters, because loyalty's ownership checks (`requireCallerOwns` and friends)
 * compare against the caller's phone.
 *
 * <p><b>No {@code userId} means no server-side revocation</b> for this token:
 * loyalty's tokenVersion check is keyed by user UUID and simply doesn't fire
 * without one. The mitigation is the short TTL below rather than the denylist.
 */
@Component
@RequiredArgsConstructor
public class LoyaltySessionTokenIssuer {

    /**
     * The {@code services} marker loyalty looks for. Deliberately NOT the plain
     * {@code "loyalty"} a full staff/customer token may already carry — this
     * value means specifically "authenticated by OTP, phone-scoped, loyalty
     * only", and loyalty grants the customer role only for it.
     */
    public static final String LOYALTY_OTP_SCOPE = "loyalty-otp";

    private final JwtUtil jwtUtil;

    /**
     * Twelve hours by default — long enough that a customer is not re-texted a
     * code every time they open the app, short enough to bound the damage from
     * a stolen token, which is the only bound there is: with no {@code userId}
     * claim the fleet's tokenVersion revocation cannot reach this token, so its
     * lifetime IS its revocation story.
     *
     * <p>Deliberately NOT {@code jwt.expiration} (15 minutes). That value is
     * tuned for a session backed by a refresh token, and there is no refresh
     * path here: refreshing re-reads the user from the database, and these
     * customers have no user row. Reusing it would mean a fresh SMS every 15
     * minutes.
     */
    @Value("${loyalty.otp-session.ttl-seconds:43200}")
    private long ttlSeconds = 43200;

    /**
     * @param phoneNumber the E.164 phone the OTP just proved possession of —
     *                    ALWAYS the number the OTP was sent to and verified
     *                    against, never one supplied alongside it in a request
     *                    body. The caller must pass the normalized form.
     */
    public String issue(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required to issue a loyalty session token");
        }
        return jwtUtil.generateScopedPhoneToken(phoneNumber, LOYALTY_OTP_SCOPE, ttlSeconds * 1000L);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
