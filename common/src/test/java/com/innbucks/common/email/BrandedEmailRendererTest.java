package com.innbucks.common.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the branded-HTML rendering contract (see {@link BrandedEmailRenderer}):
 * the body is HTML-escaped (no injection), blank-line paragraphs become
 * {@code <p>}, the footer + disclaimer are always present, and the logo is an
 * {@code <img>} when a URL is given / a CSS fallback when it isn't — either
 * way inside a NAVY header cell, because the hosted logo's lettering is white.
 */
class BrandedEmailRendererTest {

    @Test
    void rendersBodyParagraphsFooterAndDisclaimer() {
        String html = BrandedEmailRenderer.render(
                "Your event is now live",
                "Hello,\n\nYour event \"Feli Nandi\" is now live.\n\nWe hope it's a great event!",
                "https://www.innbucks.co.zw/logo.png");

        // A fragment (bare table), not a full document — safe to inject into the
        // gateway's own HTML body without nesting an <html> element.
        assertThat(html).startsWith("<table");
        assertThat(html).doesNotContain("<html");
        assertThat(html).doesNotContain("<!doctype");
        // Body content survives, split into paragraphs.
        assertThat(html).contains("is now live");
        assertThat(html).contains("<p style=");
        // Standard footer + statutory disclosure always present.
        assertThat(html).contains("The InnBucks Team");
        assertThat(html).contains("Deposit Protection Scheme");
        assertThat(html).contains("+263 (0) 8677 569 569");
    }

    @Test
    void usesHostedLogoImgWhenUrlProvided() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");
        assertThat(html).contains("<img src=\"https://cdn.innbucks.co.zw/logo.png\"");
        assertThat(html).contains("alt=\"InnBucks\"");
    }

    @Test
    void fallsBackToCssLogoWhenUrlBlank() {
        String html = BrandedEmailRenderer.render("s", "body", "");
        assertThat(html).doesNotContain("<img");
        // CSS brand lockup is rendered instead: wordmark + tagline.
        assertThat(html).contains(">InnBucks<");
        assertThat(html).contains(">MicroBank Limited<");
    }

    @Test
    void escapesHtmlInBodySoContentCannotInjectMarkup() {
        String html = BrandedEmailRenderer.render(
                "s", "Body with <b>tags</b> & an ampersand and a <script>alert(1)</script>", "");
        assertThat(html).contains("&lt;b&gt;tags&lt;/b&gt;");
        assertThat(html).contains("&amp; an ampersand");
        // The raw script tag from message content must never appear unescaped.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void escapesQuotesInLogoUrlAttribute() {
        String html = BrandedEmailRenderer.render("s", "b", "https://x/a\"onerror=alert(1)");
        assertThat(html).doesNotContain("\"onerror=alert(1)");
        assertThat(html).contains("&quot;onerror=alert(1)");
    }

    // The hosted logo (NOTIFY_LOGO_URL) is the dark-ground lockup with WHITE
    // lettering; on a white header only its four dots were visible (Gmail,
    // staging, 2026-09-29). The header cell must therefore be navy — bgcolor
    // for Outlook, inline background for Gmail — and wrap the logo.
    private static final String NAVY_HEADER_TD =
            "<td bgcolor=\"#0c2545\" style=\"background:#0c2545;padding:26px 34px 20px;\">";

    @Test
    void headerCellIsNavy_bothAsBgcolorAndInlineStyle_andWrapsTheHostedLogo() {
        String html = BrandedEmailRenderer.render("s", "body", "https://cdn.innbucks.co.zw/logo.png");

        int header = html.indexOf(NAVY_HEADER_TD);
        int img = html.indexOf("<img src=\"https://cdn.innbucks.co.zw/logo.png\"");
        assertThat(header).as("navy header cell present").isGreaterThanOrEqualTo(0);
        assertThat(img).as("logo img present").isGreaterThan(header);
        // The logo is the header cell's content — nothing closes the cell before it.
        assertThat(html.substring(header + NAVY_HEADER_TD.length(), img)).doesNotContain("</td>");
        // The body stays white and the footer stays navy.
        assertThat(html).contains("background:#ffffff;border-radius:12px");
        assertThat(html).contains("<td style=\"background:#0c2545;padding:26px 34px;");
    }

    @Test
    void headerCellIsNavy_alsoForTheCssFallbackLockup() {
        String html = BrandedEmailRenderer.render("s", "body", null);

        int header = html.indexOf(NAVY_HEADER_TD);
        int wordmark = html.indexOf(">InnBucks<");
        assertThat(header).isGreaterThanOrEqualTo(0);
        assertThat(wordmark).isGreaterThan(header);
    }

    @Test
    void fallbackLockupLetteringIsLightSoItReadsOnNavy() {
        String html = BrandedEmailRenderer.render("s", "body", "  ");

        // Wordmark white, tagline the footer's light slate — never the old navy
        // / grey that assumed a white header.
        assertThat(html).containsPattern("color:#ffffff;[^\"]*\">InnBucks</div>");
        assertThat(html).containsPattern("color:#b9c6d8;[^\"]*\">MicroBank Limited</div>");
        assertThat(html).doesNotContain("color:#5d6b7b");
        assertThat(html).doesNotContainPattern("color:#0c2545;[^\"]*\">InnBucks</div>");
        // The four brand dots keep their colours.
        assertThat(html).contains("background:#f5b71c;width:16px");
        assertThat(html).contains("background:#7a2e8f;width:16px");
        assertThat(html).contains("background:#17a98c;width:16px");
        assertThat(html).contains("background:#e11b22;width:16px");
    }

    @Test
    void logoUrlIsStillAttributeEscapedInsideTheNavyHeader() {
        String html = BrandedEmailRenderer.render("s", "b", "https://x/a\"onerror=alert(1)&b=<c>");

        int header = html.indexOf(NAVY_HEADER_TD);
        int img = html.indexOf("<img src=\"https://x/a&quot;onerror=alert(1)&amp;b=&lt;c&gt;\"");
        assertThat(header).isGreaterThanOrEqualTo(0);
        assertThat(img).isGreaterThan(header);
        assertThat(html).doesNotContain("\"onerror=alert(1)");
        assertThat(html).doesNotContain("<c>");
    }
}
