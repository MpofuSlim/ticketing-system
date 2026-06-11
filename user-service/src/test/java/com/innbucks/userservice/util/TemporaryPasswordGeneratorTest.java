package com.innbucks.userservice.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    // 3 groups of 4, hyphen-separated, drawn ONLY from the unambiguous alphabet
    // (no 0/O/o, 1/l/I). This pins both the shape and the excluded characters.
    private static final Pattern SHAPE =
            Pattern.compile("[A-HJ-NP-Za-km-z2-9]{4}-[A-HJ-NP-Za-km-z2-9]{4}-[A-HJ-NP-Za-km-z2-9]{4}");

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
        // 12 chars over a 56-symbol alphabet ≈ 70 bits; collisions across a few
        // thousand draws would signal a broken RNG (e.g. a seeded java.util.Random).
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            seen.add(TemporaryPasswordGenerator.generate());
        }
        assertThat(seen).hasSize(5000);
    }
}
