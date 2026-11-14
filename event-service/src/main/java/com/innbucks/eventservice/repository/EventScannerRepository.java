package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventScanner;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * Whether this number is a gate scanner on any organizer's team that still
     * counts: the Scan role, not switched off, not declined (an invite still
     * waiting counts, so the person can sign in to accept it). Asked by
     * user-service before it texts a gate sign-in code.
     */
    boolean existsByPhoneNumberAndCanScanTrueAndDisabledAtIsNullAndDeclinedAtIsNull(String phoneNumber);

    /**
     * One of this organizer's rows, locked FOR UPDATE (V21): the sales-event
     * upsert reads then inserts or updates, so two at once must queue.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EventScanner s where s.scannerId = :scannerId and s.organizerUuid = :organizer")
    Optional<EventScanner> lockByScannerIdAndOrganizerUuid(@Param("scannerId") UUID scannerId,
                                                           @Param("organizer") UUID organizerUuid);

    /**
     * Teams this account accepted and still belongs to, whatever they may do
     * (the gate list and the scan check then keep the rows that scan).
     */
    @Query("select s from EventScanner s where s.accountUserUuid = :account "
            + "and s.acceptedAt is not null and s.declinedAt is null and s.disabledAt is null")
    List<EventScanner> findActiveForAccount(@Param("account") UUID accountUserUuid);

    /**
     * Teams this account accepted, still belongs to, and sells for (V21): the
     * agent's selling list and booking-service's seller check. Id order, so a
     * caller that takes the first match is deterministic.
     */
    @Query("select s from EventScanner s where s.accountUserUuid = :account "
            + "and s.acceptedAt is not null and s.declinedAt is null and s.disabledAt is null "
            + "and s.canSell = true order by s.scannerId")
    List<EventScanner> findActiveSellersForAccount(@Param("account") UUID accountUserUuid);

    /** Invites waiting on this number: not accepted, declined or switched off. */
    @Query("select s from EventScanner s where s.phoneNumber = :phone "
            + "and s.acceptedAt is null and s.declinedAt is null and s.disabledAt is null "
            + "order by s.createdAt, s.scannerId")
    List<EventScanner> findPendingForPhone(@Param("phone") String phoneNumber);

    /**
     * Claims the invite message for an INVITED scanner that has not had one
     * since it was (re)invited (V20). One conditional UPDATE, so of two
     * assignments at once exactly one gets 1 and sends; the other gets 0.
     * Accepted, declined or switched-off rows are never claimed.
     */
    @Modifying(flushAutomatically = true)
    @Query("update EventScanner s set s.inviteNotifiedAt = :now, s.updatedAt = :now "
            + "where s.scannerId = :scannerId and s.inviteNotifiedAt is null "
            + "and s.acceptedAt is null and s.declinedAt is null and s.disabledAt is null")
    int claimInviteNotice(@Param("scannerId") UUID scannerId, @Param("now") LocalDateTime now);
}
