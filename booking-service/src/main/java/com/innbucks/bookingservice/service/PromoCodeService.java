package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.promo.CreatePromoCodeRequestDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeCheckDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeTermsDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.PromoCode;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.PromoCodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Promo codes (V27): the organizer creates, lists and edits them; anyone may
 * check one before booking. The booking itself applies a code in
 * {@code BookingService.persistBooking}, under the code's row lock.
 *
 * <p>No remote call runs with a transaction open: the event-service
 * ownership check comes first, then one short transaction does the work.
 * The event a code belongs to never changes, so the answer still holds.
 */
@Slf4j
@Service
public class PromoCodeService {

    static final String CODE_EXISTS = "PROMO_CODE_EXISTS";

    private final PromoCodeRepository promoCodeRepository;
    private final BookingRepository bookingRepository;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final MarketTimeZone marketTimeZone;
    private final String eventInternalToken;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private Clock clock = Clock.systemUTC();

    public PromoCodeService(PromoCodeRepository promoCodeRepository,
                            BookingRepository bookingRepository,
                            ObjectProvider<EventServiceClient> eventClientProvider,
                            MarketTimeZone marketTimeZone,
                            @Value("${innbucks.internal-api-token:}") String eventInternalToken,
                            PlatformTransactionManager transactionManager) {
        this.promoCodeRepository = promoCodeRepository;
        this.bookingRepository = bookingRepository;
        this.eventClientProvider = eventClientProvider;
        this.marketTimeZone = marketTimeZone;
        this.eventInternalToken = eventInternalToken;
        this.readTx = transactionManager == null ? null : TransactionPhases.readOnly(transactionManager);
        this.writeTx = transactionManager == null ? null : TransactionPhases.readWrite(transactionManager);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Create a code for an event the caller owns (SUPER_ADMIN: any event).
     * Refused 409 {@code PROMO_CODE_EXISTS} when the event already has it.
     */
    public PromoCodeDTO create(CreatePromoCodeRequestDTO request, UUID callerOrganizerUuid, String actor,
                               boolean isAdmin) {
        String code = PromoCodes.normalize(request.getCode());
        if (code == null || !PromoCodes.CODE_SHAPE.matcher(code).matches()) {
            throw new BadRequestException("A promo code is 3 to 40 letters, digits, '-' or '_'.");
        }
        Terms terms = validate(request, null);
        if (!isAdmin) {
            requireEventOwnership(request.getEventId(), callerOrganizerUuid);
        }
        try {
            return TransactionPhases.inTransaction(writeTx, () -> {
                if (promoCodeRepository.existsByEventIdAndCode(request.getEventId(), code)) {
                    throw codeExists(code);
                }
                PromoCode saved = promoCodeRepository.saveAndFlush(terms.applyTo(PromoCode.builder()
                        .eventId(request.getEventId())
                        .code(code)
                        .createdBy(actor)
                        .build()));
                log.info("Promo code created promoCodeId={} eventId={} code={} type={} value={} actor={}",
                        saved.getId(), saved.getEventId(), code, saved.getDiscountType(),
                        saved.getDiscountValue(), actor);
                return toDto(saved, null);
            });
        } catch (DataIntegrityViolationException raced) {
            // Two creates of the same code at once: the unique constraint is the backstop.
            throw codeExists(code);
        }
    }

    /**
     * Replace a code's terms. The event and the code itself never change (a
     * code that already went out to customers keeps meaning the same text).
     */
    public PromoCodeDTO update(UUID id, PromoCodeTermsDTO request, UUID callerOrganizerUuid, String actor,
                               boolean isAdmin) {
        PromoCode found = TransactionPhases.inTransaction(readTx, () -> load(id));
        Terms terms = validate(request, found);
        if (!isAdmin) {
            requireEventOwnership(found.getEventId(), callerOrganizerUuid);
        }
        return TransactionPhases.inTransaction(writeTx, () -> {
            PromoCode code = terms.applyTo(load(id));
            code.setUpdatedBy(actor);
            PromoCode saved = promoCodeRepository.saveAndFlush(code);
            log.info("Promo code updated promoCodeId={} eventId={} active={} actor={}",
                    saved.getId(), saved.getEventId(), saved.isActive(), actor);
            return toDto(saved, usage(List.of(saved.getId())).get(saved.getId()));
        });
    }

    /** An event's codes, newest first, each with its uses and the discount given. */
    public List<PromoCodeDTO> list(UUID eventId, UUID callerOrganizerUuid, boolean platformStaff) {
        if (!platformStaff) {
            requireEventOwnership(eventId, callerOrganizerUuid);
        }
        return TransactionPhases.inTransaction(readTx, () -> {
            List<PromoCode> codes = promoCodeRepository.findByEventIdOrderByCreatedAtDesc(eventId);
            Map<UUID, BookingRepository.PromoCodeUsage> usage =
                    usage(codes.stream().map(PromoCode::getId).toList());
            return codes.stream().map(c -> toDto(c, usage.get(c.getId()))).toList();
        });
    }

    /**
     * Whether a code can be used now, and what it gives: the same refusals a
     * booking gives (unknown or switched off, outside its window, used up),
     * except the buyer's own limit, which needs the buyer. Public: a code is
     * a marketing discount, not a credential, and an unknown one gets the same
     * answer as a switched-off one.
     */
    public PromoCodeCheckDTO check(UUID eventId, String rawCode) {
        String code = PromoCodes.normalize(rawCode);
        if (code == null) {
            throw PromoCodes.invalid();
        }
        return TransactionPhases.inTransaction(readTx, () -> {
            PromoCode found = promoCodeRepository.findByEventIdAndCode(eventId, code)
                    .filter(PromoCode::isActive)
                    .orElseThrow(PromoCodes::invalid);
            PromoCodes.requireOpen(found, nowUtc());
            if (found.getMaxUses() != null && bookingRepository.countByPromoCodeIdAndStatusNot(
                    found.getId(), Booking.BookingStatus.CANCELLED) >= found.getMaxUses()) {
                throw PromoCodes.usedUp();
            }
            return PromoCodeCheckDTO.builder()
                    .code(found.getCode())
                    .description(found.getDescription())
                    .discountType(found.getDiscountType().name())
                    .discountValue(found.getDiscountValue())
                    .categoryIds(new ArrayList<>(found.getCategoryIds()))
                    .endsAt(found.getEndsAt())
                    .build();
        });
    }

    private PromoCode load(UUID id) {
        return promoCodeRepository.findById(id).orElseThrow(() -> new NotFoundException("Promo code not found"));
    }

    private Map<UUID, BookingRepository.PromoCodeUsage> usage(List<UUID> ids) {
        Map<UUID, BookingRepository.PromoCodeUsage> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            bookingRepository.summarizePromoCodeUses(ids, Booking.BookingStatus.CANCELLED)
                    .forEach(row -> byId.put(row.getPromoCodeId(), row));
        }
        return byId;
    }

