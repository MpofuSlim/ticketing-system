package com.innbucks.common.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The optional call-to-action button: off unless passed, {@code https} only
 * (validated, not merely escaped), label HTML-escaped, and the plain-text body
 * — which callers keep the link in — rendered unchanged.
 */
class BrandedEmailRendererCtaTest {

    private static final String LINK = "https://foundry.innbucks.co.zw/set-password#token=STI-abc_DEF-123";

    @Test
    @DisplayName("no CTA: byte-identical to the three-argument form")
    void offByDefault() {
        assertThat(BrandedEmailRenderer.render("Subject", "Hello,\n\nBody.", null, null))
                .isEqualTo(BrandedEmailRenderer.render("Subject", "Hello,\n\nBody.", null));
        assertThat(BrandedEmailRenderer.render("Subject", "Hello", null)).doesNotContain("rel=\"noopener noreferrer\"");
    }

    @Test
    @DisplayName("a CTA renders one real link with the URL and the escaped label, after the body")
    void rendersButton() {
        String html = BrandedEmailRenderer.render("Subject", "Set your password here:\n" + LINK, null,
                new BrandedEmailRenderer.CallToAction("Set <your> password & go", LINK));
        assertThat(html).contains("<a href=\"" + LINK + "\"");
        assertThat(html).contains("Set &lt;your&gt; password &amp; go</a>");
        assertThat(html).doesNotContain("Set <your> password");
        assertThat(html).contains("rel=\"noopener noreferrer\"");
        // The plain-text copy keeps the link too.
        assertThat(html.indexOf("Set your password here:")).isLessThan(html.indexOf("<a href=\"" + LINK));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://foundry.innbucks.co.zw/set-password",
            "javascript:alert(1)",
            "HTTPS://foundry.innbucks.co.zw/x",
            "https://",
            "https:///path-only",
            "https://user:pass@foundry.innbucks.co.zw/x",
            "https://foundry.innbucks.co.zw/x\" onclick=\"alert(1)",
            "https://foundry.innbucks.co.zw/<script>",
            "https://foundry.innbucks.co.zw/a b",
            "https://foundry.innbucks.co.zw/a\\b",
            "//foundry.innbucks.co.zw/x",
            ""})
    @DisplayName("anything but an absolute https URL with a host is refused")
    void refusesUnsafeUrls(String url) {
        assertThatThrownBy(() -> new BrandedEmailRenderer.CallToAction("Go", url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null URL or a blank label is refused")
    void refusesMissingParts() {
        assertThatThrownBy(() -> new BrandedEmailRenderer.CallToAction("Go", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrandedEmailRenderer.CallToAction(" ", LINK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
