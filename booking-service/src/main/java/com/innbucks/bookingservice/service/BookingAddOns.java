package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.BookingAddOnDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Add-ons bought with a ticket (seat-service V12, booking-service V33).
 *
 * <ul>
 *   <li>{@link #resolve} runs before anything is held: each ticket's
 *       {@code addOnIds} must be its own category's add-ons, each at most once
 *       (400 {@code ADD_ONS_INVALID}, every problem named at once).</li>
 *   <li>{@link #requireRoom} runs under the categories' stock row locks: an
 *       add-on with a limit may not sell past it (409 {@code ADD_ON_SOLD_OUT}).
 *       The count is of tickets on bookings that are not CANCELLED, taken after
 *       the lock, so the limit is exact; a refusal rolls the claim back.</li>
 * </ul>
 *
 * <p>Add-ons are priced as listed (no tier, no promo code) and kept with their
 * name and price on the ticket. A resurrected booking (H2) keeps its add-ons,
 * so an add-on can end up above its limit by that booking: the buyer paid.
 */
public final class BookingAddOns {

    public static final String ADD_ONS_INVALID = "ADD_ONS_INVALID";
    public static final String ADD_ON_SOLD_OUT = "ADD_ON_SOLD_OUT";
    static final String ADD_ONS_INVALID_MESSAGE = "Please check the add-ons you chose.";
    static final String NOT_SOLD_WITH_TICKET = "This add-on isn't sold with this ticket.";
    static final String CHOSEN_TWICE = "Chosen more than once.";

    private BookingAddOns() {
    }

    /** Each ticket's add-ons, in the order asked, as bought (name and price from the category now). */
    public static List<List<BookingAddOnDTO>> resolve(List<CreateBookingRequestDTO.SeatItemRequest> seats,
                                                      Map<UUID, CategoryLookupDTO> categoryById) {
        Map<String, String> problems = new LinkedHashMap<>();
        List<List<BookingAddOnDTO>> perTicket = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            CreateBookingRequestDTO.SeatItemRequest seat = seats.get(i);
            List<UUID> ids = seat.getAddOnIds() == null ? List.of() : seat.getAddOnIds();
            Map<UUID, CategoryLookupDTO.AddOn> offered = new HashMap<>();
            CategoryLookupDTO category = categoryById.get(seat.getCategoryId());
            if (category != null && category.getAddOns() != null) {
                category.getAddOns().forEach(a -> offered.put(a.getId(), a));
            }
            Set<UUID> seen = new HashSet<>();
            List<BookingAddOnDTO> chosen = new ArrayList<>();
            for (UUID id : ids) {
                String key = "seats[" + i + "]." + id;
                CategoryLookupDTO.AddOn addOn = id == null ? null : offered.get(id);
                if (addOn == null) {
                    problems.putIfAbsent(key, NOT_SOLD_WITH_TICKET);
                } else if (!seen.add(id)) {
                    problems.put(key, CHOSEN_TWICE);
                } else {
                    chosen.add(BookingAddOnDTO.builder()
                            .addOnId(addOn.getId()).name(addOn.getName()).price(addOn.getPrice()).build());
                }
            }
            perTicket.add(chosen);
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ADD_ONS_INVALID, ADD_ONS_INVALID_MESSAGE,
                    Map.of("addOns", problems));
        }
        return perTicket;
    }

    /** How many of each add-on this booking takes, by add-on id. */
    public static Map<UUID, Integer> requested(List<List<BookingAddOnDTO>> perTicket) {
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        perTicket.forEach(ticket -> ticket.forEach(a -> wanted.merge(a.getAddOnId(), 1, Integer::sum)));
        return wanted;
    }

    /**
     * Refuses the booking when an add-on with a limit would sell past it.
     * {@code sold} is the count taken under the stock lock (absent = 0).
     */
    public static void requireRoom(Map<UUID, Integer> wanted, Map<UUID, CategoryLookupDTO.AddOn> terms,
                                   Map<UUID, Long> sold) {
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            CategoryLookupDTO.AddOn addOn = terms.get(e.getKey());
            if (addOn == null || addOn.getMaxQuantity() == null) {
                continue;
            }
            long left = Math.max(0L, addOn.getMaxQuantity() - sold.getOrDefault(e.getKey(), 0L));
            if (e.getValue() > left) {
                String message = left == 0
                        ? "\"" + addOn.getName() + "\" is sold out."
                        : "Only " + left + " \"" + addOn.getName() + "\" left. Please choose fewer.";
                throw new ApiProblemException(HttpStatus.CONFLICT, ADD_ON_SOLD_OUT, message,
                        Map.of("addOnId", addOn.getId(), "remaining", left));
            }
        }
    }

    /** The booking's add-ons total; null when it has none. */
    public static BigDecimal total(List<List<BookingAddOnDTO>> perTicket) {
        BigDecimal total = null;
        for (List<BookingAddOnDTO> ticket : perTicket) {
            for (BookingAddOnDTO a : ticket) {
                total = (total == null ? BigDecimal.ZERO : total).add(a.getPrice() == null ? BigDecimal.ZERO : a.getPrice());
            }
        }
        return total;
    }
}
