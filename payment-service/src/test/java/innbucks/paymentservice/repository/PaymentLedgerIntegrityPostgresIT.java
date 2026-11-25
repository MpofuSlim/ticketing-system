package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentEvent;
import innbucks.paymentservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the V5 ledger-hardening invariants on REAL Postgres (Flyway-applied
 * schema, the exact partial index production runs):
 * <ul>
 *   <li>{@code uq_payment_active_booking}: a second active (or successful)
 *       payment row for the same booking is refused BY THE DATABASE — the
 *       application pre-check is UX, this index is the truth;</li>
 *   <li>terminal failures (FAILED) free the slot so a customer can retry;</li>
 *   <li>the widened status vocabulary round-trips through the CHECK
 *       constraint;</li>
 *   <li>{@code payment_event} accepts journal rows referencing the payment.</li>
 * </ul>
 */
class PaymentLedgerIntegrityPostgresIT extends PostgresIntegrationTestBase {

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentEventRepository events;

    @Autowired
    private ReconRunRepository recons;

    private static Payment row(UUID bookingId, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId)
                .customerMsisdn("+263770000001")
                .customerAccount("CUST-1")
                .merchantAccount("MERCH-1")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(status)
                .build();
    }

    /**
     * The card sweep's query on real Postgres: it returns open card checkouts
     * past their ceiling (deadline + grace, or 30 minutes from creation when
     * the deadline is missing) and nothing a shopper may still be paying on.
     * The sweep used to read every open checkout, and a read kills a checkout
     * nobody has paid yet (staging, 2026-11-12).
     */
    @Test
    void cardSweepQuery_returnsOnlyOpenCheckoutsPastTheirCeiling() {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant deadlineBefore = now.minus(java.time.Duration.ofMinutes(2));
        java.time.Instant createdBefore = now.minus(java.time.Duration.ofMinutes(30));

        Payment lapsed = cardRow(PaymentStatus.TOKEN_ISSUED, now.minus(java.time.Duration.ofMinutes(31)),
                now.minus(java.time.Duration.ofMinutes(3)));
        Payment open = cardRow(PaymentStatus.TOKEN_ISSUED, now.minus(java.time.Duration.ofMinutes(5)),
                now.plus(java.time.Duration.ofMinutes(23)));
        Payment inGrace = cardRow(PaymentStatus.TOKEN_ISSUED, now.minus(java.time.Duration.ofMinutes(29)),
                now.minus(java.time.Duration.ofMinutes(1)));
        Payment oldNoDeadline = cardRow(PaymentStatus.TOKEN_ISSUED, now.minus(java.time.Duration.ofMinutes(31)), null);
        Payment youngNoDeadline = cardRow(PaymentStatus.TOKEN_ISSUED, now.minus(java.time.Duration.ofMinutes(10)), null);
        Payment closed = cardRow(PaymentStatus.EXPIRED, now.minus(java.time.Duration.ofMinutes(40)),
                now.minus(java.time.Duration.ofMinutes(12)));
        Payment codeRow = row(UUID.randomUUID(), PaymentStatus.TOKEN_ISSUED);
        codeRow.setCreatedAt(now.minus(java.time.Duration.ofMinutes(40)));
        codeRow.setCodeExpiresAt(now.minus(java.time.Duration.ofMinutes(20)));
        for (Payment p : java.util.List.of(lapsed, open, inGrace, oldNoDeadline, youngNoDeadline, closed, codeRow)) {
            payments.saveAndFlush(p);
        }

        java.util.Set<UUID> due = payments.findOpenPastCeiling(PaymentStatus.TOKEN_ISSUED,
                        innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD, deadlineBefore, createdBefore,
                        org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream().map(Payment::getId).collect(java.util.stream.Collectors.toSet());

        assertTrue(due.contains(lapsed.getId()), "deadline + grace passed");
        assertTrue(due.contains(oldNoDeadline.getId()), "no deadline: 30 minutes from creation passed");
        assertFalse(due.contains(open.getId()), "the shopper may still be on the card form");
        assertFalse(due.contains(inGrace.getId()), "past the local deadline but inside the grace: still payable");
        assertFalse(due.contains(youngNoDeadline.getId()));
        assertFalse(due.contains(closed.getId()), "only open rows");
        assertFalse(due.contains(codeRow.getId()), "only the card rail");
    }

    private static Payment cardRow(PaymentStatus status, java.time.Instant createdAt,
                                   java.time.Instant deadline) {
        Payment p = row(UUID.randomUUID(), status);
        p.setPaymentRail(innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD);
        p.setCheckoutId("CHK" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + ".uat01-vm-tx04");
        p.setCreatedAt(createdAt);
        p.setCodeExpiresAt(deadline);
        return p;
    }

    @Autowired
    private innbucks.paymentservice.service.PaymentRecordService records;

    // -- Paynow (review fixes, 2026-11-25) ----------------------------------------

    private static final String PAYNOW_POLL = "https://www.paynow.co.zw/Interface/CheckPayment/?guid=it";

    private static Payment paynowRow(innbucks.paymentservice.entity.PaymentRail rail, PaymentStatus status,
                                     java.time.Instant createdAt, java.time.Instant deadline,
                                     String pollUrl, java.time.Instant checkedAt) {
        Payment p = row(UUID.randomUUID(), status);
        p.setPaymentRail(rail);
        p.setPaynowTrace(UUID.randomUUID().toString().replace("-", ""));
        p.setPaynowPollUrl(pollUrl);
        p.setCreatedAt(createdAt);
        p.setCodeExpiresAt(deadline);
        p.setPaynowCheckedAt(checkedAt);
        return p;
    }

    /**
     * The Paynow sweep's query on real Postgres: it returns exactly the open
     * Paynow payments that need a check (reply lost, past the deadline, or not
     * checked for the re-check interval), least recently checked first. The
     * sweep used to read a page of every open payment and skip the ones not due
     * in Java, so on a busy sale a page of young payments hid the due ones.
     */
    @Test
    void paynowSweepQuery_returnsOnlyTheOpenPaymentsDueACheck() {
        java.time.Instant now = java.time.Instant.now();
        var card = innbucks.paymentservice.entity.PaymentRail.PAYNOW_CARD;
        var oneMoney = innbucks.paymentservice.entity.PaymentRail.ONEMONEY;
        var omari = innbucks.paymentservice.entity.PaymentRail.OMARI;
        Payment lostReply = paynowRow(oneMoney, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(60),
                now.plusSeconds(540), null, null);
        Payment youngNoReply = paynowRow(oneMoney, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(10),
                now.plusSeconds(590), null, null);
        Payment pastDeadline = paynowRow(card, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(2400),
                now.minusSeconds(180), PAYNOW_POLL, now.minusSeconds(10));
        Payment fresh = paynowRow(omari, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(60),
                now.plusSeconds(540), PAYNOW_POLL, now.minusSeconds(10));
        Payment staleCheck = paynowRow(card, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(600),
                now.plusSeconds(1200), PAYNOW_POLL, now.minusSeconds(180));
        Payment neverChecked = paynowRow(card, PaymentStatus.TOKEN_ISSUED, now.minusSeconds(170),
                now.plusSeconds(1630), PAYNOW_POLL, null);
        Payment closed = paynowRow(card, PaymentStatus.EXPIRED, now.minusSeconds(4000),
                now.minusSeconds(2000), PAYNOW_POLL, null);
        Payment ecocash = row(UUID.randomUUID(), PaymentStatus.TOKEN_ISSUED);
        ecocash.setPaymentRail(innbucks.paymentservice.entity.PaymentRail.ECOCASH);
        ecocash.setCreatedAt(now.minusSeconds(2400));
        for (Payment p : java.util.List.of(lostReply, youngNoReply, pastDeadline, fresh, staleCheck, neverChecked,
                closed, ecocash)) {
            payments.saveAndFlush(p);
        }

        java.util.List<UUID> due = payments.findPaynowDue(PaymentStatus.TOKEN_ISSUED,
                        java.util.EnumSet.of(card, oneMoney, omari),
                        now.minusSeconds(30), now.minusSeconds(120), now.minusSeconds(32 * 60), now.minusSeconds(120),
                        org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream().map(Payment::getId).toList();

        assertTrue(due.contains(lostReply.getId()), "initiate reply lost for over 30 s: looked up by trace");
        assertTrue(due.contains(pastDeadline.getId()), "past deadline + grace: closed or confirmed");
        assertTrue(due.contains(staleCheck.getId()), "not checked for the re-check interval");
        assertTrue(due.contains(neverChecked.getId()), "never checked, older than the re-check interval");
        assertFalse(due.contains(youngNoReply.getId()), "the start may still be waiting for its reply");
        assertFalse(due.contains(fresh.getId()), "checked moments ago, deadline ahead");
        assertFalse(due.contains(closed.getId()), "only open payments");
        assertFalse(due.contains(ecocash.getId()), "only the Paynow rails");
        assertTrue(due.indexOf(staleCheck.getId()) < due.indexOf(pastDeadline.getId()),
                "least recently checked first");
    }

    /**
     * Closed-unpaid Paynow payments due a look for a late payment: in the first
     * hour after closing once every 15 minutes, then every 3 hours, for a day;
     * never one already handed to an operator.
     */
    @Test
    void paynowClosedQuery_returnsClosedPaymentsDueALateLook() {
        java.time.Instant now = java.time.Instant.now();
        var card = innbucks.paymentservice.entity.PaymentRail.PAYNOW_CARD;
        java.util.function.BiFunction<Long, Long, Payment> closedAgo = (closedSecondsAgo, checkedSecondsAgo) -> {
            Payment p = paynowRow(card, PaymentStatus.EXPIRED, now.minusSeconds(closedSecondsAgo + 1800),
                    now.minusSeconds(closedSecondsAgo + 120), PAYNOW_POLL, now.minusSeconds(checkedSecondsAgo));
            p.setCompletedAt(now.minusSeconds(closedSecondsAgo));
            return p;
        };
        Payment firstHourDue = closedAgo.apply(1200L, 1200L);
        Payment firstHourChecked = closedAgo.apply(1200L, 300L);
        Payment laterDue = closedAgo.apply(5 * 3600L, 4 * 3600L);
        Payment laterChecked = closedAgo.apply(5 * 3600L, 3600L);
        Payment pastTheDay = closedAgo.apply(30 * 3600L, 29 * 3600L);
        Payment flagged = closedAgo.apply(1200L, 1200L);
        flagged.setPaynowFlaggedAt(now.minusSeconds(600));
        Payment failed = closedAgo.apply(1200L, 1200L);
        failed.setStatus(PaymentStatus.FAILED);
        for (Payment p : java.util.List.of(firstHourDue, firstHourChecked, laterDue, laterChecked, pastTheDay,
                flagged, failed)) {
            payments.saveAndFlush(p);
        }

        java.util.Set<UUID> due = payments.findPaynowClosedDue(PaymentStatus.EXPIRED,
                        java.util.EnumSet.of(card, innbucks.paymentservice.entity.PaymentRail.ONEMONEY,
                                innbucks.paymentservice.entity.PaymentRail.OMARI),
                        now.minusSeconds(24 * 3600), now.minusSeconds(3600), now.minusSeconds(900),
                        now.minusSeconds(3 * 3600), org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream().map(Payment::getId).collect(java.util.stream.Collectors.toSet());

        assertTrue(due.contains(firstHourDue.getId()), "first hour, not looked at for 15 minutes");
        assertTrue(due.contains(laterDue.getId()), "later in the day, not looked at for 3 hours");
        assertFalse(due.contains(firstHourChecked.getId()));
        assertFalse(due.contains(laterChecked.getId()));
        assertFalse(due.contains(pastTheDay.getId()), "closed more than a day ago");
        assertFalse(due.contains(flagged.getId()), "already an operator's case");
        assertFalse(due.contains(failed.getId()), "only payments closed EXPIRED");
    }

    /**
     * "Paid" and "expired" racing on one Paynow payment: exactly one applies,
     * and claimPaid says which. Each reads the row FOR UPDATE, so the second
     * waits and then sees the first one's result; before the lock both could
     * read it open and both write.
     */
    @Test
    void paynowClaimPaid_racingAnExpiry_appliesExactlyOne() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                java.time.Instant now = java.time.Instant.now();
                Payment p = payments.saveAndFlush(paynowRow(innbucks.paymentservice.entity.PaymentRail.ONEMONEY,
                        PaymentStatus.TOKEN_ISSUED, now, now.plusSeconds(600), PAYNOW_POLL, null));
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var claim = pool.submit(() -> {
                    barrier.await();
                    return records.claimPaid(p.getId(), "PAYNOW-IT", "Paynow reports the payment Paid");
                });
                var expire = pool.submit(() -> {
                    barrier.await();
                    records.markExpired(p.getId(), "Deadline passed — closing unpaid");
                    return null;
                });
                boolean claimed = claim.get(30, java.util.concurrent.TimeUnit.SECONDS);
                expire.get(30, java.util.concurrent.TimeUnit.SECONDS);
                PaymentStatus after = payments.findById(p.getId()).orElseThrow().getStatus();
                assertEquals(claimed ? PaymentStatus.COMPLETED_UNCONFIRMED : PaymentStatus.EXPIRED, after,
                        "round " + round + ": claimed=" + claimed);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void paynowOperatorCase_isFlaggedOnce() {
        java.time.Instant now = java.time.Instant.now();
        Payment p = payments.saveAndFlush(paynowRow(innbucks.paymentservice.entity.PaymentRail.PAYNOW_CARD,
                PaymentStatus.EXPIRED, now.minusSeconds(3000), now.minusSeconds(1000), PAYNOW_POLL, null));

        assertTrue(records.flagPaynowOperatorCase(p.getId()), "the first caller raises the case");
        assertFalse(records.flagPaynowOperatorCase(p.getId()), "every caller after finds it raised");
        assertTrue(payments.findById(p.getId()).orElseThrow().getPaynowFlaggedAt() != null);
    }

    /**
     * The lookup {@code POST /payments} uses to refuse a second payment: the
     * order's latest Paynow attempt we closed that was then handed to an
     * operator because Paynow reported it paid. A closed attempt nobody flagged,
     * a flagged one still parked IN_DOUBT, and another order's are not it.
     */
    @Test
    void paynowPaidAfterCloseLookup_findsOnlyTheOrdersFlaggedClosedAttempt() {
        java.time.Instant now = java.time.Instant.now();
        var card = innbucks.paymentservice.entity.PaymentRail.PAYNOW_CARD;
        var oneMoney = innbucks.paymentservice.entity.PaymentRail.ONEMONEY;
        UUID bookingId = UUID.randomUUID();
        java.util.function.BiFunction<PaymentStatus, java.time.Instant, Payment> attempt = (status, createdAt) -> {
            Payment p = paynowRow(card, status, createdAt, createdAt.plusSeconds(1800), PAYNOW_POLL, null);
            p.setBookingId(bookingId);
            return p;
        };
        Payment unflagged = payments.saveAndFlush(attempt.apply(PaymentStatus.EXPIRED, now.minusSeconds(4000)));
        Payment paidLate = attempt.apply(PaymentStatus.EXPIRED, now.minusSeconds(3000));
        paidLate.setPaynowFlaggedAt(now.minusSeconds(900));
        payments.saveAndFlush(paidLate);
        Payment laterUnflagged = attempt.apply(PaymentStatus.FAILED, now.minusSeconds(600));
        laterUnflagged.setPaymentRail(oneMoney);
        payments.saveAndFlush(laterUnflagged);
        Payment parked = attempt.apply(PaymentStatus.IN_DOUBT, now.minusSeconds(300));
        parked.setPaynowFlaggedAt(now.minusSeconds(200));
        payments.saveAndFlush(parked);
        Payment otherOrder = paynowRow(card, PaymentStatus.EXPIRED, now.minusSeconds(100), now.plusSeconds(1700),
                PAYNOW_POLL, null);
        otherOrder.setPaynowFlaggedAt(now.minusSeconds(50));
        payments.saveAndFlush(otherOrder);

        var found = payments.findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
                innbucks.paymentservice.order.OrderType.BOOKING, bookingId.toString(),
                innbucks.paymentservice.service.PaynowPaymentService.CLOSED_UNPAID);

        assertEquals(paidLate.getId(), found.map(Payment::getId).orElse(null),
                "the flagged closed attempt, ahead of the newer unflagged and IN_DOUBT ones");
        assertTrue(innbucks.paymentservice.service.PaynowPaymentService.paidAfterClose(found.orElseThrow()));
        assertFalse(payments.findFirstByOrderTypeAndOrderRefAndPaynowFlaggedAtIsNotNullAndStatusInOrderByCreatedAtDesc(
                        innbucks.paymentservice.order.OrderType.BOOKING, UUID.randomUUID().toString(),
                        innbucks.paymentservice.service.PaynowPaymentService.CLOSED_UNPAID).isPresent(),
                "an order with no such attempt");
        assertNull(payments.findById(unflagged.getId()).orElseThrow().getPaynowFlaggedAt());
    }

    @Autowired
    private innbucks.paymentservice.reconciliation.UnconfirmedPaymentAlerter alerter;

    /**
     * The stuck-payment alert never writes the confirm-retry's copy back. The
     * retry reads the row, then calls the booking confirm; a Paynow refund that
     * parks the row IN_DOUBT (and flags it) during that call must survive the
     * alert that follows. It used to save the copy, which merged every stale
     * field back (no {@code @Version}): the row returned to
     * COMPLETED_UNCONFIRMED with its flag cleared, and the next retry issued
     * tickets for money that went back.
     */
    @Test
    void stuckPaymentAlert_neverRevertsAParkThatLandedDuringTheConfirm() {
        java.time.Instant now = java.time.Instant.now();
        Payment row = paynowRow(innbucks.paymentservice.entity.PaymentRail.PAYNOW_CARD,
                PaymentStatus.COMPLETED_UNCONFIRMED, now.minusSeconds(600), now.plusSeconds(1200), PAYNOW_POLL, null);
        row.setCustomerMsisdn(null); // no WhatsApp from a test
        payments.saveAndFlush(row);
        Payment retryCopy = payments.findById(row.getId()).orElseThrow();

        // During the retry's confirm call: Paynow reports the payment refunded.
        assertTrue(records.parkUnconfirmedInDoubt(row.getId(), "refunded before the booking was confirmed"));
        assertTrue(records.flagPaynowOperatorCase(row.getId()));

        alerter.onStillFailing(retryCopy, "booking-service timed out");

        Payment after = payments.findById(row.getId()).orElseThrow();
        assertEquals(PaymentStatus.IN_DOUBT, after.getStatus(), "the park stands");
        assertTrue(after.getPaynowFlaggedAt() != null, "the operator case stays raised");
        assertNull(after.getOperatorAlertedAt(), "a payment that moved on is not announced as stuck");
    }

    /** The claim on a row still waiting for its confirm: once, and nothing but the stamp changes. */
    @Test
    void stuckPaymentAlert_claimsOnce_andTouchesNothingElse() {
        java.time.Instant now = java.time.Instant.now();
        Payment row = paynowRow(innbucks.paymentservice.entity.PaymentRail.ONEMONEY,
                PaymentStatus.COMPLETED_UNCONFIRMED, now.minusSeconds(600), now.plusSeconds(1200), PAYNOW_POLL, null);
        payments.saveAndFlush(row);

        assertTrue(records.claimStuckPaymentAlert(row.getId()), "the first retry claims the alert");
        assertFalse(records.claimStuckPaymentAlert(row.getId()), "every retry after finds it claimed");
        Payment after = payments.findById(row.getId()).orElseThrow();
        assertTrue(after.getOperatorAlertedAt() != null);
        assertEquals(PaymentStatus.COMPLETED_UNCONFIRMED, after.getStatus());
        assertEquals(PAYNOW_POLL, after.getPaynowPollUrl());
    }

    /**
     * A card decline closes the attempt on real Postgres: the row goes FAILED
     * with the decline stamped, and the booking's one payment slot
     * ({@code uq_payment_active_order}) is free at once, so another card or
     * another rail can start without waiting for the checkout's 30 minutes.
     * A row that completed in the meantime keeps its status and gets no stamp.
     */
    @Test
    void cardDecline_closesTheAttempt_andFreesTheBookingsSlot() {
        java.time.Instant now = java.time.Instant.now();
        Payment open = payments.saveAndFlush(cardRow(PaymentStatus.TOKEN_ISSUED, now, now.plusSeconds(1500)));
        Payment paid = payments.saveAndFlush(cardRow(PaymentStatus.COMPLETED_UNCONFIRMED, now, now.plusSeconds(1500)));

        records.markCardDeclined(open.getId(), "800.100.100",
                "800.100.100 (transaction declined for unknown reason)",
                "Card declined, read on the shopper's return: 800.100.100 — attempt closed");
        records.markCardDeclined(paid.getId(), "800.100.100", "declined", "Card declined");

        Payment openAfter = payments.findById(open.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, openAfter.getStatus());
        assertTrue(openAfter.getCardDeclinedAt() != null, "the closed row says the card was declined");
        assertEquals("800.100.100", openAfter.getUpstreamErrorCode());
        Payment paidAfter = payments.findById(paid.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED_UNCONFIRMED, paidAfter.getStatus());
        assertNull(paidAfter.getCardDeclinedAt(), "a row that is no longer open is not stamped");
        assertTrue(events.findAll().stream().anyMatch(e -> open.getId().equals(e.getPaymentId())
                && e.getDetail().contains("800.100.100") && "FAILED".equals(e.getToStatus())));

        // The slot is free: a new attempt for the same booking can open now.
        Payment retry = payments.saveAndFlush(row(open.getBookingId(), PaymentStatus.PENDING));
        assertEquals(open.getOrderRef(), retry.getOrderRef());
    }

    @Test
    void secondActivePaymentForSameBooking_isRefusedByTheIndex() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "two concurrent active payments for one booking must be impossible at the DB level");
    }

    @Test
    void succeededPayment_blocksAnyFurtherPaymentForTheBooking() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.SUCCEEDED));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "an already-paid booking must never accept another payment row");
    }

    @Test
    void inDoubtPayment_holdsTheSlot() {
        // IN_DOUBT means money MAY have moved — a retry payment before the
        // doubt is resolved risks a double debit, so the slot stays occupied.
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.IN_DOUBT));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)));
    }

    @Test
    void terminalFailure_freesTheSlotForARetry() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.FAILED));

        assertDoesNotThrow(() -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "a declined payment must not lock the customer out of retrying");
    }

    @Test
    void newStatusVocabulary_roundTripsThroughTheCheckConstraint() {
        UUID bookingId = UUID.randomUUID();
        Payment p = payments.saveAndFlush(row(bookingId, PaymentStatus.COMPLETED_UNCONFIRMED));

        Payment reloaded = payments.findById(p.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED_UNCONFIRMED, reloaded.getStatus());
    }

    @Test
    void tokenIssuedCode_holdsTheBookingSlot() {
        // An open InnBucks code IS an active payment: a second code for the
        // same booking would let the customer pay twice.
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.TOKEN_ISSUED));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)));
    }

    @Test
    void expiredCode_freesTheSlot_forAFreshCode() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.EXPIRED));

        assertDoesNotThrow(() -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "a lapsed code must not lock the customer out of paying again");
    }

    @Test
    void codeColumns_roundTripThroughV6() {
        // V6 columns + the relaxed account nullability (the code flow stores
        // neither customer_account nor merchant_account).
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Payment p = Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660")
                .codeAuthNumber("1616800")
                .codeQrBase64("aGVsbG8=")
                .codeExpiresAt(expiresAt)
                .build();

        Payment reloaded = payments.findById(payments.saveAndFlush(p).getId()).orElseThrow();
        assertEquals("701285660", reloaded.getInnbucksCode());
        assertEquals("1616800", reloaded.getCodeAuthNumber());
        assertEquals("aGVsbG8=", reloaded.getCodeQrBase64());
        assertEquals(expiresAt, reloaded.getCodeExpiresAt());
        assertNull(reloaded.getCustomerAccount());
        assertNull(reloaded.getMerchantAccount());
    }

    private static Payment marketplaceRow(String orderRef, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-MKT-" + UUID.randomUUID().toString().substring(0, 12))
                .orderType(innbucks.paymentservice.order.OrderType.MARKETPLACE)
                .orderRef(orderRef)
                .customerMsisdn("+263771234567")
                .amount(new BigDecimal("35.50"))
                .currency("USD")
                .status(status)
                .build();
    }

    @Test
    void marketplaceRow_roundTripsThroughV12_withNullBookingId() {
        // V12: booking_id dropped NOT NULL; (order_type, order_ref) is the
        // ledger's order identity. A MARKETPLACE row stores no booking at all.
        Payment p = payments.saveAndFlush(marketplaceRow("MKT-4F9A1C22B7D3", PaymentStatus.PENDING));

        Payment reloaded = payments.findById(p.getId()).orElseThrow();
        assertEquals(innbucks.paymentservice.order.OrderType.MARKETPLACE, reloaded.getOrderType());
        assertEquals("MKT-4F9A1C22B7D3", reloaded.getOrderRef());
        assertNull(reloaded.getBookingId());
    }

    @Test
    void secondActivePaymentForSameMarketplaceOrder_isRefusedByTheOrderIndex() {
        String ref = "MKT-" + UUID.randomUUID().toString().substring(0, 12);
        payments.saveAndFlush(marketplaceRow(ref, PaymentStatus.TOKEN_ISSUED));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(marketplaceRow(ref, PaymentStatus.PENDING)),
                "uq_payment_active_order must arbitrate marketplace orders exactly like bookings");
    }

    @Test
    void sameRefDifferentOrderType_doesNotCollide() {
        // The identity is (order_type, order_ref) — a booking UUID and a
        // marketplace ref that happened to collide textually must not block
        // each other.
        UUID sharedText = UUID.randomUUID();
        payments.saveAndFlush(row(sharedText, PaymentStatus.PENDING));

        assertDoesNotThrow(() -> payments.saveAndFlush(
                marketplaceRow(sharedText.toString(), PaymentStatus.PENDING)));
    }

    private static Payment voucherRow(String orderRef, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-VCH-" + UUID.randomUUID().toString().substring(0, 12))
                .orderType(innbucks.paymentservice.order.OrderType.LOYALTY_VOUCHER)
                .orderRef(orderRef)
                .paymentRail(innbucks.paymentservice.entity.PaymentRail.ECOCASH)
                .customerMsisdn("+263771234567")
                .amount(new BigDecimal("1.00"))
                .currency("USD")
                .status(status)
                .build();
    }

    @Test
    void loyaltyVoucherRow_roundTripsThroughV16() {
        // Before V16, chk_payment_order_type refused this row on every rail —
        // no voucher could be paid electronically at all.
        Payment p = payments.saveAndFlush(voucherRow("VCH-E46F12CF3C1E", PaymentStatus.PENDING));

        Payment reloaded = payments.findById(p.getId()).orElseThrow();
        assertEquals(innbucks.paymentservice.order.OrderType.LOYALTY_VOUCHER, reloaded.getOrderType());
        assertEquals("VCH-E46F12CF3C1E", reloaded.getOrderRef());
        assertNull(reloaded.getBookingId());
    }

    @Test
    void activeOrderIndexViolation_isClassifiedAsTheRace_onRealPostgres() {
        // The classifier reads the REAL exception chain Spring/Hibernate/pgjdbc
        // produce, so it is proven here rather than against a hand-built one.
        String ref = "VCH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        payments.saveAndFlush(voucherRow(ref, PaymentStatus.TOKEN_ISSUED));

        DataIntegrityViolationException race = assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(voucherRow(ref, PaymentStatus.PENDING)));
        assertTrue(innbucks.paymentservice.service.PaymentRecordService.isActiveOrderConflict(race),
                "a second active attempt must read as the one-payment-per-order race (409)");
    }

    @Test
    void otherUniqueViolation_isNotClassifiedAsTheRace_onRealPostgres() {
        // uq_payment_ecocash_correlator: a real integrity failure that is NOT
        // "another attempt holds this order" — it must not become a 409.
        Payment first = voucherRow("VCH-" + UUID.randomUUID().toString().substring(0, 12), PaymentStatus.FAILED);
        first.setEcocashClientCorrelator("1790768730634541617");
        payments.saveAndFlush(first);
        Payment second = voucherRow("VCH-" + UUID.randomUUID().toString().substring(0, 12), PaymentStatus.PENDING);
        second.setEcocashClientCorrelator("1790768730634541617");

        DataIntegrityViolationException other = assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(second));
        assertFalse(innbucks.paymentservice.service.PaymentRecordService.isActiveOrderConflict(other));
    }

    @Test
    void bookingRow_prePersistBackfillsOrderIdentity() {
        // A row built the pre-generalization way (bookingId only) lands as
        // BOOKING + the booking UUID's canonical text — mirroring the V12
        // backfill, so replay lookups by (order_type, order_ref) find it.
        UUID bookingId = UUID.randomUUID();
        Payment p = payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING));

        Payment reloaded = payments.findById(p.getId()).orElseThrow();
        assertEquals(innbucks.paymentservice.order.OrderType.BOOKING, reloaded.getOrderType());
        assertEquals(bookingId.toString(), reloaded.getOrderRef());
    }

    @Test
    void reconRun_roundTripsThroughV8() {
        innbucks.paymentservice.entity.ReconRun run = recons.saveAndFlush(
                innbucks.paymentservice.entity.ReconRun.builder()
                        .windowStart(java.time.Instant.parse("2026-06-11T00:00:00Z"))
                        .windowEnd(java.time.Instant.parse("2026-06-12T00:00:00Z"))
                        .source("MINI_STATEMENT")
                        .status(innbucks.paymentservice.entity.ReconRun.Status.DISCREPANT)
                        .coverageComplete(false)
                        .matchedCount(40).matchedAmountCents(160000)
                        .oursNotTheirs(1).theirsNotOurs(0).amountMismatches(0)
                        .discrepancyDetail("OURS_NOT_THEIRS TKT-PMT-x code=701 status=SUCCEEDED")
                        .build());

        innbucks.paymentservice.entity.ReconRun reloaded = recons.findById(run.getId()).orElseThrow();
        assertEquals(innbucks.paymentservice.entity.ReconRun.Status.DISCREPANT, reloaded.getStatus());
        assertEquals(160000, reloaded.getMatchedAmountCents());
        assertEquals(run.getId(), recons.findTop30ByOrderByCreatedAtDesc().get(0).getId(),
                "the newest run must lead the history the ops endpoint serves");
    }

    @Test
    void paymentEventJournal_acceptsTransitionRows() {
        Payment p = payments.saveAndFlush(row(UUID.randomUUID(), PaymentStatus.PENDING));

        PaymentEvent opening = events.saveAndFlush(PaymentEvent.builder()
                .paymentId(p.getId())
                .fromStatus(null)
                .toStatus("PENDING")
                .detail("Ledger row opened before upstream call")
                .build());

        PaymentEvent reloaded = events.findById(opening.getId()).orElseThrow();
        assertNull(reloaded.getFromStatus());
        assertEquals("PENDING", reloaded.getToStatus());
        assertEquals(1, events.findByPaymentIdOrderByCreatedAtAsc(p.getId()).size());
    }
}
