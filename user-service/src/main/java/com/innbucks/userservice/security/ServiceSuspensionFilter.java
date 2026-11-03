package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.SuspendedLoyaltyMerchantRepository;
import com.innbucks.userservice.service.Services;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Withholds, at the mint, the roles a suspended service grants (V53, owner
 * decisions 2026-11-03). Every login and refresh passes through
 * {@code AuthService.buildResponse}, and a suspension signs the people it
 * reaches out, so their next token is minted here without the role.
 *
 * <ul>
 *   <li><b>Ticketing.</b> event, booking and seat-service authorise on the
 *       account's own {@code EVENT_ORGANIZER} role (and the
 *       {@code organizerUuid} claim it brings), not on the business's product
 *       list, so the role itself is withheld from the OWNER of a business whose
 *       ticketing is suspended. Fail closed: events belong to the person, so an
 *       owner of a second business loses ticketing there too.</li>
 *   <li><b>Loyalty cashiers.</b> Shop staff carry their merchant and shop from
 *       their own row, so {@code SHOP_ADMIN} / {@code SHOP_USER} (and with them
 *       the merchant and shop claims) are withheld while their merchant is in
 *       {@code suspended_loyalty_merchants}. Till keys are refused at the
 *       exchange.</li>
 * </ul>
 * Loyalty and marketplace ADMINS need nothing here: those services read the
 * token's {@code products}, which carries only ACTIVE products.
 *
 * <p>The account itself is untouched: the role comes back at the first token
 * minted after a reinstate.
 */
@Component
@Slf4j
public class ServiceSuspensionFilter {

    private final OrganizationProductRepository products;
    private final SuspendedLoyaltyMerchantRepository suspendedMerchants;

    public ServiceSuspensionFilter(OrganizationProductRepository products,
                                   SuspendedLoyaltyMerchantRepository suspendedMerchants) {
        this.products = products;
        this.suspendedMerchants = suspendedMerchants;
    }

    /** {@code roleNames} without the roles a suspended service grants this account. */
    public List<String> withholdSuspended(User user, List<String> roleNames) {
        if (user == null || roleNames == null || roleNames.isEmpty()) {
            return roleNames;
        }
        List<String> kept = new ArrayList<>(roleNames);
        String organizer = User.Role.EVENT_ORGANIZER.name();
        if (kept.contains(organizer) && user.getId() != null
                && products.ownsBusinessWithProductSuspended(user.getId(), Services.TICKETING)) {
            kept.remove(organizer);
            log.info("Organizer role withheld, ticketing suspended userUuid={}", user.getUserUuid());
        }
        String shopAdmin = User.Role.SHOP_ADMIN.name();
        String shopUser = User.Role.SHOP_USER.name();
        if ((kept.contains(shopAdmin) || kept.contains(shopUser)) && user.getLoyaltyMerchantId() != null
                && suspendedMerchants.existsById(user.getLoyaltyMerchantId())) {
            kept.remove(shopAdmin);
            kept.remove(shopUser);
            log.info("Shop roles withheld, loyalty suspended userUuid={}", user.getUserUuid());
        }
        return kept;
    }
}
