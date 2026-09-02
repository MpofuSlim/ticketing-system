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
     * The complete role set the account should end up with.
     *
     * <p><b>Strings, not the {@link User.Role} enum, as of V35.</b> Roles are now
     * data — an operator creates them with {@code POST /admin/roles} — so an enum
     * here would make exactly the roles worth creating un-assignable.
     *
     * <p>The validation that Jackson's enum binding used to provide for free has
     * moved to {@code UserAdminService.setRoles}, which checks every name against
     * the {@code roles} table and 400s listing the unknown ones. Same status code
     * as before, a better message, and the check now covers operator-created
     * roles too — but it is service-layer, so a programmatic caller that skips
     * the service would no longer be caught. Nothing does today.
     */
    @NotEmpty(message = "roles must contain at least one role")
    @ArraySchema(
            arraySchema = @Schema(
                    description = """
                            Complete role set for the account. Every value must name a role that \
                            exists — list them with `GET /admin/roles`. The nine built-in roles are:

                            * `SUPER_ADMIN` — platform owner. Seeded once from `BOOTSTRAP_ADMIN_PASSWORD` \
                            and **never grantable or revocable through this endpoint** (403 either way).
                            * `PRODUCT_OFFICER` — internal platform staff. Not scoped to a tenant, merchant \
                            or shop, and grants no service bundle. Assignable here with no prerequisites.
                            * `PRODUCT_MANAGER` — internal platform staff, same shape as `PRODUCT_OFFICER`.
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

                            Any role an operator has created with `POST /admin/roles` is equally \
                            assignable here, by name. An unknown name is a 400 listing the names that \
                            did not resolve.
                            """,
                    example = "[\"EVENT_ORGANIZER\", \"CUSTOMER\"]"),
            schema = @Schema(type = "string", example = "EVENT_ORGANIZER"))
    private Set<String> roles;
}
