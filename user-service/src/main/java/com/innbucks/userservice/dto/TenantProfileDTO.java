package com.innbucks.userservice.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TenantProfileDTO {
    private Long id;
    private String businessName;
    private int totalEvents;
    private double rating;
}
