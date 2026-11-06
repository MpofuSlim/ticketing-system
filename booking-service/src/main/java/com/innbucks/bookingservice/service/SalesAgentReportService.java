package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.AgentHandoverRequestDTO;
import com.innbucks.bookingservice.dto.report.AgentHandoverDTO;
import com.innbucks.bookingservice.dto.report.AgentSalesReportDTO;
import com.innbucks.bookingservice.dto.report.SalesAgentsReportDTO;
import com.innbucks.bookingservice.entity.AgentCashHandover;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.repository.AgentCashHandoverRepository;
import com.innbucks.bookingservice.repository.OrganizerReportRepository;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.util.PaymentMethods;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sales agents' figures (V36): the agent's own sales
 * ({@code GET /bookings/box-office/my-sales}), the organizer's per-agent
 * report ({@code GET /event-organizer/reports/sales-agents}) and the cash
 * handovers the organizer records. Everything is read from this service's own
 * rows: no other service is asked.
 *
 * <p><b>What each figure means</b>, for sales made (or voided) in the window:
 * <ul>
 *   <li>cash taken: CASH sales made in the window, voided later or not (the
 *       cash was taken that day, as in the box office cash-up);</li>
 *   <li>cash voided: CASH sales voided in the window (the cash handed back,
 *       on the agent's side: a void takes the sale out of what the agent
 *       owes);</li>
 *   <li>online sales: ONLINE sales made in the window and paid (CONFIRMED);
 *       the money went through payment-service, not to the agent;</li>
 *   <li>commission: on CONFIRMED sales made in the window, the amount stamped
 *       at the confirm. A voided or unpaid sale earns nothing;</li>
 *   <li>handed over: live handovers received in the window; with an event
 *       filter, only those recorded against that event;</li>
 *   <li>cash owed: taken - voided - handed over.</li>
 * </ul>
 *
 * <p><b>The window is open unless the caller narrows it</b>: no {@code from}
 * means from the first sale, no {@code to} means up to now. Unlike the other
 * organizer reports (last 30 days by default) this one reconciles cash, and
 * cash taken 31 days ago and never handed over must not drop out of "owed".
 * Days are UTC days, like every organizer report.
 */
@Slf4j
@Service
public class SalesAgentReportService {

    /** {@code data.errorCode}: no sale of this agent for the caller's events. */
    public static final String AGENT_NOT_FOUND = "AGENT_NOT_FOUND";
    static final String AGENT_NOT_FOUND_MESSAGE = "This sales agent has made no sales for your events.";
    /** {@code data.errorCode}: the handover names an event the agent has not sold for. */
    public static final String HANDOVER_EVENT_INVALID = "HANDOVER_EVENT_INVALID";
    static final String HANDOVER_EVENT_INVALID_MESSAGE =
            "This sales agent has made no sales for that event. Choose one of your events they sold for.";
    /** {@code data.errorCode}: no such handover of the caller's for that agent. */
    public static final String HANDOVER_NOT_FOUND = "HANDOVER_NOT_FOUND";
    static final String HANDOVER_NOT_FOUND_MESSAGE = "Handover not found";

    static final String VOIDED = "VOIDED";

    private static final LocalDateTime OPEN_START = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime OPEN_END = LocalDateTime.of(9999, 1, 1, 0, 0);
    /** A received-at this far ahead of the server clock is still "now" (a phone's clock running fast). */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private final OrganizerReportRepository reports;
    private final AgentCashHandoverRepository handovers;
    private final MarketTimeZone marketTimeZone;
    private final String currency;

    public SalesAgentReportService(OrganizerReportRepository reports,
                                   AgentCashHandoverRepository handovers,
                                   MarketTimeZone marketTimeZone,
                                   @Value("${innbucks.currency:USD}") String currency) {
        this.reports = reports;
        this.handovers = handovers;
        this.marketTimeZone = marketTimeZone;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency.strip();
    }

    // ---- the agent's own sales ------------------------------------------------

