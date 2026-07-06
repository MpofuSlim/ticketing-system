package innbucks.paymentservice.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void stripAll_removesScriptTagAndItsContent() {
        assertEquals("Lunch", HtmlSanitizer.stripAll("<script>alert(1)</script>Lunch"));
    }

    @Test
    void stripAll_decodesEntitiesSoPlainTextRoundTrips() {
        assertEquals("Tom & Jerry", HtmlSanitizer.stripAll("Tom & Jerry"));
    }

    @Test
    void stripAll_isIdentityOnLegitimateNotes() {
        assertEquals("Cash out at agent", HtmlSanitizer.stripAll("Cash out at agent"));
    }

    @Test
    void stripAll_emptyStringStaysEmpty() {
        assertEquals("", HtmlSanitizer.stripAll(""));
    }

    @Test
    void stripAll_nullStaysNull() {
        assertNull(HtmlSanitizer.stripAll(null));
    }

    @Test
    void sanitizeRichText_keepsBasicFormattingButDropsScript() {
        String cleaned = HtmlSanitizer.sanitizeRichText("<p>Hi</p><script>x</script>");
        assertTrue(cleaned.contains("<p>Hi</p>"), () -> "expected <p>Hi</p> to survive, got: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("script"), () -> "script must be dropped, got: " + cleaned);
    }
}
