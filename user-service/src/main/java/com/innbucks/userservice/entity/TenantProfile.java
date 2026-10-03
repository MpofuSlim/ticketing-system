package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * LAZY, for the reason CustomerProfile.user gives: the FK
     * (tenant_profiles.user_id, NOT NULL, UNIQUE since V16) is on this side, so
     * a proxy needs no bytecode enhancement. The listings that batch-load
     * profiles (GET /admin/users, GET /admin/users/merchants, the internal
     * tenant lookup) read only getUser().getId(), which a Hibernate proxy
     * answers from the FK without loading anything — so EAGER cost those
     * listings one account reload, plus its two collections, per business
     * account, for nothing. Pinned by UserAssociationFetchCountIT.
     *
     * Excluded from toString/equals/hashCode so neither can initialise the
     * proxy outside a session.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.innbucks.userservice.entity.User user;

    private String businessName;
    private String businessAddress;
    private String businessEmail;
    private String businessPhoneNumber;
    private String registrationNumber;

    private String bpoNumber;

    private String metaDataFilePath;

    private int totalEvents = 0;
    private double rating = 0.0;
}
