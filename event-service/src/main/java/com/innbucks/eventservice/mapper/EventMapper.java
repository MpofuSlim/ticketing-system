package com.innbucks.eventservice.mapper;

import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.entity.Event;
import org.springframework.stereotype.Component;

@Component
public class EventMapper {

    public EventResponseDTO toDTO(Event event) {
        return EventResponseDTO.builder()
                .eventId(event.getEventId())
                .agentId(event.getAgentId())
                .title(event.getTitle())
                .description(event.getDescription())
                .venue(event.getVenue())
                .province(event.getProvince())
                .dateTime(event.getDateTime() == null ? null : event.getDateTime().toLocalDate())
                .totalCapacity(event.getTotalCapacity())
                .availableTickets(event.getAvailableTickets())
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }
}
