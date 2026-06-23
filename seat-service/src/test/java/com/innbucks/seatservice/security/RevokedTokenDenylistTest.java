package com.innbucks.seatservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit test of {@link RevokedTokenDenylist}. No live Redis — the
 * {@link StringRedisTemplate} is mocked.
 */
class RevokedTokenDenylistTest {

    private StringRedisTemplate redis;
    private RevokedTokenDenylist denylist;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        denylist = new RevokedTokenDenylist(redis);
    }

    @Test
    void isRevoked_true_whenHashPresent() {
        when(redis.hasKey(startsWith("auth:revoked:"))).thenReturn(true);

        assertTrue(denylist.isRevoked("tok"));
        // The lookup key is the agreed prefix + a 64-hex-char SHA-256 of the token.
        verify(redis).hasKey("auth:revoked:" + sha256HexLower("tok"));
    }

    @Test
    void isRevoked_false_whenHashAbsent() {
        when(redis.hasKey(startsWith("auth:revoked:"))).thenReturn(false);

        assertFalse(denylist.isRevoked("tok"));
    }

    @Test
    void isRevoked_false_whenHasKeyReturnsNull() {
        // RedisTemplate#hasKey can return null outside a transaction/pipeline.
        when(redis.hasKey(startsWith("auth:revoked:"))).thenReturn(null);

        assertFalse(denylist.isRevoked("tok"));
    }

    @Test
    void isRevoked_failsOpen_whenRedisThrows() {
        // Defence-in-depth on top of the short access-token TTL: a Redis blip
        // must not 401 all authenticated traffic, so treat it as "not revoked".
        when(redis.hasKey(startsWith("auth:revoked:")))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertFalse(denylist.isRevoked("tok"));
    }

    /** Independent re-implementation of the writer's hash, to pin the key scheme. */
    private static String sha256HexLower(String token) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(d.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