    /** The signed-in account's sales as a sales agent: never anyone else's. */
    @Transactional(readOnly = true)
    public AgentSalesReportDTO mySales(UUID sellerUserUuid, @Nullable UUID eventId,
                                       @Nullable LocalDate from, @Nullable LocalDate to) {
        Window w = window(from, to);
        List<Booking> sales = reports.findAgentSalesWithItems(null, sellerUserUuid, eventId, w.start(), w.end());
        List<UUID> agentIds = reports.findAgentIdsSoldBy(sellerUserUuid);
        List<AgentCashHandover> received = agentIds.isEmpty()
                ? List.of()
                : handovers.findLiveForAgents(agentIds, eventId, w.start(), w.end());

        Figures f = new Figures();
        List<AgentSalesReportDTO.Entry> entries = new ArrayList<>(sales.size());
        for (Booking b : sales) {
            f.add(b, w);
            entries.add(new AgentSalesReportDTO.Entry(b.getId(), b.getConfirmationNumber(), b.getEventId(),
                    b.getCreatedAt(), b.getCustomerName(), MsisdnMasking.mask(b.getPhoneNumber()), tickets(b),
                    scale2(b.getTotalAmount()), PaymentMethods.code(b.getPaymentRail()),
                    PaymentMethods.label(b.getPaymentRail()), status(b), b.getAgentCommissionPercent(),
                    b.getStatus() == Booking.BookingStatus.CONFIRMED ? b.getAgentCommissionAmount() : null));
        }
        entries.sort(Comparator.comparing(AgentSalesReportDTO.Entry::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<AgentHandoverDTO> handoverRows = new ArrayList<>(received.size());
        for (AgentCashHandover h : received) {
            f.handedOver = f.handedOver.add(h.getAmount());
            // The agent's view: who recorded it is the organizer's business.
            handoverRows.add(toDto(h, false));
        }
        return new AgentSalesReportDTO(from, to, eventId, entries,
                new AgentSalesReportDTO.Totals(f.tickets, scale2(f.cashTaken), scale2(f.cashVoided),
                        scale2(f.online), scale2(f.commission), scale2(f.handedOver), scale2(f.owed()), currency),
                handoverRows);
    }

    // ---- the organizer's per-agent report -------------------------------------------

    /** Per agent, for the organizer's events ({@code organizerUuid} null = platform staff, every organizer). */
    @Transactional(readOnly = true)
    public SalesAgentsReportDTO salesAgents(@Nullable UUID organizerUuid, @Nullable UUID eventId,
                                            @Nullable LocalDate from, @Nullable LocalDate to) {
        Window w = window(from, to);
        Map<UUID, Figures> byAgent = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        // Oldest first, so the last name seen for an agent is the latest.
        for (Booking b : reports.findAgentSalesWithItems(organizerUuid, null, eventId, w.start(), w.end())) {
            byAgent.computeIfAbsent(b.getSoldByAgentId(), k -> new Figures()).add(b, w);
            if (b.getSoldByName() != null) {
                names.put(b.getSoldByAgentId(), b.getSoldByName());
            }
        }
        for (AgentCashHandover h : handovers.findLive(organizerUuid, eventId, w.start(), w.end())) {
            Figures f = byAgent.computeIfAbsent(h.getAgentId(), k -> new Figures());
            f.handedOver = f.handedOver.add(h.getAmount());
        }
        List<UUID> unnamed = byAgent.keySet().stream().filter(id -> !names.containsKey(id)).toList();
        if (!unnamed.isEmpty()) {
            for (OrganizerReportRepository.AgentName n : reports.findAgentNames(unnamed)) {
                if (n.getAgentId() != null && n.getName() != null) {
                    names.put(n.getAgentId(), n.getName());
                }
            }
        }

        List<SalesAgentsReportDTO.Agent> rows = new ArrayList<>(byAgent.size());
        Figures total = new Figures();
        for (Map.Entry<UUID, Figures> e : byAgent.entrySet()) {
            Figures f = e.getValue();
            rows.add(new SalesAgentsReportDTO.Agent(e.getKey(),
                    names.getOrDefault(e.getKey(), BoxOfficeSeller.UNNAMED_AGENT), f.tickets,
                    scale2(f.cashTaken), scale2(f.cashVoided), scale2(f.online), scale2(f.commission),
                    scale2(f.handedOver), scale2(f.owed())));
            total.merge(f);
        }
        rows.sort(Comparator.comparing(SalesAgentsReportDTO.Agent::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SalesAgentsReportDTO.Agent::agentId));
        return new SalesAgentsReportDTO(rows, new SalesAgentsReportDTO.Totals(total.tickets,
                scale2(total.cashTaken), scale2(total.cashVoided), scale2(total.online), scale2(total.commission),
                scale2(total.handedOver), scale2(total.owed()), currency));
    }

    // ---- handovers ------------------------------------------------------------

    /**
     * Records cash the organizer received from one of their agents. Only for an
     * agent who has sold for one of the organizer's events, and, with an event,
     * for one they sold for: both read from the bookings, so no other service
     * is asked. Append-only; a mistake is soft-deleted, never edited.
     */
    @Transactional
    public AgentHandoverDTO recordHandover(UUID organizerUuid, UUID agentId, AgentHandoverRequestDTO request,
                                           @Nullable UUID byUserUuid, @Nullable String byName) {
        if (!reports.agentHasSoldFor(organizerUuid, agentId, null)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, AGENT_NOT_FOUND, AGENT_NOT_FOUND_MESSAGE);
        }
        if (request.getEventId() != null && !reports.agentHasSoldFor(organizerUuid, agentId, request.getEventId())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, HANDOVER_EVENT_INVALID,
                    HANDOVER_EVENT_INVALID_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime receivedAt = receivedAt(request.getReceivedAt(), now);
        AgentCashHandover saved = handovers.save(AgentCashHandover.builder()
                .organizerUuid(organizerUuid)
                .agentId(agentId)
                .eventId(request.getEventId())
                .amount(request.getAmount().setScale(2, RoundingMode.HALF_UP))
                .currency(currency)
                .receivedAt(receivedAt)
                .recordedByUserUuid(byUserUuid)
                .recordedBy(clip(byName, 254))
                .note(cleanNote(request.getNote()))
                .createdAt(now)
                .build());
        log.info("Agent cash handover recorded id={} agentId={} eventId={} amount={}", saved.getId(), agentId,
                request.getEventId(), saved.getAmount());
        return toDto(saved, true);
    }

    /** One agent's handovers, deleted ones included, newest first ({@code organizerUuid} null = platform staff). */
    @Transactional(readOnly = true)
    public List<AgentHandoverDTO> listHandovers(@Nullable UUID organizerUuid, UUID agentId) {
        return handovers.findAllForAgent(organizerUuid, agentId).stream().map(h -> toDto(h, true)).toList();
    }

    /**
     * Soft-deletes one of the organizer's handovers: it stays listed, with who
     * deleted it and when, and counts in no total. Deleting one already deleted
     * answers it unchanged (the first deletion is the one recorded), so a retry
     * is safe. Another organizer's, or another agent's, is a 404.
     */
    @Transactional
    public AgentHandoverDTO deleteHandover(UUID organizerUuid, UUID agentId, UUID handoverId,
                                           @Nullable UUID byUserUuid, @Nullable String byName) {
        int deleted = handovers.softDelete(handoverId, organizerUuid, agentId, LocalDateTime.now(ZoneOffset.UTC),
                byUserUuid, clip(byName, 254));
        AgentCashHandover h = handovers.findByIdAndOrganizerUuidAndAgentId(handoverId, organizerUuid, agentId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, HANDOVER_NOT_FOUND,
                        HANDOVER_NOT_FOUND_MESSAGE));
        if (deleted > 0) {
            log.info("Agent cash handover deleted id={} agentId={}", handoverId, agentId);
        }
        return toDto(h, true);
    }

    // ---- helpers ----------------------------------------------------------------

    /** The figures of one agent (or of a total), accumulated from sales and handovers. */
    private static final class Figures {
        int tickets;
        BigDecimal cashTaken = BigDecimal.ZERO;
        BigDecimal cashVoided = BigDecimal.ZERO;
        BigDecimal online = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal handedOver = BigDecimal.ZERO;

        void add(Booking b, Window w) {
            BigDecimal amount = nz(b.getTotalAmount());
            boolean cash = PaymentMethods.CASH.equals(b.getPaymentRail());
            boolean confirmed = b.getStatus() == Booking.BookingStatus.CONFIRMED;
            if (w.contains(b.getCreatedAt())) {
                if (confirmed) {
                    tickets += tickets(b);
                    commission = commission.add(nz(b.getAgentCommissionAmount()));
                    if (!cash) {
                        online = online.add(amount);
                    }
                }
                if (cash) {
                    // A cash sale's rail is set only at its confirm: the cash
                    // was taken, whatever happened to the sale since.
                    cashTaken = cashTaken.add(amount);
                }
            }
            if (b.getCancelReason() == Booking.CancelReason.CASH_VOIDED && w.contains(b.getVoidedAt())) {
                cashVoided = cashVoided.add(amount);
            }
        }

        void merge(Figures other) {
            tickets += other.tickets;
            cashTaken = cashTaken.add(other.cashTaken);
            cashVoided = cashVoided.add(other.cashVoided);
            online = online.add(other.online);
            commission = commission.add(other.commission);
            handedOver = handedOver.add(other.handedOver);
        }

        BigDecimal owed() {
            return cashTaken.subtract(cashVoided).subtract(handedOver);
        }
    }

    /** The window [start, end), open where the caller gave no bound. */
    private record Window(LocalDateTime start, LocalDateTime end) {
        boolean contains(LocalDateTime at) {
            return at != null && !at.isBefore(start) && at.isBefore(end);
        }
    }

    private static Window window(@Nullable LocalDate from, @Nullable LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' (" + from + ") must not be after 'to' (" + to + ").");
        }
        return new Window(from == null ? OPEN_START : from.atStartOfDay(),
                to == null ? OPEN_END : to.plusDays(1).atStartOfDay());
    }

