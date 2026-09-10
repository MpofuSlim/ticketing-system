package com.innbucks.userservice.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the verification contract for a federated-login assertion — the loyalty
 * {@code RegistrationAssertionVerifierTest} carried across with the user-service
 * property names, plus the one case that is new here: loyalty's own registration
 * audience must NOT log a customer in.
 *
 * <p>Pure JUnit — no Spring context. Every rejection case here is a way an
 * attacker could otherwise obtain a customer session without the middleware's
 * private key.
 */
public class FederationAssertionVerifierTest {

    private static final String ISSUER = "innbucks-middleware";
    private static final String AUDIENCE = "innbucks-foundry";
    private static final String PHONE = "+263771234567";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
    }

    public static String pem(java.security.PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static FederationAssertionVerifier verifier() {
        FederationAssertionVerifier v = new FederationAssertionVerifier(
                pem(keyPair.getPublic()), "", ISSUER, AUDIENCE, 300);
        v.parseKeys();
        return v;
    }

    public static String assertion(java.security.PrivateKey signWith, String subject, String issuer,
                                   String audience, Instant issuedAt, long ttlSeconds, String jti) {
        var builder = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlSeconds)));
        if (subject != null) builder.subject(subject);
        if (jti != null) builder.id(jti);
        return builder.signWith(signWith, Jwts.SIG.RS256).compact();
    }

    private static String validAssertion() {
        return assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");
    }

    @Test
    @DisplayName("a well-formed assertion yields the phone, its issue and expiry times and its jti")
    void validAssertion_isAccepted() {
        var verified = verifier().verify(validAssertion());

        assertThat(verified.phoneNumber()).isEqualTo(PHONE);
        assertThat(verified.jti()).isEqualTo("jti-1");
        assertThat(verified.assertedAt()).isNotNull();
        assertThat(verified.expiresAt()).isAfter(verified.assertedAt());
    }

    @Test
    @DisplayName("a token signed by a DIFFERENT key is refused")
    void wrongSigningKey_isRefused() {
        String forged = assertion(otherKeyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("HS256 signed with the PUBLIC key bytes is refused (alg confusion)")
    void algConfusion_isRefused() {
        // The classic attack on a verifier that picks its algorithm from the
        // token: use the public key everyone can see as an HMAC secret. The
        // allow-list plus jjwt's refusal to HMAC against a PublicKey both stop it.
        byte[] publicBytes = keyPair.getPublic().getEncoded();
        String forged = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(PHONE)
                .id("jti-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(new SecretKeySpec(publicBytes, "HmacSHA256"), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an unsigned token (alg: none) is refused")
    void unsignedToken_isRefused() {
        String unsigned = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(PHONE)
                .id("jti-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .compact();

        assertThatThrownBy(() -> verifier().verify(unsigned))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an expired assertion is refused")
    void expired_isRefused() {
        String stale = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE,
                Instant.now().minusSeconds(600), 60, "jti-1");

        assertThatThrownBy(() -> verifier().verify(stale))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an assertion whose own lifetime exceeds the ceiling is refused")
    void overlongLifetime_isRefused() {
        // Signature and expiry both fine — the objection is that the middleware
        // minted a token good for a day. A single capture would be a day-long login.
        String longLived = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE,
                Instant.now(), 86_400, "jti-1");

        assertThatThrownBy(() -> verifier().verify(longLived))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class)
                .hasMessageContaining("exceeds the permitted");
    }

    @Test
    @DisplayName("a token for another issuer or audience is refused — loyalty's registration audience included")
    void wrongIssuerOrAudience_isRefused() {
        // The middleware signs the SAME assertion shape for loyalty's partner
        // registration, under the audience innbucks-loyalty. That token proves a
        // phone for registration and must never double as a fleet login — the
        // audience is the only thing separating the two, so this is the case
        // that matters most in this file.
        String loyaltyRegistration = assertion(keyPair.getPrivate(), PHONE, "innbucks-app", "innbucks-loyalty",
                Instant.now(), 120, "jti-1");
        String wrongIssuer = assertion(keyPair.getPrivate(), PHONE, "innbucks-ticketing", AUDIENCE,
                Instant.now(), 120, "jti-1");
        String wrongAudience = assertion(keyPair.getPrivate(), PHONE, ISSUER, "innbucks-loyalty",
                Instant.now(), 120, "jti-1");

        assertThatThrownBy(() -> verifier().verify(loyaltyRegistration))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify(wrongIssuer))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify(wrongAudience))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("missing sub or jti is refused")
    void missingClaims_areRefused() {
        String noSubject = assertion(keyPair.getPrivate(), null, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");
        String noJti = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, null);

        assertThatThrownBy(() -> verifier().verify(noSubject))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify(noJti))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class)
                .hasMessageContaining("jti");
    }

    @Test
    @DisplayName("blank and null assertions are refused, not NPE'd")
    void blankAssertion_isRefused() {
        assertThatThrownBy(() -> verifier().verify(null))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify("   "))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("with no key configured the verifier reports unconfigured and refuses everything")
    void unconfigured_refusesEverything() {
        // Fail-closed: a cell that switched the feature on but never provisioned
        // the key must refuse. The service turns isConfigured() into a 503 so the
        // operator sees a provisioning fault, not a stream of 401s.
        FederationAssertionVerifier v = new FederationAssertionVerifier("", "", ISSUER, AUDIENCE, 300);
        v.parseKeys();

        assertThat(v.isConfigured()).isFalse();
        assertThatThrownBy(() -> v.verify(validAssertion()))
                .isInstanceOf(FederationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("the previous key keeps verifying during a rotation overlap")
    void previousKey_isAcceptedDuringRotation() {
        FederationAssertionVerifier v = new FederationAssertionVerifier(
                "", pem(keyPair.getPublic()), ISSUER, AUDIENCE, 300);
        v.parseKeys();

        assertThat(v.isConfigured()).isTrue();
        assertThat(v.verify(validAssertion()).phoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("an unparseable key fails at construction, not at the first login")
    void malformedKey_failsFast() {
        FederationAssertionVerifier v = new FederationAssertionVerifier(
                "-----BEGIN PUBLIC KEY-----\nnot-base64!!\n-----END PUBLIC KEY-----",
                "", ISSUER, AUDIENCE, 300);

        assertThatThrownBy(v::parseKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.federation.public-key");
    }
}
