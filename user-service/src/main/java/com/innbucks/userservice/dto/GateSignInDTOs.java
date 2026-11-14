package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request shapes for the Organizer app's gate sign-in ({@code /auth/gate/**}, V57). */
public final class GateSignInDTOs {

    private GateSignInDTOs() {}

    @Schema(description = "Ask for a gate sign-in code")
    public record CodeRequest(
            @Schema(description = "The scanner's phone number, in any usual spelling "
                    + "(+263771234567, 263771234567, 0771234567)", example = "+263771234567")
            @NotBlank(message = "phoneNumber is required")
            @Size(max = 32, message = "phoneNumber is too long")
            String phoneNumber) {

        /** Never print the number. */
        @Override
        public String toString() {
            return "CodeRequest[phoneNumber=****]";
        }
    }

    @Schema(description = "Sign in with the code")
    public record SignInRequest(
            @Schema(description = "The same phone number the code was asked for", example = "+263771234567")
            @NotBlank(message = "phoneNumber is required")
            @Size(max = 32, message = "phoneNumber is too long")
            String phoneNumber,
            @Schema(description = "The six-digit code from the SMS (or WhatsApp)", example = "482913")
            @NotBlank(message = "code is required")
            @Size(max = 12, message = "code is too long")
            String code) {

        /** Never print the number or the code. */
        @Override
        public String toString() {
            return "SignInRequest[phoneNumber=****, code=****]";
        }
    }
}
