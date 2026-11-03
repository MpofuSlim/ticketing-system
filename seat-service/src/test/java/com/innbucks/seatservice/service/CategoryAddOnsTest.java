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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * A category's add-ons (V12): the rules of the list, the limit that can never
 * exceed the category's tickets, and the counts shown with them.
 */
class CategoryAddOnsTest {

    private static final UUID CATEGORY = UUID.randomUUID();

    private final SeatCategoryAddOnRepository repo = mock(SeatCategoryAddOnRepository.class);
    private final BookingServiceClient bookings = mock(BookingServiceClient.class);
    private final CategoryAddOns addOns = new CategoryAddOns(repo, bookings);

    private static SeatCategory vip(int tickets) {
        SeatCategory c = new SeatCategory();
        c.setId(CATEGORY);
        c.setEventId(UUID.randomUUID());
        c.setName("VIP");
        c.setTotalSeats(tickets);
        return c;
    }

    private static AddOnRequestDTO addOn(UUID id, String name, String price, Integer max) {
        AddOnRequestDTO a = new AddOnRequestDTO();
        a.setId(id);
        a.setName(name);
        a.setPrice(price == null ? null : new BigDecimal(price));
        a.setMaxQuantity(max);
        return a;
    }

    private static AddOnsRequestDTO request(AddOnRequestDTO... list) {
        AddOnsRequestDTO r = new AddOnsRequestDTO();
        r.setAddOns(new ArrayList<>(List.of(list)));
        return r;
    }

    private static SeatCategoryAddOn stored(UUID id, String name, int max) {
        return SeatCategoryAddOn.builder().id(id).categoryId(CATEGORY).name(name).price(new BigDecimal("5.00"))
                .maxQuantity(max).build();
    }

    private String refusal(AddOnsRequestDTO request, int tickets) {
        return assertThrows(BadRequestException.class, () -> addOns.replace(vip(tickets), request)).getMessage();
    }

    @Test
    void aLimit_canNeverExceedTheCategorysTickets() {
        when(repo.findByCategoryIdOrderByPositionAsc(CATEGORY)).thenReturn(List.of());

        assertEquals("\"Parking pass\" can't be limited to more than this category's 100 tickets.",
                refusal(request(addOn(null, "Parking pass", "5.00", 200)), 100));

        addOns.replace(vip(100), request(addOn(null, "Parking pass", "5.00", 100)));
        verify(repo).saveAll(anyCollection());
    }

    @Test
    void everyRuleHasItsOwnMessage_andARefusalChangesNothing() {
        UUID unknown = UUID.randomUUID();
        when(repo.findByCategoryIdOrderByPositionAsc(CATEGORY)).thenReturn(List.of());

        assertEquals("An add-on needs a name of at most 60 characters.",
                refusal(request(addOn(null, " ", "5.00", null)), 100));
        assertEquals("An add-on needs a name of at most 60 characters.",
                refusal(request(addOn(null, "x".repeat(61), "5.00", null)), 100));
        assertEquals("Two add-ons can't have the same name: \"parking PASS\".",
                refusal(request(addOn(null, "Parking pass", "5.00", null), addOn(null, "parking PASS", "1.00", null)), 100));
        assertEquals("The price of \"Parking pass\" must be 0 or more, with at most 2 decimals.",
                refusal(request(addOn(null, "Parking pass", "-1", null)), 100));
        assertEquals("The price of \"Parking pass\" must be 0 or more, with at most 2 decimals.",
                refusal(request(addOn(null, "Parking pass", "5.005", null)), 100));
        assertEquals("The price of \"Parking pass\" must be 0 or more, with at most 2 decimals.",
                refusal(request(addOn(null, "Parking pass", null, null)), 100));
        assertEquals("The limit of \"Parking pass\" must be at least 1.",
                refusal(request(addOn(null, "Parking pass", "5.00", 0)), 100));
        AddOnRequestDTO longDescription = addOn(null, "Parking pass", "5.00", null);
        longDescription.setDescription("d".repeat(201));
        assertEquals("The description of \"Parking pass\" must be at most 200 characters.",
                refusal(request(longDescription), 100));
        assertEquals("Add-on " + unknown + " does not belong to this category.",
                refusal(request(addOn(unknown, "Parking pass", "5.00", null)), 100));

        verify(repo, never()).saveAll(any());
        verify(repo, never()).deleteAll(any());
    }

