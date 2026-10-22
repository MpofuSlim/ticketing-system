package com.innbucks.eventservice.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class SeatCategoryServiceResponseDTO {
    private UUID eventId;
    private String name;
    private String description;
    /** The category's full price. */
    private BigDecimal price;
    /** The early-bird offer, or null; see {@link EarlyBird}. */
    private EarlyBird earlyBird;
    private List<SeatCategorySectionDTO> sections;

    /**
     * seat-service's early-bird offer. Its {@code active} flag and the
     * category's {@code currentPrice} are deliberately NOT read: this answer is
     * cached, so event-service works out which price applies at each read from
     * the price and end. {@code endsAt} is a string because seat-service
     * renders it at the market offset or as {@code Z} depending on the caller,
     * and an unreadable one must drop the offer, not the whole category list.
     */
    @Data
    public static class EarlyBird {
        private BigDecimal price;
        private String endsAt;
    }
}
