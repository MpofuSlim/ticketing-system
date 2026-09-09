package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientUuidOrderByCreatedAtDesc(UUID recipientUuid, Pageable pageable);

    Page<Notification> findByRecipientUuidAndReadAtIsNullOrderByCreatedAtDesc(
            UUID recipientUuid, Pageable pageable);

    long countByRecipientUuidAndReadAtIsNull(UUID recipientUuid);

    /**
     * The id AND the recipient, always — a notification may only ever be read
     * by the person it was addressed to, and scoping the lookup is what makes
     * that true rather than a check someone can forget to write.
     */
    Optional<Notification> findByIdAndRecipientUuid(UUID id, UUID recipientUuid);

    /**
     * Mark-all-read as ONE statement rather than loading the page and saving
     * each row: an admin returning from leave can have thousands unread, and
     * the read-modify-write version is unbounded work inside their request.
     *
     * <p>The {@code read_at IS NULL} predicate makes it idempotent and keeps
     * the original read time on rows that were already read.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now "
            + "WHERE n.recipientUuid = :recipientUuid AND n.readAt IS NULL")
    int markAllRead(@Param("recipientUuid") UUID recipientUuid, @Param("now") LocalDateTime now);

    /**
     * Newest createdAt for one recipient, for the unread-count ETag. Paired
     * with the count it changes whenever the badge's meaning changes: a new
     * arrival moves the timestamp, a read moves the count.
     */
    @Query("SELECT MAX(n.createdAt) FROM Notification n WHERE n.recipientUuid = :recipientUuid")
    Optional<LocalDateTime> latestCreatedAt(@Param("recipientUuid") UUID recipientUuid);
}
