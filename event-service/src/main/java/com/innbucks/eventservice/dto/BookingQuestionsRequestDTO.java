package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** The whole list of an event's booking questions, replacing what it had (V16). */
@Data
@Schema(name = "BookingQuestionsRequest")
public class BookingQuestionsRequestDTO {

    @Schema(description = "Every question to keep, in the order to ask them; [] removes them all.")
    private List<BookingQuestionDTO> questions;
}
