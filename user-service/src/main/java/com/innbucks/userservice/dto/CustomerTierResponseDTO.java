package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

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
