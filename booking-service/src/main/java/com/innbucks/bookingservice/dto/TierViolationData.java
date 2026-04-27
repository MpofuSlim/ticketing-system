package com.innbucks.bookingservice.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierViolationData {
    private Integer requiredTier;
    private Integer currentTier;
}