    @Test
    void theSameIdTwice_isRefused() {
        UUID kept = UUID.randomUUID();
        when(repo.findByCategoryIdOrderByPositionAsc(CATEGORY)).thenReturn(List.of(stored(kept, "Parking pass", 50)));

        assertEquals("Add-on " + kept + " is listed twice.", refusal(request(
                addOn(kept, "Parking pass", "5.00", null), addOn(kept, "Car park", "5.00", null)), 100));
    }

    @Test
    void theListIsReplaced_keptOnesKeepTheirId_leftOutOnesGo_freeIsAllowed() {
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        SeatCategoryAddOn keptRow = stored(kept, "Parking pass", 50);
        SeatCategoryAddOn droppedRow = stored(dropped, "Old", 10);
        when(repo.findByCategoryIdOrderByPositionAsc(CATEGORY)).thenReturn(List.of(keptRow, droppedRow));

        addOns.replace(vip(100), request(
                addOn(null, "Free cap", "0", 50),
                addOn(kept, " Car park ", "6", null)));

        verify(repo).deleteAll(List.of(droppedRow));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SeatCategoryAddOn>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(repo).saveAll(saved.capture());
        List<SeatCategoryAddOn> rows = new ArrayList<>(saved.getValue());
        assertEquals("Free cap", rows.get(0).getName());
        assertEquals(new BigDecimal("0.00"), rows.get(0).getPrice());
        assertEquals(0, rows.get(0).getPosition());
        assertSame(keptRow, rows.get(1), "a kept add-on is updated in place");
        assertEquals("Car park", rows.get(1).getName());
        assertEquals(new BigDecimal("6.00"), rows.get(1).getPrice());
        assertNull(rows.get(1).getMaxQuantity(), "a limit left out is no limit");
        assertEquals(1, rows.get(1).getPosition());
    }

    @Test
    void attach_showsSoldAndRemaining_andNullCountsWhenBookingServiceIsUnreachable() {
        UUID limited = UUID.randomUUID();
        UUID unlimited = UUID.randomUUID();
        SeatCategoryAddOn parking = stored(limited, "Parking pass", 50);
        SeatCategoryAddOn meal = stored(unlimited, "Meal voucher", 1);
        meal.setMaxQuantity(null);
        when(repo.findByCategoryIdInOrderByCategoryIdAscPositionAsc(any())).thenReturn(List.of(parking, meal));
        when(bookings.fetchAddOnSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of(limited, 50L, unlimited, 30L)));
        CreateCategoryResponseDTO category = CreateCategoryResponseDTO.builder().seatCategoryId(CATEGORY).build();
        CreateCategoryResponseDTO other = CreateCategoryResponseDTO.builder().seatCategoryId(UUID.randomUUID()).build();

        addOns.attach(List.of(category, other));

        AddOnDTO p = category.getAddOns().get(0);
        assertEquals(50L, p.getSold());
        assertEquals(0L, p.getRemaining());
        assertTrue(p.isSoldOut());
        AddOnDTO m = category.getAddOns().get(1);
        assertEquals(30L, m.getSold());
        assertNull(m.getRemaining(), "no limit, no remaining");
        assertFalse(m.isSoldOut());
        assertEquals(List.of(), other.getAddOns());

        when(bookings.fetchAddOnSoldCounts(anyCollection())).thenReturn(Optional.empty());
        addOns.attach(category);
        assertNull(category.getAddOns().get(0).getSold());
        assertNull(category.getAddOns().get(0).getRemaining());
        assertFalse(category.getAddOns().get(0).isSoldOut(), "an unknown count is never sold out");
    }

    @Test
    void aLimitLoweredBelowWhatSold_showsNoneRemaining_neverNegative() {
        SeatCategoryAddOn parking = stored(UUID.randomUUID(), "Parking pass", 10);
        assertEquals(0L, CategoryAddOns.toDto(parking, 12L).getRemaining());
    }

    @Test
    void categoriesWithoutAddOns_askBookingServiceNothing() {
        when(repo.findByCategoryIdInOrderByCategoryIdAscPositionAsc(any())).thenReturn(List.of());
        CreateCategoryResponseDTO category = CreateCategoryResponseDTO.builder().seatCategoryId(CATEGORY).build();

        addOns.attach(category);

        assertEquals(List.of(), category.getAddOns());
        verifyNoInteractions(bookings);
    }
}
