package innbucks.paymentservice.exception;

/**
 * Client supplied a request the server can't process at the business-rule
 * layer — wrong combination of cash/points for the selected paymentMethod,
 * unparseable amount string, daily / per-transaction velocity cap exceeded,
 * Redis velocity check temporarily unreachable. Maps to HTTP 400 via
 * {@link GlobalExceptionHandler#handle(BadRequestException)}.
 *
 * <p>Replaces {@code throw new IllegalArgumentException("X")} at deliberate
 * 4xx throw sites — the old GlobalExceptionHandler {@code IllegalArgumentException}
 * mapping returned 400 with the raw exception message, which also caught
 * accidental JDK IAEs (e.g. {@code Map.of} with null value, {@code Objects.requireNonNull}
 * failures) and surfaced their internal-looking messages to clients as 400s.
 * The typed exception fixes the coupling: only deliberate 4xx text reaches
 * the client; accidental IAEs fall to the sanitised 500 catch-all.
 *
 * <p>Field-level bean-validation failures keep their own 400 path via the
 * existing {@code MethodArgumentNotValidException} handler — there the body
 * carries {@code data.fields} for granular FE rendering. Use this exception
 * for business-rule rejections that don't bind to a single request field.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
