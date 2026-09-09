package com.innbucks.userservice.notification;

import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The in-app notification store behind the console's bell.
 *
 * <p>Replaces a badge the console fabricated by polling three list endpoints
 * every 60 seconds per signed-in admin and counting rows — which could only
 * know about the three collections someone thought to poll, had no read state,
 * and reached no non-admin at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository repository;

    /**
     * Records a notification. Never throws for a bad-but-recoverable input:
     * callers are S2S producers and after-commit listeners, where an exception
     * would either fail an operation that already succeeded or lose the notice
     * entirely. Blank titles/bodies are refused at the edge, not here.
     */
    @Transactional
    public Notification create(NewNotification request) {
        Notification saved = repository.save(Notification.builder()
                .id(UUID.randomUUID())
                .recipientUuid(request.recipientUuid())
                .type(request.type() == null || request.type().isBlank()
                        ? NotificationType.GENERAL : request.type())
                .title(request.title())
                .body(request.body())
                .severity(request.severity() == null ? Notification.Severity.INFO : request.severity())
                .actorId(request.actorId())
                .actorName(request.actorName())
                .subjectKind(request.subjectKind())
                .subjectId(request.subjectId())
                .deepLink(request.deepLink())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
        log.debug("Notification recorded id={} type={} recipient={}",
                saved.getId(), saved.getType(), saved.getRecipientUuid());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID recipientUuid, boolean unreadOnly, Pageable pageable) {
        return unreadOnly
                ? repository.findByRecipientUuidAndReadAtIsNullOrderByCreatedAtDesc(recipientUuid, pageable)
                : repository.findByRecipientUuidOrderByCreatedAtDesc(recipientUuid, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientUuid) {
        return repository.countByRecipientUuidAndReadAtIsNull(recipientUuid);
    }

    /**
     * A weak validator for the unread count, pairing the count with the newest
     * arrival time.
     *
     * <p>Neither alone is enough: the count alone is unchanged when one arrives
     * and another is read, and the timestamp alone is unchanged when the user
     * reads something. Together they move whenever the badge's meaning does.
     */
    @Transactional(readOnly = true)
    public String unreadEtag(UUID recipientUuid, long count) {
        long latest = repository.latestCreatedAt(recipientUuid)
                .map(t -> t.toInstant(ZoneOffset.UTC).toEpochMilli())
                .orElse(0L);
        return "\"" + count + "-" + latest + "\"";
    }

    /**
     * Marks one notification read. Scoped by recipient in the QUERY, so another
     * user's notification is indistinguishable from a missing one — a 404
     * either way, and no way to probe for existence.
     *
     * <p>Idempotent: re-reading keeps the ORIGINAL read time, because "when did
     * they first see this" is the answer that has any value.
     */
    @Transactional
    public Notification markRead(UUID recipientUuid, UUID id) {
        Notification n = repository.findByIdAndRecipientUuid(id, recipientUuid)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
            repository.save(n);
        }
        return n;
    }

    /** @return how many were actually flipped; 0 when everything was already read. */
    @Transactional
    public int markAllRead(UUID recipientUuid) {
        int updated = repository.markAllRead(recipientUuid, LocalDateTime.now(ZoneOffset.UTC));
        log.debug("Marked {} notifications read for recipient={}", updated, recipientUuid);
        return updated;
    }

    /** What a producer supplies. */
    public record NewNotification(
            UUID recipientUuid,
            String type,
            String title,
            String body,
            Notification.Severity severity,
            String actorId,
            String actorName,
            String subjectKind,
            String subjectId,
            String deepLink) {}
}
