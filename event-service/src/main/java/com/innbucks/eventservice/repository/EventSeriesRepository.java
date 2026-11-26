package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventSeries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventSeriesRepository extends JpaRepository<EventSeries, UUID> {

    Optional<EventSeries> findByTemplateEventId(UUID templateEventId);

    Page<EventSeries> findByTenantUserUuidOrderByCreatedAtDesc(UUID tenantUserUuid, Pageable pageable);

    Page<EventSeries> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** The generator's work list: series in these statuses, oldest first so none is starved. */
    @Query("SELECT s.seriesId FROM EventSeries s WHERE s.status IN :statuses ORDER BY s.createdAt ASC, s.seriesId ASC")
    List<UUID> findIdsByStatusIn(@Param("statuses") Collection<EventSeries.Status> statuses);
}
