package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable pagination envelope. We map Spring Data's {@link Page} into this rather
 * than serialising {@code Page} directly (whose JSON shape is unstable across
 * Spring versions and logs a warning).
 */
@Schema(name = "Page", description = "A page of results plus pagination metadata.")
public record PageResponse<T>(
        @Schema(description = "Items on this page.") List<T> content,
        @Schema(description = "Zero-based page index.", example = "0") int page,
        @Schema(description = "Page size requested.", example = "20") int size,
        @Schema(description = "Total matching items across all pages.", example = "42") long totalElements,
        @Schema(description = "Total number of pages.", example = "3") int totalPages
) {
    /** Map a {@code Page<E>} to a {@code PageResponse<T>} via a per-element mapper. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
