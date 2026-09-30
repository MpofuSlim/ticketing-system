package com.innbucks.userservice.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Signs the {@code X-Support-Assertion} every support S2S call carries (design
 * §3.0). {@code X-Internal-Token} alone is held by every service in both repos,
 * so one compromised sibling could otherwise call the product support endpoints
 * directly — bypassing the permissions, masking, access log and limiter
 * user-service enforces, and forging the actor in a product's own audit. The
 * assertion is minted HERE, per call, by the only service that enforces the
 * {@code support-*} permissions, and binds the call to its agent, permission,
 * operation, lookup, the lookup's customer keys and (for detail and write
 * calls) the target and idempotency key.
 *
 * <p><b>RS256 only.</b> The private key never leaves user-service: it lives in the
 * dedicated {@code user-service-support-signing} Secret, referenced only by this
 * Deployment — never in {@code cell-zw-secrets}, which reaches every pod. The
 * public half is {@code SUPPORT_ASSERTION_PUBLIC_KEY} in the cell ConfigMap.
 *
 * <p>Claims (the verifiers in PRs 3–5 check every one; the signed test vector in
 * {@code src/test/resources/support-assertion/test-vector.json} pins the shape):
 * <ul>
 *   <li>{@code iss} = {@value #ISSUER}; {@code aud} = one of {@link Audience}</li>
 *   <li>{@code sub} = the agent's {@code userUuid}; {@code agent} = the agent's email</li>
 *   <li>{@code perm} = the permission exercised; {@code op} = the operation</li>
 *   <li>{@code lk} = the {@code lookupId}; {@code ck} = the lookup's resolved customer keys
 *       ({@code phones}, {@code emails}, {@code userUuids}, {@code buyerUuids}, {@code reference})</li>
 *   <li>{@code tgt} = the target id (detail and write calls); {@code idem} = the Idempotency-Key (writes)</li>
 *   <li>{@code iat}, {@code exp} (≤ 60s after {@code iat}), {@code jti}; header {@code kid}</li>
 * </ul>
 *
 * <p>Not a bean by itself — {@link SupportConfig} builds it from
 * {@link SupportProperties}, so the unit tests and the vector can construct it
 * with a fixed clock and jti.
 */
public class SupportAssertionSigner {

    public static final String HEADER = "X-Support-Assertion";
    public static final String ISSUER = "user-service";

    public static final String CLAIM_AGENT = "agent";
    public static final String CLAIM_PERMISSION = "perm";
    public static final String CLAIM_OPERATION = "op";
    public static final String CLAIM_LOOKUP = "lk";
    public static final String CLAIM_CUSTOMER_KEYS = "ck";
    public static final String CLAIM_TARGET = "tgt";
    public static final String CLAIM_IDEMPOTENCY = "idem";

    /** The four product audiences. A verifier accepts exactly its own. */
    public enum Audience {
        BOOKING("booking-support"),
        PAYMENT("payment-support"),
        LOYALTY("loyalty-support"),
        MARKETPLACE("marketplace-support");

        private final String wire;

        Audience(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * One call's claims.
     *
     * @param buyerUuids marketplace buyer uuids resolved for the lookup (empty until PR 5)
     * @param target     the id the call acts on or reads; null for a list call
     * @param idempotencyKey the write's Idempotency-Key; null for a read
     */
    public record Claims(Audience audience, UUID agentUuid, String agentEmail, String permission, String operation,
                         String lookupId, SupportCustomerKeys customerKeys, List<String> buyerUuids, String target,
                         UUID idempotencyKey) {
        public Claims {
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(agentUuid, "agentUuid");
            requireText(agentEmail, "agentEmail");
            requireText(permission, "permission");
            requireText(operation, "operation");
            requireText(lookupId, "lookupId");
            Objects.requireNonNull(customerKeys, "customerKeys");
            buyerUuids = buyerUuids == null ? List.of() : List.copyOf(new java.util.TreeSet<>(buyerUuids));
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey privateKey;
    private final String keyId;
    private final Duration ttl;
    private final Clock clock;
    private final Supplier<String> jti;

    public SupportAssertionSigner(SupportProperties.Assertion config, Clock clock) {
        this(config.getPrivateKey(), config.getKeyId(), config.getTtl(), clock, SupportAssertionSigner::randomJti);
    }

    SupportAssertionSigner(String privateKeyPem, String keyId, Duration ttl, Clock clock, Supplier<String> jti) {
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalStateException("support assertion ttl must be positive and at most 60 seconds");
        }
        this.privateKey = privateKeyPem == null || privateKeyPem.isBlank() ? null : parsePrivateKey(privateKeyPem);
        this.keyId = keyId;
        this.ttl = ttl;
        this.clock = clock;
        this.jti = jti;
    }

    /** False when {@code SUPPORT_ASSERTION_PRIVATE_KEY} is blank: every S2S support section is then UNAVAILABLE. */
    public boolean isConfigured() {
        return privateKey != null;
    }

    public String keyId() {
        return keyId;
    }

    /**
     * The compact JWS for one call.
     *
     * @throws IllegalStateException when the key is not provisioned — callers
     *         render the section UNAVAILABLE; they never call a product without it
     */
    public String sign(Claims c) {
        if (privateKey == null) {
            throw new IllegalStateException("SUPPORT_ASSERTION_PRIVATE_KEY is not provisioned");
        }
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(ISSUER)
                .audience().add(c.audience().wire()).and()
                .subject(c.agentUuid().toString())
                .id(jti.get())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim(CLAIM_AGENT, c.agentEmail())
                .claim(CLAIM_PERMISSION, c.permission())
                .claim(CLAIM_OPERATION, c.operation())
                .claim(CLAIM_LOOKUP, c.lookupId())
                .claim(CLAIM_CUSTOMER_KEYS, customerKeysClaim(c.customerKeys(), c.buyerUuids()));
        if (c.target() != null) builder.claim(CLAIM_TARGET, c.target());
        if (c.idempotencyKey() != null) builder.claim(CLAIM_IDEMPOTENCY, c.idempotencyKey().toString());
        return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    /**
     * {@code ck} in a fixed field order with sorted lists, so the same lookup
     * always produces the same claim and a verifier can compare field by field.
     * Numeric {@code users.id}s are internal to user-service and do not travel.
     */
    static Map<String, Object> customerKeysClaim(SupportCustomerKeys keys, List<String> buyerUuids) {
        Map<String, Object> ck = new LinkedHashMap<>();
        ck.put("phones", keys.phones());
        ck.put("emails", keys.emails());
        ck.put("userUuids", keys.userUuids());
        ck.put("buyerUuids", buyerUuids == null ? List.of() : buyerUuids);
        ck.put("reference", keys.reference());
        return ck;
    }

    private static String randomJti() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "sa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static PrivateKey parsePrivateKey(String pem) {
        PrivateKey key;
        try {
            String body = pem.replace("\\n", "\n")
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            // Fail at boot, never at the first support call. The message never
            // carries any part of the key.
            throw new IllegalStateException("Invalid SUPPORT_ASSERTION_PRIVATE_KEY (expected an RSA PKCS#8 PEM, "
                    + "-----BEGIN PRIVATE KEY-----)");
        }
        if (!(key instanceof RSAPrivateCrtKey crt) || crt.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("SUPPORT_ASSERTION_PRIVATE_KEY must be an RSA key of at least 2048 bits");
        }
        return key;
    }
}
