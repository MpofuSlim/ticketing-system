package innbucks.paymentservice.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paynow's own published examples ("Generating Hash", "Validating Hash", both
 * with integration key {@code 3e9fed89-60e1-4ce5-ab6e-6b1eb2d4f977}). If either
 * stops reproducing, our order or decoding rule is wrong and every Paynow
 * message would fail (or, worse, pass) its check.
 */
class PaynowHashTest {

    static final String DOCS_KEY = "3e9fed89-60e1-4ce5-ab6e-6b1eb2d4f977";

    @Test
    @DisplayName("Paynow's outbound example: values in sent order, unencoded, then the key")
    void outboundExample() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", "1201");
        fields.put("reference", "TEST REF");
        fields.put("amount", "99.99");
        fields.put("additionalinfo", "A test ticket transaction");
        fields.put("returnurl", "http://www.google.com/search?q=returnurl");
        fields.put("resulturl", "http://www.google.com/search?q=resulturl");
        fields.put("status", "Message");

        assertThat(PaynowHash.forOutbound(fields, DOCS_KEY))
                .startsWith("2A033FC38798D913D42ECB786B9B19645ADEDBDE788862032F1BD82CF3B92DEF84F316385D5B40DBB35F1A4FD7D5BFE738")
                .hasSize(128);
    }

    @Test
    @DisplayName("Paynow's inbound example: URL-decoded values in message order, hash excluded")
    void inboundExample() {
        String body = "status=Ok&browserurl=https%3a%2f%2fstaging.paynow.co.zw%2fPayment%2fConfirmPayment%2f9510"
                + "&pollurl=https%3a%2f%2fstaging.paynow.co.zw%2fInterface%2fCheckPayment%2f%3fguid%3dc7ed41da-0159-46da-b428-69549f770413"
                + "&paynowreference=9510&hash=";
        String expected = PaynowHash.expectedFor(PaynowMessage.parse(body), DOCS_KEY);

        assertThat(expected).startsWith(
                "750DD0B0DF374678707BB5AF915AF81C228B9058AD57BB7120569EC68BBB9C2EFC1B26C6375D2BC562AC909B3CD6B2AF1D");
        assertThat(PaynowHash.verify(PaynowMessage.parse(body + expected), DOCS_KEY)).isTrue();
    }

    @Test
    @DisplayName("a changed value, a wrong key, a missing hash or a blank key never verify")
    void tampering() {
        String body = "reference=TKZ-A-1&paynowreference=9510&amount=10.00&status=Paid&pollurl=https%3A%2F%2Fwww.paynow.co.zw%2Fp";
        String hash = PaynowHash.expectedFor(PaynowMessage.parse(body), "key-one");
        String signed = body + "&hash=" + hash;

        assertThat(PaynowHash.verify(PaynowMessage.parse(signed), "key-one")).isTrue();
        assertThat(PaynowHash.verify(PaynowMessage.parse(signed.replace("10.00", "1000.00")), "key-one")).isFalse();
        assertThat(PaynowHash.verify(PaynowMessage.parse(signed), "key-two")).isFalse();
        assertThat(PaynowHash.verify(PaynowMessage.parse(body), "key-one")).isFalse();
        assertThat(PaynowHash.verify(PaynowMessage.parse(signed), " ")).isFalse();
    }

    @Test
    @DisplayName("keys in any case, a lower-case hash, and '+' as a space all read as Paynow sends them")
    void caseAndDecoding() {
        String body = "Status=Awaiting+Delivery&Reference=R1";
        String hash = PaynowHash.expectedFor(PaynowMessage.parse(body), "k");
        PaynowMessage m = PaynowMessage.parse(body + "&Hash=" + hash.toLowerCase());

        assertThat(m.get("status")).isEqualTo("Awaiting Delivery");
        assertThat(m.status()).isEqualTo("awaiting delivery");
        assertThat(PaynowHash.verify(m, "k")).isTrue();
    }
}
