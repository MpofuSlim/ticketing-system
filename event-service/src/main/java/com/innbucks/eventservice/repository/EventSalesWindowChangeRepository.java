package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventSalesWindowChange;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only by shape: save and read, nothing that updates or deletes. */
public interface EventSalesWindowChangeRepository extends Repository<EventSalesWindowChange, UUID> {

    EventSalesWindowChange save(EventSalesWindowChange change);

    /** An event's last 200 changes, newest first. */
    List<EventSalesWindowChange> findTop200ByEventIdOrderByChangedAtDesc(UUID eventId);
}
