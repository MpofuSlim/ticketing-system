package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateServiceRequestDTO;
import com.innbucks.userservice.dto.ServiceRequestResponseDTO;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        ServiceRequest req = serviceRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Service request not found: " + requestId));

        if (req.getStatus() != ServiceRequest.Status.PENDING) {
            throw new RuntimeException("Service request " + requestId + " is not pending (status="
                    + req.getStatus() + ").");
        }

        User user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new RuntimeException("Requesting user no longer exists: id=" + req.getUserId()));

        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("Reviewer not found: " + reviewerEmail));

        user.getDefaultServices().add(req.getService());
        User.Role grantedRole = Services.BUNDLE_ROLES.get(req.getService());
        if (grantedRole != null) {
            user.getRoles().add(grantedRole);
        }
        userRepository.save(user);

        req.setStatus(ServiceRequest.Status.APPROVED);
        req.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        req.setReviewedBy(reviewer.getId());
        ServiceRequest saved = serviceRequestRepository.save(req);

        log.info("Service request approved id={} userId={} service={} reviewerId={}",
                saved.getId(), user.getId(), req.getService(), reviewer.getId());
        return toResponse(saved, user);
    }

    private ServiceRequestResponseDTO toResponse(ServiceRequest req, User user) {
        String email = user != null ? user.getEmail() : null;
        String fullName = user != null
                ? (user.getFirstName() + " " + user.getLastName()).trim()
                : null;
        return ServiceRequestResponseDTO.from(req, email, fullName);
    }
}
