package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "CreateTeamMember",
        description = "Payload for an EVENT_ORGANIZER to onboard a TEAM_MEMBER (gate-staff / scanner " +
                      "operator). The new team member is scoped to the calling organizer automatically — " +
                      "there is no organizerUuid field because the relation is derived from the caller's " +
                      "JWT. The new user is created with a randomly-generated one-time temporary password, " +
                      "delivered to them over email/SMS — they must rotate it via POST " +
                      "/auth/change-password on first login.")
public class CreateTeamMemberDTO {

    @NotBlank(message = "firstName is required")
    @Schema(example = "Tariro")
    private String firstName;

    @Schema(example = "K", nullable = true)
    private String middleName;

    @NotBlank(message = "lastName is required")
    @Schema(example = "Chikomo")
    private String lastName;

    @Email(message = "email must be valid")
    @NotBlank(message = "email is required")
    @Schema(example = "tariro@harare-arena.co.zw")
    private String email;

    @NotBlank(message = "phoneNumber is required")
    @Schema(example = "+263773456789")
    private String phoneNumber;
}
