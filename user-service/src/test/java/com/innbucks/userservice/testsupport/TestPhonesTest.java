package com.innbucks.userservice.testsupport;

import com.innbucks.userservice.util.MsisdnValidator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPhonesTest {

    @Test
    void neverHandsOutTheSameNumberTwice_andEveryOneIsAValidZimbabweMobile() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String phone = TestPhones.unique();
            assertTrue(phone.matches("\\+263778\\d{6}"), phone);
            assertTrue(seen.add(phone), "repeated " + phone);
        }
        String sample = TestPhones.unique();
        assertTrue(MsisdnValidator.isValid(sample, "ZW"), sample);
        assertEquals(sample, MsisdnValidator.normalizeToE164(sample, "ZW").orElseThrow());
    }
}
