package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.service.SeatCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/seat-categories")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Seat Categories", description = "Manage seat categories for events.")
public class SeatCategoryController {

    private final SeatCategoryService categoryService;

    @GetMapping
    @SecurityRequirements()
    @Operation(summary = "List categories by event", description = "Returns all active seat categories for a given event.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Categories returned")
    })
    public ResponseEntity<ApiResult<List<CreateCategoryResponseDTO>>> getByEvent(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId
    ) {
        log.debug("GET /seat-categories eventId={}", eventId);
        List<CreateCategoryResponseDTO> result = categoryService.getCategoriesByEvent(eventId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
        return ResponseEntity.ok(ApiResult.ok("Categories retrieved successfully", result));
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Create category", description = "Creates a seat category for an event. Requires TENANT role.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Category created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not TENANT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<ApiResult<CreateCategoryResponseDTO>> createCategory(
            @Valid @RequestBody CreateCategoryRequestDTO request
    ) {
        log.info("POST /seat-categories eventId={} name={}", request.getEventId(), request.getName());
        CreateCategoryResponseDTO created = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Seat category created successfully", created));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TENANT')")
    @Operation(summary = "Delete category", description = "Soft-deletes a seat category. Requires TENANT role.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not TENANT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Category not found")
    })
    public ResponseEntity<ApiResult<Void>> deleteCategory(@PathVariable UUID id) {
        log.info("DELETE /seat-categories/{}", id);
        categoryService.deleteCategory(id);
        return ResponseEntity.ok(ApiResult.ok("Seat category deleted successfully", null));
    }
}
