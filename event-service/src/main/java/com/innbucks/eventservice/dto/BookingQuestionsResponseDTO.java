package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "BookingQuestions")
public class BookingQuestionsResponseDTO {

    private UUID eventId;

    @Schema(description = "The saved questions, in order, each with its id.")
    private List<BookingQuestionDTO> questions;
}
