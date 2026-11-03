package com.innbucks.bookingservice.answers;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Buyers' answers are AES-GCM-256 at rest (V32); this pins the layout and the key rules. */
class BookingAnswersCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
            29, 30, 31, 32});

    @Test
    void sealsAndOpens_withAFreshIvEachTime_andNoPlaintextInTheColumn() {
        BookingAnswersCipher cipher = new BookingAnswersCipher(KEY);
        String plain = "[{\"label\":\"Medical notes\",\"value\":\"Asthma\"}]";

        String first = cipher.encrypt(plain);
        String second = cipher.encrypt(plain);

        assertTrue(first.startsWith("v1:"));
        assertNotEquals(first, second, "a fresh IV per seal");
        assertFalse(first.contains("Asthma"));
        assertEquals(plain, cipher.decrypt(first));
        assertEquals(plain, cipher.decrypt(second));
    }

    @Test
    void anotherKey_orATamperedValue_doesNotOpen() {
        String sealed = new BookingAnswersCipher(KEY).encrypt("secret");

        assertThrows(IllegalStateException.class, () -> new BookingAnswersCipher(OTHER_KEY).decrypt(sealed));
        String tampered = sealed.substring(0, sealed.length() - 4) + "AAAA";
        assertThrows(IllegalStateException.class, () -> new BookingAnswersCipher(KEY).decrypt(tampered));
        assertThrows(IllegalStateException.class, () -> new BookingAnswersCipher(KEY).decrypt("plain text"));
    }

    @Test
    void aMalformedKeyFailsTheBoot_aBlankOneIsAThrowawayForDevAndTests() {
        assertThrows(IllegalStateException.class, () -> new BookingAnswersCipher("not base64 !!"));
        assertThrows(IllegalStateException.class,
                () -> new BookingAnswersCipher(Base64.getEncoder().encodeToString(new byte[16])));

        BookingAnswersCipher throwaway = new BookingAnswersCipher("");
        assertEquals("hello", throwaway.decrypt(throwaway.encrypt("hello")));
        assertThrows(IllegalStateException.class,
                () -> new BookingAnswersCipher(" ").decrypt(throwaway.encrypt("hello")),
                "each throwaway key is its own");
    }
}