    private static ApiProblemException codeExists(String code) {
        return new ApiProblemException(HttpStatus.CONFLICT, CODE_EXISTS,
                "A promo code \"" + code + "\" already exists for this event.");
    }

    /** Validated terms, ready to apply to a new or a stored code. */
    private record Terms(String description, PromoCode.DiscountType type, BigDecimal value, Integer maxUses,
                         Integer maxUsesPerCustomer, LocalDateTime startsAt, LocalDateTime endsAt,
                         List<UUID> categoryIds, boolean active) {

        PromoCode applyTo(PromoCode code) {
            code.setDescription(description);
            code.setDiscountType(type);
            code.setDiscountValue(value);
            code.setMaxUses(maxUses);
            code.setMaxUsesPerCustomer(maxUsesPerCustomer);
            code.setStartsAt(startsAt);
            code.setEndsAt(endsAt);
            code.setActive(active);
            if (code.getCategoryIds() == null) {
                code.setCategoryIds(new HashSet<>());
            }
            code.getCategoryIds().clear();
            code.getCategoryIds().addAll(categoryIds);
            return code;
        }
    }

    /**
     * Re-checks what bean validation checks, for callers that skip it, and the
     * rules it cannot: a percentage below 100, a readable window, an end in
     * the future (an unchanged end on an edit is fine).
     */
    private Terms validate(PromoCodeTermsDTO request, PromoCode stored) {
        if (request.getDiscountType() == null) {
            throw new BadRequestException("Choose PERCENT or FIXED for discountType.");
        }
        BigDecimal value = request.getDiscountValue();
        if (value == null || value.signum() <= 0) {
            throw new BadRequestException("The discount must be greater than 0.");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("The discount can have at most 2 decimal places.");
        }
        if (request.getDiscountType() == PromoCode.DiscountType.PERCENT && value.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new BadRequestException("A percentage discount must be below 100: a code can't make tickets free.");
        }
        if (request.getMaxUses() != null && request.getMaxUses() <= 0) {
            throw new BadRequestException("maxUses must be at least 1.");
        }
        if (request.getMaxUsesPerCustomer() != null && request.getMaxUsesPerCustomer() <= 0) {
            throw new BadRequestException("maxUsesPerCustomer must be at least 1.");
        }
        LocalDateTime startsAt = parse(request.getStartsAt(), "start", "2026-09-01T00:00:00");
        LocalDateTime endsAt = parse(request.getEndsAt(), "end", "2026-09-08T23:59:59");
        if (endsAt != null) {
            boolean unchanged = stored != null && endsAt.equals(stored.getEndsAt());
            if (!unchanged && !endsAt.isAfter(nowUtc())) {
                throw new BadRequestException("The end of the promo code must be in the future.");
            }
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new BadRequestException("The start of the promo code must be before its end.");
        }
        String description = request.getDescription() == null ? null : request.getDescription().trim();
        List<UUID> categories = request.getCategoryIds() == null ? List.of()
                : request.getCategoryIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        return new Terms(description == null || description.isEmpty() ? null : description,
                request.getDiscountType(), value.setScale(2, java.math.RoundingMode.UNNECESSARY),
                request.getMaxUses(), request.getMaxUsesPerCustomer(), startsAt, endsAt, categories,
                request.getActive() == null || request.getActive());
    }

