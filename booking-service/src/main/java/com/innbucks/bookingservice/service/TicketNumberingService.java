package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AssignSoldResultDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.NumberingDTO;
import com.innbucks.bookingservice.dto.NumberingRequestDTO;
import com.innbucks.bookingservice.entity.NumberSeries;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.numbering.NumberFormat;
import com.innbucks.bookingservice.numbering.TicketNumberAssigner;
import com.innbucks.bookingservice.repository.NumberSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The organizer's side of assigned numbers (V34): set a category's series,
 * remove it, list an event's, and number the tickets sold before it existed.
 * Handing numbers out at payment is {@link TicketNumberAssigner}.
 *
 * <p>Every remote call (seat-service for the category's event, event-service
 * for its owner) runs before a transaction opens. The ownership check fails
 * CLOSED, like promo codes: anything but a confirmed owner is a 403.
 */
@Slf4j
@Service
public class TicketNumberingService {

    static final String NUMBERING_IN_USE = "NUMBERING_IN_USE";
    static final String IN_USE_MESSAGE =
            "Numbers have already been given in this category, so its numbering can't change.";
    static final String NO_NUMBERING_MESSAGE = "This ticket category has no numbering.";
    static final String CATEGORY_GONE_MESSAGE = "This ticket category is no longer available.";

    private final NumberSeriesRepository series;
    private final TicketNumberAssigner assigner;
    private final SeatServiceClient seatServiceClient;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final String eventInternalToken;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public TicketNumberingService(NumberSeriesRepository series,
                                  TicketNumberAssigner assigner,
                                  SeatServiceClient seatServiceClient,
                                  ObjectProvider<EventServiceClient> eventClientProvider,
                                  @Value("${innbucks.internal-api-token:}") String eventInternalToken,
                                  PlatformTransactionManager transactionManager) {
        this.series = series;
        this.assigner = assigner;
        this.seatServiceClient = seatServiceClient;
        this.eventClientProvider = eventClientProvider;
        this.eventInternalToken = eventInternalToken;
        this.readTx = transactionManager == null ? null : TransactionPhases.readOnly(transactionManager);
        this.writeTx = transactionManager == null ? null : TransactionPhases.readWrite(transactionManager);
    }

    /**
     * Sets the category's series, or replaces it while it has handed out
     * nothing. Refused 409 {@code NUMBERING_IN_USE} once a number is out.
     */
    public NumberingDTO set(UUID categoryId, NumberingRequestDTO request, UUID callerOrganizerUuid,
                            String actor, boolean isAdmin) {
        UUID eventId = eventOf(categoryId);
        if (!isAdmin) {
            requireEventOwnership(eventId, callerOrganizerUuid);
        }
        String label = cleanLabel(request == null ? null : request.getLabel());
        String prefix = request == null || request.getPrefix() == null ? "" : request.getPrefix();
        int start = request == null || request.getStart() == null ? 1 : request.getStart();
        int padding = request == null || request.getPadding() == null ? 0 : request.getPadding();
        return TransactionPhases.inTransaction(writeTx, () -> {
            int written = series.upsertWhileUnused(categoryId, eventId, label, prefix, start, padding, actor,
                    LocalDateTime.now(ZoneOffset.UTC));
            NumberSeries saved = series.findById(categoryId)
                    .orElseThrow(() -> new IllegalStateException("Number series vanished after its write"));
            if (written == 0) {
                throw inUse(saved);
            }
            log.info("Numbering set categoryId={} eventId={} prefix={} start={} padding={} actor={}",
                    categoryId, eventId, prefix, start, padding, actor);
            return toDto(saved);
        });
    }

    /** Removes the series while it has handed out nothing; 409 after, 404 when there is none. */
    public void remove(UUID categoryId, UUID callerOrganizerUuid, String actor, boolean isAdmin) {
        UUID eventId = eventOf(categoryId);
        if (!isAdmin) {
            requireEventOwnership(eventId, callerOrganizerUuid);
        }
        TransactionPhases.inTransaction(writeTx, () -> {
            if (series.deleteWhileUnused(categoryId) == 1) {
                log.info("Numbering removed categoryId={} eventId={} actor={}", categoryId, eventId, actor);
                return null;
            }
            NumberSeries existing = series.findById(categoryId)
                    .orElseThrow(() -> new NotFoundException(NO_NUMBERING_MESSAGE));
            throw inUse(existing);
        });
    }

