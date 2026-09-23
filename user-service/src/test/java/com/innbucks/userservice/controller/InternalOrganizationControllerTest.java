package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The S2S lookups the marketplace will use in step 2. The token check rejects
 * with a SPECIFIC 401 (never a generic 4xx, per CLAUDE.md), and nothing is
 * read until the shared token matches.
 */
class InternalOrganizationControllerTest {

    private static final String TOKEN = "the-shared-secret";

    private final OrganizationService organizations = mock(OrganizationService.class);
    private final InternalOrganizationController controller = new InternalOrganizationController(
            organizations, new InternalTokenAuthorizer(TOKEN, mock(AuditService.class)));

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/users/internal/organizations/names");
        when(req.getRemoteAddr()).thenReturn("203.0.113.7");
        return req;
    }

    @Test
    @DisplayName("names without the internal token is a 401 and reads nothing")
    void namesRequiresToken() {
        ResponseEntity<?> r = controller.names(null, List.of(UUID.randomUUID()), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(organizations);
    }

    @Test
    @DisplayName("admins with the wrong token is a 401 and reads nothing")
    void adminsRequiresToken() {
        ResponseEntity<?> r = controller.admins("wrong", UUID.randomUUID(), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(organizations);
    }

    @Test
    @DisplayName("names returns what the service resolves")
    void namesHappyPath() {
        UUID id = UUID.randomUUID();
        when(organizations.names(any())).thenReturn(List.of(new OrganizationDTOs.OrganizationName(id, "Chikwanha")));

        ResponseEntity<?> r = controller.names(TOKEN, List.of(id), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ApiResult<?>) r.getBody()).getData())
                .isEqualTo(List.of(new OrganizationDTOs.OrganizationName(id, "Chikwanha")));
    }

    @Test
    @DisplayName("an empty ask is an empty answer, without touching the database")
    void namesEmptyAsk() {
        ResponseEntity<?> r = controller.names(TOKEN, List.of(), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(organizations);
    }

    @Test
    @DisplayName("more than 200 ids is refused, so the lookup is never unbounded")
    void namesCapped() {
        List<UUID> tooMany = IntStream.range(0, InternalOrganizationController.MAX_IDS + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();

        ResponseEntity<?> r = controller.names(TOKEN, tooMany, request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(organizations);
    }
}
