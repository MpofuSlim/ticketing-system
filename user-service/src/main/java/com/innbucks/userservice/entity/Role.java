package com.innbucks.userservice.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named bundle of permissions (V35). Roles are DATA — an operator creates one
 * through {@code POST /admin/roles} and it is usable immediately, because it can
 * only ever compose permissions that already exist and are already enforced.
 *
 * <p>The permission vocabulary itself is code, not data — see
 * {@link com.innbucks.userservice.security.PermissionCatalog} for why that
 * asymmetry is deliberate rather than an omission.
 */
@Entity
@Table(name = "roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    /**
     * The role name, and the primary key — role names are the identity used
     * everywhere else (the {@code user_roles.role} column, the JWT {@code roles}
     * claim, {@code @PreAuthorize("hasRole('…')")}), so a surrogate id would add
     * a lookup without buying anything.
     *
     * <p>Uppercase snake case by convention, enforced at the DTO layer. Spring
     * Security prefixes {@code ROLE_} when mapping to an authority, so a name
     * containing whitespace or a lowercase letter would produce an authority
     * nobody can write a working {@code hasRole} for.
     */
    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /**
     * True for the nine roles that shipped as the {@code User.Role} enum.
     *
     * <p>Built-ins cannot be deleted or renamed: code references them by literal
     * name (in {@code @PreAuthorize}, in {@code Services.BUNDLE_ROLES}, in
     * {@code DataInitializer}), so a rename would not fail loudly — it would
     * quietly stop matching, and every check naming the old name would start
     * refusing everyone. Their PERMISSIONS are freely editable; that is the
     * supported way to change what a built-in role can do.
     */
    @Column(name = "builtin", nullable = false)
    @Builder.Default
    private boolean builtin = false;

    /** Email of the admin who created the role. Null for the seeded built-ins. */
    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Permission codes granted by this role, as an {@code @ElementCollection}
     * over {@code role_permissions} rather than a {@code @ManyToMany} to a
     * {@link Permission} entity. The codes are the useful value — they go
     * straight into the JWT — and the descriptions are served from the code
     * catalog, so materialising Permission rows here would buy nothing but an
     * extra join.
     *
     * <p>EAGER because every read of a role is a read of what it grants; there is
     * no access path that wants the row without its permissions.
     *
     * <p>The DB still holds a real foreign key to {@code permissions}, so a code
     * that is not in the catalog cannot be persisted even if a caller bypasses
     * the service-layer validation.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_name"))
    @Column(name = "permission_code", nullable = false)
    @Builder.Default
    private Set<String> permissions = new LinkedHashSet<>();

    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}
