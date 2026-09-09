package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One in-app notification for one recipient.
 *
 * <p>Addressed by {@code users.user_uuid} rather than the numeric id, because
 * the writers are other services holding a uuid from a JWT claim — the numeric
 * id is user-service's private key and does not travel.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "recipient_uuid", nullable = false)
    private UUID recipientUuid;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    @Column(name = "subject_kind", length = 64)
    private String subjectKind;

    @Column(name = "subject_id", length = 64)
    private String subjectId;

    @Column(name = "deep_link", length = 500)
    private String deepLink;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Null while unread. The badge counts exactly these. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** How loudly the console should render it. */
    public enum Severity { INFO, SUCCESS, WARNING, ERROR }
}
