package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.AddOnDTO;
import com.innbucks.seatservice.dto.AddOnRequestDTO;
import com.innbucks.seatservice.dto.AddOnsRequestDTO;
import com.innbucks.seatservice.dto.CategoryCapacityDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.exception.BadRequestException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * V12 on real Postgres: add-ons replace as a list (kept ids stay, left-out
 * ones go), carry their counts on the listing, reach booking-service on the
 * capacity read, refuse a limit above the category's tickets, and go with a
 * category that is hard-deleted (tests only; categories are soft-deleted).
 */
class AddOnsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private BookingServiceClient bookingServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;
    @Autowired private SeatCategoryService categoryService;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
        when(bookingServiceClient.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.of(Map.of()));
        when(bookingServiceClient.fetchTierSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of()));
        when(bookingServiceClient.fetchAddOnSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM seat_category_add_ons");
        jdbc.update("DELETE FROM seat_category_price_tiers");
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM seat_categories");
    }

    private UUID seedCategory(int tickets) {
        return tx.execute(s -> categoryRepository.save(SeatCategory.builder()
                .eventId(eventId).name("VIP").price(new BigDecimal("150.00"))
                .totalSeats(tickets).availableSeats(tickets).deleted(false).build()).getId());
    }

    private static AddOnRequestDTO addOn(UUID id, String name, String price, Integer max) {
        AddOnRequestDTO a = new AddOnRequestDTO();
        a.setId(id);
        a.setName(name);
        a.setPrice(new BigDecimal(price));
        a.setMaxQuantity(max);
        return a;
    }

    private CreateCategoryResponseDTO set(UUID categoryId, AddOnRequestDTO... addOns) {
        AddOnsRequestDTO request = new AddOnsRequestDTO();
        request.setAddOns(List.of(addOns));
        return categoryService.setAddOns(categoryId, request, null, "admin@example.com", true, null);
    }

    @Test
    void aReplacement_keepsIds_dropsTheLeftOut_andTheListingAndCapacityCarryThem() {
        UUID categoryId = seedCategory(100);
        CreateCategoryResponseDTO first = set(categoryId,
                addOn(null, "Parking pass", "5.00", 50), addOn(null, "Meal voucher", "10.00", null));
        UUID parking = first.getAddOns().get(0).getId();
        UUID meal = first.getAddOns().get(1).getId();

        CreateCategoryResponseDTO second = set(categoryId, addOn(null, "Free cap", "0", 20),
                addOn(parking, "Car park", "6.00", 40));
        assertThat(second.getAddOns()).extracting(AddOnDTO::getName).containsExactly("Free cap", "Car park");
        assertThat(second.getAddOns().get(1).getId()).isEqualTo(parking);
        assertThat(second.getAddOns()).extracting(AddOnDTO::getId).doesNotContain(meal);

        when(bookingServiceClient.fetchAddOnSoldCounts(anyCollection())).thenReturn(Optional.of(Map.of(parking, 40L)));
        CreateCategoryResponseDTO listed = categoryService.getCategoriesByEvent(eventId).get(0);
        AddOnDTO carPark = listed.getAddOns().get(1);
        assertThat(carPark.getSold()).isEqualTo(40L);
        assertThat(carPark.getRemaining()).isZero();
        assertThat(carPark.isSoldOut()).isTrue();

        CategoryCapacityDTO capacity = categoryService.getCategoryCapacity(categoryId);
        assertThat(capacity.getAddOns()).extracting(AddOnDTO::getName).containsExactly("Free cap", "Car park");
        assertThat(capacity.getAddOns().get(1).getMaxQuantity()).isEqualTo(40);
        assertThat(capacity.getAddOns().get(1).getSold()).as("terms only for booking-service").isNull();
    }

    @Test
    void aLimitAboveTheCategorysTickets_isRefused_andNothingIsStored() {
        UUID categoryId = seedCategory(100);

        assertThatThrownBy(() -> set(categoryId, addOn(null, "Parking pass", "5.00", 200)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("\"Parking pass\" can't be limited to more than this category's 100 tickets.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_category_add_ons", Integer.class)).isZero();
    }

    @Test
    void theDatabaseRefusesANegativePriceOrAZeroLimit_andAHardDeletedCategoryTakesItsAddOns() {
        UUID categoryId = seedCategory(10);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO seat_category_add_ons (id, category_id, position, name, price, "
                + "created_at) VALUES (gen_random_uuid(), ?, 0, 'Bad', -1, now())", categoryId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO seat_category_add_ons (id, category_id, position, name, price, "
                + "max_quantity, created_at) VALUES (gen_random_uuid(), ?, 0, 'Bad', 1, 0, now())", categoryId))
                .isInstanceOf(DataIntegrityViolationException.class);

        set(categoryId, addOn(null, "Parking pass", "5.00", 5));
        jdbc.update("DELETE FROM seat_categories WHERE id = ?", categoryId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_category_add_ons", Integer.class)).isZero();
    }
}
