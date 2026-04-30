package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

// Mirror of user-service's CustomerTierResponseDTO.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerTierResponseDTO {
    private String phoneNumber;
    private int currentTier;
    private Integer nextTier;
}
