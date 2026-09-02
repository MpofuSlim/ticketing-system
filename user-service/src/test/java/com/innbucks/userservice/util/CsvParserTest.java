package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CSV shapes a spreadsheet export actually produces, so the bulk
 * shop-user import parses them the way the operator expects.
 */
class CsvParserTest {

    @Test
    void parsesSimpleRows() {
        List<List<String>> rows = CsvParser.parse("a,b,c\n1,2,3");
        assertThat(rows).containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    }

    @Test
    void quotedFieldKeepsEmbeddedComma() {
        List<List<String>> rows = CsvParser.parse("name,note\n\"Ncube, Rufaro\",hi");
        assertThat(rows.get(1)).containsExactly("Ncube, Rufaro", "hi");
    }

    @Test
    void quotedFieldKeepsEmbeddedNewline() {
        List<List<String>> rows = CsvParser.parse("a,b\n\"line1\nline2\",z");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("line1\nline2", "z");
    }

    @Test
    void escapedQuotesCollapseToOne() {
        List<List<String>> rows = CsvParser.parse("q\n\"she said \"\"hi\"\"\"");
        assertThat(rows.get(1)).containsExactly("she said \"hi\"");
    }

    @Test
    void handlesCrlfAndLoneCr() {
        List<List<String>> crlf = CsvParser.parse("a,b\r\n1,2\r\n");
        assertThat(crlf.get(0)).containsExactly("a", "b");
        assertThat(crlf.get(1)).containsExactly("1", "2");

        List<List<String>> cr = CsvParser.parse("a,b\r1,2");
        assertThat(cr).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void stripsLeadingBom() {
        List<List<String>> rows = CsvParser.parse("﻿firstName,email\nA,a@x.com");
        assertThat(rows.get(0)).containsExactly("firstName", "email");
    }

    @Test
    void raggedRowIsReturnedAsIs() {
        List<List<String>> rows = CsvParser.parse("a,b,c\n1,2");
        assertThat(rows.get(1)).containsExactly("1", "2"); // caller pads short rows
    }

    @Test
    void trailingNewlineDoesNotAddDataRow_afterBlankFilter() {
        List<List<String>> rows = CsvParser.parse("a,b\n1,2\n");
        // The trailing newline yields a final all-blank row...
        assertThat(rows).hasSize(3);
        assertThat(CsvParser.isBlank(rows.get(2))).isTrue();
        // ...which isBlank lets the caller skip.
        assertThat(CsvParser.isBlank(rows.get(0))).isFalse();
        assertThat(CsvParser.isBlank(rows.get(1))).isFalse();
    }

    @Test
    void isBlankHandlesWhitespaceAndNull() {
        assertThat(CsvParser.isBlank(List.of("   ", ""))).isTrue();
        assertThat(CsvParser.isBlank(java.util.Arrays.asList("", null))).isTrue();
        assertThat(CsvParser.isBlank(List.of("x"))).isFalse();
    }
}
