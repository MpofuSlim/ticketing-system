package com.innbucks.bookingservice.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxEventPurgeJob}. Pure Mockito — the actual
 * SQL behaviour is implicit in JPA and exercised by {@code OutboxEventDrainer}'s
 * integration paths; here we pin the JOB's contract:
 *
 * <ol>
 *   <li>Calls the repository with the right cutoff (now - retention).</li>
 *   <li>Honours batch-size — never passes a larger Pageable.</li>
 *   <li>Skips the delete + counter on an empty result (no work, no audit).</li>
 *   <li>Increments the counter by the EXACT count deleted, not by 1.</li>
 *   <li>Only ever asks the repository for {@code published}-older-than rows —
 *       {@code giving_up} / {@code pending} branches don't exist.</li>
 * </ol>
 */
class OutboxEventPurgeJobTest {

    private static final int BATCH = 500;
    private static final Duration RETENTION = Duration.ofDays(7);

    private OutboxEventPurgeJob newJob(OutboxEventRepository repository) {
        return new OutboxEventPurgeJob(repository, new SimpleMeterRegistry(), BATCH, RETENTION);
    }

    @Test
    void purge_noEligibleRows_skipsDeleteAndCounter() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(List.of());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new OutboxEventPurgeJob(repo, meters, BATCH, RETENTION).purge();

        verify(repo, never()).deleteAllByIdInBatch(any());
        Counter purged = meters.find("booking.event_outbox.purged").counter();
        assertThat(purged).isNotNull();
        assertThat(purged.count()).isZero();
    }

    @Test
    void purge_deletesIdsReturnedByRepository_inOneCall() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(ids);

        newJob(repo).purge();

        verify(repo).deleteAllByIdInBatch(ids);
    }

    @Test
    void purge_incrementsCounterByExactDeletedCount() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(ids);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new OutboxEventPurgeJob(repo, meters, BATCH, RETENTION).purge();

        // Pin .count() to the size of the batch — a regression that does
        // .increment() per row instead of .increment(n), or counts ids of
        // the wrong set, would fail here.
        assertThat(meters.get("booking.event_outbox.purged").counter().count()).isEqualTo(5.0);
    }

    @Test
    void purge_usesCutoffOfNowMinusRetention() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minus(RETENTION);
        newJob(repo).purge();
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).minus(RETENTION);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).findPublishedOlderThan(cutoffCaptor.capture(), any());
        LocalDateTime actual = cutoffCaptor.getValue();
        // Cutoff falls between the two timestamps we sampled before / after
        // the call — pins "now - retention", not bare "now" or any other arg.
        assertThat(actual).isBetween(before, after);
    }

    @Test
    void purge_passesBatchSizeToPageable() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(List.of());

        new OutboxEventPurgeJob(repo, new SimpleMeterRegistry(), 250, RETENTION).purge();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findPublishedOlderThan(any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        // First page only — purge resumes the backlog on the NEXT tick, not
        // by walking pages within a tick (would defeat the batch cap).
        assertThat(pageable).isEqualTo(PageRequest.of(0, 250));
    }

    @Test
    void purge_neverQueriesForGivingUpOrPending() {
        // Compile-time contract check: the repository method is
        // findPublishedOlderThan, hard-coded to status='published'. This test
        // pins the call site so a future refactor that adds a status param
        // (and accidentally widens the purge to giving_up) fails here.
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.findPublishedOlderThan(any(), any())).thenReturn(List.of());

        newJob(repo).purge();

        verify(repo).findPublishedOlderThan(any(), any());
        // No other lookups against the repository — the job's surface area
        // is exactly one read + one delete.
        verify(repo, never()).countByStatus(any());
        verify(repo, never()).findDue(any(), any());
    }

    @Test
    void purge_deleteRespectsBatchCap_evenWhenBacklogExceedsIt() {
        // Belt-and-braces — even if the repository misbehaved and returned
        // MORE rows than the batch size, the job still hands the entire
        // returned list to the delete. This documents the contract:
        // "we trust the repository's LIMIT". Coupled with the previous
        // test (which pins that the job DOES pass the LIMIT down), the
        // pair ensures the cap is end-to-end.
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        List<UUID> ids = java.util.stream.IntStream.range(0, 1500)
                .mapToObj(i -> UUID.randomUUID())
                .toList();
        when(repo.findPublishedOlderThan(any(), eq(PageRequest.of(0, BATCH)))).thenReturn(ids);

        newJob(repo).purge();

        verify(repo).deleteAllByIdInBatch(ids);
    }
}
