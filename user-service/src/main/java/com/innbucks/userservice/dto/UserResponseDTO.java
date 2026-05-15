package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserResponse", description = "User account details returned by admin endpoints.")
public class UserResponseDTO {

    @Schema(example = "42")
    private Long id;

    @Schema(example = "Alice")
    private String firstName;

    @Schema(example = "Jane", nullable = true)
    private String middleName;

    @Schema(example = "Moyo")
    private String lastName;

    @Schema(example = "alice@innbucks.co.zw", nullable = true)
    private String email;

    @Schema(example = "+263771234567")
    private String phoneNumber;

    @Schema(example = "[\"EVENT_ORGANIZER\"]")
    private List<String> roles;

    @Schema(example = "[\"ticketing\"]", nullable = true)
    private List<String> defaultServices;

    @Schema(example = "false", description = "Whether this account is allowed to log in. Set by SUPER_ADMIN via PUT /admin/users/{id}/active.")
    private boolean active;

    @Schema(example = "2026-01-15T10:30:00")
    private LocalDateTime createdAt;

    @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
            description = "Loyalty merchant the user is scoped to. Populated for SHOP_ADMIN and SHOP_USER.")
    private UUID loyaltyMerchantId;

    @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222", nullable = true,
            description = "Loyalty shop the user operates at. Populated for SHOP_ADMIN and SHOP_USER.")
    private UUID loyaltyShopId;

    public static UserResponseDTO from(User user) {
        return UserResponseDTO.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .middleName(user.getMiddleName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .roles(user.getRoles() == null ? List.of()
                        : user.getRoles().stream().map(Enum::name).collect(Collectors.toList()))
                .defaultServices(user.getDefaultServices() == null ? null
                        : List.copyOf(user.getDefaultServices()))
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .loyaltyMerchantId(user.getLoyaltyMerchantId())
                .loyaltyShopId(user.getLoyaltyShopId())
                .build();
    }
}
