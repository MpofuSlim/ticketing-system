package com.innbucks.userservice.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationalIdHasherTest {

    private final NationalIdHasher hasher = new NationalIdHasher("test-secret");

    @Test
    void hash_matchesOpensslAndPgcryptoVector_soSqlBackfillStaysCompatible() {
        // Locks the wire format to the canonical HMAC-SHA256 hex so an operator
        // can backfill legacy plaintext rows in pure SQL with the SAME secret:
        //   printf '%s' '12345678' | openssl dgst -sha256 -hmac 'test-secret'
        //   == Postgres: encode(hmac('12345678','test-secret','sha256'),'hex')
        assertEquals(
                "hmac:58c741342a4a071b6967bd9319f9e9b7b51cc33b7cc0e17ea6a7201a9eb9995e",
                hasher.hash("12345678"));
    }

    @Test
    void hash_isDeterministic() {
        assertEquals(hasher.hash("12345678"), hasher.hash("12345678"));
    }

    @Test
    void hash_differsBySecret() {
        assertNotEquals(
                new NationalIdHasher("a-different-secret").hash("12345678"),
                hasher.hash("12345678"));
    }

    @Test
    void hash_isIdempotent_neverDoubleHashes() {
        String once = hasher.hash("12345678");
        assertEquals(once, hasher.hash(once),
                "re-hashing an already hmac:-prefixed value must be a no-op");
    }

    @Test
    void isHashed_trueForPrefixed_falseOtherwise() {
        assertTrue(hasher.isHashed("hmac:deadbeef"));
        assertFalse(hasher.isHashed("12345678"));
        assertFalse(hasher.isHashed(null));
    }

    @Test
    void hash_passesThroughNullAndBlank() {
        assertNull(hasher.hash(null));
        assertEquals("", hasher.hash(""));
        assertEquals("   ", hasher.hash("   "));
    }
}
