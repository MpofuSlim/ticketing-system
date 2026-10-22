package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.CategoryCapacityDTO;
import com.innbucks.seatservice.service.SeatCategoryAnalyticsService;
import com.innbucks.seatservice.service.SeatCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /seat-categories/internal/price-tiers/ending: the X-Internal-Token is its
 * only authentication (the path is permitAll, and the gateway denies it at the
 * edge), and the window it reads is bounded.
 */
class SeatCategoryInternalEndpointTest {

    private SeatCategoryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SeatCategoryService.class);
        SeatCategoryController controller = new SeatCategoryController(service, mock(SeatCategoryAnalyticsService.class));
        ReflectionTestUtils.setField(controller, "internalApiToken", "secret-token");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void withTheToken_listsTheCategoriesEndingInTheWindow() throws Exception {
        when(service.categoriesWithTiersEnding(LocalDateTime.of(2026, 9, 8, 8, 0), LocalDateTime.of(2026, 9, 9, 8, 0)))
                .thenReturn(List.of(CategoryCapacityDTO.builder().seatCategoryId(UUID.randomUUID()).name("VIP").build()));

        mvc.perform(get("/seat-categories/internal/price-tiers/ending")
                        .param("from", "2026-09-08T10:00:00+02:00").param("to", "2026-09-09T08:00:00Z")
                        .header("X-Internal-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("VIP"));
    }

    @Test
    void withoutTheRightToken_isUnauthorized_andAsksNothing() throws Exception {
        mvc.perform(get("/seat-categories/internal/price-tiers/ending")
                        .param("from", "2026-09-08T08:00:00Z").param("to", "2026-09-09T08:00:00Z"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/seat-categories/internal/price-tiers/ending")
                        .param("from", "2026-09-08T08:00:00Z").param("to", "2026-09-09T08:00:00Z")
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).categoriesWithTiersEnding(any(), any());
    }

    @Test
    void aBackwardsUnreadableOrTooLongWindow_isABadRequest() throws Exception {
        for (String[] window : new String[][]{
                {"2026-09-09T08:00:00Z", "2026-09-08T08:00:00Z"},
                {"tomorrow", "2026-09-08T08:00:00Z"},
                {"2026-09-01T08:00:00Z", "2026-09-09T08:00:01Z"}}) {
            mvc.perform(get("/seat-categories/internal/price-tiers/ending")
                            .param("from", window[0]).param("to", window[1])
                            .header("X-Internal-Token", "secret-token"))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).categoriesWithTiersEnding(any(), any());
    }
}
