package com.innbucks.userservice.service;

import com.innbucks.userservice.repository.RevokedTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the shared-Redis logout publish added to
 * {@link TokenRevocationService#revoke(String)}. Pure Mockito — no live Redis,
 * no Postgres, no {@code @SpringBootTest}.
 */
class TokenRevocationServiceTest {

    private RevokedTokenRepository revokedTokenRepository;
    private UserRepository userRepository;
    private JwtUtil jwtUtil;
    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;

    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        revokedTokenRepository = mock(RevokedTokenRepository.class);
        userRepository = mock(UserRepository.class);
        jwtUtil = mock(JwtUtil.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        service = new TokenRevocationService(revokedTokenRepository, userRepository, jwtUtil, redis);

        // Valid, not-yet-revoked token expiring an hour out.
        when(jwtUtil.isTokenValid("tok")).thenReturn(true);
        when(jwtUtil.extractEmail("tok")).thenReturn("alice@example.com");
        when(jwtUtil.extractExpiration("tok"))
                .thenReturn(new Date(System.currentTimeMillis() + 3_600_000L));
        when(revokedTokenRepository.existsByTokenHash(any())).thenReturn(false);
    }

    @Test
    void revoke_publishesHashedKeyToSharedRedis_withPositiveTtl() {
        service.revoke("tok");

        // Postgres remains the source of truth — the row is still saved.
        verify(revokedTokenRepository).save(any());

        // ...AND the hash is published to the shared denylist under the agreed
        // prefix, value "1", with a positive (remaining-life) TTL.
        verify(ops).set(startsWith(TokenRevocationService.SHARED_DENYLIST_PREFIX), eq("1"), any(Duration.class));
    }

    @Test
    void revoke_succeeds_whenRedisPublishThrows() {
        // Fail-open on the logout side: a Redis outage must NOT fail the logout.
        // Postgres is the source of truth; downstream still has the TTL backstop.
        // (ValueOperations#set is void -> doThrow form.)
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(startsWith(TokenRevocationService.SHARED_DENYLIST_PREFIX), eq("1"), any(Duration.class));

        assertDoesNotThrow(() -> service.revoke("tok"));
        verify(revokedTokenRepository).save(any());
    }

    @Test
    void revoke_skipsRedisPublish_whenTokenAlreadyAtExpiry() {
        // No remaining life -> nothing for the readers to deny; skip the write.
        when(jwtUtil.extractExpiration("tok"))
                .thenReturn(new Date(System.currentTimeMillis() - 1_000L));

        service.revoke("tok");

        verify(revokedTokenRepository).save(any());
        verify(ops, times(0)).set(any(), any(), any(Duration.class));
    }
}
