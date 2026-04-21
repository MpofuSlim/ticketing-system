package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "eureka.client.enabled=false"
})
@Transactional
class EventRepositoryTest {

    @Autowired EventRepository eventRepository;

    @Test
    void findAllActive_filtersByDeletedDateRangeAndVenue() {
        Event e1 = eventRepository.save(Event.builder()
                .agentId("a1")
                .title("E1")
                .venue("Harare Gardens")
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 1, 10, 10, 0))
                .totalCapacity(10)
                .availableTickets(10)
                .deleted(false)
                .build());

        eventRepository.save(Event.builder()
                .agentId("a1")
                .title("E2")
                .venue("Bulawayo Hall")
                .province(Province.BYO)
                .dateTime(LocalDateTime.of(2030, 1, 12, 10, 0))
                .totalCapacity(10)
                .availableTickets(10)
                .deleted(false)
                .build());

        eventRepository.save(Event.builder()
                .agentId("a1")
                .title("Deleted")
                .venue("Harare Gardens")
                .province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 1, 11, 10, 0))
                .totalCapacity(10)
                .availableTickets(10)
                .deleted(true)
                .build());

        Page<Event> page = eventRepository.findAllActive(
                LocalDateTime.of(2030, 1, 9, 0, 0),
                LocalDateTime.of(2030, 1, 11, 23, 59),
                "harare",
                PageRequest.of(0, 10)
        );

        assertEquals(1, page.getTotalElements());
        assertEquals(e1.getEventId(), page.getContent().get(0).getEventId());
    }

    @Test
    void eventNo_isAutoAssignedAndUniqueAcrossInserts() {
        // saveAndFlush forces the INSERT to run so Hibernate reads the
        // IDENTITY-generated eventNo back in the same transaction.
        Event a = eventRepository.saveAndFlush(Event.builder()
                .agentId("a1").title("A").venue("V1").province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 1, 1, 10, 0))
                .totalCapacity(10).availableTickets(10).deleted(false).build());
        Event b = eventRepository.saveAndFlush(Event.builder()
                .agentId("a1").title("B").venue("V2").province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 2, 1, 10, 0))
                .totalCapacity(10).availableTickets(10).deleted(false).build());
        Event c = eventRepository.saveAndFlush(Event.builder()
                .agentId("a1").title("C").venue("V3").province(Province.HRE)
                .dateTime(LocalDateTime.of(2030, 3, 1, 10, 0))
                .totalCapacity(10).availableTickets(10).deleted(false).build());

        assertNotNull(a.getEventNo());
        assertNotNull(b.getEventNo());
        assertNotNull(c.getEventNo());
        // Monotonically increasing, no duplicates.
        assertTrue(a.getEventNo() < b.getEventNo());
        assertTrue(b.getEventNo() < c.getEventNo());
    }
}
