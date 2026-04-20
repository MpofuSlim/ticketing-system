package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final SeatCategoryRepository categoryRepository;
    private final SeatLockStore seatLockStore;

    private static final long LOCK_TTL_SECONDS = 300; // 5 minutes
    private static final String LOCK_KEY_PREFIX = "seat:lock:";

    public List<SeatResponseDTO> getSeatsByCategory(UUID categoryId) {
        return seatRepository.findByCategoryId(categoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<SeatResponseDTO> getAvailableSeats(UUID categoryId) {
        return seatRepository
                .findByCategoryIdAndStatus(categoryId, Seat.SeatStatus.AVAILABLE)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public SeatLockResponseDTO lockSeat(UUID seatId, String userEmail) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new RuntimeException("Seat not found"));

        if (seat.getStatus() != Seat.SeatStatus.AVAILABLE) {
            throw new RuntimeException("Seat " + seat.getSectionLabel()
                    + seat.getSeatNumber() + " is not available");
        }

        String lockKey = LOCK_KEY_PREFIX + seatId;
        seatLockStore.put(lockKey, userEmail, LOCK_TTL_SECONDS);

        seat.setStatus(Seat.SeatStatus.LOCKED);
        seatRepository.save(seat);

        SeatCategory category = seat.getCategory();
        category.setAvailableSeats(category.getAvailableSeats() - 1);
        categoryRepository.save(category);

        return SeatLockResponseDTO.builder()
                .seatId(seat.getId())
                .sectionLabel(seat.getSectionLabel())
                .seatNumber(seat.getSeatNumber())
                .categoryName(category.getName())
                .status("LOCKED")
                .message("Seat locked for 5 minutes. Complete your booking before it expires.")
                .expiresInSeconds(LOCK_TTL_SECONDS)
                .build();
    }

    @Transactional
    public SeatResponseDTO confirmSeat(UUID seatId, String userEmail) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new RuntimeException("Seat not found"));

        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);

        if (lockOwner == null || !lockOwner.equals(userEmail)) {
            throw new RuntimeException("Lock expired or belongs to a different user");
        }

        seat.setStatus(Seat.SeatStatus.BOOKED);
        seatRepository.save(seat);
        seatLockStore.delete(lockKey);

        return toDTO(seat);
    }

    @Transactional
    public void releaseSeat(UUID seatId, String userEmail) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new RuntimeException("Seat not found"));

        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);

        if (lockOwner != null && !lockOwner.equals(userEmail)) {
            throw new RuntimeException("You cannot release a lock that belongs to another user");
        }

        seat.setStatus(Seat.SeatStatus.AVAILABLE);
        seatRepository.save(seat);

        SeatCategory category = seat.getCategory();
        category.setAvailableSeats(category.getAvailableSeats() + 1);
        categoryRepository.save(category);

        seatLockStore.delete(lockKey);
    }

    private SeatResponseDTO toDTO(Seat seat) {
        return SeatResponseDTO.builder()
                .id(seat.getId())
                .categoryId(seat.getCategory().getId())
                .categoryName(seat.getCategory().getName())
                .sectionLabel(seat.getSectionLabel())
                .seatNumber(seat.getSeatNumber())
                .status(seat.getStatus())
                .build();
    }
}
