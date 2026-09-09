package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.ServiceRequestResponseDTO;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.ServiceRequestDecided;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@code ServiceRequestService.reject} and the decision notification.
 *
 * <p><b>The gap this closes.</b> APPROVED was the only decision that could be
 * recorded — {@code Status} had no REJECTED value and no endpoint set one — so
 * an admin who decided against a request had no action available and it stayed
 * PENDING forever. The queue could not drain, and the console was already
 * rendering and filtering a REJECTED status the database could not store.
 * Separately, neither outcome told the requester anything.
 *
 * <p>Pure Mockito — no Spring context, no Docker.
 */
class ServiceRequestRejectTest {

    private static final String REVIEWER = "admin@innbucks.co.zw";
    private static final String WHY = "Your business verification is still outstanding.";

    @Test
    void rejectRecordsTheDecision_theReason_andWhoDecidedIt() {
        Fixture f = new Fixture();

        ServiceRequestResponseDTO dto = f.service.reject(14L, REVIEWER, WHY);

        assertEquals("REJECTED", dto.getStatus());
        assertEquals(WHY, dto.getDecisionReason());
        assertEquals(99L, dto.getReviewedBy(), "the deciding admin must be attributable after the fact");
        assertNotNull(dto.getReviewedAt());
        // The requester's own justification is untouched — the two reasons are
        // different fields and conflating them would overwrite the applicant.
        assertEquals("We want to sell on the marketplace.", dto.getReason());
    }

    @Test
    void rejectGrantsNothing_leavingRolesAndBundlesExactlyAsTheyWere() {
        // The whole safety property of a rejection: it must be a decision on a
        // row, never a mutation of the account.
        Fixture f = new Fixture();
        Set<String> rolesBefore = new LinkedHashSet<>(f.requester.getRoles());
        Set<String> bundlesBefore = new LinkedHashSet<>(f.requester.getDefaultServices());

        f.service.reject(14L, REVIEWER, WHY);

        assertEquals(rolesBefore, f.requester.getRoles());
        assertEquals(bundlesBefore, f.requester.getDefaultServices());
        verify(f.users, never()).save(any(User.class));
    }

    @Test
    void rejectNotifiesTheRequester_withTheReason() {
        // Without this the requester cannot tell a refusal from a request still
        // waiting, so they re-submit the identical one rather than fixing it.
        Fixture f = new Fixture();

        f.service.reject(14L, REVIEWER, WHY);

        ServiceRequestDecided event = f.capturedEvent();
        assertEquals(ServiceRequestDecided.Outcome.REJECTED, event.outcome());
        assertEquals(WHY, event.decisionReason());
        assertEquals("marketplace", event.service());
        assertEquals("alice@rudo.co.zw", event.email());
        assertEquals("+263771234567", event.phoneNumber());
    }

    @Test
    void approveAlsoNotifiesTheRequester_whichItNeverUsedTo() {
        Fixture f = new Fixture();

        f.service.approve(14L, REVIEWER);

        ServiceRequestDecided event = f.capturedEvent();
        assertEquals(ServiceRequestDecided.Outcome.APPROVED, event.outcome());
        assertEquals("marketplace", event.service());
    }

    @Test
    void approveStillGrantsTheBundle() {
        // Guard against the notification change altering what approve does.
        Fixture f = new Fixture();

        f.service.approve(14L, REVIEWER);

        assertTrue(f.requester.getDefaultServices().contains("marketplace"));
        verify(f.users).save(f.requester);
    }

    @Test
    void aBlankReasonIsRefused() {
        Fixture f = new Fixture();

        assertThrows(RuntimeException.class, () -> f.service.reject(14L, REVIEWER, "   "));
        assertThrows(RuntimeException.class, () -> f.service.reject(14L, REVIEWER, null));
        verify(f.requests, never()).save(any());
    }