    private LocalDateTime parse(String value, String which, String example) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return marketTimeZone.parseToUtc(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("The " + which + " of the promo code must be a date and time like "
                    + example + ".");
        }
    }

    private PromoCodeDTO toDto(PromoCode code, BookingRepository.PromoCodeUsage usage) {
        return PromoCodeDTO.builder()
                .id(code.getId())
                .eventId(code.getEventId())
                .code(code.getCode())
                .description(code.getDescription())
                .discountType(code.getDiscountType().name())
                .discountValue(code.getDiscountValue())
                .maxUses(code.getMaxUses())
                .maxUsesPerCustomer(code.getMaxUsesPerCustomer())
                .startsAt(code.getStartsAt())
                .endsAt(code.getEndsAt())
                .categoryIds(new ArrayList<>(code.getCategoryIds()))
                .active(code.isActive())
                .uses(usage == null || usage.getUses() == null ? 0L : usage.getUses())
                .discountGiven(usage == null || usage.getDiscountGiven() == null
                        ? BigDecimal.ZERO.setScale(2) : usage.getDiscountGiven())
                .createdAt(code.getCreatedAt())
                .build();
    }

    /**
     * The caller must own the event: its {@code tenantUserUuid} is their
     * organizer uuid. Asked of event-service every time, never cached, and
     * fail-closed: an unanswerable lookup is a 403, an unknown event a 404.
     * Same model as {@code ScanReportController.requireEventOwnership}.
     */
    private void requireEventOwnership(UUID eventId, UUID callerOrganizerUuid) {
        if (callerOrganizerUuid == null) {
            throw new AccessDeniedException("Your session is missing organizer information. Please sign in again.");
        }
        EventServiceClient client = eventClientProvider == null ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing promo codes for eventId={}", eventId);
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
        if (event == null) {
            throw new NotFoundException("Event not found");
        }
        if (event.getTenantUserUuid() == null || !event.getTenantUserUuid().equals(callerOrganizerUuid)) {
            log.warn("Promo code ownership check failed eventId={} callerOrganizerUuid={} owner={}",
                    eventId, callerOrganizerUuid, event.getTenantUserUuid());
            throw new AccessDeniedException("You do not own this event");
        }
    }
}
