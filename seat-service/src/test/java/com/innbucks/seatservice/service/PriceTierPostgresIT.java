package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.PriceChangeDTO;
import com.innbucks.seatservice.dto.PriceTierDTO;
import com.innbucks.seatservice.dto.PriceTierRequestDTO;
import com.innbucks.seatservice.dto.PriceTiersRequestDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import com.innbucks.seatservice.repository.SeatCategoryPriceTierRepository;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * V9 on real Postgres: a replacement that reorders tiers and inserts one where
 * another is leaving commits (the order constraint is deferred), kept tiers
 * keep their ids, and the CHECKs refuse a tier that could never close. V10:
 * every price change is recorded, and the record refuses UPDATE and DELETE.
 */
class PriceTierPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private BookingServiceClient bookingServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;

    @Autowired private SeatCategoryService categoryService;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private SeatCategoryPriceTierRepository tierRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
        when(bookingServiceClient.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.of(Map.of()));
        when(bookingServiceClient.fetchTierSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM seat_category_price_tiers");
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM seat_categories");
    }

    private UUID seedCategory() {
        return tx.execute(s -> categoryRepository.save(SeatCategory.builder()
                .eventId(UUID.randomUUID()).name("VIP").price(new BigDecimal("150.00"))
                .totalSeats(4).availableSeats(4).deleted(false).build()).getId());
    }

    private static PriceTierRequestDTO tier(UUID id, String name, String price, Integer quantity) {
        PriceTierRequestDTO tier = new PriceTierRequestDTO();
        tier.setId(id);
        tier.setName(name);
        tier.setPrice(new BigDecimal(price));
        tier.setEndsAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0) + "Z");
        tier.setQuantity(quantity);
        return tier;
    }

    private CreateCategoryResponseDTO set(UUID categoryId, PriceTierRequestDTO... tiers) {
        PriceTiersRequestDTO request = new PriceTiersRequestDTO();
        request.setTiers(List.of(tiers));
        return categoryService.setPriceTiers(categoryId, request, null, "admin@example.com", true, null);
    }

    @Test
    void aReplacementThatReordersAndReusesPositions_commits_andKeepsIds() {
        UUID categoryId = seedCategory();
        CreateCategoryResponseDTO first = set(categoryId,
                tier(null, "Early bird", "100.00", 100), tier(null, "Phase 1", "120.00", null));
        UUID earlyId = first.getPriceTiers().get(0).getId();
        UUID phaseId = first.getPriceTiers().get(1).getId();

        // A new tier takes position 0, Early bird moves to 1 (Phase 1's), Phase 1 goes.
        CreateCategoryResponseDTO second = set(categoryId,
                tier(null, "Flash sale", "90.00", 20), tier(earlyId, "Early bird", "100.00", 100));

        List<SeatCategoryPriceTier> stored = tierRepository.findByCategoryIdOrderByPositionAsc(categoryId);
        assertThat(stored).extracting(SeatCategoryPriceTier::getName).containsExactly("Flash sale", "Early bird");
        assertThat(stored.get(1).getId()).isEqualTo(earlyId);
        assertThat(stored).extracting(SeatCategoryPriceTier::getId).doesNotContain(phaseId);
        assertThat(second.getCurrentPrice()).isEqualByComparingTo("90.00");
        assertThat(second.getPriceTiers()).extracting(PriceTierDTO::getStatus)
                .containsExactly(PriceTierDTO.Status.ACTIVE, PriceTierDTO.Status.UPCOMING);
    }

    @Test
    void theListing_pricesFromTheStoredTiers() {
        UUID categoryId = seedCategory();
        UUID eventId = categoryRepository.findById(categoryId).orElseThrow().getEventId();
        set(categoryId, tier(null, "Early bird", "100.00", 100));

        CreateCategoryResponseDTO listed = categoryService.getCategoriesByEvent(eventId).get(0);

        assertThat(listed.getCurrentPrice()).isEqualByComparingTo("100.00");
        assertThat(listed.getCurrentTierName()).isEqualTo("Early bird");
    }

    @Test
    void aTierThatCouldNeverClose_isRefusedByTheDatabase() {
        UUID categoryId = seedCategory();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO seat_category_price_tiers "
                        + "(id, category_id, tier_order, name, price, created_at) VALUES (?, ?, 0, 'Forever', 100, now())",
                UUID.randomUUID(), categoryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_price_tier_closes");
    }

    @Test
    void everyPriceChange_isRecorded_andTheRecordCannotBeRewritten() {
        UUID categoryId = seedCategory();
        set(categoryId, tier(null, "Early bird", "100.00", 100));
        UpdateCategoryRequestDTO update = new UpdateCategoryRequestDTO();
        update.setName("VIP");
        update.setPrice(new BigDecimal("160.00"));
        categoryService.updateCategory(categoryId, update);

        List<PriceChangeDTO> history = categoryService.getPriceHistory(categoryId, null, "admin@example.com",
                true, null);

        assertThat(history).extracting(PriceChangeDTO::getChangeType)
                .containsExactly("PRICE_CHANGED", "TIERS_CHANGED");
        assertThat(history.get(1).getTiersAfter()).extracting(PriceChangeDTO.Tier::getName)
                .containsExactly("Early bird");
        assertThatThrownBy(() -> jdbc.update("UPDATE seat_category_price_changes SET new_price = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM seat_category_price_changes WHERE category_id = ?",
                categoryId)).hasMessageContaining("append-only");
        // The history outlives the category it describes: there is no foreign key.
        clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_category_price_changes WHERE category_id = ?",
                Long.class, categoryId)).isEqualTo(2);
    }

    @Test
    void theCategoriesWithATierEndingInTheWindow_areListedWithEveryTier() {
        UUID categoryId = seedCategory();
        set(categoryId, tier(null, "Early bird", "100.00", 100), tier(null, "Phase 1", "120.00", null));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Both tiers end in 30 days (see tier()).
        assertThat(categoryService.categoriesWithTiersEnding(now, now.plusDays(29))).isEmpty();
        List<com.innbucks.seatservice.dto.CategoryCapacityDTO> ending =
                categoryService.categoriesWithTiersEnding(now.plusDays(29), now.plusDays(31));

        assertThat(ending).hasSize(1);
        assertThat(ending.get(0).getSeatCategoryId()).isEqualTo(categoryId);
        assertThat(ending.get(0).getPriceTiers()).extracting(PriceTierDTO::getName)
                .containsExactly("Early bird", "Phase 1");
    }
}
