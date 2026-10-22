package com.innbucks.seatservice.service;

import com.innbucks.seatservice.cache.ReadCacheConfig;
import com.innbucks.seatservice.cache.ReadCacheProperties;
import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CategoryCapacityDTO;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.PriceChangeDTO;
import com.innbucks.seatservice.dto.EventLookupDTO;
import com.innbucks.seatservice.dto.PriceTierDTO;
import com.innbucks.seatservice.dto.PriceTierRequestDTO;
import com.innbucks.seatservice.dto.PriceTiersRequestDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.entity.SeatCategoryPriceChange;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.repository.SeatCategoryPriceChangeRepository;
import com.innbucks.seatservice.repository.SeatCategoryPriceTierRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Price tiers on a seat category: stored in order with UTC ends, validated
 * before anything is written, priced per request from live tier counts, kept
 * by id across a replacement so sold tickets still count, and edited only by
 * the event's owner.
 */
class SeatCategoryPriceTierTest {

    /** 1 September 2026, 10:00 in Harare. */
    private static final Instant START = Instant.parse("2026-09-01T08:00:00Z");
    /** "2026-09-08T23:59:59" typed in Harare. */
    private static final LocalDateTime EARLY_ENDS_UTC = LocalDateTime.of(2026, 9, 8, 21, 59, 59);
    private static final LocalDateTime PHASE_ENDS_UTC = LocalDateTime.of(2026, 9, 20, 21, 59, 59);

    private final MutableClock clock = new MutableClock(START);
    private final UUID eventId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final List<SeatCategoryPriceTier> store = new ArrayList<>();
    /** What the service recorded as price changes (V10). */
    private final List<SeatCategoryPriceChange> changes = new ArrayList<>();

    private SeatCategoryRepository catRepo;
    private SeatCategoryPriceTierRepository tierRepo;
    private SeatRepository seatRepo;
    private BookingServiceClient bookings;
    private ObjectProvider<EventServiceClient> provider;
    private SeatCategory vip;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catRepo = mock(SeatCategoryRepository.class);
        tierRepo = fakeTierRepository();
        seatRepo = mock(SeatRepository.class);
        bookings = mock(BookingServiceClient.class);
        EventServiceClient events = mock(EventServiceClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        when(events.fetchEvent(any(), any())).thenReturn(Optional.of(
                EventLookupDTO.builder().totalCapacity(1_000).tenantUserUuid(owner).build()));
        when(bookings.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.of(Map.of()));
        when(bookings.fetchTierSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of()));

