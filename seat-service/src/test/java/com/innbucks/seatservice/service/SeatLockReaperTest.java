package com.innbucks.seatservice.service;

import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SeatLockReaperTest {

    private SeatLockReaper newReaper(SeatRepository repo, SeatService service) {
        SeatLockReaper reaper = new SeatLockReaper(repo, service);
        ReflectionTestUtils.setField(reaper, "batchSize", 200);
        return reaper;
    }

    @Test
    void reap_doesNothing_whenNoExpiredLocks() {
        SeatRepository repo = mock(SeatRepository.class);
        SeatService service = mock(SeatService.class);
        when(repo.findExpiredLockIds(any(), any(Pageable.class))).thenReturn(List.of());

        newReaper(repo, service).reap();

        verify(service, never()).releaseStaleLock(any());
    }

    @Test
    void reap_callsReleaseStaleLock_forEveryCandidate() {
        SeatRepository repo = mock(SeatRepository.class);
        SeatService service = mock(SeatService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(repo.findExpiredLockIds(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(a, b, c));
        when(service.releaseStaleLock(any())).thenReturn(true, false, true);

        newReaper(repo, service).reap();

        verify(service).releaseStaleLock(a);
        verify(service).releaseStaleLock(b);
        verify(service).releaseStaleLock(c);
    }

    @Test
    void reap_continuesBatch_whenOneSeatThrows() {
        SeatRepository repo = mock(SeatRepository.class);
        SeatService service = mock(SeatService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findExpiredLockIds(any(), any(Pageable.class))).thenReturn(List.of(a, b));
        when(service.releaseStaleLock(a)).thenThrow(new RuntimeException("row locked"));
        when(service.releaseStaleLock(b)).thenReturn(true);

        // Must not throw — one bad row cannot kill the batch.
        newReaper(repo, service).reap();

        verify(service).releaseStaleLock(a);
        verify(service).releaseStaleLock(b);
    }

    @Test
    void reap_passesConfiguredBatchSizeToQuery() {
        SeatRepository repo = mock(SeatRepository.class);
        SeatService service = mock(SeatService.class);
        when(repo.findExpiredLockIds(any(), any(Pageable.class))).thenReturn(List.of());
        SeatLockReaper reaper = new SeatLockReaper(repo, service);
        ReflectionTestUtils.setField(reaper, "batchSize", 50);

        reaper.reap();

        verify(repo).findExpiredLockIds(any(), argThat((Pageable p) -> p.getPageSize() == 50));
    }
}
