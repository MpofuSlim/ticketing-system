package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.Permission;
import com.innbucks.userservice.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mirrors {@link PermissionCatalog} into the {@code permissions} table at boot,
 * so a release that adds a permission does not also need a migration just to
 * make it grantable.
 *
 * <p>Without this, adding {@code refunds:approve} to the catalog and enforcing it
 * with a {@code @PreAuthorize} would leave the code ungrantable: the
 * {@code role_permissions} foreign key would reject it, and the failure would
 * land on the operator trying to build a role, long after the deploy, looking
 * like a bug in the roles API.
 *
 * <p>Runs before {@code DataInitializer}'s default order so the seeded
 * SUPER_ADMIN's role grants resolve against a complete catalog on a first-ever
 * boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(0)
public class PermissionCatalogInitializer implements CommandLineRunner {

    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(String... args) {
        List<Permission> toWrite = new ArrayList<>();

        for (Map.Entry<String, String> entry : PermissionCatalog.ALL.entrySet()) {
            String code = entry.getKey();
            String description = entry.getValue();

            Permission existing = permissionRepository.findById(code).orElse(null);
            if (existing == null) {
                toWrite.add(Permission.builder().code(code).description(description).build());
            } else if (!description.equals(existing.getDescription())) {
                // Descriptions are operator-facing text in GET /admin/permissions.
                // Code owns them, so an edited description in the catalog should
                // reach the table; the grant itself is untouched either way.
                existing.setDescription(description);
                toWrite.add(existing);
            }
        }

        if (toWrite.isEmpty()) {
            log.debug("Permission catalog already in sync ({} permissions)", PermissionCatalog.ALL.size());
            return;
        }

        permissionRepository.saveAll(toWrite);
        log.info("Permission catalog synced: {} row(s) written, {} permissions total",
                toWrite.size(), PermissionCatalog.ALL.size());

        // Deliberately no delete pass. A permission dropped from the catalog
        // stops being enforced the moment its @PreAuthorize goes, but deleting
        // the row would cascade away every role_permissions grant an operator
        // made — silently editing their roles. Leave the orphan; clean it up in
        // a migration once you are sure nothing wants it back.
    }
}
