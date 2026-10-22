package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.PromoCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {

    /**
     * The code, row-locked for the booking that applies it: its uses are
     * counted under this lock, so "100 uses" is exact. Taken AFTER the
     * category stock locks, and nothing that takes those holds this, so the
     * order cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PromoCode p WHERE p.eventId = :eventId AND p.code = :code")
    Optional<PromoCode> lockByEventIdAndCode(@Param("eventId") UUID eventId, @Param("code") String code);

    Optional<PromoCode> findByEventIdAndCode(UUID eventId, String code);

    boolean existsByEventIdAndCode(UUID eventId, String code);

    List<PromoCode> findByEventIdOrderByCreatedAtDesc(UUID eventId);
}
