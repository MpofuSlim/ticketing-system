package com.innbucks.userservice.devicesecurity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** A rounded location of one SUCCESSFUL sign-in — the raw material of the customer's known places (§8.1). */
@Entity
@Table(name = "device_sign_in_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignInLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
