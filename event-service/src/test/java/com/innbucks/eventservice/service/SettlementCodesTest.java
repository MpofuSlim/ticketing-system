package com.innbucks.eventservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the settlement-code contract: uppercase normalization, title-derived
 * fallback (alphanumerics only, 12-char cap), and the null result for titles
 * that can't yield a meaningful [A-Z0-9]{3,12} code.
 */
class SettlementCodesTest {

    @Test
    void suppliedCode_winsAndIsUppercased() {
        assertThat(SettlementCodes.normalizeOrDerive("pinkRun26", "Some Title"))
                .isEqualTo("PINKRUN26");
    }

    @Test
    void missingCode_derivedFromTitle_alphanumericsOnly_cappedAt12() {
        assertThat(SettlementCodes.normalizeOrDerive(null,
                "Chisipite Senior School Pink Fun Run"))
                .isEqualTo("CHISIPITESEN");
        assertThat(SettlementCodes.normalizeOrDerive("  ", "Jazz Night 2026!"))
                .isEqualTo("JAZZNIGHT202");
    }

    @Test
    void shortUsableTitle_stillDerives() {
        assertThat(SettlementCodes.deriveFromTitle("Gig")).isEqualTo("GIG");
    }

    @Test
    void unusableTitles_deriveNull() {
        assertThat(SettlementCodes.deriveFromTitle(null)).isNull();
        assertThat(SettlementCodes.deriveFromTitle("!!")).isNull();
        assertThat(SettlementCodes.deriveFromTitle("ab")).isNull();
    }

    @Test
    void normalize_trimsAndUppercases() {
        assertThat(SettlementCodes.normalize(" run26 ")).isEqualTo("RUN26");
        assertThat(SettlementCodes.normalize(null)).isNull();
    }
}
