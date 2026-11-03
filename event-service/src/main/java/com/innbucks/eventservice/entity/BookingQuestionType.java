package com.innbucks.eventservice.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What kind of answer a booking question takes (V16). booking-service
 * validates each answer by this type; its own copy of the rule lives in
 * {@code BookingAnswers}. Adding a type means a migration widening
 * {@code chk_event_booking_question_type} and a booking-service release that
 * knows it first.
 */
@Schema(description = "TEXT: free text up to maxLength. NUMBER: a number. DATE: yyyy-MM-dd. "
        + "CHOICE: one of options. CHECKBOX: \"true\" or \"false\"; required means it must be ticked.")
public enum BookingQuestionType {
    TEXT, NUMBER, DATE, CHOICE, CHECKBOX
}
