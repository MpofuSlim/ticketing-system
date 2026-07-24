package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.OrganizerEventReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizerEventReminderRepository extends JpaRepository<OrganizerEventReminder, UUID> {
}
