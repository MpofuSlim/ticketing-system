package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.audit.AuditContext;
import innbucks.paymentservice.audit.AuditEventType;
import innbucks.paymentservice.audit.AuditService;
import innbucks.paymentservice.client.CodeStatementEntry;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.ReconRun;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.ReconRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settlement-reconciliation morning report — distinct from
 * {@link ReconciliationJob}, which resolves IN-FLIGHT payments. This job runs
 * AFTER the fact (nightly, 02:30 UTC, or on demand via the internal ops
 * endpoint) and answers the question every bank's back office asks daily:
 * <b>does OUR ledger agree with the COUNTERPARTY's ledger?</b>
 *
 * <p>Match: our code-bearing payment rows created in the window vs InnBucks'
 * code mini-statement ({@code GET /api/code/{accountId}/miniStatement}).
 * The merchant account id comes from {@code payments.innbucks.recon.account}
 * (env {@code PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT}) — when blank the job is a
 * no-op, so the payment flow itself never depends on it.
 *
 * <p>Buckets (see {@link ReconRun} for the operator-facing meaning):
 * ours-not-theirs (our ledger may be lying — investigate first),
 * theirs-not-ours (customer paid, we didn't book it — make-it-right queue),
 * amount mismatches (cents contract drifted). Every run is persisted; a
 * DISCREPANT run logs at ERROR and bumps
 * {@code payment.recon.discrepancies{type=...}}.
 *
 * <p>Known limitation, detected not assumed: the mini statement is
 * "recent transactions" with an unspecified cap. When its oldest entry is
 * younger than the window start, the run is marked
 * {@code coverageComplete=false} — absence-of-evidence findings then need
 * manual confirmation (the full-statement endpoint is the v2 upgrade path).
 */
@Slf4j
@Component
public class SettlementReconciliationJob {

    /** Our "money received" states — the rows the statement must corroborate. */
    private static final EnumSet<PaymentStatus> MONEY_STATES =
            EnumSet.of(PaymentStatus.SUCCEEDED, PaymentStatus.COMPLETED_UNCONFIRMED);

    private static final int MAX_ROWS = 2000;
    private static final int MAX_DETAIL_LINES = 50;
    private static final int MAX_DETAIL_CHARS = 4000;

    private final PaymentRepository paymentRepository;
    private final ReconRunRepository reconRunRepository;
    private final InnbucksApiClient innbucksApiClient;
    private final PaymentMetrics metrics;
    private final AuditService auditService;
    private final String reconAccount;

    public SettlementReconciliationJob(
            PaymentRepository paymentRepository,
            ReconRunRepository reconRunRepository,
            InnbucksApiClient innbucksApiClient,
            PaymentMetrics metrics,
            AuditService auditService,
            @Value("${payments.innbucks.recon.account:}") String reconAccount) {
        this.paymentRepository = paymentRepository;
        this.reconRunRepository = reconRunRepository;
        this.innbucksApiClient = innbucksApiClient;
        this.metrics = metrics;
        this.auditService = auditService;
        this.reconAccount = reconAccount == null ? "" : reconAccount.trim();
    }

    /** Nightly run over YESTERDAY (UTC). Cron overridable per environment. */
    @Scheduled(cron = "${payment-service.recon.cron:0 30 2 * * *}", zone = "UTC")
    public void nightly() {
        runFor(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    /**
     * Reconcile one UTC calendar day. Returns the persisted run, or null
     * when reconciliation is unconfigured (no merchant account id).
     */
    public ReconRun runFor(LocalDate day) {
        if (reconAccount.isBlank()) {
            log.info("Settlement recon skipped — payments.innbucks.recon.account "
                    + "(PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT) is not configured");
            metrics.incReconRun("skipped");
            return null;
        }
        Instant windowStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Payment> ours = paymentRepository.findByInnbucksCodeIsNotNullAndCreatedAtBetween(
                windowStart, windowEnd, PageRequest.of(0, MAX_ROWS));

        List<CodeStatementEntry> statement;
        try {
            statement = innbucksApiClient.fetchCodeMiniStatement(reconAccount);
        } catch (RuntimeException e) {
            log.error("Settlement recon FAILED for {} — could not fetch the InnBucks mini statement: {}",
                    day, e.getMessage());
            metrics.incReconRun("failed");
            return reconRunRepository.save(ReconRun.builder()
                    .windowStart(windowStart).windowEnd(windowEnd)
                    .source("MINI_STATEMENT").status(ReconRun.Status.FAILED)
                    .coverageComplete(false)
                    .error(truncate(e.getMessage(), 1000))
                    .build());
        }

        ReconRun run = match(windowStart, windowEnd, ours, statement);
        reconRunRepository.save(run);
        metrics.incReconRun(run.getStatus().name().toLowerCase(java.util.Locale.ROOT));
        if (run.getStatus() == ReconRun.Status.DISCREPANT) {
            log.error("SETTLEMENT RECON DISCREPANT for {}: oursNotTheirs={} theirsNotOurs={} amountMismatches={} "
                            + "matched={} coverageComplete={} — see recon_run {}",
                    day, run.getOursNotTheirs(), run.getTheirsNotOurs(), run.getAmountMismatches(),
                    run.getMatchedCount(), run.isCoverageComplete(), run.getId());
            // A09: seal a tamper-evident audit row for the discrepancy — our
            // ledger and the InnBucks statement disagree, a money incident that
            // must survive later edits to recon_run.
            Map<String, Object> meta = new HashMap<>();
            meta.put("day", day.toString());
            meta.put("oursNotTheirs", run.getOursNotTheirs());
            meta.put("theirsNotOurs", run.getTheirsNotOurs());
            meta.put("amountMismatches", run.getAmountMismatches());
            meta.put("matched", run.getMatchedCount());
            meta.put("coverageComplete", run.isCoverageComplete());
            auditService.record(
                    AuditEventType.PAYMENT_RECON_DISCREPANCY,
                    AuditService.OUTCOME_FAILURE,
                    "system", AuditService.ACTOR_TYPE_SYSTEM,
                    run.getId() == null ? null : String.valueOf(run.getId()), "RECON_RUN",
                    "settlement discrepancy",
                    meta,
                    AuditContext.none());
        } else {
            log.info("Settlement recon CLEAN for {}: matched={} amountCents={} coverageComplete={}",
                    day, run.getMatchedCount(), run.getMatchedAmountCents(), run.isCoverageComplete());
        }
        return run;
    }

    private ReconRun match(Instant windowStart, Instant windowEnd,
                           List<Payment> ours, List<CodeStatementEntry> statement) {
        LocalDateTime wsLocal = LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC);
        LocalDateTime weLocal = LocalDateTime.ofInstant(windowEnd, ZoneOffset.UTC);

        // Coverage: if the (recency-capped) statement's OLDEST entry is
        // younger than the window start, entries from early in the window
        // may simply have scrolled off — weak evidence for "missing".
        boolean coverageComplete = true;
        LocalDateTime oldest = statement.stream()
                .map(CodeStatementEntry::createdAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        if (!statement.isEmpty() && (oldest == null || oldest.isAfter(wsLocal))) {
            coverageComplete = false;
        }
        if (statement.isEmpty() && !ours.isEmpty()) {
            coverageComplete = false;
        }

        // Their finalised codes in the window. Entries without a parseable
        // date are kept — excluding them could hide a real credit.
        Map<String, CodeStatementEntry> theirFinalised = new HashMap<>();
        for (CodeStatementEntry e : statement) {
            if (!e.isFinalised() || e.code() == null) continue;
            LocalDateTime t = e.createdAt();
            if (t != null && (t.isBefore(wsLocal) || !t.isBefore(weLocal))) continue;
            theirFinalised.put(e.code(), e);
        }

        Map<String, Payment> oursByCode = new HashMap<>();
        for (Payment p : ours) {
            oursByCode.put(p.getInnbucksCode(), p);
        }

        int matched = 0;
        long matchedAmountCents = 0;
        int oursNotTheirs = 0;
        int theirsNotOurs = 0;
        int amountMismatches = 0;
        List<String> detail = new ArrayList<>();

        for (Payment p : ours) {
            if (!MONEY_STATES.contains(p.getStatus())) continue;
            CodeStatementEntry theirs = theirFinalised.remove(p.getInnbucksCode());
            if (theirs == null) {
                oursNotTheirs++;
                metrics.incReconDiscrepancy("ours_not_theirs");
                addDetail(detail, "OURS_NOT_THEIRS " + p.getPaymentReference()
                        + " code=" + p.getInnbucksCode() + " status=" + p.getStatus()
                        + " amount=" + p.getAmount()
                        + (coverageComplete ? "" : " (statement coverage incomplete — confirm manually)"));
                continue;
            }
            Long ourCents = toCents(p.getAmount());
            if (theirs.amountCents() != null && ourCents != null
                    && !theirs.amountCents().equals(ourCents)) {
                amountMismatches++;
                metrics.incReconDiscrepancy("amount_mismatch");
                addDetail(detail, "AMOUNT_MISMATCH " + p.getPaymentReference()
                        + " code=" + p.getInnbucksCode()
                        + " ours=" + ourCents + "c theirs=" + theirs.amountCents() + "c");
                continue;
            }
            matched++;
            matchedAmountCents += ourCents != null ? ourCents : 0;
        }

        // Whatever finalised codes remain were PAID per InnBucks but not
        // booked as money by us — either a row stuck in a non-money state
        // (poller backstop) or no row at all (worst case).
        for (CodeStatementEntry e : theirFinalised.values()) {
            theirsNotOurs++;
            metrics.incReconDiscrepancy("theirs_not_ours");
            Payment known = oursByCode.get(e.code());
            addDetail(detail, "THEIRS_NOT_OURS code=" + e.code()
                    + " theirAmount=" + e.amountCents() + "c"
                    + (known != null
                    ? " ourRow=" + known.getPaymentReference() + " status=" + known.getStatus()
                    : " NO MATCHING ROW"));
        }

        boolean discrepant = oursNotTheirs + theirsNotOurs + amountMismatches > 0;
        return ReconRun.builder()
                .windowStart(windowStart).windowEnd(windowEnd)
                .source("MINI_STATEMENT")
                .status(discrepant ? ReconRun.Status.DISCREPANT : ReconRun.Status.CLEAN)
                .coverageComplete(coverageComplete)
                .matchedCount(matched)
                .matchedAmountCents(matchedAmountCents)
                .oursNotTheirs(oursNotTheirs)
                .theirsNotOurs(theirsNotOurs)
                .amountMismatches(amountMismatches)
                .discrepancyDetail(detail.isEmpty() ? null
                        : truncate(String.join("\n", detail), MAX_DETAIL_CHARS))
                .build();
    }

    private static void addDetail(List<String> detail, String line) {
        if (detail.size() < MAX_DETAIL_LINES) {
            detail.add(line);
        } else if (detail.size() == MAX_DETAIL_LINES) {
            detail.add("... further discrepancies truncated; query the ledger directly");
        }
    }

    private static Long toCents(BigDecimal amount) {
        if (amount == null) return null;
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
