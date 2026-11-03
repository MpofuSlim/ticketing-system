package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.dto.AddOnDTO;
import com.innbucks.seatservice.dto.AddOnRequestDTO;
import com.innbucks.seatservice.dto.AddOnsRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.entity.SeatCategoryAddOn;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.repository.SeatCategoryAddOnRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A category's add-ons (V12): extras a buyer adds to each ticket, at most one
 * of each per ticket, with an optional limit that can never exceed the
 * category's tickets ("100 VIP tickets with 50 parking passes, never 200").
 *
 * <p>The list is replaced as a whole, like the price tiers: a kept add-on keeps
 * its id (its sales stay counted against its limit), one without an id is new,
 * a stored one left out is deleted. booking-service keeps each sold add-on's
 * name and price, so nothing here restates a sale.
 *
 * <p>{@code totalSeats} cannot be edited after a category is created, so a
 * limit checked here stays within it. If capacity editing is ever added, it
 * must refuse going below the largest add-on limit ("lower the add-on's limit
 * first").
 *
 * <p>Counts shown with each add-on come from booking-service and are display
 * only (null when it cannot be asked); booking-service enforces the limit from
 * its own count under the category's stock lock.
 */
@Component
@Slf4j
public class CategoryAddOns {

    static final int MAX_NAME = 60;
    static final int MAX_DESCRIPTION = 200;

    private final SeatCategoryAddOnRepository repository;
    private final BookingServiceClient bookingServiceClient;

    public CategoryAddOns(SeatCategoryAddOnRepository repository, BookingServiceClient bookingServiceClient) {
        this.repository = repository;
        this.bookingServiceClient = bookingServiceClient;
    }

    /** The add-ons' terms (no counts), read live: what booking-service checks a booking against. */
    public List<AddOnDTO> terms(UUID categoryId) {
        return repository.findByCategoryIdOrderByPositionAsc(categoryId).stream()
                .map(a -> toDto(a, null))
                .toList();
    }

    /** Sets {@code addOns} on each answer, with best-effort counts. */
    public void attach(Collection<CreateCategoryResponseDTO> categories) {
        List<UUID> ids = categories.stream()
                .map(CreateCategoryResponseDTO::getSeatCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, List<SeatCategoryAddOn>> byCategory = ids.isEmpty() ? Map.of()
                : repository.findByCategoryIdInOrderByCategoryIdAscPositionAsc(ids).stream()
                        .collect(Collectors.groupingBy(SeatCategoryAddOn::getCategoryId, LinkedHashMap::new,
                                Collectors.toList()));
        Map<UUID, Long> sold = byCategory.isEmpty() ? Map.of()
                : bookingServiceClient.fetchAddOnSoldCounts(byCategory.keySet()).orElse(null);
        for (CreateCategoryResponseDTO category : categories) {
            List<SeatCategoryAddOn> addOns = byCategory.getOrDefault(category.getSeatCategoryId(), List.of());
            category.setAddOns(addOns.stream()
                    .map(a -> toDto(a, sold == null ? null : sold.getOrDefault(a.getId(), 0L)))
                    .toList());
        }
    }

    public void attach(CreateCategoryResponseDTO category) {
        if (category != null) {
            attach(List.of(category));
        }
    }

    /**
     * Replaces the category's add-ons with {@code request.addOns}, after checking
     * every one; a refusal changes nothing. Runs in the caller's transaction.
     */
    public void replace(SeatCategory category, AddOnsRequestDTO request) {
        List<AddOnRequestDTO> wanted = request.getAddOns() == null ? List.of() : request.getAddOns();
        List<SeatCategoryAddOn> stored = repository.findByCategoryIdOrderByPositionAsc(category.getId());
        Map<UUID, SeatCategoryAddOn> storedById = new LinkedHashMap<>();
        stored.forEach(a -> storedById.put(a.getId(), a));

        Set<String> names = new HashSet<>();
        Set<UUID> kept = new HashSet<>();
        List<SeatCategoryAddOn> rows = new ArrayList<>();
        for (int position = 0; position < wanted.size(); position++) {
            AddOnRequestDTO in = wanted.get(position);
            if (in == null) {
                throw new BadRequestException("An add-on needs a name of at most 60 characters.");
            }
            if (in.getId() != null) {
                if (!storedById.containsKey(in.getId())) {
                    throw new BadRequestException("Add-on " + in.getId() + " does not belong to this category.");
                }
                if (!kept.add(in.getId())) {
                    throw new BadRequestException("Add-on " + in.getId() + " is listed twice.");
                }
            }
            String name = in.getName() == null ? "" : in.getName().strip();
            if (name.isEmpty() || name.length() > MAX_NAME || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
                throw new BadRequestException("An add-on needs a name of at most 60 characters.");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Two add-ons can't have the same name: \"" + name + "\".");
            }
            BigDecimal price = in.getPrice();
            if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > 2) {
                throw new BadRequestException("The price of \"" + name + "\" must be 0 or more, with at most 2 decimals.");
            }
            String description = in.getDescription() == null || in.getDescription().isBlank()
                    ? null : in.getDescription().strip();
            if (description != null && description.length() > MAX_DESCRIPTION) {
                throw new BadRequestException("The description of \"" + name + "\" must be at most 200 characters.");
            }
            Integer limit = in.getMaxQuantity();
            if (limit != null && limit < 1) {
                throw new BadRequestException("The limit of \"" + name + "\" must be at least 1.");
            }
            if (limit != null && category.getTotalSeats() != null && limit > category.getTotalSeats()) {
                throw new BadRequestException("\"" + name + "\" can't be limited to more than this category's "
                        + category.getTotalSeats() + " tickets.");
            }
            SeatCategoryAddOn row = in.getId() == null
                    ? SeatCategoryAddOn.builder().categoryId(category.getId()).build()
                    : storedById.get(in.getId());
            row.setPosition(position);
            row.setName(name);
            row.setDescription(description);
            row.setPrice(price.setScale(2, java.math.RoundingMode.UNNECESSARY));
            row.setMaxQuantity(limit);
            rows.add(row);
        }

        List<SeatCategoryAddOn> removed = stored.stream().filter(a -> !kept.contains(a.getId())).toList();
        repository.deleteAll(removed);
        repository.saveAll(rows);
        log.info("Add-ons set categoryId={} eventId={} count={} removed={}",
                category.getId(), category.getEventId(), rows.size(), removed.size());
    }

    static AddOnDTO toDto(SeatCategoryAddOn a, Long sold) {
        Long remaining = sold == null || a.getMaxQuantity() == null ? null
                : Math.max(0L, a.getMaxQuantity() - sold);
        return AddOnDTO.builder()
                .id(a.getId())
                .name(a.getName())
                .description(a.getDescription())
                .price(a.getPrice())
                .maxQuantity(a.getMaxQuantity())
                .sold(sold)
                .remaining(remaining)
                .soldOut(remaining != null && remaining == 0)
                .build();
    }
}
