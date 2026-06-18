package com.innbucks.bookingservice.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Wire shape for paginated responses across the scan-report endpoints.
 *
 * <p>Trimmed from Spring's {@code PageImpl} (which serialises a lot of internal
 * fields the FE can't usefully consume) to just what the gate-staff dashboard
 * needs to render. Constructed via {@link #from(Page)} so callers don't
 * accidentally hand-roll a mismatched shape.
 */
@Schema(name = "PageResponse", description = "Paginated wrapper for scan-report listings.")
public record PageResponse<T>(
        @Schema(description = "Page contents (size <= requested page size).") List<T> content,
        @Schema(example = "0", description = "Zero-based page index.") int page,
        @Schema(example = "20", description = "Page size that was used to compute this page.") int size,
        @Schema(example = "412", description = "Total matching rows across all pages.") long totalElements,
        @Schema(example = "21", description = "Total pages of size 'size' (ceil(totalElements / size)).")
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(
                p.getContent(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.getTotalPages()
        );
    }
}
