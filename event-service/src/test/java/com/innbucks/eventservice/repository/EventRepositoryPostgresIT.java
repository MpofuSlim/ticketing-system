package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the venue / keyword filter queries against a real Postgres via
 * Testcontainers. The H2-based EventRepositoryTest can't catch the failure
 * mode this exists for: when {@code :venue} or {@code :q} is null the
 * PostgreSQL JDBC driver binds the parameter as {@code bytea} (its default
 * for "unknown type" because Java null has no type), and Postgres then
 * refuses to plan a query that contains {@code lower(bytea)} — fails with
 * <em>function lower(bytea) does not exist</em>. H2 is permissive about
 * type inference and silently accepts the same query, so the bug only
 * surfaced in production logs (2026-05-18 incident).
 *
 * <p>The repository now wraps each {@code :venue} / {@code :q} in
 * {@code CAST(... AS string)} so Hibernate forces a varchar bind. These
 * tests lock that in.
 */
@Transactional
class EventRepositoryPostgresIT extends PostgresIntegrationTestBase {

    @Autowired EventRepository eventRepository;

    @Test
    void findAllActive_nullVenue_doesNotTriggerLowerBytea() {
        eventRepository.save(Event.builder()
                .tenantId("t1")
                .title("Open Air Concert")
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 6, 1, 18, 0))
                .endDateTime(LocalDateTime.of(2030, 6, 1, 20, 0))
                .totalCapacity(500)
                .availableTickets(500)
                .active(true)
                .deleted(false)
                .build());

        Page<Event> page = eventRepository.findAllActive(null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findAllActiveOnly_nullVenue_doesNotTriggerLowerBytea() {
        Page<Event> page = eventRepository.findAllActiveOnly(
                null, null, null, null,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findByTenantId_nullVenue_doesNotTriggerLowerBytea() {
        Page<Event> page = eventRepository.findByTenantId("t1", null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findByTenantIdActiveOnly_nullVenue_doesNotTriggerLowerBytea() {
        Page<Event> page = eventRepository.findByTenantIdActiveOnly(
                "t1", null, null, null, null,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findAllActive_nonNullVenue_stillMatchesCaseInsensitive() {
        eventRepository.save(Event.builder()
                .tenantId("t2")
                .title("Jazz Night")
                .venue("Reps Theatre")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 7, 1, 20, 0))
                .endDateTime(LocalDateTime.of(2030, 7, 1, 22, 0))
                .totalCapacity(200)
                .availableTickets(200)
                .active(true)
                .deleted(false)
                .build());

        Page<Event> page = eventRepository.findAllActive(null, null, "reps", PageRequest.of(0, 10));

        assertThat(page.getContent()).anySatisfy(e -> assertThat(e.getVenue()).isEqualTo("Reps Theatre"));
    }

    @Test
    void searchByKeyword_nullQ_doesNotTriggerLowerBytea() {
        // The searchByKeyword query has @Param("q") in three LIKE branches;
        // a null q must not crash with lower(bytea). The result is undefined
        // (every row evaluates the LIKE branches to NULL → false → no match);
        // what matters is that the query plans + executes.
        Page<Event> page = eventRepository.searchByKeyword(
                null,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findAllActiveOnly_hidesEventsWhoseEndDateTimeHasPassed_evenIfStillFlaggedActive() {
        // Regression guard for the "ended event still shows up in the customer
        // listing" bug. An event whose endDateTime is in the past must NOT
        // appear in the active listing — independent of whether the expiry
        // scheduler has flipped active=false yet. We persist it with active=true
        // on purpose to simulate the window between event-end and the next
        // scheduler tick.
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        Event past = eventRepository.save(Event.builder()
                .tenantId("t1")
                .title("Sunday Show (ended)")
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(now.minusDays(2))
                .endDateTime(now.minusHours(6))
                .totalCapacity(100)
                .availableTickets(100)
                .active(true) // scheduler hasn't flipped this yet
                .deleted(false)
                .build());

        Event future = eventRepository.save(Event.builder()
                .tenantId("t1")
                .title("Upcoming Show")
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(1))
                .endDateTime(now.plusDays(1).plusHours(3))
                .totalCapacity(100)
                .availableTickets(100)
                .active(true)
                .deleted(false)
                .build());

        Page<Event> page = eventRepository.findAllActiveOnly(
                null, null, null, null, now, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(Event::getEventId)
                .contains(future.getEventId())
                .doesNotContain(past.getEventId());
    }

    @Test
    void findAllActiveOnly_excludesAdminRejectedEvents() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        Event ok = eventRepository.save(Event.builder()
                .tenantId("t-rej").title("Clean Show").venue("HICC")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(1)).endDateTime(now.plusDays(1).plusHours(2))
                .totalCapacity(100).availableTickets(100)
                .active(true).rejected(false).deleted(false).build());

        // active=true AND rejected=true on purpose: even though the service
        // forces active=false on reject, the query's rejected=false filter must
        // independently keep this out of the public listing.
        Event rejected = eventRepository.save(Event.builder()
                .tenantId("t-rej").title("Blocked Show").venue("HICC")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(1)).endDateTime(now.plusDays(1).plusHours(2))
                .totalCapacity(100).availableTickets(100)
                .active(true).rejected(true).deleted(false).build());

        Page<Event> page = eventRepository.findAllActiveOnly(null, null, null, null, now, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Event::getEventId)
                .contains(ok.getEventId())
                .doesNotContain(rejected.getEventId());
    }

    @Test
    void findAllInactiveOnly_returnsInactiveEvents_includingRejected_butNotActive() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        Event draft = eventRepository.save(Event.builder()
                .tenantId("t-inact").title("Draft Show").venue("Reps")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(3)).endDateTime(now.plusDays(3).plusHours(2))
                .totalCapacity(50).availableTickets(50)
                .active(false).rejected(false).deleted(false).build());

        Event rejected = eventRepository.save(Event.builder()
                .tenantId("t-inact").title("Rejected Show").venue("Reps")
                .country("Zimbabwe").category(EventCategory.COMEDY)
                .startDateTime(now.plusDays(4)).endDateTime(now.plusDays(4).plusHours(2))
                .totalCapacity(50).availableTickets(50)
                .active(false).rejected(true).deleted(false).build());

        Event live = eventRepository.save(Event.builder()
                .tenantId("t-inact").title("Live Show").venue("Reps")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(5)).endDateTime(now.plusDays(5).plusHours(2))
                .totalCapacity(50).availableTickets(50)
                .active(true).rejected(false).deleted(false).build());

        Page<Event> page = eventRepository.findAllInactiveOnly(null, null, null, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Event::getEventId)
                .contains(draft.getEventId(), rejected.getEventId())
                .doesNotContain(live.getEventId());
    }

    @Test
    void findByCountryActiveUpcoming_excludesRejectedEvents() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        Event ok = eventRepository.save(Event.builder()
                .tenantId("t-c").title("KE Clean").venue("KICC")
                .country("Kenya").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(2)).endDateTime(now.plusDays(2).plusHours(2))
                .totalCapacity(100).availableTickets(100)
                .active(true).rejected(false).deleted(false).build());

        Event rejected = eventRepository.save(Event.builder()
                .tenantId("t-c").title("KE Blocked").venue("KICC")
                .country("Kenya").category(EventCategory.CONCERT)
                .startDateTime(now.plusDays(2)).endDateTime(now.plusDays(2).plusHours(2))
                .totalCapacity(100).availableTickets(100)
                .active(true).rejected(true).deleted(false).build());

        Page<Event> page = eventRepository
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                        "kenya", now, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(Event::getEventId)
                .contains(ok.getEventId())
                .doesNotContain(rejected.getEventId());
    }
}
