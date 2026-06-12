package innbucks.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local ledger row for an InnBucks/veengu-backed ticketing payment. ONE row
 * per attempted payment — inserted PENDING before the call to
 * innbucks-core-gateway, then UPDATEd to SUCCEEDED or FAILED based on the
 * veengu verdict. The two writes happen in separate transactions
 * (Propagation.REQUIRES_NEW on PaymentRecordService methods) so a successful
 * veengu debit followed by a local DB blip leaves a PENDING row in the
 * ledger for reconciliation to investigate, rather than the row vanishing
 * silently.
 *
 * <p>Deliberately separate from the existing {@link Transaction} table:
 * {@code transactions} is the customer-initiated wallet transfer / withdrawal
 * ledger (different semantics, different velocity caps, different state
 * machine on top in {@code TransactionService}). {@code payment} is the
 * checkout-time debit for a booking — narrower domain, simpler shape.
 *
 * <p>Conventions mirror {@link Transaction}:
 * <ul>
 *   <li>Client-assigned UUID id (so we can log it before commit).</li>
 *   <li>{@code Instant} timestamps (always UTC; aligns with TIMESTAMPTZ).</li>
 *   <li>{@code @Enumerated(STRING)} for the status enum.</li>
 *   <li>{@code created_at} not updatable; {@code completed_at} set on the
 *       terminal transition.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Caller-assigned reference passed to veengu as its {@code reference}
     * field — the idempotency key on the upstream side. We generate it
     * locally with a {@code TKT-PMT-<uuid>} prefix so support can spot
     * ticketing rows in veengu's logs at a glance.
     */
    @Column(name = "payment_reference", nullable = false, unique = true, length = 64)
    private String paymentReference;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "customer_msisdn", nullable = false, length = 32)
    private String customerMsisdn;

    /**
     * Direct-debit-era columns (nullable since V6): the 2D-code flow has no
     * wallet lookup and no destination account — the merchant identity is
     * implicit in the API credentials, and the customer approves the code
     * from their own app. Kept for historical rows.
     */
    @Column(name = "customer_account", length = 64)
    private String customerAccount;

    @Column(name = "merchant_account", length = 64)
    private String merchantAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /** Veengu's internal transaction id, populated on terminal SUCCEEDED. */
    @Column(name = "veengu_transaction_id", length = 64)
    private String veenguTransactionId;

    /** Veengu's error code (e.g. NOT_SUFFICIENT_FUNDS), populated on FAILED. */
    @Column(name = "upstream_error_code", length = 64)
    private String upstreamErrorCode;

    @Column(name = "upstream_error_message", length = 500)
    private String upstreamErrorMessage;

    /** Idempotency key supplied by the caller via the Idempotency-Key header. */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Booking-service's confirmation number, populated after the post-debit confirm. */
    @Column(name = "confirmation_number", length = 64)
    private String confirmationNumber;

    /**
     * The InnBucks 2D code the customer approves in their own app/USSD
     * ({@code code} from {@code POST /api/code/generate}). Echoed on the
     * {@code POST /payments} response — the FE renders it on the checkout
     * screen; there is no out-of-band delivery.
     */
    @Column(name = "innbucks_code", length = 32)
    private String innbucksCode;

    /**
     * InnBucks-side handle for the code ({@code authNumber} from generation) —
     * the {@code originalReference} the status poller queries with.
     */
    @Column(name = "code_auth_number", length = 64)
    private String codeAuthNumber;

    /**
     * Our local deadline for the code (issue time + configured TTL). The
     * poller expires a still-New code shortly after this passes; rows whose
     * upstream status can't be read are NEVER auto-expired (see
     * ReconciliationJob — an UNKNOWN row might already be paid).
     */
    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    /**
     * InnBucks-rendered QR image (base64) for this code — the Scan-to-Pay
     * twin of {@link #innbucksCode}. Persisted so a replay re-surfaces the
     * exact artifact the customer was shown (and the ledger keeps it for
     * disputes); a few KB per row, only while codes are in flight.
     */
    @Column(name = "code_qr_base64")
    private String codeQrBase64;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }

    /**
     * Ledger state machine. Transition legality is enforced by
     * {@code PaymentRecordService} (terminal states are immutable; every
     * transition is journalled to {@code payment_event}).
     *
     * <ul>
     *   <li>{@link #PENDING} — row opened before the upstream call.</li>
     *   <li>{@link #SUCCEEDED} — money moved AND booking confirmed. Terminal.</li>
     *   <li>{@link #FAILED} — definitively no money moved (upstream rejected,
     *       or request never reached the processor). Terminal; frees the
     *       booking for a retry payment.</li>
     *   <li>{@link #IN_DOUBT} — the upstream call timed out or returned an
     *       unclassifiable outcome: money MAY have moved. Never auto-failed —
     *       only the reconciler (by querying the processor) or an operator
     *       may resolve it. Customer-facing status stays PROCESSING.</li>
     *   <li>{@link #COMPLETED_UNCONFIRMED} — money DEFINITELY moved (the
     *       customer paid the code) but the booking confirm failed. The
     *       reconciler retries the confirm and promotes to SUCCEEDED;
     *       sustained presence here is a page.</li>
     *   <li>{@link #TOKEN_ISSUED} — the 2D-code flow's waiting state: an
     *       InnBucks PAYMENT code was issued and delivered; the customer
     *       hasn't approved it yet. The poller resolves it (Paid →
     *       SUCCEEDED, Expired/Timed Out → EXPIRED). Occupies the booking's
     *       single payment slot.</li>
     *   <li>{@link #EXPIRED} — the code lapsed unpaid. Terminal; frees the
     *       booking slot for a fresh payment attempt (new code).</li>
     * </ul>
     *
     * <p>The remaining values ({@link #CONSENTED}, {@link #EXECUTING},
     * {@link #REQUIRES_AUTH}, {@link #REJECTED}) stay RESERVED with no
     * writer — declared so the DB CHECK constraint and the
     * one-active-payment-per-booking index already cover them.
     */
    public enum PaymentStatus {
        PENDING, SUCCEEDED, FAILED,
        IN_DOUBT, COMPLETED_UNCONFIRMED,
        TOKEN_ISSUED, CONSENTED, EXECUTING, REQUIRES_AUTH,
        REJECTED, EXPIRED
    }
}
