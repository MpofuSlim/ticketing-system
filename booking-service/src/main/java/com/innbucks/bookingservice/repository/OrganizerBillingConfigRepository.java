package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Per-organizer billing-term overrides. Keyed by the organizer's stable
 * user_uuid. Absence of a row means "use the deployment defaults".
 */
public interface OrganizerBillingConfigRepository extends JpaRepository<OrganizerBillingConfig, UUID> {
}
