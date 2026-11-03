package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventBookingQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventBookingQuestionRepository extends JpaRepository<EventBookingQuestion, UUID> {

    List<EventBookingQuestion> findByEventIdOrderByPositionAsc(UUID eventId);
}