    @Test
    void anAlreadyDecidedRequestCannotBeRejected() {
        // Both directions of double-decision: the row is closed once decided.
        Fixture f = new Fixture();
        f.request.setStatus(ServiceRequest.Status.APPROVED);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> f.service.reject(14L, REVIEWER, WHY));
        assertTrue(ex.getMessage().contains("not pending"), ex.getMessage());
    }

    @Test
    void anUnknownRequestIsRefused() {
        Fixture f = new Fixture();
        when(f.requests.findById(99L)).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> f.service.reject(99L, REVIEWER, WHY));
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void theRefusalsAreTYPED_soTheAdminIsToldWhy() {
        // A bare RuntimeException is collapsed by GlobalExceptionHandler into a
        // 400 carrying "We couldn't process your request. Please try again." —
        // which invites an admin to retry an operation that can never succeed,
        // and hides that a colleague already decided the row. Both decision
        // paths therefore throw types that survive to the client.
        Fixture missing = new Fixture();
        when(missing.requests.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> missing.service.reject(99L, REVIEWER, WHY));
        assertThrows(NotFoundException.class, () -> missing.service.approve(99L, REVIEWER));

        Fixture decided = new Fixture();
        decided.request.setStatus(ServiceRequest.Status.APPROVED);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> decided.service.reject(14L, REVIEWER, WHY));
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.resolve(ex.getStatusCode().value()));
        // The status it already holds is named, so the admin can see what happened.
        assertTrue(ex.getReason().contains("APPROVED"), ex.getReason());
    }

    @Test
    void aRequestWhoseUserIsGoneCanStillBeClosed() {
        // Unlike approve there is nothing to grant, so a deleted requester must
        // not leave the row stuck PENDING in the admin's queue forever.
        Fixture f = new Fixture();
        when(f.users.findById(42L)).thenReturn(Optional.empty());

        ServiceRequestResponseDTO dto = f.service.reject(14L, REVIEWER, WHY);

        assertEquals("REJECTED", dto.getStatus());
        assertNull(dto.getUserEmail(), "no user, so no contact to report");
        // ...and nobody is notified, rather than a send to a null address.
        verify(f.events, never()).publishEvent(any(ServiceRequestDecided.class));
    }

    // ---- fixture ------------------------------------------------------------

    private static final class Fixture {
        final ServiceRequestRepository requests = mock(ServiceRequestRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        final ServiceRequestService service;
        final User requester;
        final ServiceRequest request;

        Fixture() {
            requester = User.builder()
                    .id(42L)
                    .email("alice@rudo.co.zw")
                    .phoneNumber("+263771234567")
                    .firstName("Alice").lastName("Moyo")
                    .roles(new LinkedHashSet<>(List.of(User.Role.MERCHANT_ADMIN.name())))
                    .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                    .build();
            User reviewer = User.builder()
                    .id(99L).email(REVIEWER)
                    .firstName("Super").lastName("Admin")
                    .roles(new LinkedHashSet<>(List.of(User.Role.SUPER_ADMIN.name())))
                    .defaultServices(new LinkedHashSet<>())
                    .build();
            request = ServiceRequest.builder()
                    .id(14L)
                    .userId(42L)
                    .service("marketplace")
                    .reason("We want to sell on the marketplace.")
                    .status(ServiceRequest.Status.PENDING)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();

            when(requests.findById(14L)).thenReturn(Optional.of(request));
            when(requests.save(any(ServiceRequest.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(users.findById(42L)).thenReturn(Optional.of(requester));
            when(users.findByEmail(REVIEWER)).thenReturn(Optional.of(reviewer));
            when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service = new ServiceRequestService(requests, users, events);
        }

        ServiceRequestDecided capturedEvent() {
            ArgumentCaptor<ServiceRequestDecided> captor =
                    ArgumentCaptor.forClass(ServiceRequestDecided.class);
            verify(events).publishEvent(captor.capture());
            return captor.getValue();
        }
    }
}
