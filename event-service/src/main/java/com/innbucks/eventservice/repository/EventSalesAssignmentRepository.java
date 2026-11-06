package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventSalesAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventSalesAssignmentRepository extends JpaRepository<EventSalesAssignment, UUID> {

    List<EventSalesAssignment> findByScannerIdIn(Collection<UUID> scannerIds);

    Optional<EventSalesAssignment> findByScannerIdAndEventId(UUID scannerId, UUID eventId);

    @Modifying
    @Query("delete from EventSalesAssignment a where a.scannerId = :scannerId and a.eventId = :eventId")
    int deleteByScannerIdAndEventId(@Param("scannerId") UUID scannerId, @Param("eventId") UUID eventId);
}
