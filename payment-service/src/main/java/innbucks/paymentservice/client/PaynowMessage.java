package innbucks.paymentservice.client;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One Paynow message: the {@code key=value&key=value} string Paynow answers
 * with and posts to our result URL ({@code docs/api/paynow.md}).
 *
 * <p>Two things about the wire shape matter, and both are why this is not a
 * plain map:
 * <ul>
 *   <li><b>Order is part of the signature.</b> The hash covers the values in
 *       the order they appear in the message, and Paynow's own examples do not
 *       keep one field order (the status-update table and its example list
 *       {@code amount} and {@code paynowreference} in different places). So
 *       the fields are kept exactly as received.</li>
 *   <li><b>Key case varies.</b> Paynow's initiate example answers
 *       {@code Status=Ok&BrowserUrl=…&PollUrl=…&Hash=…} while every other
 *       example is lower case, so lookups ignore case.</li>
 * </ul>
 * Values are URL-decoded once, here ({@code +} is a space), because the hash
 * is computed over the decoded values.
 */
public final class PaynowMessage {

    /** One field as received: its key as sent, its decoded value. */
    public record Field(String key, String value) {}

    private final List<Field> fields;

    private PaynowMessage(List<Field> fields) {
        this.fields = Collections.unmodifiableList(fields);
    }

    /**
     * Parse a Paynow message. Never throws: a malformed pair is kept with an
     * empty value, so the hash check (not this parser) decides whether the
     * message is Paynow's.
     */
    public static PaynowMessage parse(String body) {
        List<Field> fields = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return new PaynowMessage(fields);
        }
        for (String pair : body.strip().split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            fields.add(new Field(decode(rawKey), decode(rawValue)));
        }
        return new PaynowMessage(fields);
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed %-escape: keep the raw text. The hash then fails,
            // which is the right answer for a message we cannot read.
            return raw;
        }
    }

    /** The first value for {@code key}, ignoring case; null when absent. */
    public String get(String key) {
        for (Field f : fields) {
            if (f.key().equalsIgnoreCase(key)) {
                return f.value();
            }
        }
        return null;
    }

    /** The value for {@code key}, stripped, or null when absent or blank. */
    public String text(String key) {
        String v = get(key);
        if (v == null) return null;
        String s = v.strip();
        return s.isEmpty() ? null : s;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public List<Field> fields() {
        return fields;
    }

    /** The {@code status} value, lower-cased for comparison; null when absent. */
    public String status() {
        String s = text("status");
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Every value except {@code hash}, in message order: what the hash covers. */
    List<String> valuesForHash() {
        List<String> values = new ArrayList<>(fields.size());
        for (Field f : fields) {
            if (!f.key().equalsIgnoreCase("hash")) {
                values.add(f.value());
            }
        }
        return values;
    }
}
