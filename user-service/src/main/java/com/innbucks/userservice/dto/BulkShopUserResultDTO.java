package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Outcome of a bulk SHOP_USER CSV import. The import is <b>best-effort per
 * row</b>: each row is created in its own transaction, so a bad row (duplicate
 * email, invalid phone, blank name) fails on its own and every good row still
 * lands. This report tells the caller exactly which rows succeeded and why the
 * rest didn't, so they can fix and re-upload only the failures rather than the
 * whole file.
 *
 * @param total   data rows read from the CSV (excludes the header and blank lines)
 * @param created rows that produced a SHOP_USER
 * @param failed  rows that were rejected ({@code total == created + failed})
 * @param results one entry per data row, in file order
 */
@Schema(name = "BulkShopUserResult",
        description = "Per-row outcome of a bulk SHOP_USER CSV import. Best-effort: good rows are created " +
                      "even when others fail; each failure names the row and the reason.")
public record BulkShopUserResultDTO(
        @Schema(example = "3") int total,
        @Schema(example = "2") int created,
        @Schema(example = "1") int failed,
        List<RowResult> results) {

    /**
     * One CSV data row's result.
     *
     * @param line   1-based line number in the uploaded file (the header is
     *               line 1), so the operator can find the row in their sheet
     * @param email  the row's email, echoed back for identification (may be
     *               blank when the row itself was missing it)
     * @param status {@code CREATED} or {@code FAILED}
     * @param error  the failure reason when {@code status == FAILED}; null on success
     */
    @Schema(name = "BulkShopUserRowResult", description = "Outcome of a single CSV row.")
    public record RowResult(
            @Schema(example = "4") int line,
            @Schema(example = "rufaro@pizza-avondale.co.zw") String email,
            @Schema(example = "FAILED", allowableValues = {"CREATED", "FAILED"}) String status,
            @Schema(example = "Email already registered", nullable = true) String error) {

        public static RowResult created(int line, String email) {
            return new RowResult(line, email, "CREATED", null);
        }

        public static RowResult failed(int line, String email, String error) {
            return new RowResult(line, email, "FAILED", error);
        }
    }
}
