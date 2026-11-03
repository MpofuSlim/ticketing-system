package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One add-on bought with a ticket (V33), as bought: an organizer renaming,
 * repricing or removing the add-on later never changes it. Also the element
 * stored in {@code booking_items.add_ons}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "BookingAddOn")
public class BookingAddOnDTO {

    @Schema(example = "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01")
    private UUID addOnId;

    @Schema(example = "Parking pass")
    private String name;

    @Schema(example = "5.00")
    private BigDecimal price;
}
