package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.User;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
@Schema(name = "UpdateRoles",
        description = "Payload for replacing the full role set on a user account. This is a REPLACE, "
                + "not a merge — whatever is sent becomes the account's complete role set, so include "
                + "every role the user should keep.")
public class UpdateRolesDTO {

    /**
     * The complete role set the account should end up with. Deserialized straight
     * to the {@link User.Role} enum, so an unrecognised name is a 400 from Jackson
     * before the controller ever runs — the endpoint can never persist a role the
     * platform doesn't know about.
     */
    @NotEmpty(message = "roles must contain at least one role")
    @ArraySchema(
            arraySchema = @Schema(
                    description = """
                            Complete role set for the account. Every value must be one of the platform's \
                            seven roles:

                            * `SUPER_ADMIN` — platform owner. Seeded once from `BOOTSTRAP_ADMIN_PASSWORD` \
                            and **never grantable or revocable through this endpoint** (403 either way).
                            * `EVENT_ORGANIZER` — runs ticketed events; owns events, invoices and team members.
                            * `TEAM_MEMBER` — gate staff / scanner operator working for one EVENT_ORGANIZER. \
                            Requires the account to already be stamped with its parent organizer, which only \
                            `POST /event-organizer/team-members` does.
                            * `MERCHANT_ADMIN` — runs a loyalty merchant; manages that merchant's shops and rules.
                            * `SHOP_ADMIN` — manages staff at one loyalty shop. Requires the account to already \
                            carry `loyaltyMerchantId` + `loyaltyShopId`, which only \
                            `POST /admin/shop-staff/admins` does.
                            * `SHOP_USER` — operates the POS at one loyalty shop. Requires the same shop \
                            scoping, assigned by `POST /admin/shop-staff/users`.
                            * `CUSTOMER` — end user who earns and redeems loyalty points and buys tickets.
                            """,
                    example = "[\"EVENT_ORGANIZER\", \"CUSTOMER\"]"),
            schema = @Schema(implementation = User.Role.class))
    private Set<User.Role> roles;
}
