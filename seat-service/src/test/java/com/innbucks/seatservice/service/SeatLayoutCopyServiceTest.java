package com.innbucks.seatservice.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The series generator's layout copy: what a new date gets from its template,
 * and that a repeated call copies nothing.
 */
class SeatLayoutCopyServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 9, 0);

    private SeatCategoryRepository categories;
    private SeatCategoryPriceTierRepository tiers;
    private SeatCategoryAddOnRepository addOns;
    private SeatRepository seats;
    private SeatCategoryPriceChangeRepository changes;
    private SeatLayoutCopyService service;

    private final UUID template = UUID.randomUUID();
    private final UUID date = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categories = mock(SeatCategoryRepository.class);
        tiers = mock(SeatCategoryPriceTierRepository.class);
        addOns = mock(SeatCategoryAddOnRepository.class);
        seats = mock(SeatRepository.class);
        changes = mock(SeatCategoryPriceChangeRepository.class);
        service = new SeatLayoutCopyService(categories, tiers, addOns, seats, changes, null);
        service.setClock(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        // Saving gives each new category an id, as the database would.
        when(categories.saveAllAndFlush(anyList())).thenAnswer(inv -> {
            List<SeatCategory> saved = inv.getArgument(0);
            saved.forEach(c -> c.setId(UUID.randomUUID()));
            return saved;
        });
        when(tiers.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(addOns.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection())).thenReturn(List.of());
        when(addOns.findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection())).thenReturn(List.of());
        when(seats.countSections(anyCollection())).thenReturn(List.of());
    }

    private static SeatCategory category(UUID eventId, String name, String price, int tickets, int minutesOld) {
        return SeatCategory.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .name(name)
                .description(name + " seats")
                .price(new BigDecimal(price))
                .totalSeats(tickets)
                .availableSeats(tickets - 3)
                .deleted(false)
                .createdAt(NOW.minusMinutes(minutesOld))
                .build();
    }

    private static SeatRepository.SectionCount section(UUID categoryId, String label, long count, String image) {
        return new SeatRepository.SectionCount() {
            @Override public UUID getCategoryId() { return categoryId; }
            @Override public String getSectionLabel() { return label; }
            @Override public Long getSeatCount() { return count; }
            @Override public String getImageUrl() { return image; }
        };
    }

    @Test
    void aDateGetsEveryLiveCategory_withItsSections_openTiers_andAddOns() {
        SeatCategory vip = category(template, "VIP", "150.00", 5, 10);
        SeatCategory general = category(template, "General", "50.00", 3, 20);
        when(categories.findByEventIdAndDeletedFalse(template)).thenReturn(List.of(vip, general));
        when(categories.findByEventIdAndDeletedFalse(date)).thenReturn(List.of());
        SeatCategoryPriceTier ended = SeatCategoryPriceTier.builder().categoryId(vip.getId()).position(0)
                .name("Launch").price(new BigDecimal("90.00")).endsAt(NOW.minusDays(1)).build();
        SeatCategoryPriceTier open = SeatCategoryPriceTier.builder().categoryId(vip.getId()).position(1)
                .name("Early bird").price(new BigDecimal("100.00")).quantity(2).maxPerCustomer(1)
                .refundsReopen(false).build();
        SeatCategoryPriceTier later = SeatCategoryPriceTier.builder().categoryId(vip.getId()).position(2)
                .name("Phase 1").price(new BigDecimal("120.00")).startsAt(NOW.plusDays(1))
                .endsAt(NOW.plusDays(9)).build();
        when(tiers.findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection()))
                .thenReturn(List.of(ended, open, later));
        when(addOns.findByCategoryIdInOrderByCategoryIdAscPositionAsc(anyCollection())).thenReturn(List.of(
                SeatCategoryAddOn.builder().categoryId(general.getId()).position(0).name("Parking pass")
                        .description("Car park B").price(new BigDecimal("5.00")).maxQuantity(2).build()));
        when(seats.countSections(anyCollection())).thenReturn(List.of(
                section(vip.getId(), "A", 3, "https://cdn.example.com/a.png"),
                section(vip.getId(), "B", 2, null),
                section(general.getId(), "FLOOR", 3, null)));

        CopiedLayoutDTO result = service.copy(template, date, 8);

        assertThat(result.isCopied()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SeatCategory>> savedCategories = ArgumentCaptor.forClass(List.class);
        verify(categories).saveAllAndFlush(savedCategories.capture());
        List<SeatCategory> copies = savedCategories.getValue();
        assertThat(copies).extracting(SeatCategory::getName).as("oldest first").containsExactly("General", "VIP");
        assertThat(copies).allSatisfy(c -> {
            assertThat(c.getEventId()).isEqualTo(date);
            assertThat(c.isDeleted()).isFalse();
            assertThat(c.getAvailableSeats()).as("a new date has every ticket").isEqualTo(c.getTotalSeats());
        });
        assertThat(copies.get(1).getPrice()).isEqualByComparingTo("150.00");
        assertThat(copies.get(1).getDescription()).isEqualTo("VIP seats");

        assertThat(result.getCategories()).extracting(CopiedCategoryDTO::getSourceCategoryId)
                .containsExactly(general.getId(), vip.getId());
        assertThat(result.getCategories()).extracting(CopiedCategoryDTO::getCategoryId)
                .containsExactly(copies.get(0).getId(), copies.get(1).getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SeatCategoryPriceTier>> savedTiers = ArgumentCaptor.forClass(List.class);
        verify(tiers).saveAll(savedTiers.capture());
        assertThat(savedTiers.getValue()).as("the ended tier is not copied").extracting(SeatCategoryPriceTier::getName)
                .containsExactly("Early bird", "Phase 1");
        assertThat(savedTiers.getValue()).extracting(SeatCategoryPriceTier::getPosition).containsExactly(0, 1);
        SeatCategoryPriceTier earlyBird = savedTiers.getValue().get(0);
        assertThat(earlyBird.getCategoryId()).isEqualTo(copies.get(1).getId());
        assertThat(earlyBird.getQuantity()).isEqualTo(2);
        assertThat(earlyBird.getMaxPerCustomer()).isEqualTo(1);
        assertThat(earlyBird.isRefundsReopen()).isFalse();
        assertThat(savedTiers.getValue().get(1).getStartsAt()).as("times unchanged").isEqualTo(NOW.plusDays(1));
        assertThat(savedTiers.getValue().get(1).getEndsAt()).isEqualTo(NOW.plusDays(9));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SeatCategoryAddOn>> savedAddOns = ArgumentCaptor.forClass(List.class);
        verify(addOns).saveAll(savedAddOns.capture());
        assertThat(savedAddOns.getValue()).singleElement().satisfies(a -> {
            assertThat(a.getCategoryId()).isEqualTo(copies.get(0).getId());
            assertThat(a.getName()).isEqualTo("Parking pass");
            assertThat(a.getDescription()).isEqualTo("Car park B");
            assertThat(a.getMaxQuantity()).isEqualTo(2);
            assertThat(a.getPrice()).isEqualByComparingTo("5.00");
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> savedSeats = ArgumentCaptor.forClass(List.class);
        verify(seats).saveAll(savedSeats.capture());
        List<Seat> newSeats = savedSeats.getValue();
        assertThat(newSeats).hasSize(8);
        assertThat(newSeats).filteredOn(s -> "A".equals(s.getSectionLabel()))
                .extracting(Seat::getSeatNumber).containsExactly(1, 2, 3);
        assertThat(newSeats).filteredOn(s -> "A".equals(s.getSectionLabel()))
                .allSatisfy(s -> assertThat(s.getSectionImageUrl()).isEqualTo("https://cdn.example.com/a.png"));
        assertThat(newSeats).filteredOn(s -> "FLOOR".equals(s.getSectionLabel()))
                .allSatisfy(s -> assertThat(s.getCategory()).isSameAs(copies.get(0)));
        assertThat(newSeats).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(Seat.SeatStatus.AVAILABLE));

        ArgumentCaptor<SeatCategoryPriceChange> change = ArgumentCaptor.forClass(SeatCategoryPriceChange.class);
        verify(changes, org.mockito.Mockito.times(2)).save(change.capture());
        assertThat(change.getAllValues()).allSatisfy(c -> {
            assertThat(c.getChangeType()).isEqualTo(SeatCategoryPriceChange.ChangeType.CREATED);
            assertThat(c.getEventId()).isEqualTo(date);
            assertThat(c.getChangedAt()).isEqualTo(NOW);
        });
        assertThat(change.getAllValues().get(1).getTiersAfter()).contains("Early bird").doesNotContain("Launch");
    }

    @Test
    void theLockIsTakenOnTheTarget_beforeAnythingIsRead() {
        when(categories.findByEventIdAndDeletedFalse(any())).thenReturn(List.of());

        service.copy(template, date, null);

        InOrder order = inOrder(categories);
        order.verify(categories).lockEventLayout(SeatLayoutCopyService.COPY_LOCK_NAMESPACE, date.hashCode());
        order.verify(categories).findByEventIdAndDeletedFalse(template);
    }

    @Test
    void aRepeatedCall_copiesNothing_andMapsByName() {
        SeatCategory vip = category(template, "VIP", "150.00", 5, 10);
        SeatCategory general = category(template, "General", "50.00", 3, 20);
        SeatCategory copiedVip = category(date, "VIP", "150.00", 5, 1);
        when(categories.findByEventIdAndDeletedFalse(template)).thenReturn(List.of(vip, general));
        when(categories.findByEventIdAndDeletedFalse(date)).thenReturn(List.of(copiedVip));

        CopiedLayoutDTO result = service.copy(template, date, 8);

        assertThat(result.isCopied()).isFalse();
        assertThat(result.getCategories()).singleElement().satisfies(c -> {
            assertThat(c.getSourceCategoryId()).isEqualTo(vip.getId());
            assertThat(c.getCategoryId()).isEqualTo(copiedVip.getId());
        });
        verify(categories, never()).saveAllAndFlush(anyList());
        verify(seats, never()).saveAll(anyList());
        verify(changes, never()).save(any());
    }

    @Test
    void aLayoutBiggerThanTheTarget_isRefused_andNothingIsWritten() {
        when(categories.findByEventIdAndDeletedFalse(template)).thenReturn(List.of(
                category(template, "VIP", "150.00", 50, 10), category(template, "General", "50.00", 30, 5)));
        when(categories.findByEventIdAndDeletedFalse(date)).thenReturn(List.of());

        assertThatThrownBy(() -> service.copy(template, date, 60))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The seat layout has 80 seats, more than the event's capacity of 60.");
        verify(categories, never()).saveAllAndFlush(anyList());
    }

    @Test
    void anEmptyTemplate_copiesNothing_butIsDone() {
        when(categories.findByEventIdAndDeletedFalse(any())).thenReturn(List.of());

        CopiedLayoutDTO result = service.copy(template, date, 0);

        assertThat(result.isCopied()).isTrue();
        assertThat(result.getCategories()).isEmpty();
        verify(categories, never()).saveAllAndFlush(anyList());
    }

    @Test
    void theSameEvent_orAMissingId_isRefusedBeforeAnyLock() {
        assertThatThrownBy(() -> service.copy(template, template, null))
                .isInstanceOf(BadRequestException.class).hasMessage(SeatLayoutCopyService.SAME_EVENT);
        assertThatThrownBy(() -> service.copy(null, date, null)).isInstanceOf(BadRequestException.class);
        verify(categories, never()).lockEventLayout(anyInt(), anyInt());
    }

    @Test
    void lockNamespace_spellsLCPY() {
        assertThat(new String(java.nio.ByteBuffer.allocate(4).putInt(SeatLayoutCopyService.COPY_LOCK_NAMESPACE)
                .array(), java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("LCPY");
    }
}
