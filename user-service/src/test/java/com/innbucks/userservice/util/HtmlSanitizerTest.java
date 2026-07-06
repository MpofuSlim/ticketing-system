package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the stored-XSS sanitization contract (OWASP A03). {@link HtmlSanitizer}
 * is applied on every free-text name write path (register, customer tier-2,
 * shop-staff, team-member) — these assertions guard both halves of the deal:
 * injected markup is neutralized, and legitimate plain-text names round-trip
 * byte-for-byte so the change is invisible to the frontend.
 */
class HtmlSanitizerTest {

    @Test
    void stripAll_removesScriptTag() {
        // The canonical stored-XSS payload: no markup may survive.
        String cleaned = HtmlSanitizer.stripAll("<script>alert('XSS')</script>");
        assertThat(cleaned).doesNotContain("<script>").doesNotContain("<");
    }

    @Test
    void stripAll_stripsTagsButKeepsInnerText() {
        assertThat(HtmlSanitizer.stripAll("<b>Alice</b>")).isEqualTo("Alice");
        assertThat(HtmlSanitizer.stripAll("<a href=\"http://evil\">click</a>")).isEqualTo("click");
    }

    @Test
    void stripAll_preservesAmpersandLiteral() {
        // A legitimate name with an ampersand must round-trip unchanged — the
        // &amp; Jsoup emits is decoded back so the stored value equals the input.
        assertThat(HtmlSanitizer.stripAll("Tom & Jerry")).isEqualTo("Tom & Jerry");
    }

    @Test
    void stripAll_plainName_unchanged() {
        assertThat(HtmlSanitizer.stripAll("Alice Moyo")).isEqualTo("Alice Moyo");
    }

    @Test
    void stripAll_null_returnsNull() {
        assertThat(HtmlSanitizer.stripAll(null)).isNull();
    }

    @Test
    void sanitizeRichText_keepsAllowlistedTags_dropsScript() {
        String cleaned = HtmlSanitizer.sanitizeRichText("<h1>Title</h1><script>alert(1)</script>");
        assertThat(cleaned).contains("<h1>").doesNotContain("<script>");
    }

    @Test
    void sanitizeRichText_null_returnsNull() {
        assertThat(HtmlSanitizer.sanitizeRichText(null)).isNull();
    }
}
