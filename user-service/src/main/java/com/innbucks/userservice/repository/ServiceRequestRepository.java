package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.ServiceRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    List<ServiceRequest> findByStatusOrderByCreatedAtAsc(ServiceRequest.Status status);

    List<ServiceRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ServiceRequest> findByUserIdAndServiceAndStatus(Long userId,
                                                             String service,
                                                             ServiceRequest.Status status);
}
