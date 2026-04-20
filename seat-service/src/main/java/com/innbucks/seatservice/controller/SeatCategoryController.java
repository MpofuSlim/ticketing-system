package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.service.SeatCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@Tag(name = "Seat Categories", description = "Manage seat categories for events.")
public class SeatCategoryController {

    private final SeatCategoryService categoryService;

    @GetMapping
    @SecurityRequirements()
    @Operation(summary = "List categories by event", description = "Returns all active seat categories for a given event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Categories returned")
    })
    public ResponseEntity<List<CreateCategoryResponseDTO>> getByEvent(
            @Parameter(description = "Event UUID")
            @RequestParam UUID eventId
    ) {
        return ResponseEntity.ok(categoryService.getCategoriesByEvent(eventId));
    }

    @PostMapping
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Create category", description = "Creates a seat category for an event. Requires AGENT role.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Category created"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not AGENT"),
            @ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<CreateCategoryResponseDTO> createCategory(
            @Valid @RequestBody CreateCategoryRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categoryService.createCategory(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Delete category", description = "Soft-deletes a seat category. Requires AGENT role.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Category deleted"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not AGENT"),
            @ApiResponse(responseCode = "400", description = "Category not found")
    })
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
