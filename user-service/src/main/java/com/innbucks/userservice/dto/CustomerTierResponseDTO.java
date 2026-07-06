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
    // OWASP A01: GET /auth/customer/tier is PUBLIC and keyed only by a phone
    // number so the mobile app can route the pre-login registration flow. This
    // response must stay limited to the non-sensitive tier-progression fields —
    // it must NOT carry the customer's email (or any other PII), or it becomes
    // an unauthenticated phone -> email harvesting / account-existence oracle.
    // Do not re-add an `email` field here.
    private int currentTier;
    private Integer nextTier;
}
