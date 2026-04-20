package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserEmail(String userEmail);

    Optional<Booking> findByConfirmationNumber(String confirmationNumber);

    List<Booking> findByEventId(UUID eventId);
}
