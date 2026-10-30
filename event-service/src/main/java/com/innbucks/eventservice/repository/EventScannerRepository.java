package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventScanner;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventScannerRepository extends JpaRepository<EventScanner, UUID> {

    Optional<EventScanner> findByScannerIdAndOrganizerUuid(UUID scannerId, UUID organizerUuid);

    Optional<EventScanner> findByOrganizerUuidAndPhoneNumber(UUID organizerUuid, String phoneNumber);

    List<EventScanner> findByOrganizerUuidOrderByCreatedAtAscScannerIdAsc(UUID organizerUuid);

    List<EventScanner> findAllByOrderByCreatedAtAscScannerIdAsc();

    long countByOrganizerUuid(UUID organizerUuid);

    /**
     * Every row of one number, locked FOR UPDATE in id order: the clash rule
     * spans organizers, so two organizers assigning the same number at once
     * must queue on the same rows. The order keeps any two lockers from
     * deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EventScanner s where s.phoneNumber = :phone order by s.scannerId")
    List<EventScanner> lockAllByPhoneNumber(@Param("phone") String phoneNumber);

    /** Teams this account accepted and still belongs to (the gate list, the scan check). */
    @Query("select s from EventScanner s where s.accountUserUuid = :account "
            + "and s.acceptedAt is not null and s.declinedAt is null and s.disabledAt is null")
    List<EventScanner> findActiveForAccount(@Param("account") UUID accountUserUuid);

    /** Invites waiting on this number: not accepted, declined or switched off. */
    @Query("select s from EventScanner s where s.phoneNumber = :phone "
            + "and s.acceptedAt is null and s.declinedAt is null and s.disabledAt is null "
            + "order by s.createdAt, s.scannerId")
    List<EventScanner> findPendingForPhone(@Param("phone") String phoneNumber);
}
