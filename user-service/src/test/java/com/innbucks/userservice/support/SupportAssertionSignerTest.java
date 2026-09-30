package com.innbucks.userservice.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.devicesecurity.TestKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The support-assertion SIGNER (design §3.0) and the signed TEST VECTOR the
 * product verifiers in PRs 3–5 pin ({@code src/test/resources/support-assertion/test-vector.json}).
 *
 * <p>The vector's private half was discarded after signing, so nothing can
 * re-sign it: this test proves (a) the committed compact verifies under the
 * committed public key and yields exactly the committed claims, (b) every
 * mutation the vector lists is refused by a correctly configured verifier, and
 * (c) the signer TODAY still emits exactly that claim shape — the drift check
 * that keeps user-service and the verifiers in lock-step.
 */
class SupportAssertionSignerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant ISSUED = Instant.parse("2026-09-30T10:00:00Z");

    private static SupportAssertionSigner.Claims vectorClaims() {
        return new SupportAssertionSigner.Claims(SupportAssertionSigner.Audience.LOYALTY,
                UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f"), "agent.one@innbucks.co.zw",
                "support-loyalty:manage", "LOYALTY_VOUCHER_RESEND", "SLK-7Q2M9X",
                new SupportCustomerKeys(List.of("+263771234567"), List.of("Tariro@Example.com"),
                        List.of("9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40"), List.of(1042L), null),
                List.of(), "5f0c7b2e-8d1a-4c3b-9e6f-0a1b2c3d4e5f",
                UUID.fromString("3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d"));
    }

    private static SupportAssertionSigner signer(KeyPair pair, Instant now) {
        return new SupportAssertionSigner(TestKeys.pem(pair), "support-assertion-test-1", Duration.ofSeconds(60),
                Clock.fixed(now, ZoneOffset.UTC), () -> "sa_test-vector-0001");
    }

    private static JsonNode vector() throws Exception {
        try (InputStream in = SupportAssertionSignerTest.class.getResourceAsStream("/support-assertion/test-vector.json")) {
            assertThat(in).as("the committed test vector").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static PublicKey publicKey(String pem) throws Exception {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    /** The reference verifier a product builds: RS256 key, exact audience, issuer, 5s skew, a fixed clock. */
    private static Jws<Claims> verify(String compact, PublicKey key, String audience, Instant at) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(SupportAssertionSigner.ISSUER)
                .requireAudience(audience)
                .clock(() -> Date.from(at))
                .clockSkewSeconds(5)
                .build()
                .parseSignedClaims(compact);
    }

    private static JsonNode payload(String compact) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(compact.split("\\.")[1]));
    }

    // ---- the vector ----------------------------------------------------------------------------

    @Test
    @DisplayName("the committed vector verifies under its public key and yields exactly its expected claims")
    void vectorVerifies() throws Exception {
        JsonNode v = vector();
        PublicKey key = publicKey(v.get("publicKeyPem").asText());
        Jws<Claims> jws = verify(v.get("compact").asText(), key, v.get("expectedAudience").asText(),
                Instant.parse(v.get("verifyAt").asText()));
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(jws.getHeader().getKeyId()).isEqualTo(v.get("kid").asText());
        assertThat(payload(v.get("compact").asText())).isEqualTo(v.get("expectedClaims"));
        // It carries no private key: the vector cannot be re-signed by anyone who copies it.
        assertThat(v.has("privateKeyPem")).isFalse();
        assertThat(v.toString()).doesNotContain("PRIVATE KEY");
    }

    @Test
    @DisplayName("every mutation the vector lists is refused")
    void vectorMutationsAreRefused() throws Exception {
        JsonNode v = vector();
        PublicKey key = publicKey(v.get("publicKeyPem").asText());
        String compact = v.get("compact").asText();
        Instant ok = Instant.parse(v.get("verifyAt").asText());

        // expired (exp + 5s skew passed)
        assertThatThrownBy(() -> verify(compact, key, "loyalty-support", Instant.parse(v.get("expiredAt").asText())))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
        // another product's audience
        for (String aud : List.of("booking-support", "payment-support", "marketplace-support")) {
            assertThatThrownBy(() -> verify(compact, key, aud, ok)).as(aud)
                    .isInstanceOf(io.jsonwebtoken.IncorrectClaimException.class);
        }
        // a payload byte changed
        String[] parts = compact.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("+263771234567", "+263771234568").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verify(parts[0] + "." + tamperedPayload + "." + parts[2], key, "loyalty-support", ok))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
        // alg confusion: HS256 keyed with the public key bytes
        String hsHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"kid\":\"support-assertion-test-1\",\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
        String hsSig = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((hsHeader + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThatThrownBy(() -> verify(hsHeader + "." + parts[1] + "." + hsSig, key, "loyalty-support", ok))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
        // alg none
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verify(noneHeader + "." + parts[1] + ".", key, "loyalty-support", ok))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    @DisplayName("drift check: the signer today emits exactly the vector's claims for the vector's inputs")
    void signerStillEmitsTheVectorShape() throws Exception {
        KeyPair pair = TestKeys.generate();
        String compact = signer(pair, ISSUED).sign(vectorClaims());
        assertThat(payload(compact)).isEqualTo(vector().get("expectedClaims"));
        assertThat(JSON.readTree(Base64.getUrlDecoder().decode(compact.split("\\.")[0])))
                .isEqualTo(vector().get("expectedHeader"));
    }

    // ---- the signer ----------------------------------------------------------------------------

    @Test
    @DisplayName("a signed assertion verifies with the matching public key, lives at most 60 seconds, has a fresh jti")
    void signsAndVerifies() {
        KeyPair pair = TestKeys.generate();
        SupportAssertionSigner s = new SupportAssertionSigner(TestKeys.pem(pair), "support-assertion-1",
                Duration.ofSeconds(60), Clock.fixed(ISSUED, ZoneOffset.UTC),
                () -> "sa_" + UUID.randomUUID());
        String a = s.sign(vectorClaims());
        String b = s.sign(vectorClaims());
        Claims claims = verify(a, pair.getPublic(), "loyalty-support", ISSUED.plusSeconds(10)).getPayload();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(60_000L);
        assertThat(claims.getSubject()).isEqualTo("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f");
        assertThat(claims.getId()).isNotEqualTo(verify(b, pair.getPublic(), "loyalty-support", ISSUED).getPayload().getId());
        // users.id is internal to user-service and never travels.
        assertThat(claims.get("ck", Map.class)).doesNotContainKey("userIds");
    }

    @Test
    @DisplayName("a read carries no tgt/idem claims when it has none")
    void readHasNoTargetOrIdempotency() throws Exception {
        KeyPair pair = TestKeys.generate();
        SupportAssertionSigner.Claims read = new SupportAssertionSigner.Claims(SupportAssertionSigner.Audience.BOOKING,
                UUID.randomUUID(), "agent.one@innbucks.co.zw", "support-ticketing:read", "BOOKINGS_LIST", "SLK-7Q2M9X",
                SupportCustomerKeys.empty(), null, null, null);
        JsonNode p = payload(signer(pair, ISSUED).sign(read));
        assertThat(p.has("tgt")).isFalse();
        assertThat(p.has("idem")).isFalse();
        assertThat(p.get("aud").get(0).asText()).isEqualTo("booking-support");
    }

    @Test
    @DisplayName("a TTL over 60 seconds is refused at construction")
    void ttlCapped() {
        assertThatThrownBy(() -> new SupportAssertionSigner(TestKeys.privatePem(), "k", Duration.ofSeconds(61),
                Clock.systemUTC(), () -> "j")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("blank key: not configured, and sign refuses rather than calling a product unsigned")
    void blankKey() {
        SupportAssertionSigner s = new SupportAssertionSigner("", "k", Duration.ofSeconds(60), Clock.systemUTC(),
                () -> "j");
        assertThat(s.isConfigured()).isFalse();
        assertThatThrownBy(() -> s.sign(vectorClaims())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a malformed or short key fails at boot, and the message carries no key material")
    void badKeysFailAtBoot() throws Exception {
        String garbage = "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5LWF0LWFsbA==\n-----END PRIVATE KEY-----";
        assertThatThrownBy(() -> new SupportAssertionSigner(garbage, "k", Duration.ofSeconds(60), Clock.systemUTC(),
                () -> "j"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("bm90");
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        String weak = TestKeys.pem(g.generateKeyPair());
        assertThatThrownBy(() -> new SupportAssertionSigner(weak, "k", Duration.ofSeconds(60), Clock.systemUTC(),
                () -> "j"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("an env-style PEM with literal \\n escapes is accepted (single-line Secret values)")
    void escapedNewlines() {
        String escaped = TestKeys.privatePem().replace("\n", "\\n");
        assertThat(new SupportAssertionSigner(escaped, "k", Duration.ofSeconds(60), Clock.systemUTC(), () -> "j")
                .isConfigured()).isTrue();
    }

    @Test
    @DisplayName("the four audiences are exactly the design's")
    void audiences() {
        assertThat(List.of(SupportAssertionSigner.Audience.values()).stream()
                .map(SupportAssertionSigner.Audience::wire).toList())
                .containsExactly("booking-support", "payment-support", "loyalty-support", "marketplace-support");
    }
}
