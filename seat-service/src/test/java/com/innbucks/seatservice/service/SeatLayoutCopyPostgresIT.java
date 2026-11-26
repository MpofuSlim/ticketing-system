package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CopiedLayoutDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.entity.SeatCategoryAddOn;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.repository.SeatCategoryAddOnRepository;
import com.innbucks.seatservice.repository.SeatCategoryPriceTierRepository;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The series generator's layout copy on real Postgres: every row a date needs
 * commits in one transaction (categories before the rows that name them, with
 * inserts reordered for batching), a repeated call copies nothing, and two
 * copies onto one date at once leave exactly one layout.
 */
class SeatLayoutCopyPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private BookingServiceClient bookingServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;

    @Autowired private SeatLayoutCopyService copyService;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private SeatCategoryPriceTierRepository tierRepository;
    @Autowired private SeatCategoryAddOnRepository addOnRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private final UUID template = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        // seat_category_price_changes refuses DELETE (V11); each test uses new events.
        jdbc.update("DELETE FROM seat_category_add_ons");
        jdbc.update("DELETE FROM seat_category_price_tiers");
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM seat_categories");
    }

    private void seedTemplate() {
        tx.executeWithoutResult(s -> {
            SeatCategory vip = categoryRepository.save(SeatCategory.builder().eventId(template).name("VIP")
                    .description("Front rows").price(new BigDecimal("150.00")).totalSeats(5).availableSeats(5)
                    .deleted(false).build());
            SeatCategory general = categoryRepository.save(SeatCategory.builder().eventId(template).name("General")
                    .price(new BigDecimal("50.00")).totalSeats(3).availableSeats(3).deleted(false).build());
            categoryRepository.save(SeatCategory.builder().eventId(template).name("Old")
                    .price(new BigDecimal("10.00")).totalSeats(2).availableSeats(2).deleted(true).build());
            List<Seat> seats = new ArrayList<>();
            for (int n = 1; n <= 3; n++) {
                seats.add(Seat.builder().category(vip).sectionLabel("A").sectionImageUrl("https://cdn.example.com/a.png")
                        .seatNumber(n).status(Seat.SeatStatus.AVAILABLE).build());
            }
            for (int n = 1; n <= 2; n++) {
                seats.add(Seat.builder().category(vip).sectionLabel("B").seatNumber(n)
                        .status(Seat.SeatStatus.AVAILABLE).build());
            }
            for (int n = 1; n <= 3; n++) {
                seats.add(Seat.builder().category(general).sectionLabel("FLOOR").seatNumber(n)
                        .status(Seat.SeatStatus.AVAILABLE).build());
            }
            seatRepository.saveAll(seats);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            tierRepository.save(SeatCategoryPriceTier.builder().categoryId(vip.getId()).position(0).name("Launch")
                    .price(new BigDecimal("90.00")).endsAt(now.minusDays(1)).build());
            tierRepository.save(SeatCategoryPriceTier.builder().categoryId(vip.getId()).position(1).name("Early bird")
                    .price(new BigDecimal("100.00")).quantity(2).build());
            addOnRepository.save(SeatCategoryAddOn.builder().categoryId(general.getId()).position(0)
                    .name("Parking pass").price(new BigDecimal("5.00")).maxQuantity(2).build());
        });
    }

    @Test
    void aDateGetsTheWholeLiveLayout_inOneTransaction() {
        seedTemplate();
        UUID date = UUID.randomUUID();

        CopiedLayoutDTO result = copyService.copy(template, date, 8);

        assertThat(result.isCopied()).isTrue();
        assertThat(result.getCategories()).hasSize(2);
        List<SeatCategory> copies = categoryRepository.findByEventIdAndDeletedFalse(date);
        assertThat(copies).extracting(SeatCategory::getName).containsExactlyInAnyOrder("VIP", "General");
        SeatCategory vip = copies.stream().filter(c -> c.getName().equals("VIP")).findFirst().orElseThrow();
        SeatCategory general = copies.stream().filter(c -> c.getName().equals("General")).findFirst().orElseThrow();
        assertThat(vip.getAvailableSeats()).isEqualTo(5);
        assertThat(seatRepository.countSections(List.of(vip.getId(), general.getId())))
                .extracting(SeatRepository.SectionCount::getSectionLabel, SeatRepository.SectionCount::getSeatCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("A", 3L),
                        org.assertj.core.groups.Tuple.tuple("B", 2L),
                        org.assertj.core.groups.Tuple.tuple("FLOOR", 3L));
        assertThat(tierRepository.findByCategoryIdOrderByPositionAsc(vip.getId()))
                .extracting(SeatCategoryPriceTier::getName).containsExactly("Early bird");
        assertThat(addOnRepository.findByCategoryIdOrderByPositionAsc(general.getId()))
                .extracting(SeatCategoryAddOn::getName).containsExactly("Parking pass");
        Integer recorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_category_price_changes WHERE event_id = ? AND change_type = 'CREATED'",
                Integer.class, date);
        assertThat(recorded).isEqualTo(2);
    }

    @Test
    void aRepeatedCall_copiesNothing_andReturnsTheSameCategories() {
        seedTemplate();
        UUID date = UUID.randomUUID();
        CopiedLayoutDTO first = copyService.copy(template, date, 8);

        CopiedLayoutDTO second = copyService.copy(template, date, 8);

        assertThat(second.isCopied()).isFalse();
        assertThat(second.getCategories()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(first.getCategories());
        assertThat(categoryRepository.findByEventIdAndDeletedFalse(date)).hasSize(2);
    }

    @Test
    void twoCopiesAtOnce_leaveOneLayout() throws Exception {
        seedTemplate();
        UUID date = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<CopiedLayoutDTO> copy = () -> {
                start.await();
                return copyService.copy(template, date, 8);
            };
            Future<CopiedLayoutDTO> a = pool.submit(copy);
            Future<CopiedLayoutDTO> b = pool.submit(copy);
            start.countDown();
            List<Boolean> copied = List.of(a.get().isCopied(), b.get().isCopied());
            assertThat(copied).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(categoryRepository.findByEventIdAndDeletedFalse(date)).hasSize(2);
        Integer seats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats s JOIN seat_categories c ON c.id = s.category_id WHERE c.event_id = ?",
                Integer.class, date);
        assertThat(seats).isEqualTo(8);
    }

    @Test
    void aLayoutBiggerThanTheDate_isRefused_andLeavesNothing() {
        seedTemplate();
        UUID date = UUID.randomUUID();

        assertThatThrownBy(() -> copyService.copy(template, date, 7)).isInstanceOf(ConflictException.class);

        assertThat(categoryRepository.findByEventIdAndDeletedFalse(date)).isEmpty();
    }
}
