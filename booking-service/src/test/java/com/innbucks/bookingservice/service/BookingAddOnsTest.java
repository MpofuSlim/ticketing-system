package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingAddOnDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.testsupport.OnSaleEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Add-ons bought with a ticket (V33): one of each per ticket, the category's
 * own only, a limit counted under the stock lock, and the total that includes
 * them while each ticket's price stays its own.
 */
class BookingAddOnsTest {

    private static final UUID VIP = UUID.randomUUID();
    private static final UUID GA = UUID.randomUUID();
    private static final UUID PARKING = UUID.randomUUID();
    private static final UUID MEAL = UUID.randomUUID();
    private static final UUID GA_ONLY = UUID.randomUUID();

    private final UUID eventId = UUID.randomUUID();
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final SeatServiceClient seats = mock(SeatServiceClient.class);

    private CategoryLookupDTO category(UUID id, String name, String price, CategoryLookupDTO.AddOn... addOns) {
        return CategoryLookupDTO.builder().seatCategoryId(id).eventId(eventId).name(name)
                .price(new BigDecimal(price)).totalSeats(100).availableSeats(100)
                .addOns(List.of(addOns)).build();
    }

    private static CategoryLookupDTO.AddOn addOn(UUID id, String name, String price, Integer max) {
        return CategoryLookupDTO.AddOn.builder().id(id).name(name).price(new BigDecimal(price)).maxQuantity(max).build();
    }

    private Map<UUID, CategoryLookupDTO> categories() {
        return Map.of(
                VIP, category(VIP, "VIP", "150.00", addOn(PARKING, "Parking pass", "5.00", 2), addOn(MEAL, "Meal voucher", "10.00", null)),
                GA, category(GA, "GA", "20.00", addOn(GA_ONLY, "Wristband", "1.00", null)));
    }

    private static CreateBookingRequestDTO.SeatItemRequest seat(UUID category, UUID... addOns) {
        CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
        s.setCategoryId(category);
        s.setAddOnIds(addOns.length == 0 ? null : new ArrayList<>(List.of(addOns)));
        return s;
    }

    // ---- the rules, pure ------------------------------------------------------------

    @Test
    void resolve_keepsEachTicketsOwnCategorysAddOns_asListed() {
        List<List<BookingAddOnDTO>> resolved = BookingAddOns.resolve(
                List.of(seat(VIP, MEAL, PARKING), seat(GA), seat(GA, GA_ONLY)), categories());

        assertEquals(List.of("Meal voucher", "Parking pass"), resolved.get(0).stream().map(BookingAddOnDTO::getName).toList());
        assertEquals(new BigDecimal("10.00"), resolved.get(0).get(0).getPrice());
        assertEquals(List.of(), resolved.get(1));
        assertEquals(GA_ONLY, resolved.get(2).get(0).getAddOnId());
        assertEquals(new BigDecimal("16.00"), BookingAddOns.total(resolved));
        assertEquals(Map.of(MEAL, 1, PARKING, 1, GA_ONLY, 1), BookingAddOns.requested(resolved));
    }

