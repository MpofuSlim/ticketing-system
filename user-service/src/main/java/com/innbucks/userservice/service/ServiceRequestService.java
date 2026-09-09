package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateServiceRequestDTO;
import com.innbucks.userservice.dto.ServiceRequestResponseDTO;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.ServiceRequestDecided;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceRequestService {

    private final ServiceRequestRepository serviceRequestRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    /** Submit a request to be granted access to an additional default service bundle. */
    @Transactional
    public ServiceRequestResponseDTO submit(String requesterEmail, CreateServiceRequestDTO request) {
        User user = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new RuntimeException("User not found: " + requesterEmail));

        String service = request.getService().trim().toLowerCase(Locale.ROOT);

        if (!Services.isKnownBundle(service)) {
            throw new RuntimeException("Unknown service bundle: " + request.getService()
                    + ". Valid bundles: " + Services.ALL_BUNDLES);
        }

        if (user.getDefaultServices() != null && user.getDefaultServices().contains(service)) {
            throw new RuntimeException("You already have access to '" + service + "'.");
        }

        // Reject duplicate pending requests for the same bundle.
        serviceRequestRepository
                .findByUserIdAndServiceAndStatus(user.getId(), service, ServiceRequest.Status.PENDING)
                .ifPresent(existing -> {
                    throw new RuntimeException("A pending request for '" + service
                            + "' already exists (id=" + existing.getId() + ").");
                });

        ServiceRequest saved = serviceRequestRepository.save(ServiceRequest.builder()
                .userId(user.getId())
                .service(service)
                .reason(request.getReason().trim())
                .status(ServiceRequest.Status.PENDING)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());

        log.info("Service request submitted id={} userId={} service={}", saved.getId(), user.getId(), service);
        return toResponse(saved, user);
    }

    /**
     * List the caller's service bundles, newest first. Combines two sources:
     *   1. Rows from {@code service_requests} (PENDING / APPROVED) — i.e. anything
     *      they explicitly asked for via the request flow.
     *   2. Bundles in {@code users.default_services} that were never represented
     *      as an APPROVED service_request row (typically picked at registration);
     *      these are surfaced as synthetic APPROVED rows so the caller sees the
     *      full picture of "what services do I have / have I asked for".
     */
    @Transactional(readOnly = true)
    public List<ServiceRequestResponseDTO> listMine(String requesterEmail) {
        User user = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new RuntimeException("User not found: " + requesterEmail));

        List<ServiceRequest> requests = serviceRequestRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId());

        // Bundles already represented as APPROVED in service_requests; skip
        // synthesising duplicates for them below.
        Set<String> alreadyApproved = requests.stream()
                .filter(r -> r.getStatus() == ServiceRequest.Status.APPROVED)
                .map(ServiceRequest::getService)
                .collect(Collectors.toCollection(HashSet::new));

        List<ServiceRequestResponseDTO> result = new ArrayList<>(requests.size()
                + (user.getDefaultServices() == null ? 0 : user.getDefaultServices().size()));

        for (ServiceRequest req : requests) {
            result.add(toResponse(req, user));
        }

        if (user.getDefaultServices() != null) {
            String email = user.getEmail();
            String fullName = (user.getFirstName() + " " + user.getLastName()).trim();
            for (String svc : user.getDefaultServices()) {
                if (alreadyApproved.contains(svc)) continue;
                result.add(ServiceRequestResponseDTO.builder()
                        .userId(user.getId())
                        .userEmail(email)
                        .userFullName(fullName)
                        .service(svc)
                        .status(ServiceRequest.Status.APPROVED.name())
                        .createdAt(user.getCreatedAt())
                        .build());
            }
        }

        // Newest first; null createdAt (shouldn't happen) sinks to the bottom.
        result.sort(Comparator.comparing(
                ServiceRequestResponseDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /** Admin: list every pending request, oldest first. */
    @Transactional(readOnly = true)
    public List<ServiceRequestResponseDTO> listPending() {
        return serviceRequestRepository
                .findByStatusOrderByCreatedAtAsc(ServiceRequest.Status.PENDING)
                .stream()
                .map(req -> toResponse(req, userRepository.findById(req.getUserId()).orElse(null)))
                .toList();
    }

    /**
     * Admin: approve a pending request. Adds the bundle to the user's defaultServices
     * and grants the matching role. The user must log in again to receive a JWT
     * carrying the new service/role claims.
     */
    @Transactional
    public ServiceRequestResponseDTO approve(Long requestId, String reviewerEmail) {
        // Typed, not a bare RuntimeException: the global handler collapses those
        // to a 400 carrying "We couldn't process your request. Please try again.",
        // so an admin acting on an already-decided row was invited to retry an
        // operation that can never succeed. These reach the client intact.
        ServiceRequest req = serviceRequestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Service request not found: " + requestId));

        if (req.getStatus() != ServiceRequest.Status.PENDING) {
            throw alreadyDecided(requestId, req.getStatus());
        }

        User user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new RuntimeException("Requesting user no longer exists: id=" + req.getUserId()));

        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found: " + reviewerEmail));

        user.getDefaultServices().add(req.getService());
        User.Role grantedRole = Services.BUNDLE_ROLES.get(req.getService());
        if (grantedRole != null) {
            user.getRoles().add(grantedRole.name());
        }
        userRepository.save(user);

        req.setStatus(ServiceRequest.Status.APPROVED);
        req.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        req.setReviewedBy(reviewer.getId());
        ServiceRequest saved = serviceRequestRepository.save(req);

        log.info("Service request approved id={} userId={} service={} reviewerId={}",
                saved.getId(), user.getId(), req.getService(), reviewer.getId());
        publishDecision(saved, user, ServiceRequestDecided.Outcome.APPROVED);
        return toResponse(saved, user);
    }

    /**
     * Admin: reject a pending request, with a reason the requester is told.
     *
     * <p><b>Why this had to exist.</b> APPROVED was the only decision that could
     * be recorded — {@code Status} had no REJECTED value and there was no
     * endpoint — so an admin who decided against a request had no action
     * available and it stayed PENDING forever. The queue could not drain, and
     * the console was already rendering and filtering a REJECTED status the
     * database could not store.
     *
     * <p>Grants nothing and touches the user's roles or bundles in no way: the
     * only mutation is on the request row itself.
     */
    @Transactional
    public ServiceRequestResponseDTO reject(Long requestId, String reviewerEmail, String decisionReason) {
        if (decisionReason == null || decisionReason.isBlank()) {
            // Belt-and-braces behind the DTO's @NotBlank: a blank reason tells
            // the requester their request was refused and nothing else, which
            // is what makes them re-submit the identical request.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reason is required when rejecting a service request.");
        }

        ServiceRequest req = serviceRequestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Service request not found: " + requestId));

        if (req.getStatus() != ServiceRequest.Status.PENDING) {
            throw alreadyDecided(requestId, req.getStatus());
        }

        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found: " + reviewerEmail));

        // The requesting account may have been deleted since they applied. That
        // must not block the admin from clearing the row — unlike approve, there
        // is nothing to grant them, so a missing user is simply a request that
        // can be closed and a notification that goes nowhere.
        User user = userRepository.findById(req.getUserId()).orElse(null);

        req.setStatus(ServiceRequest.Status.REJECTED);
        req.setDecisionReason(decisionReason.trim());
        req.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        req.setReviewedBy(reviewer.getId());
        ServiceRequest saved = serviceRequestRepository.save(req);

        log.info("Service request rejected id={} userId={} service={} reviewerId={}",
                saved.getId(), req.getUserId(), req.getService(), reviewer.getId());
        if (user != null) {
            publishDecision(saved, user, ServiceRequestDecided.Outcome.REJECTED);
        }
        return toResponse(saved, user);
    }

    /**
     * Fire-and-forget: {@code ServiceRequestDecisionListener} picks this up
     * AFTER_COMMIT and tells the requester. Published rather than sent inline so
     * a decision that rolls back never notifies anyone it happened, and so the
     * admin's response is not held behind an outbound call.
     */
    private void publishDecision(ServiceRequest req, User user, ServiceRequestDecided.Outcome outcome) {
        if (events == null) {
            return; // plain unit test with no publisher wired
        }
        events.publishEvent(new ServiceRequestDecided(
                req.getId(), user.getId(), user.getEmail(), user.getPhoneNumber(),
                req.getService(), outcome, req.getDecisionReason()));
    }

    /**
     * A request can be decided once. Carries the status it already holds so the
     * admin can see a colleague got there first, rather than being told to
     * "try again" on something that will never change.
     */
    private static ResponseStatusException alreadyDecided(Long requestId, ServiceRequest.Status status) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Service request " + requestId + " is not pending (status=" + status + ").");
    }

    private ServiceRequestResponseDTO toResponse(ServiceRequest req, User user) {
        String email = user != null ? user.getEmail() : null;
        String fullName = user != null
                ? (user.getFirstName() + " " + user.getLastName()).trim()
                : null;
        return ServiceRequestResponseDTO.from(req, email, fullName);
    }
}
