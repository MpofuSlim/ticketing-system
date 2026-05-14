package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the bug where a customer who registered in user-service BEFORE their
 * first loyalty interaction was being created as PENDING in loyalty-service —
 * the promotion webhook fires at registration time, and by then there was no
 * registration left to trigger it. Fix: findOrCreatePending now consults
 * user-service and seeds ACTIVE when the phone is already registered.
 */
class UserServiceFindOrCreatePendingTest {

    private LoyaltyUserRepository users;
    private WalletRepository wallets;
    private UserServiceClient userServiceClient;
    private UserService userService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        wallets = mock(WalletRepository.class);
        userServiceClient = mock(UserServiceClient.class);
        // Stub the repository so save() returns the entity unchanged.
        when(users.save(any(LoyaltyUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wallets.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        userService = new UserService(users, wallets, userServiceClient,
                new LoyaltyMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void phoneAlreadyRegisteredInUserService_createsActive() {
        String phone = "254777224008";
        when(users.findByTenantIdAndPhoneNumber(tenantId, phone)).thenReturn(Optional.empty());
        when(userServiceClient.getCustomerTier(phone))
                .thenReturn(Optional.of(new CustomerTierResponseDTO(phone, 2, 3)));

        LoyaltyUser u = userService.findOrCreatePending(tenantId, phone, merchantId);

        // ACTIVE means redemption/spending works on first contact — the whole
        // point of this change. Without it, the shop-checkout cash+points
        // flow 403s on a customer who's already fully registered.
        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    void phoneNotInUserService_createsPending() {
        String phone = "+263770000000";
        when(users.findByTenantIdAndPhoneNumber(tenantId, phone)).thenReturn(Optional.empty());
        when(userServiceClient.getCustomerTier(phone)).thenReturn(Optional.empty());

        LoyaltyUser u = userService.findOrCreatePending(tenantId, phone, merchantId);

        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
    }

    @Test
    void userServiceLookupFailsSilently_degradesToPending() {
        // UserServiceClient.getCustomerTier swallows exceptions and returns
        // empty on network failure. From this service's POV that's the same
        // as "unknown phone" — accrual still works, customer just can't
        // spend until the promote webhook flips them post-registration.
        String phone = "+263777777777";
        when(users.findByTenantIdAndPhoneNumber(tenantId, phone)).thenReturn(Optional.empty());
        when(userServiceClient.getCustomerTier(phone)).thenReturn(Optional.empty());

        LoyaltyUser u = userService.findOrCreatePending(tenantId, phone, merchantId);

        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
    }

    @Test
    void existingLoyaltyUserReturned_userServiceNotConsulted() {
        // Idempotency: once the projection exists we keep using it. Don't
        // re-probe user-service on every call (would be a per-request HTTP
        // hop on the shop-checkout hot path).
        String phone = "+263771111111";
        LoyaltyUser existing = new LoyaltyUser();
        existing.setStatus(LoyaltyUser.Status.ACTIVE);
        when(users.findByTenantIdAndPhoneNumber(tenantId, phone)).thenReturn(Optional.of(existing));

        LoyaltyUser u = userService.findOrCreatePending(tenantId, phone, merchantId);

        assertThat(u).isSameAs(existing);
        org.mockito.Mockito.verifyNoInteractions(userServiceClient);
    }
}
