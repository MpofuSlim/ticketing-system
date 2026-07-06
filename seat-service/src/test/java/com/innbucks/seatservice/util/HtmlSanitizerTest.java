package com.innbucks.seatservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HtmlSanitizer} — the OWASP A03 stored-XSS guard applied
 * to customer-facing seat-category free text (category name/description, section
 * labels) on write paths. Pins the two invariants callers rely on: (1) legitimate
 * plain text round-trips unchanged, so the sanitizer is invisible to real input,
 * and (2) injected markup is neutralized before it can reach the DB.
 */
class HtmlSanitizerTest {

    // --- stripAll: null-safety + legitimate input is untouched -----------------

    @Test
    void stripAll_null_returnsNull() {
        assertThat(HtmlSanitizer.stripAll(null)).isNull();
    }

    @Test
    void stripAll_plainCategoryName_isUnchanged() {
        assertThat(HtmlSanitizer.stripAll("General Admission")).isEqualTo("General Admission");
    }

    @Test
    void stripAll_ampersand_roundTripsDecoded() {
        // The whole point of unescaping entities after cleaning: "Tom & Jerry"
        // must survive as-is, not become "Tom &amp; Jerry".
        assertThat(HtmlSanitizer.stripAll("Tom & Jerry")).isEqualTo("Tom & Jerry");
    }

    // --- stripAll: markup is neutralized ---------------------------------------

    @Test
    void stripAll_removesScript() {
        String cleaned = HtmlSanitizer.stripAll("<script>alert('xss')</script>");
        assertThat(cleaned).doesNotContain("<").doesNotContain("script");
    }

    @Test
    void stripAll_dropsFormattingTags_keepsText() {
        assertThat(HtmlSanitizer.stripAll("<b>VIP</b> Balcony")).isEqualTo("VIP Balcony");
    }

    @Test
    void stripAll_removesEventHandlerMarkup() {
        String cleaned = HtmlSanitizer.stripAll("<img src=x onerror=alert(1)>Front Row");
        assertThat(cleaned).doesNotContain("<").contains("Front Row");
    }

    // --- sanitizeRichText: keeps allowlisted formatting, drops scripts ---------

    @Test
    void sanitizeRichText_keepsBasicTags_dropsScript() {
        String cleaned = HtmlSanitizer.sanitizeRichText("<b>hi</b><script>steal()</script>");
        assertThat(cleaned).contains("<b>hi</b>").doesNotContain("<script");
    }
}
