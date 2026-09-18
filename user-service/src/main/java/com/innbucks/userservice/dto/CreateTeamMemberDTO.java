package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "CreateTeamMember",
        description = "Payload to onboard a TEAM_MEMBER (gate-staff / scanner operator). An " +
                      "EVENT_ORGANIZER creates one for themselves and omits organizerUuid — the relation " +
                      "is derived from their JWT. A SUPER_ADMIN acting on an organizer's behalf MUST " +
                      "supply organizerUuid, because a team member has to belong to some organizer and " +
                      "an admin is not one. The new user is created with a randomly-generated one-time " +
                      "temporary password, delivered over email/SMS — they must rotate it via POST " +
                      "/auth/change-password on first login.")
public class CreateTeamMemberDTO {

    @NotBlank(message = "firstName is required")
    @Size(max = 100, message = "firstName must not exceed 100 characters")
    @Schema(example = "Tariro")
    private String firstName;

    @Schema(example = "K", nullable = true)
    @Size(max = 100, message = "middleName must not exceed 100 characters")
    private String middleName;

    @NotBlank(message = "lastName is required")
    @Size(max = 100, message = "lastName must not exceed 100 characters")
    @Schema(example = "Chikomo")
    private String lastName;

    @Email(message = "email must be valid")
    @NotBlank(message = "email is required")
    @Schema(example = "tariro@harare-arena.co.zw")
    private String email;

    @NotBlank(message = "phoneNumber is required")
    @Schema(example = "+263773456789")
    private String phoneNumber;

    @Schema(description = "Which EVENT_ORGANIZER the new team member belongs to. Omit as an "
                          + "EVENT_ORGANIZER — you are the owner, and a value that is not your own "
                          + "uuid is refused. REQUIRED for a SUPER_ADMIN, who has no organizer "
                          + "identity of their own to stamp.",
            example = "3f1c5a6e-2b44-4c0e-9a77-1d2e3f4a5b6c")
    private java.util.UUID organizerUuid;
}
