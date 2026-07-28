package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.FineractGatewayMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FineractGatewayMessageRepository extends JpaRepository<FineractGatewayMessage, Long> {

    Optional<FineractGatewayMessage> findByTenantIdAndFineractId(String tenantId, Long fineractId);

    List<FineractGatewayMessage> findByTenantIdAndFineractIdIn(String tenantId, Collection<Long> fineractIds);
}
