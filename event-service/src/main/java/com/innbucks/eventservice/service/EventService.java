package com.innbucks.eventservice.service;

import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final RestTemplate restTemplate;

    @Value("${seat-service.base-url:http://localhost:8083}")
    private String seatServiceBaseUrl;
    
    public Page<EventResponseDTO> getAllActiveEvents(
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active events from={} to={} venue={} page={} size={} sortBy={}",
                from, to, venue, page, size, sortBy);
        return eventRepository
                .findAllActive(from, to, venue, pageable)
                .map(eventMapper::toDTO);
    }

    public EventResponseDTO getEventById(UUID eventId) {
        log.debug("Fetching event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Event not found eventId={}", eventId);
                    return new RuntimeException("Event not found");
                });
        EventResponseDTO response = eventMapper.toDTO(event);
        response.setSeatCategories(fetchSeatCategoriesForEvent(eventId));
        return response;
    }

    public Page<EventResponseDTO> getEventsByProvince(
            Province province,
            int page,
            int size
    ) {
        // Always sorted by dateTime ascending so the soonest-starting event
        // for the province surfaces first. Only includes events that are
        // non-deleted, agent-active, AND have not yet started.
        Pageable pageable = PageRequest.of(page, size, Sort.by("dateTime").ascending());
        LocalDateTime now = LocalDateTime.now();
        log.debug("Fetching active upcoming events by province={} cutoff={} page={} size={}",
                province, now, page, size);

        Page<Event> entities = eventRepository
                .findByProvinceAndDeletedFalseAndActiveTrueAndDateTimeGreaterThanEqual(
                        province, now, pageable);

        // Assign per-page eventNo 1..N in the order the entities appear (date asc).
        List<EventResponseDTO> dtos = new ArrayList<>(entities.getNumberOfElements());
        int n = 1;
        for (Event event : entities.getContent()) {
            EventResponseDTO dto = eventMapper.toDTO(event);
            dto.setEventNo(n++);
            dtos.add(dto);
        }
        return new PageImpl<>(dtos, pageable, entities.getTotalElements());
    }

    public EventResponseDTO createEvent(String agentId, CreateEventRequestDTO request) {
        log.info("Creating event agentId={} title={} venue={} dateTime={} capacity={}",
                agentId, request.getTitle(), request.getVenue(), request.getDateTime(), request.getTotalCapacity());
        Event event = Event.builder()
                .agentId(agentId)
                .title(request.getTitle())
                .description(request.getDescription())
                .venue(request.getVenue())
                .province(request.getProvince())
                .dateTime(request.getDateTime())
                .totalCapacity(request.getTotalCapacity())
                .availableTickets(request.getTotalCapacity()) // starts fully available
                .deleted(false)
                .build();

        Event saved = eventRepository.save(event);
        log.info("Event created eventId={} agentId={}", saved.getEventId(), agentId);
        return eventMapper.toDTO(saved);
    }

    public EventResponseDTO updateEvent(String agentId, String role, UUID eventId, UpdateEventRequestDTO request) {
        log.info("Updating event eventId={} agentId={} role={}", eventId, agentId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Update failed, event not found eventId={} agentId={}", eventId, agentId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_ADMIN".equals(role);

        // AGENT can update only own event; ADMIN can update any event
        if (!isAdmin && !event.getAgentId().equals(agentId)) {
            log.warn("Unauthorized update attempt eventId={} agentId={} ownerAgentId={}",
                    eventId, agentId, event.getAgentId());
            throw new RuntimeException("You are not authorized to update this event");
        }

        if (request.getTitle() != null)        event.setTitle(request.getTitle());
        if (request.getDescription() != null)  event.setDescription(request.getDescription());
        if (request.getVenue() != null)        event.setVenue(request.getVenue());
        if (request.getProvince() != null)     event.setProvince(request.getProvince());
        if (request.getDateTime() != null)     event.setDateTime(request.getDateTime());
        if (request.getTotalCapacity() != null) {
            // Adjust available tickets by the difference in capacity
            int diff = request.getTotalCapacity() - event.getTotalCapacity();
            event.setTotalCapacity(request.getTotalCapacity());
            event.setAvailableTickets(event.getAvailableTickets() + diff);
        }

        Event saved = eventRepository.save(event);
        log.info("Event updated eventId={} agentId={} title={} venue={} dateTime={}",
                eventId, agentId, saved.getTitle(), saved.getVenue(), saved.getDateTime());
        return eventMapper.toDTO(saved);
    }

    public EventResponseDTO updateEvent(String agentId, UUID eventId, UpdateEventRequestDTO request) {
        return updateEvent(agentId, "ROLE_AGENT", eventId, request);
    }

    public void deleteEvent(String agentId, String role, UUID eventId) {
        log.info("Deleting event eventId={} agentId={} role={}", eventId, agentId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Delete failed, event not found eventId={} agentId={}", eventId, agentId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_ADMIN".equals(role);

        // AGENT can delete only own event; ADMIN can delete any event
        if (!isAdmin && !event.getAgentId().equals(agentId)) {
            log.warn("Unauthorized delete attempt eventId={} agentId={} ownerAgentId={}",
                    eventId, agentId, event.getAgentId());
            throw new RuntimeException("You are not authorized to delete this event");
        }

        event.setDeleted(true);
        eventRepository.save(event);
        log.info("Event deleted (soft) eventId={} agentId={}", eventId, agentId);
    }

    public void deleteEvent(String agentId, UUID eventId) {
        deleteEvent(agentId, "ROLE_AGENT", eventId);
    }

    private List<EventSeatCategoryResponseDTO> fetchSeatCategoriesForEvent(UUID eventId) {
        String url = UriComponentsBuilder
                .fromUriString(seatServiceBaseUrl)
                .path("/seat-categories")
                .queryParam("eventId", eventId)
                .toUriString();

        try {
            SeatCategoryServiceResponseDTO[] categories = restTemplate.getForObject(
                    url,
                    SeatCategoryServiceResponseDTO[].class
            );

            if (categories == null || categories.length == 0) {
                return Collections.emptyList();
            }

            return Arrays.stream(categories)
                    .map(category -> EventSeatCategoryResponseDTO.builder()
                            .name(category.getName())
                            .description(category.getDescription())
                            .categoryPrice(category.getPrice())
                            .sections(mapSectionsWithPrice(category.getSections(), category.getPrice()))
                            .build())
                    .collect(Collectors.toList());
        } catch (RestClientException ex) {
            log.warn("Failed to fetch seat categories for eventId={} from seat-service. Returning event without categories. error={}",
                    eventId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<EventSectionResponseDTO> mapSectionsWithPrice(
            List<SeatCategorySectionDTO> sections,
            java.math.BigDecimal price
    ) {
        if (sections == null || sections.isEmpty()) {
            return Collections.emptyList();
        }

        return sections.stream()
                .map(section -> EventSectionResponseDTO.builder()
                        .section(section.getSection())
                        .seatCount(section.getSeatCount())
                        .price(price)
                        .build())
                .collect(Collectors.toList());
    }
}
