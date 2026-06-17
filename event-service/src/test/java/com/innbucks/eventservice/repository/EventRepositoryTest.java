package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventRepositoryTest {

    @Autowired EventRepository eventRepository;

    // Stable per-organizer UUID for this test class — replaces the legacy
    // "a1" tenant-id string seed.
    private static final UUID ORGANIZER_A1 = UUID.randomUUID();

    @Test
    void findAllActive_filtersByDeletedDateRangeAndVenue() {
        Event e1 = eventRepository.save(Event.builder()
                .tenantUserUuid(ORGANIZER_A1)
                .title("E1")
                .venue("Harare Gardens")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 1, 10, 10, 0))
                .endDateTime(LocalDateTime.of(2030, 1, 10, 12, 0))
                .totalCapacity(10)
                .availableTickets(10)
                .deleted(false)
                .build());

        eventRepository.save(Event.builder()
                .tenantUserUuid(ORGANIZER_A1)
                .title("E2")
                .venue("Bulawayo Hall")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 1, 12, 10, 0))
                .endDateTime(LocalDateTime.of(2030, 1, 12, 12, 0))
                .totalCapacity(10)
                .availableTickets(10)
                .deleted(false)
                .build());

        eventRepository.save(Event.builder()
                .tenantUserUuid(ORGANIZER_A1)
                .title("Deleted")
                .venue("Harare Gardens")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 1, 11, 10, 0))
                .endDateTime(LocalDateTime.of(2030, 1, 11, 12, 0))
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
}
