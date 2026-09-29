package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The single writer of {@code users.token_version}. Pure Mockito: the SQL runs
 * against Postgres in {@code ConcurrentLoginAndDeactivateIT}; here we pin that
 * each method takes the value the database RETURNS (never computes one), copies
 * it onto the entity, publishes it after commit, and refuses to run outside a
 * transaction.
 */
class TokenVersionBumperTest {

    private UserRepository users;
    private TokenVersionPublisher publisher;
    private TokenVersionBumper bumper;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        publisher = mock(TokenVersionPublisher.class);
        bumper = new TokenVersionBumper(users, publisher);
        user = User.builder().id(7L).userUuid(UUID.randomUUID()).active(true).tokenVersion(3L).build();
    }

    @Test
    void bump_takesTheDatabasesValue_notEntityPlusOne() {
        // A concurrent bump already took it to 4; ours lands at 5. Computing
        // entity+1 would have written 4 again — the lost update this replaces.
        when(users.incrementTokenVersion(7L)).thenReturn(5L);

        assertThat(bumper.bump(user)).isEqualTo(5L);
        assertThat(user.getTokenVersion()).isEqualTo(5L);
        verify(publisher).publishAfterCommit(user.getUserUuid(), 5L);
        verify(publisher, never()).publish(user.getUserUuid(), 5L);
    }

    @Test
    void bump_onAVanishedRow_failsLoudly_andPublishesNothing() {
        when(users.incrementTokenVersion(7L)).thenReturn(null);

        assertThatThrownBy(() -> bumper.bump(user)).isInstanceOf(IllegalStateException.class);
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        verifyNoInteractions(publisher);
    }

    @Test
    void bumpIfActive_whenAConcurrentDeactivationCommitted_changesNothing() {
        when(users.incrementTokenVersionIfActive(7L)).thenReturn(null);

        assertThat(bumper.bumpIfActive(user)).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        verifyNoInteractions(publisher);
    }

    @Test
    void bumpIfActive_success() {
        when(users.incrementTokenVersionIfActive(7L)).thenReturn(4L);

        assertThat(bumper.bumpIfActive(user)).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(4L);
        verify(publisher).publishAfterCommit(user.getUserUuid(), 4L);
    }

    @Test
    void bumpIfCurrent_isACompareAndSetAgainstTheBoundVersion() {
        when(users.incrementTokenVersionIfCurrent(7L, 3L)).thenReturn(4L);
        when(users.incrementTokenVersionIfCurrent(7L, 2L)).thenReturn(null);

        assertThat(bumper.bumpIfCurrent(user, 2L)).as("stale: spent or superseded").isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        assertThat(bumper.bumpIfCurrent(user, 3L)).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(4L);
        verify(publisher).publishAfterCommit(user.getUserUuid(), 4L);
    }

    @Test
    void deactivateAndBump_isOneStatement_andUpdatesTheEntity() {
        when(users.deactivateAndIncrementTokenVersion(7L)).thenReturn(4L);

        assertThat(bumper.deactivateAndBump(user)).isEqualTo(4L);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(4L);
        verify(publisher).publishAfterCommit(user.getUserUuid(), 4L);
        verify(users, never()).incrementTokenVersion(anyLong());
    }

    @Test
    void everyBumpIsMandatoryTransactional() {
        // A bump is always part of a larger change and must commit or roll back
        // with it; outside a transaction the after-commit publish would also be
        // meaningless. MANDATORY makes a stray call fail instead of committing alone.
        for (Method m : TokenVersionBumper.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as(m.getName()).isNotNull();
            assertThat(tx.propagation()).as(m.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }

    @Test
    void theRepositoryWritersAreAtomicIncrementsReturningTheNewValue() {
        // The whole point: the database does the +1 under the row lock.
        for (String name : Arrays.asList("incrementTokenVersion", "incrementTokenVersionIfActive",
                "incrementTokenVersionIfCurrent", "deactivateAndIncrementTokenVersion")) {
            Method m = Arrays.stream(UserRepository.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            Query q = m.getAnnotation(Query.class);
            assertThat(q).as(name).isNotNull();
            assertThat(q.nativeQuery()).as(name).isTrue();
            assertThat(q.value()).as(name)
                    .contains("token_version = token_version + 1")
                    .contains("RETURNING token_version");
        }
    }
}
