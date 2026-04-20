package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "agent_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private com.innbucks.userservice.entity.User user;

    private String businessName;
    private String businessAddress;
    private String businessEmail;
    private String businessPhoneNumber;
    private String registrationNumber;

    // File path/URL for uploaded company registration documents
    private String metaDataFilePath;

    private int totalEvents = 0;
    private double rating = 0.0;
}
