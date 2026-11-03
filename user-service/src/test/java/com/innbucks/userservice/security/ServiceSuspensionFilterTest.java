package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.SuspendedLoyaltyMerchantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The mint withholds the roles a suspended service grants (V53): the organizer
 * role from the owner of a business whose ticketing is suspended, the shop roles
 * from a cashier of a suspended loyalty merchant. Everything else is minted as
 * before.
 */
class ServiceSuspensionFilterTest {

    private final OrganizationProductRepository products = mock(OrganizationProductRepository.class);
    private final SuspendedLoyaltyMerchantRepository suspended = mock(SuspendedLoyaltyMerchantRepository.class);
    private final ServiceSuspensionFilter filter = new ServiceSuspensionFilter(products, suspended);

    private static User account(long id, UUID merchant) {
        return User.builder().id(id).userUuid(UUID.randomUUID()).password("x").loyaltyMerchantId(merchant).build();
    }

    @Test
    void anOrganizerWhoseBusinessHasTicketingSuspended_isMintedWithoutTheRole() {
        when(products.ownsBusinessWithProductSuspended(7L, "ticketing")).thenReturn(true);

        assertThat(filter.withholdSuspended(account(7, null), List.of("EVENT_ORGANIZER", "MERCHANT_ADMIN", "CUSTOMER")))
                .containsExactly("MERCHANT_ADMIN", "CUSTOMER");
    }

    @Test
    void anOrganizerWithNothingSuspended_keepsTheRole() {
        when(products.ownsBusinessWithProductSuspended(anyLong(), any())).thenReturn(false);

        assertThat(filter.withholdSuspended(account(7, null), List.of("EVENT_ORGANIZER")))
                .containsExactly("EVENT_ORGANIZER");
    }

    @Test
    void aCashierOfASuspendedMerchant_losesBothShopRoles() {
        UUID merchant = UUID.randomUUID();
        when(suspended.existsById(merchant)).thenReturn(true);

        assertThat(filter.withholdSuspended(account(9, merchant), List.of("SHOP_ADMIN", "SHOP_USER", "CUSTOMER")))
                .containsExactly("CUSTOMER");
    }

    @Test
    void aCashierOfAnotherMerchant_keepsTheShopRole() {
        UUID merchant = UUID.randomUUID();
        when(suspended.existsById(merchant)).thenReturn(false);

        assertThat(filter.withholdSuspended(account(9, merchant), List.of("SHOP_USER"))).containsExactly("SHOP_USER");
    }

    @Test
    void anAccountWithNeitherRole_isNotLookedUp() {
        assertThat(filter.withholdSuspended(account(3, UUID.randomUUID()), List.of("CUSTOMER")))
                .containsExactly("CUSTOMER");
        verifyNoInteractions(products, suspended);
    }
}
