package com.innbucks.userservice.util;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, dependency-free RFC-4180 CSV reader — enough to back a staff bulk
 * import without pulling in commons-csv / opencsv (user-service ships no CSV
 * dependency, and one bulk endpoint doesn't justify one).
 *
 * <p>Handles the shapes a spreadsheet actually exports:
 * <ul>
 *   <li>quoted fields (<code>"a,b"</code>) — a comma or newline inside quotes
 *       is data, not a delimiter;</li>
 *   <li>escaped quotes inside a quoted field (<code>""</code> → <code>"</code>);</li>
 *   <li><code>CRLF</code>, lone <code>LF</code> and lone <code>CR</code> line
 *       endings, mixed within one file;</li>
 *   <li>a leading UTF-8 BOM (Excel adds one), stripped once.</li>
 * </ul>
 *
 * <p>Every record is returned verbatim, including blank lines — the caller
 * decides what a blank row means (the importer skips all-blank rows so a
 * trailing newline or an interior gap is harmless). Fields are returned
 * untrimmed; trimming is the caller's business, since leading/trailing spaces
 * can be significant.
 */
public final class CsvParser {

    private CsvParser() {}

    /**
     * Parse the whole document into rows of fields. Never returns null; a null
     * or empty input yields a single empty-field row, which the caller treats
     * as "no usable content".
     */
    public static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>();
        if (input == null) {
            rows.add(new ArrayList<>(List.of("")));
            return rows;
        }
        // Strip a single leading UTF-8 BOM if present (Excel/Numbers add one).
        if (!input.isEmpty() && input.charAt(0) == '\uFEFF') {
            input = input.substring(1);
        }

        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        final int n = input.length();
        int i = 0;
        while (i < n) {
            char c = input.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && input.charAt(i + 1) == '"') {
                        field.append('"'); // escaped quote
                        i += 2;
                    } else {
                        inQuotes = false; // closing quote
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            switch (c) {
                case '"' -> { inQuotes = true; i++; }
                case ',' -> { current.add(field.toString()); field.setLength(0); i++; }
                case '\n' -> { current.add(field.toString()); field.setLength(0);
                               rows.add(current); current = new ArrayList<>(); i++; }
                case '\r' -> { current.add(field.toString()); field.setLength(0);
                               rows.add(current); current = new ArrayList<>();
                               i += (i + 1 < n && input.charAt(i + 1) == '\n') ? 2 : 1; }
                default -> { field.append(c); i++; }
            }
        }
        // Flush the trailing field/row. A file ending in a newline leaves an
        // empty pending row here; that surfaces as an all-blank row, which the
        // caller skips — cheaper than tracking "did the last char end a line".
        current.add(field.toString());
        rows.add(current);
        return rows;
    }

    /** True when every field in the row is blank (null / empty / whitespace). */
    public static boolean isBlank(List<String> row) {
        if (row == null) return true;
        for (String f : row) {
            if (f != null && !f.isBlank()) return false;
        }
        return true;
    }
}
