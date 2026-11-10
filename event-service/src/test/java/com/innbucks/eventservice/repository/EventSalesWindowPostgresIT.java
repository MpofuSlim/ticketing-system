package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.dto.SalesStatus;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sales end (V22) against real Postgres: the organizer list's
 * {@code salesStatus} filter is {@link SalesStatus#of} written in SQL, so each
 * status is checked against the Java rule over events in every state; and the
 * change record refuses UPDATE and DELETE.
 */
@Transactional
class EventSalesWindowPostgresIT extends PostgresIntegrationTestBase {

    @Autowired EventRepository eventRepository;
    @Autowired EventSalesWindowChangeRepository changes;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;

    @Test
    void salesStatusFilter_matchesTheJavaRule_forEveryStatus() {
        UUID organizer = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime soon = now.plusDays(2);
        LocalDateTime later = now.plusDays(5);
        LocalDateTime past = now.minusDays(1);

        List<Event> events = new ArrayList<>();
        // {active, rejected, salesStart, salesEnd, end}
        events.add(save(organizer, false, false, null, null, later));          // draft
        events.add(save(organizer, true, true, null, null, later));            // rejected
        events.add(save(organizer, false, false, null, past, later));          // draft, sales "closed"
        events.add(save(organizer, true, false, null, null, past));            // ended
        events.add(save(organizer, true, false, null, past.minusDays(1), past)); // ended with a sales end
        events.add(save(organizer, true, false, null, past, later));           // sales closed
        events.add(save(organizer, true, false, past.minusDays(1), past, later)); // opened, then closed
        events.add(save(organizer, true, false, soon, null, later));           // not yet on sale
        events.add(save(organizer, true, false, soon, later.minusHours(1), later)); // not yet, with an end
        events.add(save(organizer, true, false, null, null, later));           // on sale, no schedule
        events.add(save(organizer, true, false, past, soon, later));           // on sale, window open
        events.add(save(organizer, true, false, null, later, later));          // sales end AT the event end
        // Another organizer's on-sale event never appears.
        save(UUID.randomUUID(), true, false, null, null, later);

        for (SalesStatus status : SalesStatus.values()) {
            Set<UUID> expected = events.stream()
                    .filter(e -> SalesStatus.of(e.isActive(), e.isRejected(), e.getSalesStartAt(),
                            e.getSalesEndAt(), e.getEndDateTime(), now) == status)
                    .map(Event::getEventId)
                    .collect(Collectors.toSet());

            Page<Event> page = eventRepository.findByTenantUserUuidAndSalesStatus(
                    organizer, null, null, null, status.name(), now, PageRequest.of(0, 50));

            assertThat(page.getContent()).extracting(Event::getEventId)
                    .as("status %s", status)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(page.getTotalElements()).as("count for %s", status).isEqualTo(expected.size());
        }
    }

    @Test
    void salesStatusFilter_pagesOverTheFilteredSet() {
        UUID organizer = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            save(organizer, true, false, null, now.minusHours(1), now.plusDays(3 + i)); // closed
            save(organizer, true, false, null, null, now.plusDays(3 + i));              // on sale
        }

        Page<Event> first = eventRepository.findByTenantUserUuidAndSalesStatus(
                organizer, null, null, null, "SALES_CLOSED", now, PageRequest.of(0, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).allMatch(e -> e.getSalesEndAt() != null);
    }

    @Test
    void changeRecord_isSaved_andReadNewestFirst() {
        UUID eventId = UUID.randomUUID();
        LocalDateTime t = LocalDateTime.of(2026, 11, 10, 12, 0);
        persist(change(eventId, EventSalesWindowChange.ChangeType.CREATED, t));
        persist(change(eventId, EventSalesWindowChange.ChangeType.CHANGED, t.plusHours(1)));

        List<EventSalesWindowChange> read = changes.findTop200ByEventIdOrderByChangedAtDesc(eventId);

        assertThat(read).extracting(EventSalesWindowChange::getChangeType)
                .containsExactly(EventSalesWindowChange.ChangeType.CHANGED, EventSalesWindowChange.ChangeType.CREATED);
    }

    @Test
    void changeRecord_refusesUpdate() {
        UUID eventId = UUID.randomUUID();
        persist(change(eventId, EventSalesWindowChange.ChangeType.CREATED, LocalDateTime.of(2026, 11, 10, 12, 0)));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE event_sales_window_changes SET changed_by = 'someone-else' WHERE event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void changeRecord_refusesDelete() {
        UUID eventId = UUID.randomUUID();
        persist(change(eventId, EventSalesWindowChange.ChangeType.CREATED, LocalDateTime.of(2026, 11, 10, 12, 0)));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM event_sales_window_changes WHERE event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private Event save(UUID organizer, boolean active, boolean rejected, LocalDateTime salesStart,
                       LocalDateTime salesEnd, LocalDateTime end) {
        return eventRepository.save(Event.builder()
                .tenantUserUuid(organizer)
                .title("Event " + UUID.randomUUID())
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(end.minusHours(4))
                .endDateTime(end)
                .salesStartAt(salesStart)
                .salesEndAt(salesEnd)
                .totalCapacity(100)
                .availableTickets(100)
                .active(active)
                .rejected(rejected)
                .deleted(false)
                .build());
    }

    private static EventSalesWindowChange change(UUID eventId, EventSalesWindowChange.ChangeType type,
                                                 LocalDateTime at) {
        return EventSalesWindowChange.builder()
                .eventId(eventId)
                .changeType(type)
                .newSalesEndAt(at.plusDays(1))
                .changedBy("8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e")
                .changedAt(at)
                .build();
    }

    /** Saved AND flushed: the trigger tests need the row in the table, not only in the session. */
    private void persist(EventSalesWindowChange change) {
        changes.save(change);
        entityManager.flush();
    }
}
