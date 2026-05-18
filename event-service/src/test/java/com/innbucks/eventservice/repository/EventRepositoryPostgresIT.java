package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
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
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 6, 1, 18, 0))
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
        Page<Event> page = eventRepository.findAllActiveOnly(null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findByTenantId_nullVenue_doesNotTriggerLowerBytea() {
        Page<Event> page = eventRepository.findByTenantId("t1", null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findByTenantIdActiveOnly_nullVenue_doesNotTriggerLowerBytea() {
        Page<Event> page = eventRepository.findByTenantIdActiveOnly("t1", null, null, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void findAllActive_nonNullVenue_stillMatchesCaseInsensitive() {
        eventRepository.save(Event.builder()
                .tenantId("t2")
                .title("Jazz Night")
                .venue("Reps Theatre")
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 7, 1, 20, 0))
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
        Page<Event> page = eventRepository.searchByKeyword(null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }
}
