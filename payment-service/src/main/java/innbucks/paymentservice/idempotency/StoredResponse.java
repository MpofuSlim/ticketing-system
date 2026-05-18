package innbucks.paymentservice.idempotency;

/**
 * Snapshot of a response produced for a given {@code Idempotency-Key}.
 *
 * <p>{@code bodySha256} fingerprints the inbound request body that produced
 * this response. On a replay we recompute the inbound SHA and compare —
 * a match replays the cached response; a mismatch is a key-reuse-with-
 * different-body conflict and gets 422 {@code idempotency_conflict}.
 * Without this, a careless client reusing one key for "$1 transfer" and
 * "$1000 transfer" would silently get the $1 response back for the second
 * call. (Stripe's idempotency contract — same key + different body — works
 * the same way.)
 */
public record StoredResponse(int status, String contentType, byte[] body, String bodySha256) {
}