    /** Every numbered category of the event, in the order their series were set. */
    public List<NumberingDTO> list(UUID eventId, UUID callerOrganizerUuid, boolean platformStaff) {
        if (!platformStaff) {
            requireEventOwnership(eventId, callerOrganizerUuid);
        }
        return TransactionPhases.inTransaction(readTx, () ->
                series.findByEventIdOrderByCreatedAtAscCategoryIdAsc(eventId).stream()
                        .map(TicketNumberingService::toDto).toList());
    }

    /**
     * Numbers the category's paid tickets that have none (numbering added
     * after sales began), continuing the series. Safe to call twice.
     */
    public AssignSoldResultDTO assignSold(UUID categoryId, UUID callerOrganizerUuid, String actor,
                                          boolean isAdmin) {
        UUID eventId = eventOf(categoryId);
        if (!isAdmin) {
            requireEventOwnership(eventId, callerOrganizerUuid);
        }
        return TransactionPhases.inTransaction(writeTx, () -> {
            OptionalInt numbered = assigner.assignSold(categoryId);
            if (numbered.isEmpty()) {
                throw new NotFoundException(NO_NUMBERING_MESSAGE);
            }
            NumberSeries after = series.findById(categoryId)
                    .orElseThrow(() -> new NotFoundException(NO_NUMBERING_MESSAGE));
            log.info("Numbers given to tickets already sold categoryId={} eventId={} count={} actor={}",
                    categoryId, eventId, numbered.getAsInt(), actor);
            return new AssignSoldResultDTO(numbered.getAsInt(), toDto(after));
        });
    }

    static NumberingDTO toDto(NumberSeries s) {
        return NumberingDTO.builder()
                .categoryId(s.getCategoryId())
                .eventId(s.getEventId())
                .label(s.getLabel())
                .prefix(s.getPrefix())
                .start(s.getStartValue())
                .padding(s.getPadding())
                .example(NumberFormat.render(s.getPrefix(), s.getStartValue(), s.getPadding()))
                .assigned(s.getAssignedCount())
                .nextNumber(NumberFormat.render(s.getPrefix(), (long) s.getStartValue() + s.getAssignedCount(),
                        s.getPadding()))
                .locked(s.getAssignedCount() > 0)
                .build();
    }

    /** Plain text: control characters and angle brackets dropped, spaces collapsed. Blank = the default. */
    static String cleanLabel(String raw) {
        if (raw == null) {
            return NumberFormat.DEFAULT_LABEL;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\p{Cf}<>]", " ").replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() ? NumberFormat.DEFAULT_LABEL : cleaned;
    }

    private static ApiProblemException inUse(NumberSeries s) {
        return new ApiProblemException(HttpStatus.CONFLICT, NUMBERING_IN_USE, IN_USE_MESSAGE,
                Map.of("assigned", s.getAssignedCount()));
    }

    /** The category's event, from seat-service, read live. An outage is the seat-service 503. */
    private UUID eventOf(UUID categoryId) {
        ApiResult<CategoryLookupDTO> envelope = seatServiceClient.getCategory(categoryId);
        if (envelope == null || envelope.getData() == null || envelope.getData().getEventId() == null) {
            throw new NotFoundException(CATEGORY_GONE_MESSAGE);
        }
        return envelope.getData().getEventId();
    }

    private void requireEventOwnership(UUID eventId, UUID callerOrganizerUuid) {
        if (callerOrganizerUuid == null) {
            throw new AccessDeniedException("Your session is missing organizer information. Please sign in again.");
        }
        EventServiceClient client = eventClientProvider == null ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing numbering for eventId={}", eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        EventLookupDTO event;
        try {
            ApiResult<EventLookupDTO> lookup = client.getEventInternal(eventId, eventInternalToken);
            event = lookup == null ? null : lookup.getData();
        } catch (Exception e) {
            log.warn("Event ownership lookup failed eventId={} cause={}", eventId, e.toString());
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        if (event == null || event.getTenantUserUuid() == null
                || !event.getTenantUserUuid().equals(callerOrganizerUuid)) {
            log.warn("Numbering ownership check failed eventId={} callerOrganizerUuid={}",
                    eventId, callerOrganizerUuid);
            throw new AccessDeniedException("You do not own this event");
        }
    }
}
