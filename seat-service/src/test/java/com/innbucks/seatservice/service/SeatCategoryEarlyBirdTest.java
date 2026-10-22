package com.innbucks.seatservice.service;

import com.innbucks.seatservice.cache.ReadCacheConfig;
import com.innbucks.seatservice.cache.ReadCacheProperties;
import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CategoryCapacityDTO;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EarlyBirdRequestDTO;
import com.innbucks.seatservice.dto.EventLookupDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Early-bird prices on a seat category: stored as a UTC end, charged until it,
 * worked out per request so neither a cached layout nor a missed job can keep
 * the lower price running, and edited only by the event's owner.
 */
class SeatCategoryEarlyBirdTest {

    /** 1 September 2026, 10:00 in Harare. */
    private static final Instant START = Instant.parse("2026-09-01T08:00:00Z");
    /** "2026-09-08T23:59:59" typed in Harare. */
    private static final LocalDateTime ENDS_UTC = LocalDateTime.of(2026, 9, 8, 21, 59, 59);

    private final MutableClock clock = new MutableClock(START);
    private final UUID eventId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private SeatCategoryRepository catRepo;
    private SeatRepository seatRepo;
    private BookingServiceClient bookings;
    private EventServiceClient events;
    private ObjectProvider<EventServiceClient> provider;
    private SeatCategory vip;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catRepo = mock(SeatCategoryRepository.class);
        seatRepo = mock(SeatRepository.class);
        bookings = mock(BookingServiceClient.class);
        events = mock(EventServiceClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        when(events.fetchEvent(any(), any())).thenReturn(Optional.of(
                EventLookupDTO.builder().totalCapacity(1_000).tenantUserUuid(owner).build()));
        when(bookings.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.of(Map.of()));

        vip = SeatCategory.builder()
                .id(UUID.randomUUID()).eventId(eventId).name("VIP").description("Front rows")
                .price(new BigDecimal("150.00")).totalSeats(50).availableSeats(50).deleted(false)
                .build();
        when(catRepo.findById(vip.getId())).thenReturn(Optional.of(vip));
        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(vip));
    }

    private SeatCategoryService service() {
        return withClock(new SeatCategoryService(catRepo, seatRepo, bookings, provider));
    }

    private SeatCategoryService cachedService() {
        return withClock(new SeatCategoryService(catRepo, seatRepo, bookings, provider,
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties())));
    }

    private SeatCategoryService withClock(SeatCategoryService service) {
        service.setClock(clock);
        return service;
    }

    private void withEarlyBird() {
        vip.setEarlyBirdPrice(new BigDecimal("100.00"));
        vip.setEarlyBirdEndsAt(ENDS_UTC);
    }

    private static EarlyBirdRequestDTO earlyBird(String price, String endsAt) {
        EarlyBirdRequestDTO request = new EarlyBirdRequestDTO();
        request.setPrice(new BigDecimal(price));
        request.setEndsAt(endsAt);
        return request;
    }

    private CreateCategoryRequestDTO createRequest(EarlyBirdRequestDTO earlyBird) {
        SectionSeatConfigDTO section = new SectionSeatConfigDTO();
        section.setSection("A");
        section.setSeatCount(10);
        CreateCategoryRequestDTO request = new CreateCategoryRequestDTO();
        request.setEventId(eventId);
        request.setName("Gold");
        request.setPrice(new BigDecimal("150.00"));
        request.setSections(List.of(section));
        request.setEarlyBird(earlyBird);
        return request;
    }

    private static UpdateCategoryRequestDTO update(String price) {
        UpdateCategoryRequestDTO request = new UpdateCategoryRequestDTO();
        request.setName("VIP");
        request.setDescription("Front rows");
        request.setPrice(new BigDecimal(price));
        return request;
    }

    // --- create ---

    @Test
    void create_withAnEarlyBird_storesItsEndInUtc_andShowsItAsTheCurrentPrice() {
        CreateCategoryResponseDTO created = service().createCategory(
                createRequest(earlyBird("100.00", "2026-09-08T23:59:59")));

        verify(catRepo).save(argThat(saved ->
                new BigDecimal("100.00").equals(saved.getEarlyBirdPrice())
                        && ENDS_UTC.equals(saved.getEarlyBirdEndsAt())
                        && new BigDecimal("150.00").equals(saved.getPrice())));
        assertEquals(new BigDecimal("150.00"), created.getPrice());
        assertEquals(new BigDecimal("100.00"), created.getCurrentPrice());
        assertTrue(created.getEarlyBird().isActive());
        assertEquals(ENDS_UTC, created.getEarlyBird().getEndsAt());
    }

    @Test
    void create_withoutAnEarlyBird_chargesThePrice() {
        CreateCategoryResponseDTO created = service().createCategory(createRequest(null));

        assertEquals(new BigDecimal("150.00"), created.getCurrentPrice());
        assertNull(created.getEarlyBird());
    }

    @Test
    void create_refusesAnEarlyBirdNotBelowThePrice_andWritesNothing() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service().createCategory(
                createRequest(earlyBird("150.00", "2026-09-08T23:59:59"))));

        assertEquals("The early-bird price must be lower than the ticket price (150.00).", ex.getMessage());
        verify(catRepo, never()).save(any());
        verify(seatRepo, never()).saveAll(any());
    }

    @Test
    void create_refusesAnEndThatHasPassed() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service().createCategory(
                createRequest(earlyBird("100.00", "2026-09-01T09:59:59"))));

        assertEquals("The early-bird end must be in the future.", ex.getMessage());
        verify(catRepo, never()).save(any());
    }

    @Test
    void create_refusesAnEndItCannotRead() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service().createCategory(
                createRequest(earlyBird("100.00", "8 Sept"))));

        assertEquals("Early-bird end must be a date and time like 2026-09-08T23:59:59.", ex.getMessage());
    }

    @Test
    void create_refusesMoreThanTwoDecimals() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service().createCategory(
                createRequest(earlyBird("99.999", "2026-09-08T23:59:59"))));

        assertEquals("Early-bird price can have at most 2 decimal places.", ex.getMessage());
    }

    // --- what a booking is charged ---

    @Test
    void theBookingRead_chargesTheEarlyBirdUntilItsEnd_thenThePrice() {
        withEarlyBird();
        SeatCategoryService service = service();

        CategoryCapacityDTO during = service.getCategoryCapacity(vip.getId());
        clock.set(Instant.parse("2026-09-08T21:59:59Z"));
        CategoryCapacityDTO after = service.getCategoryCapacity(vip.getId());

        assertEquals(new BigDecimal("100.00"), during.getCurrentPrice());
        assertEquals(new BigDecimal("150.00"), after.getCurrentPrice());
        assertEquals(new BigDecimal("150.00"), during.getPrice());
        assertTrue(during.getEarlyBird().isActive());
        assertFalse(after.getEarlyBird().isActive());
    }

    @Test
    void aCachedLayout_stillEndsTheEarlyBirdOnTime() {
        withEarlyBird();
        SeatCategoryService service = cachedService();

        BigDecimal during = service.getCategoriesByEvent(eventId).get(0).getCurrentPrice();
        clock.set(Instant.parse("2026-09-09T06:00:00Z"));
        CreateCategoryResponseDTO after = service.getCategoriesByEvent(eventId).get(0);

        verify(catRepo, times(1)).findByEventIdAndDeletedFalse(eventId);
        assertEquals(new BigDecimal("100.00"), during);
        assertEquals(new BigDecimal("150.00"), after.getCurrentPrice());
        assertFalse(after.getEarlyBird().isActive());
    }

    // --- set and remove ---

    @Test
    void theOwner_setsAnEarlyBird_andTheListingShowsItAtOnce() {
        SeatCategoryService service = cachedService();
        assertEquals(new BigDecimal("150.00"), service.getCategoriesByEvent(eventId).get(0).getCurrentPrice());

        CreateCategoryResponseDTO set = service.setEarlyBird(vip.getId(),
                earlyBird("100.00", "2026-09-08T23:59:59"), owner, "organizer@example.com", false, "Bearer t");

        assertEquals(new BigDecimal("100.00"), set.getCurrentPrice());
        assertEquals(ENDS_UTC, vip.getEarlyBirdEndsAt());
        verify(catRepo).save(vip);
        assertEquals(new BigDecimal("100.00"), service.getCategoriesByEvent(eventId).get(0).getCurrentPrice());
        verify(catRepo, times(2)).findByEventIdAndDeletedFalse(eventId);
    }

    @Test
    void anotherOrganizer_cannotSetAnEarlyBird() {
        assertThrows(AccessDeniedException.class, () -> service().setEarlyBird(vip.getId(),
                earlyBird("100.00", "2026-09-08T23:59:59"), UUID.randomUUID(), "other@example.com", false,
                "Bearer t"));

        assertNull(vip.getEarlyBirdPrice());
        verify(catRepo, never()).save(any());
    }

    @Test
    void setEarlyBird_isCheckedAgainstTheStoredPrice() {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> service().setEarlyBird(
                vip.getId(), earlyBird("160.00", "2026-09-08T23:59:59"), null, "admin@example.com", true, null));

        assertEquals("The early-bird price must be lower than the ticket price (150.00).", ex.getMessage());
        assertNull(vip.getEarlyBirdPrice());
    }

    @Test
    void setEarlyBird_replacesARunningOne() {
        withEarlyBird();

        CreateCategoryResponseDTO set = service().setEarlyBird(vip.getId(),
                earlyBird("110.00", "2026-09-15T23:59:59"), null, "admin@example.com", true, null);

        assertEquals(new BigDecimal("110.00"), set.getCurrentPrice());
        assertEquals(LocalDateTime.of(2026, 9, 15, 21, 59, 59), vip.getEarlyBirdEndsAt());
    }

    @Test
    void removeEarlyBird_returnsToThePrice_andCanBeRepeated() {
        withEarlyBird();
        SeatCategoryService service = service();

        CreateCategoryResponseDTO removed = service.removeEarlyBird(vip.getId(), owner, "organizer@example.com",
                false, "Bearer t");
        CreateCategoryResponseDTO again = service.removeEarlyBird(vip.getId(), owner, "organizer@example.com",
                false, "Bearer t");

        assertNull(vip.getEarlyBirdPrice());
        assertNull(vip.getEarlyBirdEndsAt());
        assertEquals(new BigDecimal("150.00"), removed.getCurrentPrice());
        assertNull(removed.getEarlyBird());
        assertEquals(new BigDecimal("150.00"), again.getCurrentPrice());
    }

    // --- editing the price around an early bird ---

    @Test
    void whileAnEarlyBirdRuns_thePriceMustStayAboveIt() {
        withEarlyBird();
        SeatCategoryService service = service();

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.updateCategory(vip.getId(), update("100.00")));
        assertThrows(BadRequestException.class, () -> service.updateCategory(vip.getId(), update("90.00")));
        assertEquals("The ticket price must be higher than the early-bird price (100.00) while the early bird "
                + "runs. Lower or remove the early bird first.", ex.getMessage());
        assertEquals(new BigDecimal("150.00"), vip.getPrice());

        CreateCategoryResponseDTO raised = service.updateCategory(vip.getId(), update("160.00"));
        assertEquals(new BigDecimal("160.00"), raised.getPrice());
        assertEquals(new BigDecimal("100.00"), raised.getCurrentPrice());
    }

    @Test
    void afterTheEarlyBirdEnded_anyPriceIsAllowed() {
        withEarlyBird();
        clock.set(Instant.parse("2026-09-10T08:00:00Z"));

        CreateCategoryResponseDTO updated = service().updateCategory(vip.getId(), update("80.00"));

        assertEquals(new BigDecimal("80.00"), updated.getCurrentPrice());
        assertFalse(updated.getEarlyBird().isActive());
    }

    /** A clock a test can move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
