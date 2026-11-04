package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserResponse", description = "User account details returned by admin endpoints.")
public class UserResponseDTO {

    @Schema(example = "42")
    private Long id;

    @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
            description = "Stable cross-service identifier for this user. Use this — not `id` — when " +
                          "referring to this user from other services or storing a reference to them.")
    private UUID userUuid;

    @Schema(example = "Alice")
    private String firstName;

    @Schema(example = "Jane", nullable = true)
    private String middleName;

    @Schema(example = "Moyo")
    private String lastName;

    @Schema(example = "alice@innbucks.co.zw", nullable = true)
    private String email;

    @Schema(example = "+263771234567",
            description = "The account's SIGN-IN phone. A staff account has none (accepting the staff invite "
                    + "moves the number to the staff profile as a contact number), so it is absent on staff "
                    + "rows; see `contactPhone`.")
    private String phoneNumber;

    @Schema(example = "+263779637565",
            description = "A staff account's contact number, from its staff profile. Only on "
                    + "`GET /admin/users`, only for staff accounts, and only when the caller may read the "
                    + "Staff tab (`staff:read`, SUPER_ADMIN). Never a sign-in identifier. Absent otherwise.")
    private String contactPhone;

    @Schema(example = "[\"EVENT_ORGANIZER\"]",
            description = "The account's roles, WITHOUT `CUSTOMER` when it holds any other role. "
                    + "CUSTOMER is the account's super-app side and is not managed from the console; "
                    + "`PUT /admin/users/{id}/roles` keeps it when a submitted set leaves it out. "
                    + "A customer-only account (listed with `?includeCustomers=true`) shows `[\"CUSTOMER\"]`. "
                    + "A SUPER_ADMIN shows `[\"SUPER_ADMIN\"]` alone: it already holds every permission, so the "
                    + "staff roles beside it add nothing. A business role (EVENT_ORGANIZER, MERCHANT_ADMIN, shop "
                    + "or team roles) on one is still shown after it. The account keeps every role; "
                    + "`GET /admin/super-admins` lists them all.")
    private List<String> roles;

    @Schema(example = "[\"ticketing\"]", nullable = true)
    private List<String> defaultServices;

    @Schema(example = "false", description = "Whether this account is allowed to log in. Set by SUPER_ADMIN via PUT /admin/users/{id}/active.")
    private boolean active;

    @Schema(example = "2026-01-15T10:30:00")
    private LocalDateTime createdAt;

    @Schema(example = "2026-01-15T10:31:42", nullable = true,
            description = "When ANY notification channel confirmed delivery of the user's temporary password. "
                    + "NULL means activation succeeded but no channel reached the user — the admin UI should "
                    + "flag this account and offer a Resend Credentials action (POST /admin/users/{id}/reset-temp-password).")
    private LocalDateTime credentialDeliveredAt;

    @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
            description = "Loyalty merchant the user is scoped to. Populated for SHOP_ADMIN and SHOP_USER.")
    private UUID loyaltyMerchantId;

    @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222", nullable = true,
            description = "Loyalty shop the user operates at. Populated for SHOP_ADMIN and SHOP_USER.")
    private UUID loyaltyShopId;

    @Schema(example = "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", nullable = true,
            description = "For TEAM_MEMBER rows, the user_uuid of the EVENT_ORGANIZER that created " +
                          "this team member. Null for every other role.")
    private UUID createdByOrganizerUuid;

    @Schema(example = "true", description = "Whether this is a business account (set at registration). " +
            "Business accounts carry a tenant profile exposed via businessDetails.")
    private boolean business;

    @Schema(nullable = true, description = "Tenant/business profile for a business account. " +
            "Null for personal accounts.")
    private BusinessDetails businessDetails;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "BusinessDetails", description = "Business/tenant profile attached to a business account.")
    public static class BusinessDetails {
        @Schema(example = "Acme Merchandising (Pvt) Ltd")
        private String businessName;
        @Schema(example = "12 Samora Machel Ave, Harare", nullable = true)
        private String businessAddress;
        @Schema(example = "accounts@acme-merch.co.zw", nullable = true)
        private String businessEmail;
        @Schema(example = "+263242123456", nullable = true)
        private String businessPhoneNumber;
        @Schema(example = "CR-2026-00891", nullable = true)
        private String registrationNumber;
        @Schema(example = "BPO-44512", nullable = true)
        private String bpoNumber;
        @Schema(example = "37", description = "Number of events the organizer has run.")
        private int totalEvents;
        @Schema(example = "4.6", description = "Average organizer rating.")
        private double rating;

        public static BusinessDetails from(TenantProfile p) {
            return BusinessDetails.builder()
                    .businessName(p.getBusinessName())
                    .businessAddress(p.getBusinessAddress())
                    .businessEmail(p.getBusinessEmail())
                    .businessPhoneNumber(p.getBusinessPhoneNumber())
                    .registrationNumber(p.getRegistrationNumber())
                    .bpoNumber(p.getBpoNumber())
                    .totalEvents(p.getTotalEvents())
                    .rating(p.getRating())
                    .build();
        }
    }

    public static UserResponseDTO from(User user) {
        return from(user, null);
    }

    public static UserResponseDTO from(User user, TenantProfile profile) {
        return UserResponseDTO.builder()
                .id(user.getId())
                .userUuid(user.getUserUuid())
                .firstName(user.getFirstName())
                .middleName(user.getMiddleName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .roles(consoleRoles(user.getRoles()))
                .defaultServices(user.getDefaultServices() == null ? null
                        : List.copyOf(user.getDefaultServices()))
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .credentialDeliveredAt(user.getCredentialDeliveredAt())
                .loyaltyMerchantId(user.getLoyaltyMerchantId())
                .loyaltyShopId(user.getLoyaltyShopId())
                .createdByOrganizerUuid(user.getCreatedByOrganizerUuid())
                .business(user.isBusiness())
                .businessDetails(profile == null ? null : BusinessDetails.from(profile))
                .build();
    }

    /**
     * The roles the console shows: {@code CUSTOMER} is dropped when the account
     * holds anything else. A merchant admin who also shops in the super app
     * holds CUSTOMER, and printing it beside their business roles reads as if
     * the console granted or could revoke it — it cannot usefully do either.
     * A customer-only account keeps it, or it would render with no roles at all.
     *
     * <p>Display only. The account keeps the role, and
     * {@code UserAdminService.setRoles} re-adds it when a submitted set omits
     * it, so a console that PUTs back what it was shown cannot strip it.
     */
    static List<String> consoleRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        String customer = User.Role.CUSTOMER.name();
        String superAdmin = User.Role.SUPER_ADMIN.name();
        if (roles.contains(superAdmin)) {
            return superAdminRoles(roles, superAdmin, customer);
        }
        if (roles.size() == 1 || !roles.contains(customer)) return List.copyOf(roles);
        return roles.stream().filter(r -> !customer.equals(r)).toList();
    }

    /**
     * A SUPER_ADMIN holds every permission through the {@code *} wildcard, so
     * the staff roles beside it (PRODUCT_MANAGER, CALL_CENTER_SUPERVISOR,
     * FRAUD_DESK, a custom role, ...) grant it nothing more, and listing them
     * reads as if they did. The row shows {@code SUPER_ADMIN} first and alone,
     * except for a business built-in (EVENT_ORGANIZER, MERCHANT_ADMIN, shop or
     * team roles): those carry scope claims the wildcard does not, and staff
     * never belong to a business, so one on a SUPER_ADMIN is worth seeing.
     *
     * <p>Display only, as for CUSTOMER. The account keeps every role, and they
     * apply again if the person is demoted (demotion keeps the other roles).
     * {@code GET /admin/super-admins} still lists them all, and the role writers
     * refuse a SUPER_ADMIN target, so nothing can PUT this shorter list back.
     */
    private static List<String> superAdminRoles(Set<String> roles, String superAdmin, String customer) {
        List<String> shown = new java.util.ArrayList<>();
        shown.add(superAdmin);
        roles.stream()
                .filter(r -> !superAdmin.equals(r) && !customer.equals(r)
                        && com.innbucks.userservice.security.StaffRoles.isBusinessBuiltIn(r))
                .sorted()
                .forEach(shown::add);
        return List.copyOf(shown);
    }
}
