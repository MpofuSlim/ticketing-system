package com.innbucks.userservice.devicesecurity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

class DeviceIdentityTest {

    @Test
    void destinationMasked_showsCountryFirstTwoAndLastTwo() {
        assertThat(DeviceIdentity.destinationMasked("+263771234512")).isEqualTo("+263 77 *** **12");
        assertThat(DeviceIdentity.destinationMasked("+254712345678")).isEqualTo("+254 71 *** **78");
    }

    @Test
    void logMask_keepsOnlyTheLastTwoDigits() {
        assertThat(DeviceIdentity.logMask("+263771234512")).isEqualTo("********12");
        assertThat(DeviceIdentity.logMask(null)).isEqualTo("**");
    }

    @Test
    void installIdHash_isStableAndCaseInsensitive() {
        String a = DeviceIdentity.hashInstallId("8B1E6C0E-4F2A-4D8E-9B3C-1A2B3C4D5E6F");
        assertThat(a).hasSize(64).isEqualTo(DeviceIdentity.hashInstallId(" 8b1e6c0e-4f2a-4d8e-9b3c-1a2b3c4d5e6f "));
        assertThat(a).doesNotContain("8b1e");
    }

    @Test
    void labels() {
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F", "android")).isEqualTo("Samsung SM-A155F");
        assertThat(DeviceIdentity.shortLabel("Apple", "iPhone 15", "ios")).isEqualTo("Apple iPhone 15");
        assertThat(DeviceIdentity.shortLabel("TECNO", "TECNO KL4", "android")).isEqualTo("TECNO KL4");
        assertThat(DeviceIdentity.shortLabel(null, null, "android")).isEqualTo("Android phone");
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F", "android", "15")).isEqualTo("Samsung SM-A155F · Android 15");
    }

    @Test
    void supportRefs_areReadableAndForgiving() {
        String ref = SupportRefs.next();
        assertThat(ref).matches("SEC-[0-9A-HJKMNP-TV-Z]{6}");
        assertThat(SupportRefs.normalise("sec-8f2kq7")).isEqualTo("SEC-8F2KQ7");
        assertThat(SupportRefs.normalise("8F2KQ7")).isEqualTo("SEC-8F2KQ7");
        assertThat(SupportRefs.normalise("SEC 8F2KQO")).isEqualTo("SEC-8F2KQ0");
    }

    @Test
    void integrityThreats_recogniseFreeRaspNames() {
        assertThat(IntegrityThreat.classify(java.util.List.of("privilegedAccess", "hooks", "appIntegrity",
                "automation", "malware", "simulator", "debug", "unofficialStore", "somethingNew")))
                .containsExactlyInAnyOrder(IntegrityThreat.values());
    }

    // ---- A label is the app's text, so it is forced onto one line --------------------------

