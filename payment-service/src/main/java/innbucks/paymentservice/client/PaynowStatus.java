package innbucks.paymentservice.client;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * A Paynow status, from a poll, a trace, an O'mari code reply or a result-URL
 * update. Only the outcome and the echoes are load-bearing.
 *
 * @param outcome         what the status means for us
 * @param rawStatus       Paynow's status text as sent, e.g. "Awaiting Delivery"
 * @param reference       our reference, echoed
 * @param paynowReference Paynow's own reference for the transaction
 * @param amount          the amount, echoed (major units)
 * @param pollUrl         the poll URL, echoed (a trace is how we learn it after a timeout)
 */
public record PaynowStatus(
        Outcome outcome,
        String rawStatus,
        String reference,
        String paynowReference,
        BigDecimal amount,
        String pollUrl) {

    /**
     * <ul>
     *   <li>{@link #PAID}: Paid, Awaiting Delivery, Delivered. All three mean
     *       the customer paid; the last two only say Paynow holds the funds
     *       until delivery is confirmed, which is a settlement matter.</li>
     *   <li>{@link #OPEN}: Created, Sent. Not paid yet.</li>
     *   <li>{@link #CANCELLED}: Cancelled, Failed. Not paid, and never will
     *       be: "may not be resumed and needs to be recreated".</li>
     *   <li>{@link #REVERSED}: Refunded, Disputed. Money paid and then taken
     *       back or held: an operator's case, never automatic.</li>
     *   <li>{@link #NOT_FOUND}: a trace found no transaction for our trace id.</li>
     *   <li>{@link #UNKNOWN}: a status we do not know. Never guessed.</li>
     * </ul>
     */
    public enum Outcome { PAID, OPEN, CANCELLED, REVERSED, NOT_FOUND, UNKNOWN }

    /** The outcome for a Paynow status text. */
    public static Outcome outcomeOf(String status) {
        if (status == null) return Outcome.UNKNOWN;
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "paid", "awaiting delivery", "delivered" -> Outcome.PAID;
            case "created", "sent" -> Outcome.OPEN;
            case "cancelled", "canceled", "failed" -> Outcome.CANCELLED;
            case "refunded", "disputed" -> Outcome.REVERSED;
            case "notfound" -> Outcome.NOT_FOUND;
            default -> Outcome.UNKNOWN;
        };
    }
}
