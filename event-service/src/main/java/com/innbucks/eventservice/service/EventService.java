package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final SeatCategoryGateway seatCategoryGateway;

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
        response.setSeatCategories(seatCategoryGateway.fetchForEvent(eventId));
        return response;
    }

    public Page<EventResponseDTO> getEventsByProvince(
            Province province,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("dateTime").ascending());
        LocalDateTime now = LocalDateTime.now();
        log.debug("Fetching active upcoming events by province={} cutoff={} page={} size={}",
                province, now, page, size);

        Page<Event> entities = eventRepository
                .findByProvinceAndDeletedFalseAndActiveTrueAndDateTimeGreaterThanEqual(
                        province, now, pageable);

        List<EventResponseDTO> dtos = new ArrayList<>(entities.getNumberOfElements());
        int n = 1;
        for (Event event : entities.getContent()) {
            EventResponseDTO dto = eventMapper.toDTO(event);
            dto.setEventNo(n++);
            dtos.add(dto);
        }
        return new PageImpl<>(dtos, pageable, entities.getTotalElements());
    }

    public EventResponseDTO createEvent(String tenantId, CreateEventRequestDTO request, MultipartFile eventBanner) {
        log.info("Creating event tenantId={} title={} venue={} dateTime={} capacity={}",
                tenantId, request.getTitle(), request.getVenue(), request.getDateTime(), request.getTotalCapacity());

        if (eventRepository.existsByTenantIdAndTitleAndVenueAndDateTimeAndDeletedFalse(
                tenantId, request.getTitle(), request.getVenue(), request.getDateTime())) {
            log.warn("Event create rejected, duplicate tenantId={} title={} venue={} dateTime={}",
                    tenantId, request.getTitle(), request.getVenue(), request.getDateTime());
            throw new RuntimeException("An event with the same title, venue and date already exists");
        }

        byte[] bannerBytes = null;
        String bannerContentType = null;
        if (eventBanner != null && !eventBanner.isEmpty()) {
            try {
                bannerBytes = eventBanner.getBytes();
            } catch (IOException e) {
                log.error("Failed to read event banner upload tenantId={} title={}", tenantId, request.getTitle(), e);
                throw new RuntimeException("Failed to read event banner upload", e);
            }
            bannerContentType = eventBanner.getContentType();
        }

        Event event = Event.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .description(request.getDescription())
                .venue(request.getVenue())
                .province(request.getProvince())
                .dateTime(request.getDateTime())
                .totalCapacity(request.getTotalCapacity())
                .availableTickets(request.getTotalCapacity())
                .location(request.getLocation())
                .eventBanner(bannerBytes)
                .eventBannerContentType(bannerContentType)
                .deleted(false)
                .build();

        Event saved = eventRepository.save(event);
        log.info("Event created eventId={} tenantId={}", saved.getEventId(), tenantId);
        return eventMapper.toDTO(saved);
    }

    public EventResponseDTO updateEvent(String tenantId, String role, UUID eventId, UpdateEventRequestDTO request) {
        log.info("Updating event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Update failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_ADMIN".equals(role);

        // TENANT can update only own event; ADMIN can update any event
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized update attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new RuntimeException("You are not authorized to update this event");
        }

        if (request.getTitle() != null)        event.setTitle(request.getTitle());
        if (request.getDescription() != null)  event.setDescription(request.getDescription());
        if (request.getVenue() != null)        event.setVenue(request.getVenue());
        if (request.getProvince() != null)     event.setProvince(request.getProvince());
        if (request.getDateTime() != null)     event.setDateTime(request.getDateTime());
        if (request.getTotalCapacity() != null) {
            int diff = request.getTotalCapacity() - event.getTotalCapacity();
            event.setTotalCapacity(request.getTotalCapacity());
            event.setAvailableTickets(event.getAvailableTickets() + diff);
        }

        Event saved = eventRepository.save(event);
        log.info("Event updated eventId={} tenantId={} title={} venue={} dateTime={}",
                eventId, tenantId, saved.getTitle(), saved.getVenue(), saved.getDateTime());
        return eventMapper.toDTO(saved);
    }

    public EventResponseDTO updateEvent(String tenantId, UUID eventId, UpdateEventRequestDTO request) {
        return updateEvent(tenantId, "ROLE_TENANT", eventId, request);
    }

    public void deleteEvent(String tenantId, String role, UUID eventId) {
        log.info("Deleting event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Delete failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_ADMIN".equals(role);

        // TENANT can delete only own event; ADMIN can delete any event
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized delete attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new RuntimeException("You are not authorized to delete this event");
        }

        event.setDeleted(true);
        eventRepository.save(event);
        log.info("Event deleted (soft) eventId={} tenantId={}", eventId, tenantId);
    }

    public void deleteEvent(String tenantId, UUID eventId) {
        deleteEvent(tenantId, "ROLE_TENANT", eventId);
    }

}
