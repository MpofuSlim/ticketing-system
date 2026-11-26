package com.innbucks.seatservice.service;

import com.innbucks.seatservice.cache.ReadCacheConfig;
import com.innbucks.seatservice.cache.ReadThroughCache;
import com.innbucks.seatservice.dto.CopiedCategoryDTO;
import com.innbucks.seatservice.dto.CopiedLayoutDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.entity.SeatCategoryAddOn;
import com.innbucks.seatservice.entity.SeatCategoryPriceChange;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.repository.SeatCategoryAddOnRepository;
import com.innbucks.seatservice.repository.SeatCategoryPriceChangeRepository;
import com.innbucks.seatservice.repository.SeatCategoryPriceTierRepository;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Copies one event's seat layout onto another: the second step of
 * event-service's series generator, which copies a series' template event
 * onto each new date (TIC-70).
 *
 * <p>What is copied, per live category of the source: its name, description,
 * full price and ticket count; its sections (label, image, seat count), as
 * fresh seats numbered from 1; its price tiers that have not ended, in order
 * and with their times unchanged (tier times are instants, so a tier ending on
 * 8 September ends then on every date); and its add-ons. Each new category gets
 * a CREATED price-change row, as an organizer-made one does. Not copied: number
 * series and promo codes (both booking-service's, keyed by the old ids).
 *
 * <p><b>Idempotent, so the caller can retry blindly.</b> The copy runs in one
 * transaction behind an advisory lock on the target, and does nothing when the
 * target already has a live category: a repeated call (the first answer was
 * lost) finds the first copy and answers with the mapping by category name,
 * which is unique among an event's live categories. The caller makes this call
 * with no transaction of its own open.
 */
@Service
@Slf4j
public class SeatLayoutCopyService {

    /** {@code pg_advisory_xact_lock} namespace of a layout copy, "LCPY"; the key is the target event's hash. */
    static final int COPY_LOCK_NAMESPACE = 0x4C43_5059;

    private final SeatCategoryRepository categoryRepository;
    private final SeatCategoryPriceTierRepository tierRepository;
    private final SeatCategoryAddOnRepository addOnRepository;
    private final SeatRepository seatRepository;
    private final SeatCategoryPriceChangeRepository priceChangeRepository;
    private final ReadThroughCache layoutCache;
    /** Decides which tiers have ended. Tests move it. */
    private Clock clock = Clock.systemUTC();

    public SeatLayoutCopyService(SeatCategoryRepository categoryRepository,
                                 SeatCategoryPriceTierRepository tierRepository,
                                 SeatCategoryAddOnRepository addOnRepository,
                                 SeatRepository seatRepository,
                                 SeatCategoryPriceChangeRepository priceChangeRepository,
                                 @Qualifier(ReadCacheConfig.READ_CACHE_MANAGER) CacheManager cacheManager) {
        this.categoryRepository = categoryRepository;
        this.tierRepository = tierRepository;
        this.addOnRepository = addOnRepository;
        this.seatRepository = seatRepository;
        this.priceChangeRepository = priceChangeRepository;
        this.layoutCache = ReadThroughCache.of(cacheManager, ReadCacheConfig.CATEGORY_LAYOUT);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    static final String SAME_EVENT = "The source and target events must be different.";

    static String tooManySeats(long seats, int capacity) {
        return "The seat layout has " + seats + " seats, more than the event's capacity of " + capacity + ".";
    }

    @Transactional
    public CopiedLayoutDTO copy(UUID sourceEventId, UUID targetEventId, Integer maxTotalSeats) {
        if (sourceEventId == null || targetEventId == null) {
            throw new BadRequestException("sourceEventId and targetEventId are required.");
        }
        if (sourceEventId.equals(targetEventId)) {
            throw new BadRequestException(SAME_EVENT);
        }
        categoryRepository.lockEventLayout(COPY_LOCK_NAMESPACE, targetEventId.hashCode());

        List<SeatCategory> source = new ArrayList<>(categoryRepository.findByEventIdAndDeletedFalse(sourceEventId));
        source.sort(Comparator.comparing(SeatCategory::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SeatCategory::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        List<SeatCategory> existing = categoryRepository.findByEventIdAndDeletedFalse(targetEventId);
        if (!existing.isEmpty()) {
            log.info("Layout copy skipped, target already has categories sourceEventId={} targetEventId={} existing={}",
                    sourceEventId, targetEventId, existing.size());
            return CopiedLayoutDTO.builder().copied(false).categories(byName(source, existing)).build();
        }
        long seats = source.stream().mapToLong(c -> c.getTotalSeats() == null ? 0 : c.getTotalSeats()).sum();
        if (maxTotalSeats != null && seats > maxTotalSeats) {
            log.warn("Layout copy refused, more seats than the target's capacity sourceEventId={} targetEventId={} "
                    + "seats={} capacity={}", sourceEventId, targetEventId, seats, maxTotalSeats);
            throw new ConflictException(tooManySeats(seats, maxTotalSeats));
        }
        if (source.isEmpty()) {
            return CopiedLayoutDTO.builder().copied(true).categories(List.of()).build();
        }

        List<UUID> sourceIds = source.stream().map(SeatCategory::getId).toList();
        Map<UUID, List<SeatCategoryPriceTier>> tiers = new LinkedHashMap<>();
        tierRepository.findByCategoryIdInOrderByCategoryIdAscPositionAsc(sourceIds)
                .forEach(t -> tiers.computeIfAbsent(t.getCategoryId(), k -> new ArrayList<>()).add(t));
        Map<UUID, List<SeatCategoryAddOn>> addOns = new LinkedHashMap<>();
        addOnRepository.findByCategoryIdInOrderByCategoryIdAscPositionAsc(sourceIds)
                .forEach(a -> addOns.computeIfAbsent(a.getCategoryId(), k -> new ArrayList<>()).add(a));
        Map<UUID, List<SeatRepository.SectionCount>> sections = new LinkedHashMap<>();
        seatRepository.countSections(sourceIds)
                .forEach(s -> sections.computeIfAbsent(s.getCategoryId(), k -> new ArrayList<>()).add(s));

        List<SeatCategory> copies = new ArrayList<>(source.size());
        for (SeatCategory from : source) {
            copies.add(SeatCategory.builder()
                    .eventId(targetEventId)
                    .name(from.getName())
                    .description(from.getDescription())
                    .price(from.getPrice())
                    .totalSeats(from.getTotalSeats())
                    .availableSeats(from.getTotalSeats())
                    .deleted(false)
                    .build());
        }
        // Flushed before the children: tiers and add-ons name their category
        // by a plain column, so nothing else tells Hibernate the order.
        categoryRepository.saveAllAndFlush(copies);

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<CopiedCategoryDTO> mapping = new ArrayList<>(source.size());
        List<Seat> newSeats = new ArrayList<>();
        int tiersCopied = 0;
        int tiersSkipped = 0;
        for (int i = 0; i < source.size(); i++) {
            SeatCategory from = source.get(i);
            SeatCategory to = copies.get(i);

            List<SeatCategoryPriceTier> newTiers = new ArrayList<>();
            for (SeatCategoryPriceTier tier : tiers.getOrDefault(from.getId(), List.of())) {
                if (tier.getEndsAt() != null && !tier.getEndsAt().isAfter(now)) {
                    tiersSkipped++;
                    continue;
                }
                newTiers.add(SeatCategoryPriceTier.builder()
                        .categoryId(to.getId())
                        .position(newTiers.size())
                        .name(tier.getName())
                        .price(tier.getPrice())
                        .startsAt(tier.getStartsAt())
                        .endsAt(tier.getEndsAt())
                        .quantity(tier.getQuantity())
                        .maxPerCustomer(tier.getMaxPerCustomer())
                        .refundsReopen(tier.isRefundsReopen())
                        .build());
            }
            List<SeatCategoryPriceTier> savedTiers = newTiers.isEmpty() ? List.of() : tierRepository.saveAll(newTiers);
            tiersCopied += savedTiers.size();

            List<SeatCategoryAddOn> newAddOns = new ArrayList<>();
            for (SeatCategoryAddOn addOn : addOns.getOrDefault(from.getId(), List.of())) {
                newAddOns.add(SeatCategoryAddOn.builder()
                        .categoryId(to.getId())
                        .position(newAddOns.size())
                        .name(addOn.getName())
                        .description(addOn.getDescription())
                        .price(addOn.getPrice())
                        .maxQuantity(addOn.getMaxQuantity())
                        .build());
            }
            if (!newAddOns.isEmpty()) {
                addOnRepository.saveAll(newAddOns);
            }

            for (SeatRepository.SectionCount section : sections.getOrDefault(from.getId(), List.of())) {
                long count = section.getSeatCount() == null ? 0 : section.getSeatCount();
                for (int number = 1; number <= count; number++) {
                    newSeats.add(Seat.builder()
                            .category(to)
                            .sectionLabel(section.getSectionLabel())
                            .sectionImageUrl(section.getImageUrl())
                            .seatNumber(number)
                            .status(Seat.SeatStatus.AVAILABLE)
                            .build());
                }
            }

            priceChangeRepository.save(SeatCategoryPriceChange.builder()
                    .categoryId(to.getId())
                    .eventId(targetEventId)
                    .changeType(SeatCategoryPriceChange.ChangeType.CREATED)
                    .newPrice(to.getPrice())
                    .tiersAfter(PriceHistory.snapshot(savedTiers))
                    .changedAt(now)
                    .build());

            mapping.add(CopiedCategoryDTO.builder()
                    .sourceCategoryId(from.getId())
                    .categoryId(to.getId())
                    .name(to.getName())
                    .build());
        }
        if (!newSeats.isEmpty()) {
            seatRepository.saveAll(newSeats);
        }
        layoutCache.evict(targetEventId);
        log.info("Layout copied sourceEventId={} targetEventId={} categories={} seats={} tiers={} tiersEndedSkipped={}",
                sourceEventId, targetEventId, copies.size(), newSeats.size(), tiersCopied, tiersSkipped);
        return CopiedLayoutDTO.builder().copied(true).categories(mapping).build();
    }

    /** Each source category paired with the target's live category of the same name, where there is one. */
    private static List<CopiedCategoryDTO> byName(List<SeatCategory> source, List<SeatCategory> target) {
        Map<String, SeatCategory> targetByName = new LinkedHashMap<>();
        target.forEach(c -> targetByName.putIfAbsent(c.getName(), c));
        List<CopiedCategoryDTO> mapping = new ArrayList<>();
        for (SeatCategory from : source) {
            SeatCategory to = targetByName.get(from.getName());
            if (to != null) {
                mapping.add(CopiedCategoryDTO.builder()
                        .sourceCategoryId(from.getId())
                        .categoryId(to.getId())
                        .name(to.getName())
                        .build());
            }
        }
        return mapping;
    }
}