    /**
     * What a label must never contain: a control character, an invisible format character
     * (bidi overrides and isolates, zero-width characters), a line or a paragraph separator.
     */
    private static final Pattern BREAKER = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    /**
     * Every character the app could slip into a model, maker, platform or OS version to start
     * a new line in a WhatsApp notice or a *569# menu: LF, CRLF, TAB, NEL (U+0085), the Unicode
     * line and paragraph separators, and other C0/C1 controls — or to make WhatsApp draw the rest
     * of the notice reversed or hidden: bidi overrides and isolates, zero-width characters.
     */
    static Stream<Arguments> breakers() {
        return Stream.of(
                Arguments.of(named("LF", "\n")),
                Arguments.of(named("CR LF", "\r\n")),
                Arguments.of(named("TAB", "\t")),
                Arguments.of(named("NEL U+0085", "\u0085")),
                Arguments.of(named("LINE SEPARATOR U+2028", " ")),
                Arguments.of(named("PARAGRAPH SEPARATOR U+2029", " ")),
                Arguments.of(named("VT", "\013")),
                Arguments.of(named("FF", "\f")),
                Arguments.of(named("ESC", "\u001B")),
                Arguments.of(named("DEL", "\u007F")),
                Arguments.of(named("CSI U+009B", "\u009B")),
                Arguments.of(named("RIGHT-TO-LEFT OVERRIDE U+202E", "\u202E")),
                Arguments.of(named("LEFT-TO-RIGHT ISOLATE U+2066", "\u2066")),
                Arguments.of(named("POP DIRECTIONAL ISOLATE U+2069", "\u2069")),
                Arguments.of(named("ZERO WIDTH SPACE U+200B", "\u200B")),
                Arguments.of(named("BYTE ORDER MARK U+FEFF", "\uFEFF")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("breakers")
    @DisplayName("a line break or control character in the model, maker, platform or OS version becomes ONE space "
            + "(runs collapse, ends trim): a crafted model cannot start a second line of a notice or a *569# menu")
    void labels_neverCarryALineBreak(String breaker) {
        // In the model: the injected words stay (they are the phone's own text), on the same line.
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F" + breaker + "Call 0771 now", "android"))
                .isEqualTo("Samsung SM-A155F Call 0771 now");
        // A run of them, mixed with ordinary spaces, still collapses to one space.
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F" + breaker + breaker + " " + breaker
                + "Call 0771 now", "android")).isEqualTo("Samsung SM-A155F Call 0771 now");
        // Leading and trailing ones are trimmed away like ordinary spaces.
        assertThat(DeviceIdentity.shortLabel("samsung", breaker + "SM-A155F" + breaker, "android"))
                .isEqualTo("Samsung SM-A155F");
        // In the maker: inside it becomes a space, around it it disappears.
        assertThat(DeviceIdentity.shortLabel("Infinix" + breaker + "Mobility", "X6525", "android"))
                .isEqualTo("Infinix Mobility X6525");
        assertThat(DeviceIdentity.shortLabel(breaker + "samsung" + breaker, "SM-A155F", "android"))
                .isEqualTo("Samsung SM-A155F");
        // In the OS version (the "Your devices" label).
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F", "android", "15" + breaker + "Call now"))
                .isEqualTo("Samsung SM-A155F · Android 15 Call now");
        // In the platform.
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F", "android" + breaker, "15"))
                .isEqualTo("Samsung SM-A155F · Android 15");
        assertThat(DeviceIdentity.shortLabel(null, null, breaker + "ios" + breaker)).isEqualTo("iOS phone");

        // Whatever the field, nothing that breaks a line survives in either label.
        String everywhere = "a" + breaker + "b";
        assertThat(DeviceIdentity.shortLabel(everywhere, everywhere, everywhere)).doesNotContainPattern(BREAKER);
        assertThat(DeviceIdentity.fullLabel(everywhere, everywhere, everywhere, everywhere))
                .doesNotContainPattern(BREAKER);
        assertThat(DeviceIdentity.shortLabel(null, null, everywhere)).doesNotContainPattern(BREAKER);
    }

    @Test
    @DisplayName("exact labels for injected line breaks: LF and CRLF in the model, NEL in the maker, U+2029 with "
            + "TAB+LF in the model, U+2028 and CRLF+TAB in the OS version")
    void labels_lineBreaks_exactOutputs() {
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F\nCall 0771 now", "android"))
                .isEqualTo("Samsung SM-A155F Call 0771 now");
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F\r\nFake line", "android"))
                .isEqualTo("Samsung SM-A155F Fake line");
        assertThat(DeviceIdentity.shortLabel("samsung\u0085", "Galaxy A15 \t\n 5G", "android"))
                .isEqualTo("Samsung Galaxy A15 5G");
        assertThat(DeviceIdentity.fullLabel("Apple", "iPhone 15 Dial *569#", "ios", "18.1\r\n\tCall now"))
                .isEqualTo("Apple iPhone 15 Dial *569# · iOS 18.1 Call now");
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F\n", "android", "15 Call now"))
                .isEqualTo("Samsung SM-A155F · Android 15 Call now");
    }

    @Test
    @DisplayName("a clean label is unchanged by the one-line rule: the same text as before, typography and "
            + "non-ASCII letters included")
    void labels_cleanTextIsUnchanged() {
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F", "android")).isEqualTo("Samsung SM-A155F");
        assertThat(DeviceIdentity.shortLabel("Apple", "iPhone 15", "ios")).isEqualTo("Apple iPhone 15");
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F", "android", "15"))
                .isEqualTo("Samsung SM-A155F · Android 15");
        assertThat(DeviceIdentity.fullLabel("Xiaomi", "Redmi Note 13 Pro+", "android", "14 (UKQ1)"))
                .isEqualTo("Xiaomi Redmi Note 13 Pro+ · Android 14 (UKQ1)");
        assertThat(DeviceIdentity.shortLabel("Téléphone", "Écran X", "android")).isEqualTo("Téléphone Écran X");

        for (String clean : new String[]{"Samsung SM-A155F", "Samsung SM-A155F · Android 15", "Apple iPhone 15",
                "Téléphone Écran X", "Redmi Note 13 Pro+"}) {
            assertThat(DeviceIdentity.oneLine(clean)).as(clean).isEqualTo(clean);
        }
        assertThat(DeviceIdentity.oneLine(null)).isNull();
    }
}
