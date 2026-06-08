package com.innbucks.eventservice.scheduler;

import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventExpirySchedulerTest {

    @Test
    void expirePassedEvents_callsDeactivateWithCurrentUtcInstant() {
        EventRepository repo = mock(EventRepository.class);
        when(repo.deactivateExpiredEvents(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        new EventExpiryScheduler(repo).expirePassedEvents();
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> updated = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo, times(1)).deactivateExpiredEvents(cutoff.capture(), updated.capture());

        // Both args should be the same "now" instant in UTC, bracketed by our
        // before/after probes — pins that the scheduler uses LocalDateTime.now(UTC)
        // and not the bare LocalDateTime.now() that CLAUDE.md forbids.
        assertThat(cutoff.getValue()).isBetween(before, after);
        assertThat(updated.getValue()).isEqualTo(cutoff.getValue());
    }

    @Test
    void expirePassedEvents_zeroExpired_isNoOpLog_butStillCalls() {
        EventRepository repo = mock(EventRepository.class);
        when(repo.deactivateExpiredEvents(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        new EventExpiryScheduler(repo).expirePassedEvents();

        verify(repo, times(1)).deactivateExpiredEvents(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
