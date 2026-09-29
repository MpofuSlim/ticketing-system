package com.innbucks.userservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TokenVersionPublisher} — the A07 / CWE-613 shared-Redis
 * publish side. Pure Mockito, no live Redis, no {@code @SpringBootTest}. Pins the
 * exact wire contract downstream services read:
 * {@code auth:tokenver:<userUuid> -> "<version>"}, written through the
 * never-lower script. What the script itself does to a real Redis — two
 * publishes landing in the reverse order of their commits among them — is
 * {@code TokenVersionPublisherRedisIT}.
 */
class TokenVersionPublisherTest {

    /** 1 day, in millis — the value the test/it profiles pin jwt.refresh-expiration to. */
    private static final long REFRESH_TTL_MS = 86_400_000L;

    private StringRedisTemplate redis;
    private TokenVersionPublisher publisher;

    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyScript() {
        return any(RedisScript.class);
    }

    /** The one call a publish makes: the script, the key, the version and the TTL in ms. */
    private void verifyPublished(UUID uuid, String version, String ttlMs) {
        verify(redis).execute(anyScript(),
                eq(List.of(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + uuid)),
                eq(version), eq(ttlMs));
    }

    private void verifyNothingPublished() {
        verify(redis, never()).execute(anyScript(), anyList(), anyString(), anyString());
    }

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);

        publisher = new TokenVersionPublisher(redis);
        // Field-injected @Value in production; set it directly here.
        ReflectionTestUtils.setField(publisher, "refreshExpirationMs", REFRESH_TTL_MS);
    }

    @Test
    void publish_writesCanonicalKeyValue_withRefreshLifetimeTtl() {
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 8L);

        // Key = prefix + canonical hyphenated-lowercase UUID (== the JWT userUuid
        // claim); value = the version as a decimal String; TTL = refresh lifetime.
        verifyPublished(uuid, "8", Long.toString(REFRESH_TTL_MS));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publish_goesThroughTheNeverLowerScript() {
        // A plain SET let an out-of-order after-commit publish move Redis
        // BACKWARDS (a login's v+1 landing after a deactivation's v+2).
        publisher.publish(UUID.randomUUID(), 8L);

        org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), anyList(), anyString(), anyString());
        assertEquals(TokenVersionPublisher.PUBLISH_IF_NOT_OLDER_LUA, script.getValue().getScriptAsString());
        assertEquals(Long.class, script.getValue().getResultType());
        String lua = script.getValue().getScriptAsString();
        org.junit.jupiter.api.Assertions.assertTrue(lua.contains(">= current"),
                "must refuse to replace a HIGHER stored version");
        org.junit.jupiter.api.Assertions.assertTrue(lua.contains("'PX', ARGV[2]"), "must keep the TTL");
    }

    @Test
    void publish_supersededByANewerVersion_isNotAFailure() {
        // The script answers 0 when a newer version is already there: an
        // out-of-order callback, working as designed — not an alertable failure.
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        publisher.setMeterRegistry(registry);
        when(redis.execute(anyScript(), anyList(), anyString(), anyString())).thenReturn(0L);

        assertDoesNotThrow(() -> publisher.publish(UUID.randomUUID(), 3L));

        assertEquals(0.0, registry.counter(TokenVersionPublisher.PUBLISH_FAILED_METRIC).count());
    }

    @Test
    void publish_isNoOp_whenUuidIsNull() {
        // Legacy tokens carry no userUuid claim -> nothing downstream can key on,
        // so we must not write anything (downstream fails open for them).
        publisher.publish(null, 5L);

        verifyNoInteractions(redis);
    }

    @Test
    void publish_failsOpen_whenRedisThrows() {
        // A Redis outage must NOT propagate — Postgres (users.token_version) is the
        // source of truth for user-service's own JwtFilter; downstream keeps the
        // access-token TTL backstop.
        when(redis.execute(anyScript(), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> publisher.publish(UUID.randomUUID(), 3L));
    }

    @Test
    void publish_usesFallbackTtl_whenRefreshLifetimeMisconfiguredNonPositive() {
        // Guard: a 0/negative refresh lifetime must not lead to a SET with a
        // non-positive expiry (which Redis rejects) — fall back to 30 days.
        ReflectionTestUtils.setField(publisher, "refreshExpirationMs", 0L);
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 1L);

        verifyPublished(uuid, "1", Long.toString(java.time.Duration.ofDays(30).toMillis()));
    }

    // ---- publish AFTER COMMIT (1a) ------------------------------------------

    @Test
    void publishAfterCommit_waitsForTheCommit() {
        UUID uuid = UUID.randomUUID();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(uuid, 9L);

            // Nothing reaches Redis while the transaction is still open…
            verifyNothingPublished();
            java.util.List<org.springframework.transaction.support.TransactionSynchronization> syncs =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            org.junit.jupiter.api.Assertions.assertEquals(1, syncs.size());

            // …and the version lands the moment it commits.
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verifyPublished(uuid, "9", Long.toString(REFRESH_TTL_MS));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishAfterCommit_aRolledBackBumpPublishesNothing() {
        // setRoles used to publish INSIDE its transaction, so a rollback left
        // Redis ahead of Postgres and every downstream service rejecting tokens
        // user-service still accepted.
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(UUID.randomUUID(), 9L);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNothingPublished();
    }

    @Test
    void publishAfterCommit_withNoTransaction_publishesImmediately() {
        UUID uuid = UUID.randomUUID();

        publisher.publishAfterCommit(uuid, 2L);

        verifyPublished(uuid, "2", Long.toString(REFRESH_TTL_MS));
    }

    @Test
    void aFailedPublish_isCounted_forTheAlert() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        publisher.setMeterRegistry(registry);
        // Registered at ZERO before any failure: increase() cannot see a series'
        // first sample, so a counter born at 1 would hide the first failure.
        io.micrometer.core.instrument.Counter counter =
                registry.find(TokenVersionPublisher.PUBLISH_FAILED_METRIC).counter();
        assertNotNull(counter, "the alerted counter must exist before the first failure");
        assertEquals(0.0, counter.count());
        when(redis.execute(anyScript(), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        publisher.publish(UUID.randomUUID(), 3L);
        publisher.publishAfterCommit(UUID.randomUUID(), 4L);

        org.junit.jupiter.api.Assertions.assertEquals(2.0,
                registry.counter(TokenVersionPublisher.PUBLISH_FAILED_METRIC).count());
        org.junit.jupiter.api.Assertions.assertEquals("user.tokenver.publish_failed",
                TokenVersionPublisher.PUBLISH_FAILED_METRIC);
    }

    // ---- bulk publish (a role losing a PLATFORM code) -----------------------

    /** Runs each pipelined callback against a mocked connection and records every EVAL it sends. */
    @SuppressWarnings("unchecked")
    private org.springframework.data.redis.connection.RedisScriptingCommands pipelineRecorder(int[] pipelines) {
        org.springframework.data.redis.connection.RedisConnection connection =
                mock(org.springframework.data.redis.connection.RedisConnection.class);
        org.springframework.data.redis.connection.RedisScriptingCommands scripting =
                mock(org.springframework.data.redis.connection.RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripting);
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenAnswer(inv -> {
                    pipelines[0]++;
                    Object result = ((org.springframework.data.redis.core.RedisCallback<Object>) inv.getArgument(0))
                            .doInRedis(connection);
                    org.junit.jupiter.api.Assertions.assertNull(result, "a pipelined callback must return null");
                    return List.of();
                });
        return scripting;
    }

    private static java.util.Map<UUID, Long> holders(int n) {
        java.util.Map<UUID, Long> versions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) versions.put(UUID.randomUUID(), 5L + i);
        return versions;
    }

    @Test
    void publishAll_pipelinesTheNeverLowerScript_oneRoundTripPerBatch() {
        int[] pipelines = {0};
        var scripting = pipelineRecorder(pipelines);
        java.util.Map<UUID, Long> versions = holders(TokenVersionPublisher.PIPELINE_BATCH * 2 + 1);

        publisher.publishAll(versions);

        org.junit.jupiter.api.Assertions.assertEquals(3, pipelines[0], "1001 holders = three round trips");
        verify(scripting, org.mockito.Mockito.times(versions.size())).eval(any(byte[].class),
                eq(org.springframework.data.redis.connection.ReturnType.INTEGER), eq(1),
                any(byte[].class), any(byte[].class), any(byte[].class));
        // The exact wire contract for one of them: the same script, key, value and TTL as publish().
        var first = versions.entrySet().iterator().next();
        verify(scripting).eval(
                eq(TokenVersionPublisher.PUBLISH_IF_NOT_OLDER_LUA.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eq(org.springframework.data.redis.connection.ReturnType.INTEGER), eq(1),
                eq((TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + first.getKey())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eq(Long.toString(first.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eq(Long.toString(REFRESH_TTL_MS).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verifyNothingPublished();
    }

    @Test
    void publishAllAfterCommit_isOneSynchronization_andWaitsForTheCommit() {
        int[] pipelines = {0};
        pipelineRecorder(pipelines);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAllAfterCommit(holders(3));

            org.junit.jupiter.api.Assertions.assertEquals(0, pipelines[0]);
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            org.junit.jupiter.api.Assertions.assertEquals(1, syncs.size(), "one callback for the whole set");

            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            org.junit.jupiter.api.Assertions.assertEquals(1, pipelines[0]);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishAllAfterCommit_aRolledBackBumpPublishesNothing() {
        int[] pipelines = {0};
        pipelineRecorder(pipelines);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAllAfterCommit(holders(3));
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, pipelines[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishAll_aFailedBatch_isCountedPerVersion_andNeverThrows() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        publisher.setMeterRegistry(registry);
        when(redis.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(List.of());

        // Two batches: the first fails, the second still goes.
        assertDoesNotThrow(() -> publisher.publishAll(holders(TokenVersionPublisher.PIPELINE_BATCH + 7)));

        verify(redis, org.mockito.Mockito.times(2))
                .executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
        assertEquals((double) TokenVersionPublisher.PIPELINE_BATCH,
                registry.counter(TokenVersionPublisher.PUBLISH_FAILED_METRIC).count());
    }

    @Test
    void publishAll_skipsNullUuids_andAnEmptySetTouchesNothing() {
        java.util.Map<UUID, Long> versions = new java.util.HashMap<>();
        versions.put(null, 3L);
        publisher.publishAll(versions);
        publisher.publishAllAfterCommit(java.util.Map.of());

        verifyNoInteractions(redis);
    }
}
