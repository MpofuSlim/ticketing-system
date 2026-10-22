package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.EventReminderSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventReminderSubscriptionRepository extends JpaRepository<EventReminderSubscription, UUID> {

    Optional<EventReminderSubscription> findByEventIdAndPhoneNumber(UUID eventId, String phoneNumber);

    List<EventReminderSubscription> findByPhoneNumberAndUnsubscribedAtIsNullOrderByCreatedAtDesc(String phoneNumber);

    long countByPhoneNumberAndUnsubscribedAtIsNull(String phoneNumber);

    /** The events' active subscriptions, for the reminder job. */
    List<EventReminderSubscription> findByEventIdInAndUnsubscribedAtIsNull(Collection<UUID> eventIds);

    /**
     * Claim one (subscription, tier) message before it is sent: 1 when this
     * call claimed it, 0 when it was already claimed (by an earlier run, or a
     * replica racing this one). Committed before the send, so a crash loses a
     * reminder rather than repeating one.
     */
    @Modifying
    @Query(value = """
        INSERT INTO price_tier_reminders_sent (subscription_id, price_tier_id, sent_at)
        VALUES (:subscriptionId, :priceTierId, :sentAt)
        ON CONFLICT DO NOTHING
    """, nativeQuery = true)
    int claimReminder(@Param("subscriptionId") UUID subscriptionId,
                      @Param("priceTierId") UUID priceTierId,
                      @Param("sentAt") LocalDateTime sentAt);
}
