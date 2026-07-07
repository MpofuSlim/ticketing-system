package innbucks.paymentservice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Writes append-only rows to the {@code audit_events} table for
 * security-sensitive actions (auth events, admin operations,
 * password changes, etc.).
 *
 * <h2>Transactional isolation</h2>
 * Every {@code record(...)} call runs in a REQUIRES_NEW transaction
 * managed by {@link TransactionTemplate}. Two properties follow:
 * <ul>
 *   <li><b>Survives caller rollback</b> — a /auth/login that throws
 *       and rolls back STILL persists the audit row, so an attacker
 *       can't suppress their own audit trail by crashing the
 *       request mid-flight.</li>
 *   <li><b>Doesn't fail caller</b> — exceptions from the audit
 *       write are caught and logged inside {@code record(...)} so
 *       a transient DB hiccup on the audit path doesn't bubble up
 *       and reject an otherwise-valid login.</li>
 * </ul>
 *
 * <p>This is a deliberate trade-off: an audit gap is preferable to
 * a hard outage. Operators reading the application logs will see
 * the {@code AUDIT_WRITE_FAILED} signal and can reconcile from
 * other sources (gateway access log, OTel spans).
 *
 * <h2>Sensitive data</h2>
 * Callers MUST NOT pass passwords, raw tokens, OTP codes, or PIN
 * material in {@code metadata}. Identifiers (email / msisdn) are
 * acceptable because they're already in {@code users} alongside
 * the {@code actor_id} this row points at. Phone numbers in
 * metadata should use the last-4 masking the rest of the codebase
 * uses for log lines.
 */
@Service
@Slf4j
public class AuditService {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    public static final String ACTOR_TYPE_USER = "USER";
    public static final String ACTOR_TYPE_ANONYMOUS = "ANONYMOUS";
    public static final String ACTOR_TYPE_SYSTEM = "SYSTEM";

    public static final String TARGET_TYPE_USER = "USER";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** Field separator for the canonical HMAC input — the ASCII Unit Separator,
     *  which cannot appear in any of the hashed values, so field boundaries are
     *  unambiguous (no delimiter-injection across fields). */
    private static final char SEP = '';

    private final AuditEventRepository repository;
    private final AuditChainHeadRepository chainHeadRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SecretKeySpec hmacKey;

