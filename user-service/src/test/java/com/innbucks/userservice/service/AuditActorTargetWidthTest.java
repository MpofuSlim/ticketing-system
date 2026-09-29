package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.AuditEvent;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code audit_events.actor_id} / {@code target_id} (V42) and the entity move
 * together. The actor is an administrator's email on every admin action, so a
 * width under 254 (RFC 5321) makes the insert fail — and the audit write is
 * fail-open, so the row is lost silently. The Postgres-backed ITs prove the
 * migration applies; this pins the two halves to each other without Docker.
 */
class AuditActorTargetWidthTest {

    private static final Path MIGRATIONS = Paths.get("src", "main", "resources", "db", "migration");

    @Test
    void entityColumnsAreWideEnoughForAnyEmail() throws NoSuchFieldException {
        assertThat(AuditEvent.class.getDeclaredField("actorId").getAnnotation(Column.class).length())
                .isEqualTo(254);
        assertThat(AuditEvent.class.getDeclaredField("targetId").getAnnotation(Column.class).length())
                .isEqualTo(254);
    }

    @Test
    void v42WidensBothColumns_failingFastRatherThanQueueingBehindAuditWriters() throws IOException {
        Path v42 = MIGRATIONS.resolve("V42__audit_actor_target_width.sql");
        String sql = Files.readString(v42);

        int lockTimeout = sql.indexOf("SET LOCAL lock_timeout");
        int firstAlter = sql.indexOf("ALTER TABLE audit_events");
        assertThat(lockTimeout).as("lock_timeout must be set").isGreaterThanOrEqualTo(0);
        assertThat(firstAlter).isGreaterThan(lockTimeout);
        assertThat(sql).contains("ALTER COLUMN actor_id  TYPE VARCHAR(254)")
                .contains("ALTER COLUMN target_id TYPE VARCHAR(254)");
    }

    @Test
    void v42IsTheOnlyMigrationClaimingThatVersion() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("V42__")).toList())
                    .containsExactly("V42__audit_actor_target_width.sql");
        }
    }
}
