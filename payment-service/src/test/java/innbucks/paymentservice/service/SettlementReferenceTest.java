package innbucks.paymentservice.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the settlement-reference contract the merchant statement depends on:
 * TKZ-&lt;CODE&gt;-&lt;12-hex&gt; when the event has a settlement code, the event-id
 * tag fallback when it doesn't, the legacy TKT-PMT shape with no event at all,
 * uniqueness across calls, and the ≤64-char / statement-safe-charset bound of
 * the payment_reference column.
 */
class SettlementReferenceTest {

    private static final UUID EVENT = UUID.fromString("20c96393-8ac8-480a-93d0-ef89981c53e0");

    @Test
    void withSettlementCode_buildsPrefixedGroupableReference() {
        String ref = SettlementReference.build("PINKRUN26", EVENT);

        assertThat(ref).matches("TKZ-PINKRUN26-[0-9A-F]{12}");
    }

    @Test
    void unnormalizedUpstreamCode_isSanitizedNotTrusted() {
        String ref = SettlementReference.build(" pink run-26! ", EVENT);

        assertThat(ref).matches("TKZ-PINKRUN26-[0-9A-F]{12}");
    }

    @Test
    void noCode_fallsBackToEventIdTag() {
        String ref = SettlementReference.build(null, EVENT);

        assertThat(ref).matches("TKZ-20C96393-[0-9A-F]{12}");
    }

    @Test
    void unusableCode_fallsBackToEventIdTag() {
        assertThat(SettlementReference.build("!!", EVENT))
                .matches("TKZ-20C96393-[0-9A-F]{12}");
    }

    @Test
    void noEventAtAll_keepsLegacyUniqueShape() {
        String ref = SettlementReference.build(null, null);

        assertThat(ref).matches("TKT-PMT-[0-9a-f-]{36}");
    }

    @Test
    void references_areUniqueAndWithinColumnBounds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String ref = SettlementReference.build("PINKRUN26", EVENT);
            assertThat(ref.length()).isLessThanOrEqualTo(64);
            assertThat(seen.add(ref)).as("duplicate reference generated: %s", ref).isTrue();
        }
    }

    @Test
    void overlongCode_cappedAtTwelve() {
        String ref = SettlementReference.build("ABCDEFGHIJKLMNOP", EVENT);

        assertThat(ref).matches("TKZ-ABCDEFGHIJKL-[0-9A-F]{12}");
    }
}
