package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Counts over one event's confirmed tickets (V32): how each booking question
 * was answered, and how many bookings accepted each terms version. Values of
 * TEXT, NUMBER and DATE answers are never summarised here: read them in the
 * guest list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "BookingQuestionReport")
public class BookingQuestionReportDTO {

    private UUID eventId;

    @Schema(example = "120", description = "Tickets of the event's confirmed bookings.")
    private long confirmedTickets;

    @Schema(description = "True once the answers were deleted (90 days after the event ended); questions is then [].")
    private boolean answersDeleted;

    private List<Question> questions;

    @Schema(description = "Confirmed bookings per accepted terms version, newest version first.")
    private List<TermsCount> termsAccepted;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BookingQuestionReportQuestion")
    public static class Question {
        private UUID questionId;
        @Schema(description = "The most recent label it was answered under.")
        private String label;
        private String type;
        @Schema(description = "Tickets that answered it.")
        private long answered;
        @Schema(description = "CHOICE: each option's count, most picked first. [] otherwise.")
        private List<OptionCount> options;
        @Schema(nullable = true, description = "CHECKBOX only.")
        private Long yes;
        @Schema(nullable = true, description = "CHECKBOX only.")
        private Long no;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BookingQuestionReportOption")
    public static class OptionCount {
        private String value;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BookingQuestionReportTerms")
    public static class TermsCount {
        private int version;
        private long bookings;
    }
}
