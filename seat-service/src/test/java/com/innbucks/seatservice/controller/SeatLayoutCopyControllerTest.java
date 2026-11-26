package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.CopiedCategoryDTO;
import com.innbucks.seatservice.dto.CopiedLayoutDTO;
import com.innbucks.seatservice.exception.GlobalExceptionHandler;
import com.innbucks.seatservice.service.SeatLayoutCopyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /seat-categories/internal/copy: the X-Internal-Token is its only
 * authentication (the exact path is permitAll, and the gateway denies
 * /seat-categories/internal/** at the edge), checked before the body.
 */
class SeatLayoutCopyControllerTest {

    private SeatLayoutCopyService service;
    private MockMvc mvc;

    private final UUID template = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private final UUID date = UUID.fromString("9b2c7e10-4d5f-4a8b-9c1d-2e3f4a5b6c7d");

    @BeforeEach
    void setUp() {
        service = mock(SeatLayoutCopyService.class);
        SeatLayoutCopyController controller = new SeatLayoutCopyController(service);
        ReflectionTestUtils.setField(controller, "internalApiToken", "secret-token");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String body() {
        return "{\"sourceEventId\":\"" + template + "\",\"targetEventId\":\"" + date + "\",\"maxTotalSeats\":60}";
    }

    @Test
    void withTheToken_copies_andAnswersWithTheMapping() throws Exception {
        UUID vip = UUID.randomUUID();
        UUID copiedVip = UUID.randomUUID();
        when(service.copy(template, date, 60)).thenReturn(CopiedLayoutDTO.builder().copied(true)
                .categories(List.of(CopiedCategoryDTO.builder().sourceCategoryId(vip).categoryId(copiedVip)
                        .name("VIP").build())).build());

        mvc.perform(post("/seat-categories/internal/copy").header("X-Internal-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Layout copied"))
                .andExpect(jsonPath("$.data.copied").value(true))
                .andExpect(jsonPath("$.data.categories[0].sourceCategoryId").value(vip.toString()))
                .andExpect(jsonPath("$.data.categories[0].categoryId").value(copiedVip.toString()));
    }

    @Test
    void aRepeatedCall_saysTheLayoutIsAlreadyInPlace() throws Exception {
        when(service.copy(template, date, 60)).thenReturn(CopiedLayoutDTO.builder().copied(false)
                .categories(List.of()).build());

        mvc.perform(post("/seat-categories/internal/copy").header("X-Internal-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Layout already in place"))
                .andExpect(jsonPath("$.data.copied").value(false));
    }

    @Test
    void withoutTheRightToken_isUnauthorized_beforeTheBodyIsRead() throws Exception {
        mvc.perform(post("/seat-categories/internal/copy")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/seat-categories/internal/copy").header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        verify(service, never()).copy(any(), any(), any());
    }

    @Test
    void aMissingId_orANegativeCapacity_isABadRequest() throws Exception {
        mvc.perform(post("/seat-categories/internal/copy").header("X-Internal-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sourceEventId\":\"" + template + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sourceEventId and targetEventId are required."));
        mvc.perform(post("/seat-categories/internal/copy").header("X-Internal-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceEventId\":\"" + template + "\",\"targetEventId\":\"" + date
                                + "\",\"maxTotalSeats\":-1}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).copy(any(), any(), any());
    }
}
