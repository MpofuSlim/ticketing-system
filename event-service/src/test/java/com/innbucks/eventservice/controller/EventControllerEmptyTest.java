package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the "404-on-empty-collection" bug.
 *
 * <p>These browse endpoints used to {@code return 404 "Not found"} when the
 * result page was empty. That's non-RESTful — a collection with no matches is
 * a 200 + empty page, not a missing resource — and it made every fresh / empty
 * environment look broken: {@code GET /events} returned 404 on a DB with no
 * events, which then surfaced through the gateway as a blanket 404 that looked
 * like a routing failure. Pin the corrected behaviour so it can't regress.
 */
class EventControllerEmptyTest {

    @Test
    void getAllEvents_emptyResult_returns200WithEmptyPage_notNotFound() {
        EventService svc = mock(EventService.class);
        when(svc.getAllActiveEvents(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<ApiResult<Page<EventResponseDTO>>> resp =
                new EventController(svc).getAllEvents(null, null, null, null, 0, 10, "startDateTime");

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "empty list must be 200, never 404");
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().getData().isEmpty());
    }
}
