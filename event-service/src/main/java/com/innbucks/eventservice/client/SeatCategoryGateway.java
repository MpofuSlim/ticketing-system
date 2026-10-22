package com.innbucks.eventservice.client;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.EventPriceTierDTO;
import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import com.innbucks.eventservice.dto.EventSectionResponseDTO;
import com.innbucks.eventservice.dto.SeatCategorySectionDTO;
import com.innbucks.eventservice.dto.SeatCategoryServiceResponseDTO;
import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadThroughCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wraps the outbound call to seat-service in a circuit breaker (Resilience4j
 * via Spring Cloud's abstraction) plus retry. Falls back to an empty
 * category list when the call fails or the breaker is open — event-service
 * still returns the event itself, just without enriched seat detail.
 */
@Component
@Slf4j
public class SeatCategoryGateway {

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final String seatServiceBaseUrl;
    private final ReadThroughCache layoutCache;
    /** Decides whether a price tier is still open. Tests move it. */
    private Clock clock = Clock.systemUTC();

    @Autowired
    public SeatCategoryGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Value("${seat-service.base-url:http://seat-service}") String seatServiceBaseUrl,
            @Qualifier(ReadCacheConfig.READ_CACHE_MANAGER) CacheManager cacheManager) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("seatCategories");
        this.seatServiceBaseUrl = seatServiceBaseUrl;
        this.layoutCache = ReadThroughCache.of(cacheManager, ReadCacheConfig.SEAT_CATEGORIES);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** No cache: every {@link #fetchForEvent} calls seat-service. */
    public SeatCategoryGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            String seatServiceBaseUrl) {
        this(restTemplate, circuitBreakerFactory, seatServiceBaseUrl, null);
    }

    /**
     * The event's seat categories for PUBLIC display, served from a per-event
     * cache ({@code events.cache.seat-categories.ttl}, 30s) when this pod has
     * a fresh copy.
     *
     * <p>What is cached is the static layout only — name, description, price,
     * the price tiers' terms and sold counts, section labels and seat counts (the allocation, not remaining stock;
     * this response has never carried live availability). Category writes
     * happen in seat-service, so nothing here can evict: a new, repriced or
     * deleted category reaches the public detail view within the TTL. The
     * booking itself prices from seat-service live, never from this.
     *
     * <p><b>A failure is never cached.</b> Only a real seat-service answer is
     * stored; the breaker fallback (empty list) is returned and forgotten, so
     * one blip does not hide an event's categories for the whole TTL.
     */
    public List<EventSeatCategoryResponseDTO> fetchForEvent(UUID eventId) {
        Cache.ValueWrapper hit = layoutCache.lookup(eventId);
        if (hit != null && hit.get() instanceof CategoryLayout layout) {
            return layout.toDtos(nowUtc());
        }
        return circuitBreaker.run(
                () -> {
                    CategoryLayout layout = CategoryLayout.of(fetchRaw(eventId));
                    layoutCache.put(eventId, layout);
                    return layout.toDtos(nowUtc());
                },
                throwable -> {
                    log.warn("seatCategories breaker fallback eventId={}",
                            eventId, throwable);
                    return Collections.emptyList();
                }
        );
    }

    /**
     * Same answer as {@link #fetchForEvent}, always from seat-service. For the
     * event's owner and platform staff, who must see their own seat-map edits
     * at once rather than after another replica's TTL.
     */
    public List<EventSeatCategoryResponseDTO> fetchForEventUncached(UUID eventId) {
        return circuitBreaker.run(
                () -> doFetch(eventId),
                throwable -> {
                    log.warn("seatCategories breaker fallback eventId={}",
                            eventId, throwable);
                    return Collections.emptyList();
                }
        );
    }

    /**
     * Total seats allocated across the event's live categories, or empty when
     * seat-service could not be asked.
     *
     * <p><b>This deliberately does NOT reuse {@link #fetchForEvent}'s fallback.</b>
     * That one degrades to an empty list so an event still renders without its
     * seat detail — right for a read. Here the answer gates a WRITE, and an
     * empty list is indistinguishable from "no categories", so the same fallback
     * would report zero seats allocated during a seat-service outage and wave
     * through exactly the capacity cut this exists to refuse. Empty Optional
     * means "could not ask" and the caller must refuse; {@code Optional.of(0L)}
     * means "asked, genuinely nothing allocated".
     *
     * <p>Summed from section seat counts because that IS the category's
     * {@code totalSeats} (seat-service derives one from the other at creation),
     * and the existing listing endpoint already carries it — no new endpoint,
     * no seat-service change. Deliberately not {@code availableSeats}, which is
     * live remaining stock, not the allocation.
     *
     * <p><b>Never cached</b>, unlike {@link #fetchForEvent}: it gates a
     * capacity write (the oversell guard), and a cached allocation would let a
     * capacity cut through against a seat map that has since grown.
     */
    public java.util.Optional<Long> fetchAllocatedSeats(UUID eventId) {
        return circuitBreaker.run(
                () -> java.util.Optional.of(
                        fetchRaw(eventId).stream()
                                .flatMap(c -> c.getSections() == null
                                        ? java.util.stream.Stream.<SeatCategorySectionDTO>empty()
                                        : c.getSections().stream())
                                .mapToLong(s -> s.getSeatCount() == null ? 0L : s.getSeatCount())
                                .sum()),
                throwable -> {
                    log.warn("seatCategories breaker fallback on allocation lookup eventId={}",
                            eventId, throwable);
                    return java.util.Optional.empty();
                }
        );
    }

    private List<SeatCategoryServiceResponseDTO> fetchRaw(UUID eventId) {
        String url = UriComponentsBuilder
                .fromUriString(seatServiceBaseUrl)
                .path("/seat-categories")
                .queryParam("eventId", eventId)
                .toUriString();

        // seat-service wraps every payload in its ApiResult envelope
        // {code, message, data: [...]}; deserialise into that and read .data.
        // (Direct array deserialisation died on every call after seat-service
        // moved to the standard envelope — every event lookup fell through to
        // the breaker fallback with an empty category list.)
        ResponseEntity<ApiResult<List<SeatCategoryServiceResponseDTO>>> response =
                restTemplate.exchange(url, HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {});
        ApiResult<List<SeatCategoryServiceResponseDTO>> envelope = response.getBody();
        List<SeatCategoryServiceResponseDTO> categories =
                envelope == null ? null : envelope.getData();
        return categories == null ? Collections.emptyList() : categories;
    }

    private List<EventSeatCategoryResponseDTO> doFetch(UUID eventId) {
        return CategoryLayout.of(fetchRaw(eventId)).toDtos(nowUtc());
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * The cached form of one event's categories: immutable records, rebuilt
     * into fresh DTOs on every read so no caller can mutate what the next one
     * is served. Holds each price tier's terms and the sold count seat-service
     * reported, never which tier applies: {@link #toDtos} works that out at the
     * time of each read, so a cached copy can never keep a tier open past its
     * end. A sold count can be up to the TTL old (display only; the booking is
     * priced by booking-service itself).
     */
    record CategoryLayout(List<CategoryView> categories) {

        static CategoryLayout of(List<SeatCategoryServiceResponseDTO> raw) {
            List<CategoryView> views = new java.util.ArrayList<>(raw.size());
            for (SeatCategoryServiceResponseDTO category : raw) {
                List<SectionView> sections = new java.util.ArrayList<>();
                if (category.getSections() != null) {
                    for (SeatCategorySectionDTO section : category.getSections()) {
                        sections.add(new SectionView(section.getSection(), section.getSeatCount()));
                    }
                }
                List<TierView> tiers = new java.util.ArrayList<>();
                if (category.getPriceTiers() != null) {
                    for (SeatCategoryServiceResponseDTO.PriceTier tier : category.getPriceTiers()) {
                        LocalDateTime endsAt = parseUtc(tier.getEndsAt());
                        if (tier.getEndsAt() != null && !tier.getEndsAt().isBlank() && endsAt == null) {
                            continue;
                        }
                        tiers.add(new TierView(tier.getName(), tier.getPrice(), endsAt, tier.getQuantity(),
                                tier.getSold()));
                    }
                }
                views.add(new CategoryView(category.getName(), category.getDescription(),
                        category.getPrice(), List.copyOf(tiers), List.copyOf(sections)));
            }
            return new CategoryLayout(List.copyOf(views));
        }

        List<EventSeatCategoryResponseDTO> toDtos(LocalDateTime nowUtc) {
            if (categories.isEmpty()) {
                return Collections.emptyList();
            }
            return categories.stream()
                    .map(c -> {
                        List<EventPriceTierDTO> tiers = c.priceTiers(nowUtc);
                        EventPriceTierDTO active = tiers.stream()
                                .filter(t -> t.getStatus() == EventPriceTierDTO.Status.ACTIVE)
                                .findFirst().orElse(null);
                        return EventSeatCategoryResponseDTO.builder()
                                .name(c.name())
                                .description(c.description())
                                .categoryPrice(c.price())
                                .currentPrice(active == null ? c.price() : active.getPrice())
                                .currentTierName(active == null ? null : active.getName())
                                .priceTiers(tiers)
                                .sections(c.sections().isEmpty()
                                        ? Collections.emptyList()
                                        : c.sections().stream()
                                                .map(s -> EventSectionResponseDTO.builder()
                                                        .section(s.section())
                                                        .seatCount(s.seatCount())
                                                        .price(c.price())
                                                        .build())
                                                .collect(Collectors.toList()))
                                .build();
                    })
                    .collect(Collectors.toList());
        }
    }

    record CategoryView(String name, String description, BigDecimal price, List<TierView> tiers,
                        List<SectionView> sections) {

        /**
         * seat-service's rule (its {@code CategoryPricing}), kept in lock-step:
         * tiers apply in order, the first one neither past its end nor sold to
         * its quantity (and below the full price) is ACTIVE.
         */
        List<EventPriceTierDTO> priceTiers(LocalDateTime nowUtc) {
            List<EventPriceTierDTO> out = new java.util.ArrayList<>(tiers.size());
            boolean activeFound = false;
            for (TierView tier : tiers) {
                EventPriceTierDTO.Status status;
                if (tier.endsAtUtc() != null && !nowUtc.isBefore(tier.endsAtUtc())) {
                    status = EventPriceTierDTO.Status.ENDED;
                } else if (tier.quantity() != null && tier.sold() != null && tier.sold() >= tier.quantity()) {
                    status = EventPriceTierDTO.Status.SOLD_OUT;
                } else if (!activeFound && price != null && tier.price() != null
                        && tier.price().compareTo(price) < 0) {
                    status = EventPriceTierDTO.Status.ACTIVE;
                    activeFound = true;
                } else {
                    status = EventPriceTierDTO.Status.UPCOMING;
                }
                out.add(EventPriceTierDTO.builder()
                        .name(tier.name())
                        .price(tier.price())
                        .endsAt(tier.endsAtUtc())
                        .quantity(tier.quantity())
                        .remaining(tier.quantity() == null || tier.sold() == null ? null
                                : Math.max(0L, tier.quantity() - tier.sold()))
                        .status(status)
                        .build());
            }
            return out;
        }
    }

    record TierView(String name, BigDecimal price, LocalDateTime endsAtUtc, Integer quantity, Long sold) {
    }

    record SectionView(String section, Integer seatCount) {
    }

    /**
     * seat-service's {@code endsAt} as a UTC wall clock: with a zone (it
     * renders the market offset unless told the caller is a service) that
     * instant, without one UTC, the S2S convention. Unreadable is null, which
     * drops that tier from the page; the booking is still priced by
     * booking-service from seat-service's own answer.
     */
    static LocalDateTime parseUtc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException withoutZone) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException unreadable) {
                log.warn("seatCategories price tier end unreadable, tier dropped from the event page");
                return null;
            }
        }
    }
}
