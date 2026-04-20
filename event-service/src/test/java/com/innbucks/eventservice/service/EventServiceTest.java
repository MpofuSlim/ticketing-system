package com.innbucks.eventservice.service;

import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventServiceTest {

    @Test
    void updateEvent_rejectsWhenAgentDoesNotMatch() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        EventService service = new EventService(repo, mapper, restTemplate);

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .agentId("owner-agent")
                .title("Old")
                .venue("Venue")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build();

        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateEvent("other-agent", eventId, new UpdateEventRequestDTO()));

        assertEquals("You are not authorized to update this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_adjustsAvailableTicketsByCapacityDiff() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        EventService service = new EventService(repo, mapper, restTemplate);

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .agentId("agent-1")
                .title("Old")
                .venue("Venue")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(100)
                .availableTickets(60)
                .deleted(false)
                .build();

        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toDTO(any(Event.class))).thenReturn(null);

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setTotalCapacity(120); // diff +20

        service.updateEvent("agent-1", eventId, req);

        ArgumentCaptor<Event> savedCaptor = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(savedCaptor.capture());

        Event saved = savedCaptor.getValue();
        assertEquals(120, saved.getTotalCapacity());
        assertEquals(80, saved.getAvailableTickets()); // 60 + 20
    }
}