    @Test
    void resolve_namesEveryWrongOrRepeatedChoice_atOnce() {
        UUID removed = UUID.randomUUID();
        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> BookingAddOns.resolve(
                List.of(seat(VIP, PARKING, PARKING), seat(GA, PARKING), seat(VIP, removed)), categories()));

        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatus());
        assertEquals("ADD_ONS_INVALID", refused.getErrorCode());
        assertEquals("Please check the add-ons you chose.", refused.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, String> problems = (Map<String, String>) refused.getExtra().get("addOns");
        assertEquals("Chosen more than once.", problems.get("seats[0]." + PARKING));
        assertEquals("This add-on isn't sold with this ticket.", problems.get("seats[1]." + PARKING));
        assertEquals("This add-on isn't sold with this ticket.", problems.get("seats[2]." + removed));
        assertEquals(3, problems.size());
    }

    @Test
    void requireRoom_refusesPastTheLimit_withWhatIsLeft() {
        Map<UUID, CategoryLookupDTO.AddOn> terms = Map.of(PARKING, addOn(PARKING, "Parking pass", "5.00", 2),
                MEAL, addOn(MEAL, "Meal voucher", "10.00", null));

        BookingAddOns.requireRoom(Map.of(PARKING, 1, MEAL, 500), terms, Map.of(PARKING, 1L));

        ApiProblemException fewer = assertThrows(ApiProblemException.class,
                () -> BookingAddOns.requireRoom(Map.of(PARKING, 2), terms, Map.of(PARKING, 1L)));
        assertEquals(HttpStatus.CONFLICT, fewer.getStatus());
        assertEquals("ADD_ON_SOLD_OUT", fewer.getErrorCode());
        assertEquals("Only 1 \"Parking pass\" left. Please choose fewer.", fewer.getMessage());
        assertEquals(PARKING, fewer.getExtra().get("addOnId"));
        assertEquals(1L, fewer.getExtra().get("remaining"));

        ApiProblemException gone = assertThrows(ApiProblemException.class,
                () -> BookingAddOns.requireRoom(Map.of(PARKING, 1), terms, Map.of(PARKING, 3L)));
        assertEquals("\"Parking pass\" is sold out.", gone.getMessage());
        assertEquals(0L, gone.getExtra().get("remaining"), "a limit lowered below what sold reads as none left");
    }

    @Test
    void theReceiptLine_countsEachAddOnAtItsPrice() {
        BookingItem one = BookingItem.builder().addOns(List.of(
                BookingAddOnDTO.builder().name("Parking pass").price(new BigDecimal("5.00")).build())).build();
        BookingItem two = BookingItem.builder().addOns(List.of(
                BookingAddOnDTO.builder().name("Parking pass").price(new BigDecimal("5.00")).build(),
                BookingAddOnDTO.builder().name("Meal voucher").price(new BigDecimal("10.00")).build())).build();

        assertEquals("2 x Parking pass at $5.00, 1 x Meal voucher at $10.00",
                TicketDeliveryService.addOnsBreakdown(List.of(one, two), "USD"));
        assertNull(TicketDeliveryService.addOnsBreakdown(List.of(BookingItem.builder().build()), "USD"));
        assertEquals("Parking pass, Meal voucher", two.addOnsLabel());
        assertNull(BookingItem.builder().build().addOnsLabel());
    }

    // ---- through createBooking --------------------------------------------------------

    private BookingService service() {
        CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
        when(inventory.seedIfAbsent(any(), anyInt())).thenReturn(1);
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);
        when(seats.getCategory(VIP)).thenReturn(ApiResult.ok("ok", categories().get(VIP)));
        when(seats.getCategory(GA)).thenReturn(ApiResult.ok("ok", categories().get(GA)));
        return new BookingService(bookingRepo, itemRepo, inventory, seats, mock(ApplicationEventPublisher.class),
                new QrCodeGenerator(), OnSaleEvents.provider(), mock(PlatformTransactionManager.class));
    }

    private CreateBookingRequestDTO request(CreateBookingRequestDTO.SeatItemRequest... tickets) {
        CreateBookingRequestDTO r = new CreateBookingRequestDTO();
        r.setEventId(eventId);
        r.setCustomerName("Rufaro Moyo");
        r.setSeats(List.of(tickets));
        return r;
    }

    private static BookingItemRepository.CategoryAddOnCount sold(UUID category, UUID addOn, long count) {
        return new BookingItemRepository.CategoryAddOnCount() {
            public UUID getCategoryId() { return category; }
            public String getAddOnId() { return addOn.toString(); }
            public Long getCount() { return count; }
        };
    }

    @Test
    void aBooking_addsTheAddOnsToTheTotal_keepsEachTicketsPrice_andStoresThemOnTheTicket() {
        when(itemRepo.countActiveAddOnsByCategories(anyCollection())).thenReturn(List.of(sold(VIP, PARKING, 1L)));

        BookingResponseDTO created = service().createBooking(null, "+263771234567",
                request(seat(VIP, PARKING, MEAL), seat(GA)));

        assertEquals(new BigDecimal("185.00"), created.getTotalAmount(), "150 + 20 tickets, 15 add-ons");
        assertEquals(new BigDecimal("15.00"), created.getAddOnsAmount());
        assertEquals(new BigDecimal("150.00"), created.getItems().get(0).getPriceAtBooking());
        assertEquals(List.of("Parking pass", "Meal voucher"),
                created.getItems().get(0).getAddOns().stream().map(BookingAddOnDTO::getName).toList());
        assertEquals(List.of(), created.getItems().get(1).getAddOns());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAllAndFlush(items.capture());
        assertEquals(2, items.getValue().get(0).getAddOns().size());
        ArgumentCaptor<Booking> booking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(booking.capture());
        assertEquals(new BigDecimal("15.00"), booking.getValue().getAddOnsAmount());
    }

    @Test
    void anAddOnPastItsLimit_isA409_andNothingIsSaved() {
        when(itemRepo.countActiveAddOnsByCategories(anyCollection())).thenReturn(List.of(sold(VIP, PARKING, 2L)));

        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> service().createBooking(
                null, "+263771234567", request(seat(VIP, PARKING))));

        assertEquals("ADD_ON_SOLD_OUT", refused.getErrorCode());
        assertEquals("\"Parking pass\" is sold out.", refused.getMessage());
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAllAndFlush(anyList());
    }

    @Test
    void addOnsWithoutALimit_neverAskForACount_andABookingWithoutAddOnsHasNoAmount() {
        BookingResponseDTO withMeal = service().createBooking(null, "+263771234567", request(seat(VIP, MEAL)));
        BookingResponseDTO plain = service().createBooking(null, "+263771234567", request(seat(GA)));

        verify(itemRepo, never()).countActiveAddOnsByCategories(anyCollection());
        assertEquals(new BigDecimal("160.00"), withMeal.getTotalAmount());
        assertNull(plain.getAddOnsAmount());
        assertEquals(new BigDecimal("20.00"), plain.getTotalAmount());
    }

    @Test
    void aWrongAddOn_isRefusedBeforeAnythingIsHeld() {
        BookingService service = service();
        assertEquals("ADD_ONS_INVALID", assertThrows(ApiProblemException.class, () -> service.createBooking(
                null, "+263771234567", request(seat(GA, PARKING)))).getErrorCode());
        verify(bookingRepo, never()).save(any());
    }
}
