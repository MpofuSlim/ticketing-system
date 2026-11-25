package innbucks.paymentservice.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Paynow's message signature (docs "Generating Hash" / "Validating Hash"):
 * every value of the message except {@code hash}, in message order, joined
 * with nothing between them, then the integration key, UTF-8, SHA-512,
 * upper-case hex.
 *
 * <p>Both of Paynow's published examples reproduce with this exactly (pinned
 * by {@code PaynowHashTest}), which is how we know the order and decoding
 * rules are right: outbound values are hashed as sent (before URL-encoding),
 * inbound values after URL-decoding.
 *
 * <p>The integration key is a secret: anyone holding it can sign messages
 * that pass as Paynow's. It is never logged, and a computed hash is never
 * logged next to the values it covers.
 */
public final class PaynowHash {

    private PaynowHash() {
    }

    /** The hash of an outbound message: {@code fields} in the order they will be sent, {@code hash} excluded. */
    public static String forOutbound(Map<String, String> fields, String integrationKey) {
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!e.getKey().equalsIgnoreCase("hash")) {
                joined.append(e.getValue() == null ? "" : e.getValue());
            }
        }
        return sha512Upper(joined.append(integrationKey).toString());
    }

    /** The hash a genuine Paynow message carrying these values would have. */
    static String expectedFor(PaynowMessage message, String integrationKey) {
        StringBuilder joined = new StringBuilder();
        for (String v : message.valuesForHash()) {
            joined.append(v);
        }
        return sha512Upper(joined.append(integrationKey).toString());
    }

    /**
     * Whether {@code message} carries a {@code hash} that matches its values
     * under {@code integrationKey}. Constant-time comparison; a message with
     * no hash, or a blank key, never verifies.
     */
    public static boolean verify(PaynowMessage message, String integrationKey) {
        String presented = message.text("hash");
        if (presented == null || integrationKey == null || integrationKey.isBlank()) {
            return false;
        }
        byte[] expected = expectedFor(message, integrationKey).getBytes(StandardCharsets.US_ASCII);
        byte[] given = presented.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    static String sha512Upper(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available on this JVM", e);
        }
    }
}
