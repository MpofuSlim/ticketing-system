package com.innbucks.bookingservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit test of {@link TokenVersionStore}. No live Redis — the
 * {@link StringRedisTemplate} is mocked. Pins the shared key scheme
 * ({@code auth:tokenver:<userUuid>}) and the fail-open contract.
 */
class TokenVersionStoreTest {

    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";

    private ValueOperations<String, String> valueOps;
    private TokenVersionStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new TokenVersionStore(redis);
    }

    @Test
    void currentVersion_returnsStoredValue_usingSharedKeyScheme() {
        when(valueOps.get("auth:tokenver:" + USER_UUID)).thenReturn("7");

        assertEquals(Long.valueOf(7), store.currentVersion(USER_UUID));
        verify(valueOps).get("auth:tokenver:" + USER_UUID);
    }

    @Test
    void currentVersion_null_whenKeyAbsent() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertNull(store.currentVersion(USER_UUID));
    }

    @Test
    void currentVersion_null_andSkipsRedis_whenUuidNullOrBlank() {
        assertNull(store.currentVersion(null));
        assertNull(store.currentVersion("   "));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void currentVersion_failsOpen_whenValueNotNumeric() {
        when(valueOps.get(anyString())).thenReturn("not-a-number");

        assertNull(store.currentVersion(USER_UUID));
    }

    @Test
    void currentVersion_failsOpen_whenRedisThrows() {
        // Defence-in-depth on top of the short access-token TTL: a Redis blip
        // must not 401 all authenticated traffic, so treat it as "no version".
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(store.currentVersion(USER_UUID));
    }
}
