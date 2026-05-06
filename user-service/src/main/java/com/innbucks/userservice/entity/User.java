package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    private String middleName;

    @Column(nullable = false)
    private String lastName;

    @Column(unique = true, nullable = false)
    private String phoneNumber;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @ElementCollection(targetClass = Role.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_default_services", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "service", nullable = false)
    @Builder.Default
    private Set<String> defaultServices = new HashSet<>();

    private boolean mfaEnabled = false;
    private String mfaSecret;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }

    public enum Role {
        SUPER_ADMIN,
        EVENT_ORGANIZER,
        MERCHANT_ADMIN,
        CUSTOMER
    }
}
