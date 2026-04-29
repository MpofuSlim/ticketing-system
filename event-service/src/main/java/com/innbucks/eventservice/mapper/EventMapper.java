package com.innbucks.eventservice.mapper;

import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Location;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class EventMapper {

    public EventResponseDTO toDTO(Event event) {
        boolean hasBanner = event.getBannerImage() != null && event.getBannerImage().length > 0
                && event.getBannerContentType() != null && !event.getBannerContentType().isBlank();
        return EventResponseDTO.builder()
                .eventId(event.getEventId())
                .tenantId(event.getTenantId())
                .title(event.getTitle())
                .description(event.getDescription())
                .venue(event.getVenue())
                .province(event.getProvince())
                .location(toLocationDTO(event.getLocation()))
                .bannerUrl(hasBanner ? "/events/" + event.getEventId() + "/banner" : null)
                .eventBanner(hasBanner ? toDataUri(event.getBannerContentType(), event.getBannerImage()) : null)
                .dateTime(event.getDateTime() == null ? null : event.getDateTime().toLocalDate())
                .totalCapacity(event.getTotalCapacity())
                .availableTickets(event.getAvailableTickets())
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

    private static String toDataUri(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