    public AuditService(AuditEventRepository repository,
                        AuditChainHeadRepository chainHeadRepository,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        @Value("${audit.hmac-secret:change-me-audit-hmac-secret}") String hmacSecret) {
        this.repository = repository;
        this.chainHeadRepository = chainHeadRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hmacKey = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * Persist one audit row. Any exception during the write is
     * caught, logged, and swallowed — the caller's flow is never
     * interrupted by an audit failure.
     *
     * <p>{@code context} carries the network-side fields (ip,
     * userAgent); pass {@link AuditContext#none()} when the call
     * site doesn't have an HTTP request handy (background jobs).
     */
    public void record(AuditEventType type,
                       String outcome,
                       String actorId,
                       String actorType,
                       String targetId,
                       String targetType,
                       String failureReason,
                       Map<String, Object> metadata,
                       AuditContext context) {
        AuditEvent event = AuditEvent.builder()
                .occurredAt(Instant.now())
                .eventType(type.name())
                .outcome(outcome)
                .actorId(actorId)
                .actorType(actorType)
                .targetId(targetId)
                .targetType(targetType)
                .ipAddress(context == null ? null : context.ipAddress())
                .userAgent(truncate(context == null ? null : context.userAgent(), 512))
                .failureReason(truncate(failureReason, 255))
                .correlationId(MDC.get("correlationId"))
                .metadata(serialiseMetadata(metadata))
                .build();
        // Tamper-evidence: seal the row with an HMAC over its immutable fields
        // before it is persisted (OWASP A09). See computeHmac / AuditIntegrityVerifier.
        event.setRowHmac(computeHmac(event));
        try {
            transactionTemplate.execute(status -> appendChained(event));
        } catch (RuntimeException ex) {
            // Don't propagate: a broken audit path must not break login.
            // Operators reading logs will see this marker and can pivot
            // to gateway access logs / OTel for reconstruction.
            log.error("AUDIT_WRITE_FAILED type={} outcome={} actorId={} reason={}",
                    type.name(), outcome, actorId, ex.getMessage(), ex);
        }
    }

    /**
     * Persist one already-sealed row as the next link in the hash-chain (OWASP
     * A09). Runs inside the caller's REQUIRES_NEW audit transaction.
     *
     * <p>Takes a {@code SELECT ... FOR UPDATE} on the single {@code audit_chain_head}
     * row FIRST, so concurrent audit writers serialise here and each appends to a
     * single, un-forked chain. Then it links this row to its predecessor —
     * {@code chain_hmac = HMAC(key, prev_chain_hmac || row_hmac)} — persists the
     * row, and advances the head. The lock is released when the transaction
     * commits. The head row is seeded by migration V10; the {@code orElseGet}
     * genesis fallback only fires if it is somehow absent (keeps a fresh/legacy
     * DB writable rather than wedging the audit path).
     */
    private AuditEvent appendChained(AuditEvent event) {
        AuditChainHead head = chainHeadRepository.lockHead()
                .orElseGet(() -> new AuditChainHead(1, null, null));
        String chainHmac = computeChainHmac(head.getChainHmac(), event.getRowHmac());
        event.setChainHmac(chainHmac);
        AuditEvent saved = repository.save(event);
        head.setChainHmac(chainHmac);
        head.setLastEventId(saved.getId());
        chainHeadRepository.save(head);
        return saved;
    }

    /** Convenience overload for success rows that need no failure_reason. */
    public void recordSuccess(AuditEventType type,
                              String actorId,
                              String actorType,
                              String targetId,
                              String targetType,
                              Map<String, Object> metadata,
                              AuditContext context) {
        record(type, OUTCOME_SUCCESS, actorId, actorType, targetId, targetType,
                null, metadata, context);
    }

    /** Convenience overload for failure rows that need a reason. */
    public void recordFailure(AuditEventType type,
                              String actorId,
                              String actorType,
                              String targetId,
                              String targetType,
                              String failureReason,
                              Map<String, Object> metadata,
                              AuditContext context) {
        record(type, OUTCOME_FAILURE, actorId, actorType, targetId, targetType,
                failureReason, metadata, context);
    }

    private String serialiseMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            // A serialisation failure shouldn't drop the row entirely —
            // record a marker so the rest of the audit data still
            // lands and operators know to investigate.
            log.warn("AUDIT_METADATA_SERIALISATION_FAILED keys={} reason={}",
                    metadata.keySet(), ex.getMessage());
            return "{\"_error\":\"metadata-serialisation-failed\"}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * HMAC-SHA256 over the row's immutable fields, hex-encoded (OWASP A09
     * tamper-evidence). Deterministic — the same row always yields the same tag,
     * so {@code AuditIntegrityVerifier} can recompute and compare to detect any
     * post-write modification. Fields are joined with the {@link #SEP} unit
     * separator so no value can forge a boundary. Excludes {@code id}
     * (DB-assigned) and {@code rowHmac} itself.
     */
    public String computeHmac(AuditEvent e) {
        String canonical = String.valueOf(e.getOccurredAt()) + SEP
                + nz(e.getEventType()) + SEP
                + nz(e.getOutcome()) + SEP
                + nz(e.getActorId()) + SEP
                + nz(e.getActorType()) + SEP
                + nz(e.getTargetId()) + SEP
                + nz(e.getTargetType()) + SEP
                + nz(e.getIpAddress()) + SEP
                + nz(e.getUserAgent()) + SEP
                + nz(e.getFailureReason()) + SEP
                + nz(e.getCorrelationId()) + SEP
                + nz(e.getMetadata());
        return hmacHex(canonical);
    }

    /**
     * Chain link tag: {@code HMAC-SHA256(key, prev_chain_hmac || row_hmac)},
     * hex-encoded (OWASP A09 deletion/reorder-evidence). Binding each row to its
     * predecessor's chain tag makes any deletion, reordering, or tail-truncation
     * break the link at the next surviving row — and the attacker can't repair
     * the downstream chain without the HMAC key. {@code prevChainHmac} is null
     * for the genesis row (first ever chained write). Uses the {@link #SEP} unit
     * separator so the two hex fields can't forge a boundary.
     */
    public String computeChainHmac(String prevChainHmac, String rowHmac) {
        return hmacHex(nz(prevChainHmac) + SEP + nz(rowHmac));
    }

    private String hmacHex(String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] tag = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(tag);
        } catch (GeneralSecurityException ex) {
            // HmacSHA256 is a JCE standard algorithm — always present. A failure
            // here is unrecoverable and must not silently persist an unsealed row.
            throw new IllegalStateException("Failed to compute audit HMAC", ex);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
