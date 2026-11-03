package com.innbucks.eventservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A question an organizer asks for each ticket at checkout (V16). The list is
 * replaced as a whole by {@code PUT /events/{id}/booking-questions}; a kept
 * question keeps its id, so answers already given stay linked to it.
 *
 * <p>{@code categoryIds} holds strings, not UUIDs, so the converter stays one
 * type: an empty list means every ticket is asked.
 */
@Entity
@Table(name = "event_booking_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventBookingQuestion {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 80)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingQuestionType type;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "help_text", length = 200)
    private String helpText;

    @Convert(converter = JsonStringListConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> options = new ArrayList<>();

    @Column(name = "max_length")
    private Integer maxLength;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "category_ids", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> categoryIds = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
