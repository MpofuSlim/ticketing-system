package com.innbucks.userservice.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The 12-month retention of {@code support_access_log} (design §3.1.2), and the
 * written reason CLAUDE.md requires of a {@code @Scheduled} job that carries no
 * {@code @SchedulerLock} on N replicas.
 */
class SupportAccessLogRetentionJobTest {

    @Test
    @DisplayName("deletes rows older than 12 months, by UTC wall clock")
    void deletesOlderThanTwelveMonths() {
        SupportAccessLogRepository repo = mock(SupportAccessLogRepository.class);
        SupportAccessLogRetentionJob job = new SupportAccessLogRetentionJob(repo,
                Clock.fixed(Instant.parse("2026-09-30T02:41:00Z"), ZoneOffset.UTC));
        job.purge();
        verify(repo).deleteCreatedBefore(LocalDateTime.of(2025, 9, 30, 2, 41));
    }

    @Test
    @DisplayName("scheduled, transactional, unlocked — and the class states why that is safe on N replicas")
    void writtenReasonForNoLock() throws Exception {
        Method purge = SupportAccessLogRetentionJob.class.getMethod("purge");
        assertThat(purge.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(purge.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(Arrays.stream(purge.getAnnotations()).map(a -> a.annotationType().getSimpleName()))
                .doesNotContain("SchedulerLock");
        String source = Files.readString(Path.of(
                "src/main/java/com/innbucks/userservice/support/SupportAccessLogRetentionJob.java"));
        assertThat(source).contains("IDEMPOTENT").contains("SAFE TO RUN CONCURRENTLY");
        assertThat(SupportAccessLogRetentionJob.RETENTION_MONTHS).isEqualTo(12);
    }
}
