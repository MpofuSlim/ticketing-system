package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.EventSalesWindowChange;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One change to an event's ticket sales window, as recorded (V22). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SalesWindowChange")
public class SalesWindowChangeDTO {

    /** What an organizer reads in {@link #changedBy} when someone other than them made the change. */
    public static final String CHANGED_BY_INNBUCKS = "InnBucks";

    @Schema(example = "CHANGED", description = "CREATED: the event was created with a sales start or end. "
            + "CHANGED: an update moved either.")
    private EventSalesWindowChange.ChangeType changeType;

    @Schema(nullable = true, description = "The sales start before the change. Null: none.")
    private LocalDateTime oldSalesStartDateTime;

    @Schema(nullable = true, description = "The sales start after the change. Null: none (on sale once published).")
    private LocalDateTime newSalesStartDateTime;

    @Schema(nullable = true, description = "The sales end before the change. Null: none.")
    private LocalDateTime oldSalesEndDateTime;

    @Schema(nullable = true, description = "The sales end after the change. Null: none (sales close when the "
            + "event ends).")
    private LocalDateTime newSalesEndDateTime;

    @Schema(example = "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", nullable = true,
            description = "Who made the change: their account uuid (an email for an older session without one). "
                    + "The organizer reads their own uuid, or \"InnBucks\" for a change InnBucks staff made; "
                    + "only staff read who on the staff it was.")
    private String changedBy;

    @Schema(example = "true", description = "Whether the event's organizer made the change.")
    private boolean changedByOrganizer;

    @Schema(description = "When the change was made.")
    private LocalDateTime changedAt;

    /**
     * {@code ownerUuid} is the event's organizer. An organizer reading their
     * own event never learns which staff member changed it (an account uuid or
     * an InnBucks email names a staff account), so that goes to staff only.
     */
    public static SalesWindowChangeDTO of(EventSalesWindowChange change, UUID ownerUuid, boolean staffReader) {
        boolean byOrganizer = ownerUuid != null && ownerUuid.toString().equalsIgnoreCase(change.getChangedBy());
        String changedBy = staffReader || byOrganizer ? change.getChangedBy() : CHANGED_BY_INNBUCKS;
        return SalesWindowChangeDTO.builder()
                .changeType(change.getChangeType())
                .oldSalesStartDateTime(change.getOldSalesStartAt())
                .newSalesStartDateTime(change.getNewSalesStartAt())
                .oldSalesEndDateTime(change.getOldSalesEndAt())
                .newSalesEndDateTime(change.getNewSalesEndAt())
                .changedBy(changedBy)
                .changedByOrganizer(byOrganizer)
                .changedAt(change.getChangedAt())
                .build();
    }
}
