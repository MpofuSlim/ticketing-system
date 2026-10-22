package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Body of {@code POST /bookings/event-reminders}. */
@Data
@Schema(name = "EventReminderRequest")
public class EventReminderRequestDTO {
    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "eventId is required")
    private UUID eventId;
}
