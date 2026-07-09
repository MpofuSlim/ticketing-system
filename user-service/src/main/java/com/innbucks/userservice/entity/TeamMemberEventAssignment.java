package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One (team member, event) assignment. Presence of any row for a team member
 * switches them from organizer-wide scanning to "only the assigned events".
 * See V21 migration for the no-rows-means-organizer-wide product rule.
 */
@Entity
@Table(name = "team_member_event_assignment",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_team_member_event",
                columnNames = {"team_member_user_uuid", "event_id"}))
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMemberEventAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The team member's stable user_uuid (FK to users.user_uuid). */
    @Column(name = "team_member_user_uuid", nullable = false)
    private UUID teamMemberUserUuid;

    /** event-service's event UUID. No JPA relationship — cross-service id. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** The organizer's user_uuid that created this assignment. Audit only. */
    @Column(name = "assigned_by_organizer_uuid", nullable = false)
    private UUID assignedByOrganizerUuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Acting principal (user_uuid or JWT email) that created this assignment,
     *  auto-stamped by JPA auditing. Mirrors assignedByOrganizerUuid for the
     *  common case; set uniformly across audited entities. */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Acting principal on the last update (JPA auditing). */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
