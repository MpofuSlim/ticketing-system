package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A row in the {@code permissions} table (V35) — a MIRROR of
 * {@link com.innbucks.userservice.security.PermissionCatalog}, not a second
 * source of truth.
 *
 * <p>The catalog in code decides what permissions mean and which ones exist;
 * {@link com.innbucks.userservice.security.PermissionCatalogInitializer} upserts
 * it into this table at boot. The table earns its keep two ways: it lets
 * {@code role_permissions} carry a real foreign key (so a role can never be
 * persisted holding a permission nothing enforces, even by direct SQL), and it
 * gives an operator something to list when composing a role.
 *
 * <p>Rows are never deleted by the initializer. A permission removed from the
 * catalog in code stops being enforced, but its row and any {@code
 * role_permissions} grants stay — dropping them would silently strip grants an
 * operator deliberately made, and the row is harmless. Clean up in a migration
 * when you are sure.
 */
@Entity
@Table(name = "permissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    @Id
    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
