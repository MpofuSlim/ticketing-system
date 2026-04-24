package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerRegistrationResponseDTO {
    private Long userId;
    private String phoneNumber;
    private int tier;
    private boolean verified;
    private String nextStep;
}
