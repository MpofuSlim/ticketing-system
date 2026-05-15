package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Number of active (PENDING + CONFIRMED) booking items for one event.")
public class EventActiveCountDTO {

    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID eventId;

    @Schema(example = "42")
    private long count;
}
