package com.innbucks.common.email;

/**
 * Wraps a plain-text email body in the InnBucks branded HTML shell — logo
 * header, colour accent bar, the message as formatted paragraphs, and the
 * standard contact + Deposit-Protection footer.
 *
 * <p>Used only when {@code innbucks-notify.html-enabled=true}; otherwise
 * {@link EmailSignature} (plain text) is the wire format. The HTML is
 * deliberately email-client-robust: a single centred table, all styles inline,
 * web-safe fonts, no external CSS/JS — the lowest-common-denominator that
 * Gmail / Outlook / Apple Mail all render. The logo is an {@code <img>} at a
 * hosted URL (clients block {@code data:} URIs); when no URL is configured it
 * falls back to a CSS-drawn four-dot roundel + wordmark so the header is still
 * branded without a hosted asset.
 *
 * <p><b>The header row is brand NAVY, not white.</b> The hosted logo asset
 * ({@code NOTIFY_LOGO_URL}) is the dark-ground lockup: its "InnBucks" and
 * "MicroBank Limited" lettering is WHITE, so on the white header this shell
 * used to draw, only the four coloured dots were visible (seen in Gmail on
 * staging, 2026-09-29). The navy is set twice on the header cell — as the
 * {@code bgcolor} attribute, which Outlook honours, and as an inline
 * {@code background}, which Gmail honours — and is the same {@link #NAVY} the
 * footer uses, so the shell reads as navy top and bottom around a white body.
 * The CSS fallback lockup sits on that same navy, so its lettering is light
 * to match the hosted asset.
 *
 * <p>The caller's body is plain text (the same string the plain-text path
 * sends), so it is HTML-escaped here and its blank-line-separated paragraphs
 * become {@code <p>} blocks — no HTML is ever taken from the message content,
 * which keeps injected markup impossible.
 */
public final class BrandedEmailRenderer {

    private BrandedEmailRenderer() {
    }

    private static final String NAVY = "#0c2545";
    private static final String TEAL = "#17a98c";
    /** Light slate that reads on {@link #NAVY} — the footer's body text colour. */
    private static final String ON_NAVY_MUTED = "#b9c6d8";

    /**
     * Render {@code plainBody} into a full branded HTML document.
     *
     * @param subject  used as the visually-hidden preheader / heading
     * @param plainBody the plain-text message (escaped + paragraphed here)
     * @param logoUrl  hosted logo image URL; blank → CSS roundel fallback
     */
    public static String render(String subject, String plainBody, String logoUrl) {
        return render(subject, plainBody, logoUrl, null);
    }

    /**
     * A call-to-action button: a label and the {@code https} link it opens.
     *
     * <p>OFF unless a caller passes one. The escaped plain-text body alone leaves
     * a link to the reader's client to auto-link, which some do not; a button is
     * a real {@code <a href>}. The URL is VALIDATED here, not just escaped:
     * absolute {@code https} with a host, and only the characters a URL is made
     * of — no whitespace, quote, angle bracket or backslash — so nothing a caller
     * builds from data can become a {@code javascript:} link or break out of the
     * attribute. The label is HTML-escaped. Callers keep the URL in the plain
     * text too, for clients that strip buttons.
     */
    public record CallToAction(String label, String url) {

        private static final java.util.regex.Pattern URL_CHARS =
                java.util.regex.Pattern.compile("^https://[A-Za-z0-9._~:/?#\\[\\]@!$&()*+,;=%-]+$");

