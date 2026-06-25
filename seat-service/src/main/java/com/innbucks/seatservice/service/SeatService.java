package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatLookupResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.exception.ForbiddenException;
import com.innbucks.seatservice.exception.NotFoundException;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    // Bounded, randomised variant. Returns at most `limit` AVAILABLE seats
    // chosen at random, so a caller that only needs a handful (booking-service
    // picking one seat) never has to transfer the whole pool of a large category.
    //
    // Indexed random sampling: pick a random UUID pivot and take the `limit`
    // available seats with id >= pivot, wrapping past the smallest ids if the
    // pivot lands near the top. Seat PKs are random UUIDs, so a random pivot
    // yields a random window — O(log N + limit) via idx_seats_category_status_id,
    // NOT the O(N) full scan + sort that ORDER BY random() required (which
    // saturated seat-service under load and tripped the caller's 1s circuit
    // breaker on large categories).
    public List<SeatResponseDTO> getAvailableSeats(UUID categoryId, int limit) {
        log.debug("Sampling up to {} random available seats categoryId={}", limit, categoryId);
        UUID pivot = UUID.randomUUID();
        List<Seat> picked = seatRepository.findAvailableFromPivot(
                categoryId, pivot, PageRequest.of(0, limit));
        if (picked.size() < limit) {
            // Pivot landed near the top of the id keyspace — top up by wrapping
            // to the smallest ids so callers still get a full sample.
            List<Seat> wrapped = seatRepository.findAvailableBeforePivot(
                    categoryId, pivot, PageRequest.of(0, limit - picked.size()));
            picked = new ArrayList<>(picked);
            picked.addAll(wrapped);
        }
        return picked.stream()
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
                    return new NotFoundException("Seat not found");
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
                    return new NotFoundException("Seat not found");
                });

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
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
            throw new ConflictException("Seat " + seat.getSectionLabel()
                    + seat.getSeatNumber() + " is not available");
        }

        SeatCategory category = seat.getCategory();
        if (!reclaimingStale) {
            int updated = categoryRepository.decrementAvailableSeats(category.getId());
            if (updated == 0) {
                log.warn("Lock rejected, category exhausted seatId={} categoryId={}",
                        seatId, category.getId());
                throw new ConflictException("No seats available in category " + category.getName());
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
                    return new NotFoundException("Seat not found");
                });

        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);

        if (lockOwner == null || !lockOwner.equals(userEmail)) {
            // 409 covers both branches the message lumps together: lock expired
            // (legit user retried after TTL, state changed under them) and lock
            // is owned by a different user (race lost). Both surface as "your
            // seat hold isn't valid anymore, restart the booking" in the FE.
            log.warn("Confirm rejected, lock expired or owned by another user seatId={} userEmail={} lockOwner={}",
                    seatId, userEmail, lockOwner);
            throw new ConflictException("Lock expired or belongs to a different user");
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
        // Row-lock the seat so the status check + transition can't race a
        // concurrent confirm/lock (mirrors lockSeat / releaseStaleLock). The
        // previous findById (no lock) plus a fail-OPEN ownership check let any
        // tier-2 user release a seat whose Redis lock had gone — and the lock is
        // gone exactly after a seat is CONFIRMED (confirmSeat deletes the key) or
        // its TTL lapsed — flipping another customer's BOOKED seat back to
        // AVAILABLE and inflating the availability counter (oversell).
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> {
                    log.warn("Release failed, seat not found seatId={}", seatId);
                    return new NotFoundException("Seat not found");
                });

        // Only a held (LOCKED) seat is releasable through this user path. A
        // BOOKED seat is a confirmed (paid) booking and must NOT be releasable
        // here — that would void the booking and oversell the category; an
        // AVAILABLE seat has nothing to release.
        if (seat.getStatus() != Seat.SeatStatus.LOCKED) {
            log.warn("Release rejected, seat not in LOCKED state seatId={} userEmail={} status={}",
                    seatId, userEmail, seat.getStatus());
            throw new ConflictException("Seat is not held and cannot be released");
        }

        // Ownership is authoritative from the lock store, and is now required to
        // be present AND match: a null owner (lock evicted/expired) on a
        // still-LOCKED seat cannot be proven to belong to the caller, so it is
        // NOT releasable here — the reaper (releaseStaleLock) reclaims genuinely
        // expired holds instead. This closes the fail-open hole.
        String lockKey = LOCK_KEY_PREFIX + seatId;
        String lockOwner = seatLockStore.get(lockKey);
        if (lockOwner == null || !lockOwner.equals(userEmail)) {
            // 403: ownership violation (releasing a hold that isn't yours, or one
            // whose ownership can't be proven).
            log.warn("Release rejected, caller does not own the lock seatId={} userEmail={} lockOwner={}",
                    seatId, userEmail, lockOwner);
            throw new ForbiddenException("You cannot release a lock that belongs to another user");
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
                || seat.getLockExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
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