    /** CONFIRMED, PENDING, VOIDED (a voided cash sale) or CANCELLED. */
    static String status(Booking b) {
        if (b.getStatus() == Booking.BookingStatus.CANCELLED
                && b.getCancelReason() == Booking.CancelReason.CASH_VOIDED) {
            return VOIDED;
        }
        return b.getStatus() == null ? null : b.getStatus().name();
    }

    private LocalDateTime receivedAt(@Nullable String value, LocalDateTime now) {
        if (value == null || value.isBlank()) {
            return now;
        }
        LocalDateTime at;
        try {
            at = marketTimeZone.parseToUtc(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("When the cash was received must be a date and time like "
                    + "2026-11-14T18:30:00.");
        }
        if (at.isAfter(now.plus(CLOCK_SKEW))) {
            throw new BadRequestException("When the cash was received can't be in the future.");
        }
        return at;
    }

    private static AgentHandoverDTO toDto(AgentCashHandover h, boolean organizerView) {
        return new AgentHandoverDTO(h.getId(), h.getAgentId(), h.getEventId(), scale2(h.getAmount()),
                h.getCurrency(), h.getReceivedAt(), organizerView ? h.getRecordedBy() : null, h.getNote(),
                h.getCreatedAt(), organizerView ? h.getDeletedAt() : null, organizerView ? h.getDeletedBy() : null);
    }

    /** One line, no control or invisible formatting characters, at most 500. Blank = none. */
    static String cleanNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        String cleaned = note.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").strip();
        return cleaned.isEmpty() ? null : clip(cleaned, 500);
    }

    private static String clip(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int tickets(Booking b) {
        return b.getItems() == null ? 0 : b.getItems().size();
    }

    private static BigDecimal nz(@Nullable BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(@Nullable BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