        public CallToAction {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("A call-to-action needs a label");
            }
            if (url == null || !URL_CHARS.matcher(url).matches()) {
                throw new IllegalArgumentException("A call-to-action link must be an absolute https URL");
            }
            java.net.URI uri;
            try {
                uri = java.net.URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("A call-to-action link must be an absolute https URL", e);
            }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("A call-to-action link must be an absolute https URL");
            }
        }
    }

    /**
     * As {@link #render(String, String, String)}, with an optional call-to-action
     * button under the body; {@code cta} null renders exactly what the 3-argument
     * form renders.
     */
    public static String render(String subject, String plainBody, String logoUrl, CallToAction cta) {
        String paragraphs = toParagraphs(plainBody) + (cta == null ? "" : button(cta));
        String logo = (logoUrl == null || logoUrl.isBlank())
                ? cssRoundel()
                : "<img src=\"" + escapeAttr(logoUrl) + "\" width=\"180\" alt=\"InnBucks\" "
                    + "style=\"display:block;border:0;outline:none;text-decoration:none;height:auto;\">";

        // A FRAGMENT, not a full <html> document: the notification gateway
        // renders the message field as HTML and (per the payment-rail template)
        // may inject it into its own <html><body>. Emitting a bare centred table
        // renders correctly both when the gateway wraps it and when it doesn't —
        // a nested <html> would be invalid. All styles inline, web-safe fonts,
        // no external CSS/JS: the lowest common denominator Gmail/Outlook render.
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"background:#eef1f5;margin:0;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">"
            + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"width:600px;max-width:100%;background:#ffffff;border-radius:12px;overflow:hidden;"
            +   "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"
            // header — navy, because the hosted logo's lettering is white (see
            // class javadoc). bgcolor for Outlook, inline background for Gmail.
            + "<tr><td bgcolor=\"" + NAVY + "\" style=\"background:" + NAVY + ";padding:26px 34px 20px;\">"
            +   logo + "</td></tr>"
            // accent bar
            + "<tr><td style=\"font-size:0;line-height:0;\">"
            +   "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#f5b71c;\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#7a2e8f;\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:" + TEAL + ";\"></td>"
            +   "<td width=\"25%\" height=\"4\" style=\"background:#e11b22;\"></td>"
            +   "</tr></table></td></tr>"
            // body
            + "<tr><td style=\"padding:30px 34px 8px;color:#2b3a4b;font-size:15px;line-height:1.6;\">"
            +   paragraphs + "</td></tr>"
            // footer
            + "<tr><td style=\"background:" + NAVY + ";padding:26px 34px;color:#b9c6d8;font-size:13px;"
            +   "line-height:1.55;\">"
            +   "<div style=\"color:#ffffff;font-weight:700;font-size:15px;margin-bottom:8px;\">"
            +     "The InnBucks Team</div>"
            +   "InnBucks Microfinance Bank<br>"
            +   "+263 (0) 8677 569 569 &nbsp;&middot;&nbsp; "
            +     "<a href=\"https://www.innbucks.co.zw\" style=\"color:#9fd9cc;text-decoration:none;\">"
            +     "www.innbucks.co.zw</a> &nbsp;&middot;&nbsp; Dial *569#<br>"
            +   "2 Northridge Close, Northridge Park, Harare"
            +   "<div style=\"margin-top:16px;padding-top:14px;border-top:1px solid rgba(255,255,255,.14);"
            +     "font-size:11.5px;color:#8496ad;\">"
            +     "InnBucks MicroBank Ltd is a registered Deposit-Taking Microfinance Bank and a member "
            +     "of the Deposit Protection Scheme.</div>"
            + "</td></tr>"
            + "</table></td></tr></table>";
    }

    /**
     * CSS/table-drawn brand lockup — the four-dot roundel + "InnBucks" wordmark
     * + "MicroBank Limited" tagline. Used as the header when no hosted logo URL
     * is set: it renders crisply in every client with no image to load (or wash
     * out). It sits on the NAVY header cell, so — like the hosted dark-ground
     * logo — the wordmark is white and the tagline a light slate; the four dots
     * keep their brand colours.
     */
    private static String cssRoundel() {
        String dot = "width:16px;height:16px;border-radius:50%;font-size:0;line-height:0;"
                + "mso-line-height-rule:exactly;";
        String face = "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"padding-right:12px;vertical-align:middle;\">"
            +   "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"3\">"
            +   "<tr><td style=\"background:#f5b71c;" + dot + "\"></td>"
            +     "<td style=\"background:#7a2e8f;" + dot + "\"></td></tr>"
            +   "<tr><td style=\"background:" + TEAL + ";" + dot + "\"></td>"
            +     "<td style=\"background:#e11b22;" + dot + "\"></td></tr></table></td>"
            + "<td style=\"vertical-align:middle;" + face + "\">"
            +   "<div style=\"font-size:28px;font-weight:800;color:#ffffff;letter-spacing:-.5px;"
            +     "line-height:1;\">InnBucks</div>"
            +   "<div style=\"font-size:12px;font-weight:600;color:" + ON_NAVY_MUTED + ";letter-spacing:.4px;"
            +     "margin-top:3px;\">MicroBank Limited</div>"
            + "</td></tr></table>";
    }

    /**
     * A "bulletproof" button: a one-cell table (background as both
     * {@code bgcolor}, for Outlook, and inline {@code background}, for Gmail)
     * around the link itself.
     */
    private static String button(CallToAction cta) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 24px;\">"
            + "<tr><td bgcolor=\"" + TEAL + "\" style=\"background:" + TEAL + ";border-radius:8px;\">"
            + "<a href=\"" + escapeAttr(cta.url()) + "\" target=\"_blank\" rel=\"noopener noreferrer\" "
            +   "style=\"display:inline-block;padding:12px 28px;font-size:15px;font-weight:700;color:#ffffff;"
            +   "text-decoration:none;border-radius:8px;\">" + escape(cta.label()) + "</a>"
            + "</td></tr></table>";
    }

    /** Split on blank lines into &lt;p&gt; blocks; single newlines become &lt;br&gt;. */
    private static String toParagraphs(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] blocks = body.trim().split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            sb.append("<p style=\"margin:0 0 16px;\">")
              .append(escape(block).replace("\n", "<br>"))
              .append("</p>");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return escape(s).replace("\"", "&quot;");
    }
}