        vip = SeatCategory.builder()
                .id(UUID.randomUUID()).eventId(eventId).name("VIP").description("Front rows")
                .price(new BigDecimal("150.00")).totalSeats(50).availableSeats(50).deleted(false)
                .build();
        when(catRepo.findById(vip.getId())).thenReturn(Optional.of(vip));
        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(vip));
        when(catRepo.save(any())).thenAnswer(inv -> {
            SeatCategory saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    /** An in-memory tier table: rows read back are the stored objects, like managed entities. */
    @SuppressWarnings("unchecked")
    private SeatCategoryPriceTierRepository fakeTierRepository() {
        SeatCategoryPriceTierRepository repo = mock(SeatCategoryPriceTierRepository.class);
        Comparator<SeatCategoryPriceTier> order = Comparator.comparingInt(SeatCategoryPriceTier::getPosition);
        when(repo.findByCategoryIdOrderByPositionAsc(any())).thenAnswer(inv -> store.stream()
                .filter(t -> t.getCategoryId().equals(inv.getArgument(0))).sorted(order).toList());
        when(repo.findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return store.stream().filter(t -> ids.contains(t.getCategoryId())).sorted(order).toList();
        });
        when(repo.saveAll(any())).thenAnswer(inv -> {
            List<SeatCategoryPriceTier> rows = new ArrayList<>();
            for (SeatCategoryPriceTier row : (Iterable<SeatCategoryPriceTier>) inv.getArgument(0)) {
                if (row.getId() == null) {
                    row.setId(UUID.randomUUID());
                }
                if (!store.contains(row)) {
                    store.add(row);
                }
                rows.add(row);
            }
            return rows;
        });
        doAnswer(inv -> {
            for (SeatCategoryPriceTier row : (Iterable<SeatCategoryPriceTier>) inv.getArgument(0)) {
                store.remove(row);
            }
            return null;
        }).when(repo).deleteAll(any());
        return repo;
    }

    private SeatCategoryService service() {
        return withClock(new SeatCategoryService(catRepo, tierRepo, seatRepo, bookings, provider, null));
    }

    private SeatCategoryService cachedService() {
        return withClock(new SeatCategoryService(catRepo, tierRepo, seatRepo, bookings, provider,
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties())));
    }

    private SeatCategoryService withClock(SeatCategoryService service) {
        service.setClock(clock);
        SeatCategoryPriceChangeRepository changeRepo = mock(SeatCategoryPriceChangeRepository.class);
        when(changeRepo.save(any())).thenAnswer(inv -> {
            changes.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(changeRepo.findTop200ByCategoryIdOrderByChangedAtDesc(any())).thenAnswer(inv -> {
            List<SeatCategoryPriceChange> newestFirst = new ArrayList<>(changes);
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });
        service.setPriceChangeRepository(changeRepo);
        return service;
    }

    private SeatCategoryPriceTier storeTier(int position, String name, String price, LocalDateTime endsAt,
                                            Integer quantity) {
        SeatCategoryPriceTier tier = SeatCategoryPriceTier.builder()
                .id(UUID.randomUUID()).categoryId(vip.getId()).position(position).name(name)
                .price(new BigDecimal(price)).endsAt(endsAt).quantity(quantity).build();
        store.add(tier);
        return tier;
    }

    /** Early bird 100.00 for 100 tickets or until 8 Sept; Phase 1 120.00 until 20 Sept. */
    private List<SeatCategoryPriceTier> storeStandardTiers() {
        return List.of(storeTier(0, "Early bird", "100.00", EARLY_ENDS_UTC, 100),
                storeTier(1, "Phase 1", "120.00", PHASE_ENDS_UTC, null));
    }

    private static PriceTierRequestDTO tier(UUID id, String name, String price, String endsAt, Integer quantity) {
        PriceTierRequestDTO tier = new PriceTierRequestDTO();
        tier.setId(id);
        tier.setName(name);
        tier.setPrice(new BigDecimal(price));
        tier.setEndsAt(endsAt);
        tier.setQuantity(quantity);
        return tier;
    }

    private static PriceTiersRequestDTO tiers(PriceTierRequestDTO... tiers) {
        PriceTiersRequestDTO request = new PriceTiersRequestDTO();
        request.setTiers(List.of(tiers));
        return request;
    }

    private CreateCategoryRequestDTO createRequest(PriceTierRequestDTO... tiers) {
        SectionSeatConfigDTO section = new SectionSeatConfigDTO();
        section.setSection("A");
        section.setSeatCount(10);
        CreateCategoryRequestDTO request = new CreateCategoryRequestDTO();
        request.setEventId(eventId);
        request.setName("Gold");
        request.setPrice(new BigDecimal("150.00"));
        request.setSections(List.of(section));
        request.setPriceTiers(List.of(tiers));
        return request;
    }

    private static UpdateCategoryRequestDTO update(String price) {
        UpdateCategoryRequestDTO request = new UpdateCategoryRequestDTO();
        request.setName("VIP");
        request.setDescription("Front rows");
        request.setPrice(new BigDecimal(price));
        return request;
    }

    private static List<PriceTierDTO.Status> statuses(CreateCategoryResponseDTO category) {
        return category.getPriceTiers().stream().map(PriceTierDTO::getStatus).toList();
    }

    private void assertRefused(String message, Runnable call) {
        BadRequestException ex = assertThrows(BadRequestException.class, call::run);
        assertEquals(message, ex.getMessage());
    }

    // --- create ---

    @Test
    void create_storesTheTiersInOrder_withUtcEnds_andPricesTheFirst() {
        CreateCategoryResponseDTO created = service().createCategory(createRequest(
                tier(null, "Early bird", "100.00", "2026-09-08T23:59:59", 100),
                tier(null, "Phase 1", "120.00", "2026-09-20T23:59:59", null)));

        assertEquals(2, store.size());
        SeatCategoryPriceTier early = store.stream().filter(t -> t.getPosition() == 0).findFirst().orElseThrow();
        assertEquals("Early bird", early.getName());
        assertEquals(EARLY_ENDS_UTC, early.getEndsAt());
        assertEquals(100, early.getQuantity());
        assertEquals(new BigDecimal("150.00"), created.getPrice());
        assertEquals(new BigDecimal("100.00"), created.getCurrentPrice());
        assertEquals("Early bird", created.getCurrentTierName());
        assertEquals(List.of(PriceTierDTO.Status.ACTIVE, PriceTierDTO.Status.UPCOMING), statuses(created));
        assertEquals(0L, created.getPriceTiers().get(0).getSold());
        verify(bookings, never()).fetchTierSoldCounts(anyCollection());
    }

    @Test
    void create_refusesEveryBadTier_beforeWritingAnything() {
        SeatCategoryService service = service();
        assertRefused("The price of \"Early bird\" must be lower than the ticket price (150.00).",
                () -> service.createCategory(createRequest(tier(null, "Early bird", "150.00", null, 100))));
        assertRefused("\"Early bird\" needs an end date, a number of tickets, or both.",
                () -> service.createCategory(createRequest(tier(null, "Early bird", "100.00", null, null))));
        assertRefused("The end of \"Early bird\" must be in the future.",
                () -> service.createCategory(createRequest(
                        tier(null, "Early bird", "100.00", "2026-09-01T09:59:59", null))));
        assertRefused("The end of \"Early bird\" must be a date and time like 2026-09-08T23:59:59.",
                () -> service.createCategory(createRequest(tier(null, "Early bird", "100.00", "8 Sept", null))));
        assertRefused("Two price tiers are named \"early BIRD\". Give each tier its own name.",
                () -> service.createCategory(createRequest(
                        tier(null, "Early bird", "100.00", null, 10), tier(null, "early BIRD", "110.00", null, 10))));
        assertRefused("The price of \"Early bird\" can have at most 2 decimal places.",
                () -> service.createCategory(createRequest(tier(null, "Early bird", "99.999", null, 10))));
        assertRefused("The number of tickets for \"Early bird\" must be at least 1.",
                () -> service.createCategory(createRequest(tier(null, "Early bird", "100.00", null, 0))));

        verify(catRepo, never()).save(any());
        verify(seatRepo, never()).saveAll(any());
        assertTrue(store.isEmpty());
    }

    @Test
    void create_storesAStartACapAndTheRefundRule() {
        PriceTierRequestDTO flash = tier(null, "Flash sale", "90.00", "2026-09-06T23:59:59", 50);
        flash.setStartsAt("2026-09-05T18:00:00");
        flash.setMaxPerCustomer(2);
        flash.setRefundsReopen(false);

        CreateCategoryResponseDTO created = service().createCategory(createRequest(flash));

        SeatCategoryPriceTier stored = store.get(0);
        assertEquals(LocalDateTime.of(2026, 9, 5, 16, 0), stored.getStartsAt());
        assertEquals(2, stored.getMaxPerCustomer());
        assertFalse(stored.isRefundsReopen());
        assertEquals(new BigDecimal("150.00"), created.getCurrentPrice());
        assertEquals(List.of(PriceTierDTO.Status.UPCOMING), statuses(created));
    }

    @Test
    void create_refusesABadStartOrCap() {
        SeatCategoryService service = service();
        PriceTierRequestDTO unreadable = tier(null, "Flash sale", "90.00", null, 50);
        unreadable.setStartsAt("Friday evening");
        assertRefused("The start of \"Flash sale\" must be a date and time like 2026-09-05T18:00:00.",
                () -> service.createCategory(createRequest(unreadable)));
        PriceTierRequestDTO backwards = tier(null, "Flash sale", "90.00", "2026-09-05T18:00:00", null);
        backwards.setStartsAt("2026-09-05T18:00:00");
        assertRefused("The start of \"Flash sale\" must be before its end.",
                () -> service.createCategory(createRequest(backwards)));
        PriceTierRequestDTO noCap = tier(null, "Flash sale", "90.00", null, 50);
        noCap.setMaxPerCustomer(0);
        assertRefused("The limit per customer for \"Flash sale\" must be at least 1.",
                () -> service.createCategory(createRequest(noCap)));

        verify(catRepo, never()).save(any());
        assertTrue(store.isEmpty());
    }

    // --- pricing on the listing ---

    @Test
    void refundedTickets_keepAPlace_whenTheTierDoesNotReopen() {
        storeStandardTiers();
        SeatCategoryPriceTier early = store.get(0);
        early.setRefundsReopen(false);
        when(bookings.fetchTierSoldCounts(anyCollection()))
                .thenReturn(Optional.of(Map.of(vip.getId(), Map.of(early.getId(), new CategoryPricing.Sold(98, 2)))));

        CreateCategoryResponseDTO listed = service().getCategoriesByEvent(eventId).get(0);

        assertEquals(100L, listed.getPriceTiers().get(0).getSold());
        assertEquals(new BigDecimal("120.00"), listed.getCurrentPrice());
    }

    @Test
    void aTierSoldToItsQuantity_handsOverToTheNext() {
        storeStandardTiers();
        UUID earlyId = store.get(0).getId();
        when(bookings.fetchTierSoldCounts(anyCollection()))
                .thenReturn(Optional.of(Map.of(vip.getId(), Map.of(earlyId, new CategoryPricing.Sold(100, 0)))));

        CreateCategoryResponseDTO listed = service().getCategoriesByEvent(eventId).get(0);

        assertEquals(new BigDecimal("120.00"), listed.getCurrentPrice());
        assertEquals("Phase 1", listed.getCurrentTierName());
        assertEquals(List.of(PriceTierDTO.Status.SOLD_OUT, PriceTierDTO.Status.ACTIVE), statuses(listed));
        assertEquals(0L, listed.getPriceTiers().get(0).getRemaining());
    }

    @Test
    void whenTierCountsCannotBeAsked_theTiersShowWithoutCounts() {
        storeStandardTiers();
        when(bookings.fetchTierSoldCounts(anyCollection())).thenReturn(Optional.empty());

        CreateCategoryResponseDTO listed = service().getCategoriesByEvent(eventId).get(0);

        assertEquals(new BigDecimal("100.00"), listed.getCurrentPrice());
        assertNull(listed.getPriceTiers().get(0).getSold());
    }

    @Test
    void aCategoryWithoutTiers_neverAsksForTierCounts() {
        CreateCategoryResponseDTO listed = service().getCategoriesByEvent(eventId).get(0);

        assertEquals(new BigDecimal("150.00"), listed.getCurrentPrice());
        assertTrue(listed.getPriceTiers().isEmpty());
        verify(bookings, never()).fetchTierSoldCounts(anyCollection());
    }

    @Test
    void aCachedLayout_stillClosesATierOnTime() {
        storeStandardTiers();
        SeatCategoryService service = cachedService();

        BigDecimal during = service.getCategoriesByEvent(eventId).get(0).getCurrentPrice();
        clock.set(Instant.parse("2026-09-09T06:00:00Z"));
        CreateCategoryResponseDTO after = service.getCategoriesByEvent(eventId).get(0);

        verify(catRepo, times(1)).findByEventIdAndDeletedFalse(eventId);
        verify(tierRepo, times(1)).findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection());
        assertEquals(new BigDecimal("100.00"), during);
        assertEquals(new BigDecimal("120.00"), after.getCurrentPrice());
        assertEquals(PriceTierDTO.Status.ENDED, after.getPriceTiers().get(0).getStatus());
    }

    // --- replacing the list ---

    @Test
    void replacing_keepsSentIds_insertsNewTiers_andDeletesTheRest() {
        List<SeatCategoryPriceTier> stored = storeStandardTiers();
        UUID earlyId = stored.get(0).getId();
        UUID phaseId = stored.get(1).getId();

        CreateCategoryResponseDTO set = service().setPriceTiers(vip.getId(), tiers(
                        tier(earlyId, "Early bird", "100.00", "2026-09-08T23:59:59", 150),
                        tier(null, "Phase 2", "140.00", "2026-09-27T23:59:59", null)),
                owner, "organizer@example.com", false, "Bearer t");

        assertEquals(2, store.size());
        assertTrue(store.stream().noneMatch(t -> t.getId().equals(phaseId)));
        SeatCategoryPriceTier early = store.stream().filter(t -> t.getId().equals(earlyId)).findFirst().orElseThrow();
        assertEquals(150, early.getQuantity());
        assertEquals(0, early.getPosition());
        assertEquals(List.of("Early bird", "Phase 2"),
                set.getPriceTiers().stream().map(PriceTierDTO::getName).toList());
        verify(tierRepo).deleteAll(List.of(stored.get(1)));
    }

    @Test
    void replacing_refusesATierOfAnotherCategory() {
        storeStandardTiers();
        UUID foreign = UUID.randomUUID();

        assertRefused("Price tier " + foreign + " does not belong to this category.",
                () -> service().setPriceTiers(vip.getId(), tiers(tier(foreign, "Early bird", "100.00", null, 10)),
                        null, "admin@example.com", true, null));
        assertEquals(2, store.size());
    }

    @Test
    void anEndedTierSentBackUnchanged_isKept_butMovingItIntoThePastIsRefused() {
        List<SeatCategoryPriceTier> stored = storeStandardTiers();
        UUID earlyId = stored.get(0).getId();
        UUID phaseId = stored.get(1).getId();
        clock.set(Instant.parse("2026-09-10T08:00:00Z"));
        SeatCategoryService service = service();

        CreateCategoryResponseDTO kept = service.setPriceTiers(vip.getId(), tiers(
                        tier(earlyId, "Early bird", "100.00", "2026-09-08T23:59:59", 100),
                        tier(phaseId, "Phase 1", "125.00", "2026-09-20T23:59:59", null)),
                null, "admin@example.com", true, null);
        assertEquals(new BigDecimal("125.00"), kept.getCurrentPrice());

        assertRefused("The end of \"Early bird\" must be in the future.",
                () -> service.setPriceTiers(vip.getId(), tiers(
                                tier(earlyId, "Early bird", "100.00", "2026-09-09T23:59:59", 100)),
                        null, "admin@example.com", true, null));
    }

    @Test
    void anEmptyList_removesEveryTier() {
        storeStandardTiers();

        CreateCategoryResponseDTO set = service().setPriceTiers(vip.getId(), tiers(),
                owner, "organizer@example.com", false, "Bearer t");

        assertTrue(store.isEmpty());
        assertEquals(new BigDecimal("150.00"), set.getCurrentPrice());
        assertTrue(set.getPriceTiers().isEmpty());
    }

    @Test
    void anotherOrganizer_cannotSetTiers() {
        assertThrows(AccessDeniedException.class, () -> service().setPriceTiers(vip.getId(),
                tiers(tier(null, "Early bird", "100.00", null, 10)), UUID.randomUUID(), "other@example.com",
                false, "Bearer t"));

        assertTrue(store.isEmpty());
    }

    @Test
    void settingTiers_evictsTheCachedLayout() {
        SeatCategoryService service = cachedService();
        assertEquals(new BigDecimal("150.00"), service.getCategoriesByEvent(eventId).get(0).getCurrentPrice());

        service.setPriceTiers(vip.getId(), tiers(tier(null, "Early bird", "100.00", null, 10)),
                owner, "organizer@example.com", false, "Bearer t");

        assertEquals(new BigDecimal("100.00"), service.getCategoriesByEvent(eventId).get(0).getCurrentPrice());
        verify(catRepo, times(2)).findByEventIdAndDeletedFalse(eventId);
    }

    // --- the full price around open tiers ---

    @Test
    void thePriceMustStayAboveEveryTierThatCanStillApply() {
        storeStandardTiers();
        SeatCategoryService service = service();

        assertRefused("The ticket price must be higher than the price tier \"Phase 1\" (120.00). "
                + "Lower or remove that tier first.", () -> service.updateCategory(vip.getId(), update("110.00")));
        assertEquals(new BigDecimal("150.00"), vip.getPrice());

        assertEquals(new BigDecimal("160.00"), service.updateCategory(vip.getId(), update("160.00")).getPrice());
    }

    @Test
    void afterEveryTierHasEnded_anyPriceIsAllowed() {
        storeStandardTiers();
        clock.set(Instant.parse("2026-09-21T08:00:00Z"));

        CreateCategoryResponseDTO updated = service().updateCategory(vip.getId(), update("80.00"));

        assertEquals(new BigDecimal("80.00"), updated.getCurrentPrice());
        assertNull(updated.getCurrentTierName());
    }

    // --- the booking read ---

    @Test
    void theBookingRead_carriesTheTermsInOrder_withoutCounts() {
        storeStandardTiers();

        CategoryCapacityDTO capacity = service().getCategoryCapacity(vip.getId());

        assertEquals(List.of("Early bird", "Phase 1"),
                capacity.getPriceTiers().stream().map(PriceTierDTO::getName).toList());
        assertEquals(EARLY_ENDS_UTC, capacity.getPriceTiers().get(0).getEndsAt());
        assertEquals(100, capacity.getPriceTiers().get(0).getQuantity());
        assertNull(capacity.getPriceTiers().get(0).getSold());
        assertNull(capacity.getPriceTiers().get(0).getStatus());
        verify(bookings, never()).fetchTierSoldCounts(anyCollection());
    }

    // --- price history (V10) ---

    @Test
    void create_recordsThePriceAndTiersItWasCreatedWith() {
        service().createCategory(createRequest(tier(null, "Early bird", "100.00", "2026-09-08T23:59:59", 100)));

        assertEquals(1, changes.size());
        SeatCategoryPriceChange created = changes.get(0);
        assertEquals(SeatCategoryPriceChange.ChangeType.CREATED, created.getChangeType());
        assertEquals(new BigDecimal("150.00"), created.getNewPrice());
        assertNull(created.getTiersBefore());
        assertTrue(created.getTiersAfter().contains("\"name\":\"Early bird\""));
        assertTrue(created.getTiersAfter().contains("\"endsAt\":\"2026-09-08T21:59:59\""));
    }

    @Test
    void aPriceEdit_isRecorded_butARenameIsNot() {
        SeatCategoryService service = service();
        service.updateCategory(vip.getId(), update("150.00"));
        assertTrue(changes.isEmpty());

        service.updateCategory(vip.getId(), update("160.00"));

        assertEquals(1, changes.size());
        assertEquals(SeatCategoryPriceChange.ChangeType.PRICE_CHANGED, changes.get(0).getChangeType());
        assertEquals(new BigDecimal("150.00"), changes.get(0).getOldPrice());
        assertEquals(new BigDecimal("160.00"), changes.get(0).getNewPrice());
    }

    @Test
    void replacingTiers_recordsBothSides_asTheyWere_andAnUnchangedListRecordsNothing() {
        storeStandardTiers();
        SeatCategoryService service = service();
        UUID earlyId = store.get(0).getId();
        UUID phaseId = store.get(1).getId();

        service.setPriceTiers(vip.getId(), tiers(
                        tier(earlyId, "Early bird", "100.00", "2026-09-08T23:59:59", 150),
                        tier(phaseId, "Phase 1", "120.00", "2026-09-20T23:59:59", null)),
                null, "admin@example.com", true, null);
        service.setPriceTiers(vip.getId(), tiers(
                        tier(earlyId, "Early bird", "100.00", "2026-09-08T23:59:59", 150),
                        tier(phaseId, "Phase 1", "120.00", "2026-09-20T23:59:59", null)),
                null, "admin@example.com", true, null);

        assertEquals(1, changes.size());
        SeatCategoryPriceChange change = changes.get(0);
        assertEquals(SeatCategoryPriceChange.ChangeType.TIERS_CHANGED, change.getChangeType());
        assertTrue(change.getTiersBefore().contains("\"quantity\":100"));
        assertTrue(change.getTiersAfter().contains("\"quantity\":150"));
    }

    @Test
    void theHistory_readsBackNewestFirst_withTheTiersAsTheyWere() {
        storeStandardTiers();
        SeatCategoryService service = service();
        service.updateCategory(vip.getId(), update("160.00"));
        service.setPriceTiers(vip.getId(), tiers(), null, "admin@example.com", true, null);

        List<PriceChangeDTO> history = service.getPriceHistory(vip.getId(), null, "admin@example.com", true, null);

        assertEquals(List.of("TIERS_CHANGED", "PRICE_CHANGED"),
                history.stream().map(PriceChangeDTO::getChangeType).toList());
        assertEquals(List.of("Early bird", "Phase 1"),
                history.get(0).getTiersBefore().stream().map(PriceChangeDTO.Tier::getName).toList());
        assertEquals(EARLY_ENDS_UTC, history.get(0).getTiersBefore().get(0).getEndsAt());
        assertTrue(history.get(0).getTiersAfter().isEmpty());
    }

    @Test
    void anotherOrganizer_cannotReadTheHistory() {
        SeatCategoryService service = service();

        assertThrows(AccessDeniedException.class,
                () -> service.getPriceHistory(vip.getId(), UUID.randomUUID(), "other@example.com", false, "Bearer x"));
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
