package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.OrganizationMember;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for organizations (V39). */
public final class OrganizationDTOs {

    private OrganizationDTOs() {}

    @Schema(name = "OrganizationSummary",
            description = "One organization the caller belongs to — a row of the organization picker.")
    public record OrganizationSummary(
            @Schema(example = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f") UUID organizationId,
            @Schema(example = "Chikwanha Traders") String name,
            @Schema(example = "OWNER", description = "The caller's role in this organization: OWNER, ADMIN or STAFF.")
            String role,
            @Schema(example = "ACTIVE", description = "ACTIVE or SUSPENDED. A suspended organization cannot be chosen.")
            String status,
            @Schema(example = "[\"marketplace\"]", description = "The organization's ACTIVE products.")
            List<String> products) {}

    @Schema(name = "Organization")
    public record OrganizationResponse(
            @Schema(example = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f") UUID organizationId,
            @Schema(example = "Chikwanha Traders") String name,
            @Schema(example = "rudo@chikwanha-traders.co.zw", nullable = true) String contactEmail,
            @Schema(example = "+263772123456", nullable = true) String contactPhone,
            @Schema(example = "12 Samora Machel Ave, Harare", nullable = true) String address,
            @Schema(example = "BR-2024-0417", nullable = true) String registrationNumber,
            @Schema(example = "ACTIVE") String status,
            @Schema(example = "[\"marketplace\"]") List<String> products,
            @Schema(example = "OWNER", description = "The caller's role in this organization.") String yourRole) {}

    @Data
    @Schema(name = "UpdateOrganizationRequest",
            description = "Replace the organization's profile. Every field is written as sent; send the "
                    + "current value to keep one.")
    public static class UpdateOrganizationRequest {

        @NotBlank(message = "name is required")
        @Size(max = 255)
        @Schema(example = "Chikwanha Traders")
        private String name;

        @Email
        @Size(max = 255)
        @Schema(example = "rudo@chikwanha-traders.co.zw", nullable = true)
        private String contactEmail;

        @Size(max = 32)
        @Schema(example = "+263772123456", nullable = true)
        private String contactPhone;

        @Size(max = 255)
        @Schema(example = "12 Samora Machel Ave, Harare", nullable = true)
        private String address;

        @Size(max = 255)
        @Schema(example = "BR-2024-0417", nullable = true)
        private String registrationNumber;
    }

    @Schema(name = "OrganizationMember")
    public record MemberResponse(
            @Schema(example = "3f6c1a2b-7d8e-4f90-a1b2-c3d4e5f60718") UUID userUuid,
            @Schema(example = "Rudo") String firstName,
            @Schema(example = "Chikwanha") String lastName,
            @Schema(example = "rudo@chikwanha-traders.co.zw", nullable = true) String email,
            @Schema(example = "OWNER") String role,
            @Schema(example = "2026-09-23T12:15:00+02:00",
                    description = "When they joined, in the market's local time with its offset.")
            LocalDateTime joinedAt) {}

    @Data
    @Schema(name = "AddOrganizationMemberRequest",
            description = "Add an EXISTING account to the organization. The person must already have "
                    + "an account; this does not create one.")
    public static class AddMemberRequest {

        @NotBlank(message = "email is required")
        @Email
        @Size(max = 255)
        @Schema(example = "tendai@chikwanha-traders.co.zw")
        private String email;

        @NotNull(message = "role is required")
        @Schema(example = "STAFF", description = "OWNER, ADMIN or STAFF. Only an OWNER may add an OWNER or "
                + "an ADMIN; an ADMIN may add STAFF.")
        private OrganizationMember.Role role;
    }

    @Data
    @Schema(name = "ChangeOrganizationRoleRequest")
    public static class ChangeRoleRequest {

        @NotNull(message = "role is required")
        @Schema(example = "ADMIN", description = "OWNER, ADMIN or STAFF. OWNER only.")
        private OrganizationMember.Role role;
    }

    @Data
    @Schema(name = "SelectOrganizationRequest",
            description = "The organization this session should act for.")
    public static class SelectOrganizationRequest {

        @NotNull(message = "organizationId is required")
        @Schema(example = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f")
        private UUID organizationId;
    }

    @Schema(name = "OrganizationDirectoryEntry",
            description = "One organization in the platform directory (SUPER_ADMIN). Its id is what "
                    + "loyalty's `POST /loyalty/merchants` and the marketplace's on-behalf listing "
                    + "create take to act for this business.")
    public record DirectoryEntry(
            @Schema(example = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f") UUID organizationId,
            @Schema(example = "Chikwanha Traders") String name,
            @Schema(example = "ACTIVE", description = "ACTIVE or SUSPENDED.") String status,
            @Schema(example = "[\"loyalty\", \"marketplace\"]",
                    description = "The organization's ACTIVE products.")
            List<String> products,
            @Schema(example = "[\"rudo@chikwanha-traders.co.zw\"]",
                    description = "Email of every OWNER, so two businesses with one name can be told "
                            + "apart. Empty when no owner has an email on file.")
            List<String> ownerEmails,
            @Schema(example = "rudo@chikwanha-traders.co.zw", nullable = true) String contactEmail,
            @Schema(example = "2026-09-23T12:15:00+02:00",
                    description = "When the organization was created, in the market's local time with its offset.")
            LocalDateTime createdAt) {}

    @Schema(name = "OrganizationDirectoryPage")
    public record DirectoryPage(
            List<DirectoryEntry> content,
            @Schema(example = "1") long totalElements,
            @Schema(example = "1") int totalPages,
            @Schema(example = "0", description = "Zero-based page index.") int number,
            @Schema(example = "20") int size) {}

    /** S2S: an organization's display name. */
    public record OrganizationName(UUID organizationId, String name) {}

    /** S2S: a person who may act for an organization (OWNER or ADMIN). */
    public record OrganizationAdmin(UUID userUuid, String email) {}
}
