package com.innbucks.eventservice.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void stripAll_removesScriptTagAndItsContent() {
        assertEquals("Steers", HtmlSanitizer.stripAll("<script>alert(1)</script>Steers"));
    }

    @Test
    void stripAll_decodesEntitiesSoPlainTextRoundTrips() {
        assertEquals("Tom & Jerry", HtmlSanitizer.stripAll("Tom & Jerry"));
    }

    @Test
    void sanitizeRichText_keepsBasicFormattingButDropsScript() {
        String cleaned = HtmlSanitizer.sanitizeRichText("<p>Hi</p><script>x</script>");
        assertTrue(cleaned.contains("<p>Hi</p>"), () -> "expected <p>Hi</p> to survive, got: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("script"), () -> "script must be dropped, got: " + cleaned);
    }
}
