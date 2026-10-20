package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(name = "AttendeeUpdateResponse")
public record AttendeeUpdateResponseDTO(
        UUID bookingId,
        String confirmationNumber,
        String ticketNumber,
        String attendeeName,
        String attendeeEmail,
        String attendeePhone,
        @Schema(description = "Attendee if named, else the purchaser.") String holderName,
        @Schema(description = "Which of fullName / email / phoneNumber actually changed (empty = nothing did).")
        List<String> changed
) {
}
