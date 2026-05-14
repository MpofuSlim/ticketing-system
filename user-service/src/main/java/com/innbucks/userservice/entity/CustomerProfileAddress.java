package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfileAddress {

    @Column(name = "address_street1")
    private String street1;

    @Column(name = "address_city")
    private String city;

    @Column(name = "address_post_code")
    private String postCode;

    @Column(name = "address_country")
    private String country;
}
