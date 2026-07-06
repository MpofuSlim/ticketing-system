package innbucks.paymentservice.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/**
 * Centralized HTML sanitization for user-supplied free text (OWASP A03 / stored-XSS).
 * stripAll removes ALL markup (plain-text fields). Applied on write paths so stored data
 * is safe regardless of how any client renders it. Entities are decoded so "Tom &amp; Jerry"
 * round-trips unchanged.
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {}

    private static final Safelist RICH_TEXT = Safelist.basic()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6");

    private static final Document.OutputSettings NO_PRETTY =
            new Document.OutputSettings().prettyPrint(false);

    public static String stripAll(String input) {
        if (input == null) {
            return null;
        }
        return Parser.unescapeEntities(Jsoup.clean(input, "", Safelist.none(), NO_PRETTY), false);
    }

    public static String sanitizeRichText(String input) {
        if (input == null) {
            return null;
        }
        return Jsoup.clean(input, "", RICH_TEXT, NO_PRETTY);
    }
}
