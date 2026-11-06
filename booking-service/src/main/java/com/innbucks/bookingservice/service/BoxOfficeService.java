package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.dto.SellerAccessDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The box office (V35, V36): tickets sold at a counter, by the event's
 * organizer or by a sales agent on the organizer's event team, from the
 * Foundry portal or the InnBucks app.
 *
 * <p>A sale is an ordinary booking, created through the online path (so every
 * check an online sale gets — the sales window, terms and answers, add-ons,
 * promo codes, the per-booking ceiling, the stock lock — applies unchanged)
 * and stamped at insert with its seller. Paid in CASH it is CONFIRMED in the
 * same request; paid ONLINE it stays PENDING and the buyer pays through
 * POST /payments like any checkout. The tickets, assigned numbers and
 * messages are an online sale's exactly. Nothing here calls payment-service.
 *
 * <p><b>Who sells.</b> The event's organizer, decided from the live sales
 * window against the session's {@code organizerUuid}, keeps the V35 path
 * (promo codes allowed). Anyone else is asked about, live and with no
 * transaction open, as a sales agent (event-service
 * {@code GET /events/internal/seller-access} by the session's
 * {@code userUuid}): refused 403 {@value BookingService#NOT_A_SALES_AGENT},
 * and an unanswered question is a 503, never a sale. An agent may not use a
 * promo code ({@value #PROMO_CODE_NOT_ALLOWED}, before anything else is
 * asked), may not sell past their ticket cap for the event (checked inside
 * the claim, {@code BookingService.requireAgentRoom}), and earns their
 * commission on what the buyer paid once the sale is CONFIRMED. Because the
 * check is live on every sale, an organizer switching an agent off, taking
 * away Sell or unassigning the event stops their next sale on both surfaces.
 *
 * <p>Owner decisions: V35 (2026-11-03) — the organizer's own box office, voids
 * by the organizer only, until the event starts and while no ticket is
 * scanned, cash sales carry commission, same sales window as online. V36
 * (2026-11-06) — sales agents, no promo codes for them, an optional ticket cap
 * and a commission percentage per agent and event, CASH or ONLINE at the
 * counter, voids still the organizer's alone.
 */
@Slf4j
@Service
public class BoxOfficeService {

    static final String TRY_AGAIN_MESSAGE = "We couldn't check this event just now. Please try again.";
    static final String SELLER_UNCHECKED_MESSAGE = "We couldn't check that you can sell for this event just now. "
            + "Please try again.";

    /** {@code data.errorCode} of the 400 an agent's sale naming a promo code answers. */
    public static final String PROMO_CODE_NOT_ALLOWED = "PROMO_CODE_NOT_ALLOWED";
    static final String PROMO_CODE_NOT_ALLOWED_MESSAGE =
            "Promo codes can't be used on a sales agent's sale. Remove the code and try again.";

    private final BookingService bookings;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final String eventInternalToken;
    private volatile BookingMetrics metrics;

    public BoxOfficeService(BookingService bookings,
                            ObjectProvider<EventServiceClient> eventClientProvider,
                            @Value("${innbucks.internal-api-token:}") String eventInternalToken) {
        this.bookings = bookings;
        this.eventClientProvider = eventClientProvider;
        this.eventInternalToken = eventInternalToken;
    }

    /** Setter, so the constructor tests use stays; without it agent sales are simply not counted. */
    @Autowired(required = false)
    public void setMetrics(BookingMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * The signed-in account selling: its {@code userUuid} and
     * {@code organizerUuid} claims, whether it holds EVENT_ORGANIZER, and its
     * sign-in name (the email; null on a super-app session).
     */
    public record Caller(UUID userUuid, UUID organizerUuid, boolean organizerRole, String name) {
    }

    /**
     * Sells. Whether the caller is the event's organizer is asked first (an
     * unknown or unpublished event is the online path's 404, an unanswered
     * question a 503); anyone else must be a sales agent for the event. Then
     * the booking is created; a CASH sale is confirmed at once. A failure
     * between the two leaves an unpaid hold that expires on its own: the
     * seller is told the sale failed and takes no cash.
     */
    public BookingResponseDTO sell(BoxOfficeSaleRequestDTO request, String buyerEmail, String buyerPhone,
                                   Caller caller) {
        boolean online = request.paymentMethodOrDefault() == BoxOfficeSaleRequestDTO.PaymentMethod.ONLINE;
        BoxOfficeSeller seller = organizerSeller(request.getEventId(), caller);
        if (seller == null) {
            seller = agentSeller(request, caller);
        }
        BookingResponseDTO held = bookings.createBoxOfficeBooking(buyerEmail, buyerPhone, request, seller, online);
        BookingResponseDTO sold = online
                ? held
                : bookings.confirmBoxOfficeSale(held.getId(), seller.userUuid(), seller.name());
        if (seller.isAgent()) {
            count(online ? BookingMetrics.AgentSaleOutcome.SOLD_ONLINE : BookingMetrics.AgentSaleOutcome.SOLD_CASH);
            log.info("Agent sale bookingId={} agentId={} eventId={} method={}", held.getId(), seller.agentId(),
                    request.getEventId(), online ? "ONLINE" : "CASH");
        }
        return sold;
    }

    /** Voids a cash sale; see {@link BookingService#voidCashSale}. The organizer's alone. */
    public BookingResponseDTO voidSale(UUID bookingId, String reason, UUID callerOrganizerUuid,
                                       UUID actorUserUuid, String actorName) {
        return bookings.voidCashSale(bookingId, callerOrganizerUuid, actorUserUuid, actorName, reason,
                this::eventStart);
    }

    /**
     * The organizer's own seller stamp when the caller is the event's
     * organizer, else null (the caller is then asked about as an agent). An
     * organizer of ANOTHER organizer's event may still be that organizer's
     * sales agent.
     */
    private BoxOfficeSeller organizerSeller(UUID eventId, Caller caller) {
        if (!caller.organizerRole() || caller.organizerUuid() == null) {
            return null;
        }
        EventSalesWindowDTO window = eventClient()
                .map(c -> c.salesWindow(eventId, eventInternalToken))
                .map(ApiResult::getData)
                .orElse(null);
        if (window == null || window.getStatus() == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", TRY_AGAIN_MESSAGE);
        }
        if (EventSalesWindowDTO.NOT_FOUND.equals(window.getStatus())
                || EventSalesWindowDTO.NOT_PUBLISHED.equals(window.getStatus())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found");
        }
        if (!caller.organizerUuid().equals(window.getTenantUserUuid())) {
            log.info("Box office sale by an organizer of another event, asked about as a sales agent eventId={}",
                    eventId);
            return null;
        }
        UUID userUuid = caller.userUuid() != null ? caller.userUuid() : caller.organizerUuid();
        return BoxOfficeSeller.organizer(userUuid, caller.name());
    }

    /**
     * The caller as a sales agent for the event, or a refusal: a promo code
     * (400, before anything is asked), no account on the session or not an
     * agent for the event (403), event-service not answering or answering
     * nonsense (503). Asked with no transaction open.
     */
    private BoxOfficeSeller agentSeller(BoxOfficeSaleRequestDTO request, Caller caller) {
        if (PromoCodes.normalize(request.getPromoCode()) != null) {
            count(BookingMetrics.AgentSaleOutcome.PROMO_CODE_REFUSED);
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, PROMO_CODE_NOT_ALLOWED,
                    PROMO_CODE_NOT_ALLOWED_MESSAGE);
        }
        UUID eventId = request.getEventId();
        if (caller.userUuid() == null) {
            count(BookingMetrics.AgentSaleOutcome.NOT_A_SALES_AGENT);
            log.warn("Agent sale refused, the session names no account eventId={}", eventId);
            throw BookingService.notASalesAgent();
        }
        SellerAccessDTO access = sellerAccess(caller.userUuid(), eventId);
        if (access == null) {
            count(BookingMetrics.AgentSaleOutcome.UNAVAILABLE);
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, BookingService.TRY_AGAIN,
                    SELLER_UNCHECKED_MESSAGE);
        }
        if (!access.isAllowed()) {
            count(BookingMetrics.AgentSaleOutcome.NOT_A_SALES_AGENT);
            log.info("Agent sale refused, not a sales agent for the event eventId={} userUuid={}",
                    eventId, caller.userUuid());
            throw BookingService.notASalesAgent();
        }
        BigDecimal percent = access.getCommissionPercent();
        if (access.getAgentId() == null || access.getOrganizerUuid() == null
                || (percent != null && (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0))) {
            // An "allowed" with no agent, no organizer or a commission outside
            // 0..100 is an answer this build cannot act on: refused like no
            // answer, never sold under a guess.
            count(BookingMetrics.AgentSaleOutcome.UNAVAILABLE);
            log.error("Agent sale refused, seller-access answered allowed with agentId={} organizerUuid={} "
                    + "commissionPercent={} eventId={}", access.getAgentId(), access.getOrganizerUuid(), percent,
                    eventId);
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, BookingService.TRY_AGAIN,
                    SELLER_UNCHECKED_MESSAGE);
        }
        return BoxOfficeSeller.agent(caller.userUuid(), access.getDisplayName(), access.getAgentId(),
                access.getOrganizerUuid(), access.getTicketCap(), percent);
    }

    /** event-service's live answer, or null when it could not be asked. */
    private SellerAccessDTO sellerAccess(UUID accountUserUuid, UUID eventId) {
        EventServiceClient client = eventClient().orElse(null);
        if (client == null) {
            return null;
        }
        try {
            ApiResult<SellerAccessDTO> answer = client.sellerAccess(accountUserUuid, eventId, eventInternalToken);
            return answer == null ? null : answer.getData();
        } catch (Exception ex) {
            log.warn("Seller access check failed eventId={} error={}", eventId, ex.toString());
            return null;
        }
    }

    /** The event's start, UTC, asked live. Fails CLOSED: no answer is a 503, never "not started". */
    private LocalDateTime eventStart(UUID eventId) {
        EventLookupDTO event = eventClient()
                .map(c -> c.getEventInternal(eventId, eventInternalToken))
                .map(ApiResult::getData)
                .orElse(null);
        if (event == null || event.getStartDateTime() == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", TRY_AGAIN_MESSAGE);
        }
        return event.getStartDateTime();
    }

    private void count(BookingMetrics.AgentSaleOutcome outcome) {
        BookingMetrics m = metrics;
        if (m != null) {
            m.agentSale(outcome);
        }
    }

    private Optional<EventServiceClient> eventClient() {
        return Optional.ofNullable(eventClientProvider == null ? null : eventClientProvider.getIfAvailable());
    }
}
