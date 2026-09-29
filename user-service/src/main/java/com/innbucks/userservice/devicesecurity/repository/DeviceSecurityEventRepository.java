package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.entity.DeviceSecurityEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface DeviceSecurityEventRepository extends JpaRepository<DeviceSecurityEvent, Long> {

    Page<DeviceSecurityEvent> findByMsisdnOrderByOccurredAtDescIdDesc(String msisdn, Pageable pageable);

    Page<DeviceSecurityEvent> findByMsisdnAndEventTypeInOrderByOccurredAtDescIdDesc(
            String msisdn, Collection<String> eventTypes, Pageable pageable);

    List<DeviceSecurityEvent> findBySupportRefOrderByOccurredAtAscIdAsc(String supportRef);

    /** The ladder's count: automatic temporary blocks on this pair (a call-center hold is excluded by reason). */
    long countByMsisdnAndInstallIdHashAndEventTypeAndReasonNotAndOccurredAtAfter(
            String msisdn, String installIdHash, String eventType, String excludedReason, LocalDateTime since);

    long countByMsisdnAndActorTypeAndEventTypeInAndOccurredAtAfter(
            String msisdn, String actorType, Collection<String> eventTypes, LocalDateTime since);

    long countByMsisdnAndEventTypeAndPurposeAndOccurredAtAfter(
            String msisdn, String eventType, String purpose, LocalDateTime since);

    long countByInstallIdHashAndEventTypeAndPurposeAndOccurredAtAfter(
            String installIdHash, String eventType, String purpose, LocalDateTime since);

    /** The numbers one device has tried to sign in to recently (§8.4: more than 3 in an hour). */
    @Query("""
            select distinct e.msisdn from DeviceSecurityEvent e
            where e.installIdHash = :installIdHash and e.eventType = 'SIGN_IN_DECISION'
              and e.occurredAt > :since and e.msisdn is not null
            """)
    List<String> distinctNumbersOnDeviceSince(@Param("installIdHash") String installIdHash,
                                              @Param("since") LocalDateTime since);

    @Modifying
    @Query("delete from DeviceSecurityEvent e where e.occurredAt < :cutoff")
    int deleteOccurredBefore(@Param("cutoff") LocalDateTime cutoff);
}
