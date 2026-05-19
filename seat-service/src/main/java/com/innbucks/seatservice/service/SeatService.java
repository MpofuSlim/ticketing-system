package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatLookupResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatRepository seatRepository;
    private final SeatCategoryRepository categoryRepository;
    private final SeatLockStore seatLockStore;

    static final long LOCK_TTL_SECONDS = 300; // 5 minutes
    static final String LOCK_KEY_PREFIX = "seat:lock:";

    public List<SeatResponseDTO> getSeatsByCategory(UUID categoryId) {
        log.debug("Fetching seats categoryId={}", categoryId);
        return seatRepository.findByCategoryId(categoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<SeatResponseDTO> getAvailableSeats(UUID categoryId) {
        log.debug("Fetching available seats categoryId={}", categoryId);
        return seatRepository
                .findByCategoryIdAndStatus(categoryId, Seat.SeatStatus.AVAILABLE)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Authoritative lookup used by other services (e.g. booking-service) to
    // resolve a seat to its category, price, event, and status without
    // trusting client-supplied values.
    public SeatLookupResponseDTO lookupSeat(UUID seatId) {
        log.debug("Looking up seat seatId={}", seatId);
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> {
                    log.warn("Lookup failed, seat not found seatId={}", seatId);
                    return new RuntimeException("Seat not found");
                });
        SeatCategory category = seat.getCategory();
        return SeatLookupResponseDTO.builder()
                .seatId(seat.getId())
                .eventId(category.getEventId())
                .categoryId(category.getId())
                .categoryName(category.getName())
                .sectionLabel(seat.getSectionLabel())
                .seatNumber(seat.getSeatNumber())
                .price(category.getPrice())
                .status(seat.getStatus())
                .build();
    }

    @Transactional
    public SeatLockResponseDTO lockSeat(UUID seatId, String userEmail) {
        log.info("Locking seat seatId={} userEmail={}", seatId, userEmail);
        // Pessimistic row lock: concurrent lock attempts on the same seat are
        // serialised here so the status check and update can't race.
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> {
                    log.warn("Lock failed, seat not found seatId={}", seatId);
                    return new RuntimeException("Seat not found");
                });

        LocalDateTime now = LocalDateTime.now();
        // A LOCKED seat whose lockExpiresAt is in the past is stale (previous
        // owner's TTL elapsed before the reaper got to it). The category
        // counter was already decremented when the previous owner locked, so
        // we skip the decrement here and just rewrite the owner + expiry.
        boolean reclaimingStale = seat.getStatus() == Seat.SeatStatus.LOCKED
                && seat.getLockExpiresAt() != null
                && seat.getLockExpiresAt().isBefore(now);

        if (!reclaimingStale && seat.getStatus() != Seat.SeatStatus.AVAILABLE) {
            log.warn("Lock rejected, seat not available seatId={} section={} number={} status={}",
                    seatId, seat.getSectionLabel(), seat.getSeatNumber(), seat.getStatus());
            throw new RuntimeException("Seat " + seat.getSectionLabel()
                    + seat.getSeatNumber() + " is not available");
        }

        SeatCategory category = seat.getCategory();
        if (!reclaimingStale) {
            int updated = categoryRepository.decrementAvailableSeats(category.getId());
            if (updated == 0) {
                log.warn("Lock rejected, category exhausted seatId={} categoryId={}",
                        seatId, category.getId());
                throw new RuntimeException("No seats available in category " + category.getName());
            }
        } else {
            log.info("Reclaiming stale lock seatId={} previousExpiresAt={}",
                    seatId, seat.getLockExpiresAt());
        }

        seat.setStatus(Seat.SeatStatus.LOCKED);
        seat.setLockExpiresAt(now.plusSeconds(LOCK_TTL_SECONDS));
        seatRepository.save(seat);

        // Redis put goes last: if it throws, the surrounding transaction rolls
        // back and the seat returns to AVAILABLE. Doing it before the DB writes
        // would leak a Redis owner whenever the transaction failed afterwards.
        String lockKey = LOCK_KEY_PREFIX + seatId;
        seatLockStore.put(lockKey, userEmail, LOCK_TTL_SECONDS);

        log.info("Seat locked seatId={} section={} number={} userEmail={} ttlSeconds={}",
                seatId, seat.getSectionLabel(), seat.getSeatNumber(), userEmail, LOCK_TTL_SECONDS);

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
        log.info("Confirming seat seatId={} userEmail={}", seatId, userEmail);
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> {
                    log.warn("Confirm failed, seat not found seatId={}", seatId);
                    return new RuntimeException("Seat not found");
                });

        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);

        if (lockOwner == null || !lockOwner.equals(userEmail)) {
            log.warn("Confirm rejected, lock expired or owned by another user seatId={} userEmail={} lockOwner={}",
                    seatId, userEmail, lockOwner);
            throw new RuntimeException("Lock expired or belongs to a different user");
        }

        seat.setStatus(Seat.SeatStatus.BOOKED);
        seat.setLockExpiresAt(null);
        seatRepository.save(seat);
        seatLockStore.delete(lockKey);

        log.info("Seat booked seatId={} section={} number={} userEmail={}",
                seatId, seat.getSectionLabel(), seat.getSeatNumber(), userEmail);
        return toDTO(seat);
    }

    @Transactional
    public void releaseSeat(UUID seatId, String userEmail) {
        log.info("Releasing seat seatId={} userEmail={}", seatId, userEmail);
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> {
                    log.warn("Release failed, seat not found seatId={}", seatId);
                    return new RuntimeException("Seat not found");
                });

        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);

        if (lockOwner != null && !lockOwner.equals(userEmail)) {
            log.warn("Release rejected, lock owned by another user seatId={} userEmail={} lockOwner={}",
                    seatId, userEmail, lockOwner);
            throw new RuntimeException("You cannot release a lock that belongs to another user");
        }

        seat.setStatus(Seat.SeatStatus.AVAILABLE);
        seat.setLockExpiresAt(null);
        seatRepository.save(seat);

        UUID categoryId = seat.getCategory().getId();
        int updated = categoryRepository.incrementAvailableSeats(categoryId);
        if (updated == 0) {
            log.warn("Release: category counter not incremented (already at total) categoryId={}", categoryId);
        }

        seatLockStore.delete(lockKey);
        log.info("Seat released seatId={} section={} number={} userEmail={}",
                seatId, seat.getSectionLabel(), seat.getSeatNumber(), userEmail);
    }

    // Called by SeatLockReaper for each stale candidate. REQUIRES_NEW so a
    // single seat failing (e.g. another transaction holds the row lock) does
    // not roll back the whole reaper batch. Returns true iff the seat was
    // actually transitioned LOCKED -> AVAILABLE (so the reaper can tally).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseStaleLock(UUID seatId) {
        Seat seat = seatRepository.findByIdForUpdate(seatId).orElse(null);
        if (seat == null) {
            return false;
        }
        // Re-check inside the pessimistic-locked transaction: another caller
        // (e.g. a real user reclaiming via lockSeat) may have already moved
        // this seat between candidate-discovery and now.
        if (seat.getStatus() != Seat.SeatStatus.LOCKED
                || seat.getLockExpiresAt() == null
                || seat.getLockExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }

        seat.setStatus(Seat.SeatStatus.AVAILABLE);
        seat.setLockExpiresAt(null);
        seatRepository.save(seat);

        UUID categoryId = seat.getCategory().getId();
        int updated = categoryRepository.incrementAvailableSeats(categoryId);
        if (updated == 0) {
            log.warn("Reaper: category counter not incremented (already at total) seatId={} categoryId={}",
                    seatId, categoryId);
        }

        seatLockStore.delete(LOCK_KEY_PREFIX + seatId);
        log.info("Reaper released stale lock seatId={} section={} number={}",
                seatId, seat.getSectionLabel(), seat.getSeatNumber());
        return true;
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
