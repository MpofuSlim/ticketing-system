package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;

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
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.decrementAvailableSeats(cat.getId())).thenReturn(1);

        SeatLockResponseDTO resp = service.lockSeat(seatId, "user@example.com");

        assertEquals(Seat.SeatStatus.LOCKED, seat.getStatus());
        assertEquals("LOCKED", resp.getStatus());
        verify(store).put(eq("seat:lock:" + seatId), eq("user@example.com"), eq(300L));
        verify(seatRepo).save(seat);
        verify(catRepo).decrementAvailableSeats(cat.getId());
        verify(catRepo, never()).save(any());
    }

    @Test
    void lockSeat_throwsWhenCategoryExhausted() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, catRepo, store);

        UUID seatId = UUID.randomUUID();
        SeatCategory cat = category(0);
        Seat seat = availableSeat(seatId, cat);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(catRepo.decrementAvailableSeats(cat.getId())).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(seatId, "u@example.com"));
        assertTrue(ex.getMessage().contains("No seats available"));
    }

    @Test
    void lockSeat_rejectsWhenSeatAlreadyLocked() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), mock(SeatLockStore.class));

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(10));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(seatId, "u@example.com"));
        assertTrue(ex.getMessage().contains("is not available"));
        verify(seatRepo, never()).save(any());
    }

    @Test
    void lockSeat_throwsWhenSeatMissing() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        when(seatRepo.findById(any())).thenReturn(Optional.empty());
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), mock(SeatLockStore.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.lockSeat(UUID.randomUUID(), "u@example.com"));
        assertEquals("Seat not found", ex.getMessage());
    }

    @Test
    void confirmSeat_transitionsLockedToBooked_andClearsLock_whenOwnerMatches() {
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatLockStore store = mock(SeatLockStore.class);
        SeatService service = new SeatService(seatRepo, mock(SeatCategoryRepository.class), store);

        UUID seatId = UUID.randomUUID();
        Seat seat = availableSeat(seatId, category(9));
        seat.setStatus(Seat.SeatStatus.LOCKED);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get("seat:lock:" + seatId)).thenReturn("user@example.com");

        service.confirmSeat(seatId, "user@example.com");

        assertEquals(Seat.SeatStatus.BOOKED, seat.getStatus());
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
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(store.get(any())).thenReturn("me@example.com");
        when(catRepo.incrementAvailableSeats(cat.getId())).thenReturn(1);

        service.releaseSeat(seatId, "me@example.com");

        assertEquals(Seat.SeatStatus.AVAILABLE, seat.getStatus());
        verify(catRepo).incrementAvailableSeats(cat.getId());
        verify(catRepo, never()).save(any());
        verify(store).delete("seat:lock:" + seatId);
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
