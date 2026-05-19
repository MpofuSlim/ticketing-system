package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeatServiceTest {

    private Seat availableSeat(UUID seatId, SeatCategory category) {
        return Seat.builder()
                .id(seatId)
                .category(category)
                .sectionLabel("A")
                .seatNumber(1)
                .status(Seat.SeatStatus.AVAILABLE)
                .build();
    }

    private SeatCategory category(int available) {
        return SeatCategory.builder()
                .id(UUID.randomUUID())
                .name("VIP")
                .totalSeats(10)
                .availableSeats(available)
                .build();
    }

    @Test
    void lockSeat_marksSeatLocked_storesLock_andDecrementsAvailability() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(10);
        Seat seat = availableSeat(seatId, cat);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.decrementAvailableSeats(cat.getId())).thenReturn(1);

        LocalDateTime beforeLock = LocalDateTime.now();
        SeatLockResponseDTO resp = service.lockSeat(seatId, "user@example.com");

        assertEquals(Seat.SeatStatus.LOCKED, seat.getStatus());
        assertEquals("LOCKED", resp.getStatus());
        // DB-tracked expiry must be set so the reaper can sweep it later.
        assertNotNull(seat.getLockExpiresAt());
        assertTrue(seat.getLockExpiresAt().isAfter(beforeLock.plusSeconds(299)));
        verify(store).put(eq("seat:lock:" + seatId), eq("user@example.com"), eq(300L));
        verify(seatRepo).save(seat);
        verify(catRepo).decrementAvailableSeats(cat.getId());
        verify(catRepo, never()).save(any());
    }

    @Test
    void lockSeat_reclaimsStaleLock_withoutDecrementingCategoryAgain() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(9); // previous owner already cost us 1
        Seat seat = availableSeat(seatId, cat);
        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(LocalDateTime.now().minusSeconds(30));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));

        SeatLockResponseDTO resp = service.lockSeat(seatId, "new-owner@example.com");

        assertEquals(Seat.SeatStatus.LOCKED, seat.getStatus());
        assertEquals("LOCKED", resp.getStatus());
        // Counter must NOT be decremented again — previous owner's decrement still stands.
        verify(catRepo, never()).decrementAvailableSeats(any());
        // Expiry must be pushed into the future.
        assertNotNull(seat.getLockExpiresAt());
        assertTrue(seat.getLockExpiresAt().isAfter(LocalDateTime.now()));
        // New owner's lock takes over.
        verify(store).put(eq("seat:lock:" + seatId), eq("new-owner@example.com"), eq(300L));
    }

    @Test
    void lockSeat_rejectsWhenLockedAndNotYetExpired() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(LocalDateTime.now().plusSeconds(120));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(seatId, "intruder@example.com"));
        assertTrue(ex.getMessage().contains("is not available"));
        verify(store, never()).put(any(), any(), anyLong());
    }

    @Test
    void lockSeat_acquiresPessimisticLock_andPutsRedisAfterDbWrites() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(10);
        Seat seat = availableSeat(seatId, cat);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.decrementAvailableSeats(cat.getId())).thenReturn(1);

        service.lockSeat(seatId, "user@example.com");

        verify(seatRepo, never()).findById(any());
        InOrder order = inOrder(seatRepo, catRepo, store);
        order.verify(seatRepo).findByIdForUpdate(seatId);
        order.verify(catRepo).decrementAvailableSeats(cat.getId());
        order.verify(seatRepo).save(seat);
        order.verify(store).put(eq("seat:lock:" + seatId), eq("user@example.com"), eq(300L));
    }

    @Test
    void lockSeat_doesNotPutRedisOrSaveSeat_whenCategoryExhausted() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(0);
        Seat seat = availableSeat(seatId, cat);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.decrementAvailableSeats(cat.getId())).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(seatId, "u@example.com"));
        assertTrue(ex.getMessage().contains("No seats available"));
        verify(seatRepo, never()).save(any());
        verify(store, never()).put(any(), any(), anyLong());
    }

    @Test
    void lockSeat_rejectsWhenSeatAlreadyLocked() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(10));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(seatId, "u@example.com"));
        assertTrue(ex.getMessage().contains("is not available"));
        verify(seatRepo, never()).save(any());
        verify(store, never()).put(any(), any(), anyLong());
    }

    @Test
    void lockSeat_throwsWhenSeatMissing() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        when(seatRepo.findByIdForUpdate(any())).thenReturn(Optional.empty());
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(UUID.randomUUID(), "u@example.com"));
        assertEquals("Seat not found", ex.getMessage());
        verify(store, never()).put(any(), any(), anyLong());
    }

    @Test
    void confirmSeat_transitionsLockedToBooked_andClearsLock_whenOwnerMatches() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(LocalDateTime.now().plusSeconds(60));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get("seat:lock:" + seatId)).thenReturn("user@example.com");

        service.confirmSeat(seatId, "user@example.com");

        assertEquals(Seat.SeatStatus.BOOKED, seat.getStatus());
        assertNull(seat.getLockExpiresAt());
        verify(store).delete("seat:lock:" + seatId);
    }

    @Test
    void confirmSeat_rejectsWhenLockBelongsToOtherUser() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn("other@example.com");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.confirmSeat(seatId, "me@example.com"));
        assertTrue(ex.getMessage().contains("Lock expired"));
        verify(seatRepo, never()).save(any());
        verify(store, never()).delete(any());
    }

    @Test
    void confirmSeat_rejectsWhenLockExpired() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> service.confirmSeat(seatId, "me@example.com"));
    }

    @Test
    void releaseSeat_byOwner_restoresAvailabilityAndDeletesLock() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(9);
        Seat seat = availableSeat(seatId, cat);
        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(LocalDateTime.now().plusSeconds(60));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn("me@example.com");
        when(catRepo.incrementAvailableSeats(cat.getId())).thenReturn(1);

        service.releaseSeat(seatId, "me@example.com");

        assertEquals(Seat.SeatStatus.AVAILABLE, seat.getStatus());
        assertNull(seat.getLockExpiresAt());
        verify(catRepo).incrementAvailableSeats(cat.getId());
        verify(catRepo, never()).save(any());
        verify(store).delete("seat:lock:" + seatId);
    }

    @Test
    void releaseStaleLock_revertsExpiredLockedSeatToAvailable_andIncrementsCategory() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(9);
        Seat seat = availableSeat(seatId, cat);
        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(LocalDateTime.now().minusSeconds(10));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.incrementAvailableSeats(cat.getId())).thenReturn(1);

        boolean released = service.releaseStaleLock(seatId);

        assertTrue(released);
        assertEquals(Seat.SeatStatus.AVAILABLE, seat.getStatus());
        assertNull(seat.getLockExpiresAt());
        verify(catRepo).incrementAvailableSeats(cat.getId());
        verify(store).delete("seat:lock:" + seatId);
    }

    @Test
    void releaseStaleLock_returnsFalse_whenSeatNoLongerLocked() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        // Already BOOKED — reaper raced and lost. Must not touch anything.
        seat.setStatus(Seat.SeatStatus.BOOKED);
        seat.setLockExpiresAt(null);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));

        boolean released = service.releaseStaleLock(seatId);

        assertFalse(released);
        verify(catRepo, never()).incrementAvailableSeats(any());
        verify(store, never()).delete(any());
    }

    @Test
    void releaseStaleLock_returnsFalse_whenLockExpiryStillInFuture() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        // Another user reclaimed via lockSeat between candidate-find and now.
        seat.setLockExpiresAt(LocalDateTime.now().plusSeconds(290));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));

        boolean released = service.releaseStaleLock(seatId);

        assertFalse(released);
        assertEquals(Seat.SeatStatus.LOCKED, seat.getStatus());
        verify(catRepo, never()).incrementAvailableSeats(any());
        verify(store, never()).delete(any());
    }

    @Test
    void releaseStaleLock_returnsFalse_whenSeatMissing() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        when(seatRepo.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertFalse(service.releaseStaleLock(UUID.randomUUID()));
    }

    @Test
    void releaseSeat_rejectsWhenLockBelongsToOtherUser() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn("owner@example.com");

        assertThrows(RuntimeException.class,
                () -> service.releaseSeat(seatId, "intruder@example.com"));
        verify(seatRepo, never()).save(any());
    }

    @Test
    void releaseSeat_succeedsWhenLockAlreadyExpired() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(9);
        Seat seat = availableSeat(seatId, cat);
        seat.setStatus(Seat.SeatStatus.LOCKED);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn(null);
        when(catRepo.incrementAvailableSeats(cat.getId())).thenReturn(1);

        service.releaseSeat(seatId, "anyone@example.com");

        assertEquals(Seat.SeatStatus.AVAILABLE, seat.getStatus());
        verify(catRepo).incrementAvailableSeats(cat.getId());
    }
}
