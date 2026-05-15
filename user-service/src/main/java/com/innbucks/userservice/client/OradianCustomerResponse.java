package com.innbucks.userservice.client;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OradianCustomerResponse {
    private String customerId;
    private String oradianExternalId;
    private Long oradianClientId;
    private String clientStatus;
    private String country;
}
