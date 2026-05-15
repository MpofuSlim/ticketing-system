package com.innbucks.userservice.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OradianCustomerRequest {
    private String firstName;
    private String middleName;
    private String lastName;
    private LocalDate dateOfBirth;
    private String gender;
    private String msisdn;
    private String nationalId;
    private String email;
    private Address address;
    private Map<String, String> clientCustomFields;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String street1;
        private String city;
        private String postCode;
        private String country;
    }
}
