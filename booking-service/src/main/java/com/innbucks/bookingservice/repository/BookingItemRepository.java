package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {
}
