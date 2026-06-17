package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    // 2 groups of 5 (10 password chars + 1 hyphen for readability), drawn ONLY
    // from the unambiguous alphabet (no 0/O/o, 1/l/I). This pins both the
    // shape and the excluded characters.
    private static final Pattern SHAPE =
            Pattern.compile("[A-HJ-NP-Za-km-z2-9]{5}-[A-HJ-NP-Za-km-z2-9]{5}");

    @Test
    void generatesValueOfExpectedShapeAndAlphabet() {
        for (int i = 0; i < 1000; i++) {
            String pw = TemporaryPasswordGenerator.generate();
            assertThat(pw).matches(SHAPE);
        }
    }

    @Test
    void neverContainsAmbiguousCharacters() {
        for (int i = 0; i < 1000; i++) {
            String pw = TemporaryPasswordGenerator.generate();
            // No look-alikes that cause typos when keyed off an SMS.
            assertThat(pw).doesNotContain("0").doesNotContain("O").doesNotContain("o")
                    .doesNotContain("1").doesNotContain("l").doesNotContain("I");
        }
    }

    @Test
    void isNotTheRetiredSharedDefault() {
        for (int i = 0; i < 100; i++) {
            assertThat(TemporaryPasswordGenerator.generate()).isNotEqualTo("#Pass123");
        }
    }

    @Test
    void producesDistinctValues() {
        // 10 chars over a 56-symbol alphabet ≈ 58 bits; collisions across a few
        // thousand draws would signal a broken RNG (e.g. a seeded java.util.Random).
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            seen.add(TemporaryPasswordGenerator.generate());
        }
        assertThat(seen).hasSize(5000);
    }

    @Test
    void rawPasswordLengthIsTenCharacters() {
        // Operator-stated requirement: temp passwords MUST be exactly 10 chars
        // (hyphen is visual grouping for readability and does NOT count toward
        // the secret length).
        for (int i = 0; i < 1000; i++) {
            String pw = TemporaryPasswordGenerator.generate();
            assertThat(pw.replace("-", "")).hasSize(10);
        }
    }
}
