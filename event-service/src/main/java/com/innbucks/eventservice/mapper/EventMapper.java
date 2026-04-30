package com.innbucks.eventservice.mapper;

import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Location;
import org.springframework.stereotype.Component;

@Component
public class EventMapper {

    public EventResponseDTO toDTO(Event event) {
        return EventResponseDTO.builder()
                .eventId(event.getEventId())
                .tenantId(event.getTenantId())
                .title(event.getTitle())
                .description(event.getDescription())
                .venue(event.getVenue())
                .province(event.getProvince())
                .location(toLocationDTO(event.getLocation()))
                .bannerUrl(event.getBannerContentType() != null && !event.getBannerContentType().isBlank()
                        ? "/events/" + event.getEventId() + "/banner"
                        : null)
                .dateTime(event.getDateTime() == null ? null : event.getDateTime().toLocalDate())
                .totalCapacity(event.getTotalCapacity())
                .availableTickets(event.getAvailableTickets())
                .active(event.isActive())
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }

    private LocationDTO toLocationDTO(Location location) {
        if (location == null || (location.getLatitude() == null && location.getLongitude() == null)) {
            return null;
        }
        return LocationDTO.builder()
                .latitude(location.getLatitude())
                .longitude(location.getLongitude())
                .build();
    }
}
