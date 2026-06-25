package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Guards the cross-tenant fix on the three reporting endpoints that previously
 * leaked another tenant's data ({@code /reports/merchant/{id}},
 * {@code /reports/points/merchant/{id}}, {@code /reports/points/user/{id}}).
 * Each now resolves the path id through the owning service's tenant guard
 * ({@code requireMerchant} / {@code require}) before touching any data, so a
 * foreign id throws CROSS_TENANT (403) and no rows are read.
 */
class ReportingServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final FraudAttemptRepository fraud = mock(FraudAttemptRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final MerchantService merchantService = mock(MerchantService.class);
    private final UserService userService = mock(UserService.class);

    private final ReportingService reporting = new ReportingService(
            tenants, merchants, users, transactions, vouchers, invoices, fraud, campaigns,
            merchantService, userService);

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);

    @Test
    void merchant_throwsCrossTenant_andReadsNoData_whenMerchantInAnotherTenant() {
        when(merchantService.requireMerchant(TENANT_A, MERCHANT_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant"));

        LoyaltyException ex = assertThrows(LoyaltyException.class,
                () -> reporting.merchant(TENANT_A, MERCHANT_B));
        assertTrue(ex.getMessage().contains("different tenant"));

        // The guard fires before any aggregation — no merchant data is read.
        verify(transactions, never()).sumPointsIssued(any(), any(), any());
        verify(vouchers, never()).findByMerchantIdAndIssuedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForMerchant_throwsCrossTenant_andReadsNoData_whenMerchantInAnotherTenant() {
        when(merchantService.requireMerchant(TENANT_A, MERCHANT_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant"));

        assertThrows(LoyaltyException.class,
                () -> reporting.pointsForMerchant(TENANT_A, MERCHANT_B, FROM, TO));

        verify(transactions, never()).sumPointsIssued(any(), any(), any());
        verify(transactions, never()).countByMerchantIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForUser_throwsCrossTenant_andReadsNoData_whenUserInAnotherTenant() {
        when(userService.require(TENANT_A, USER_B))
                .thenThrow(LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant"));

        LoyaltyException ex = assertThrows(LoyaltyException.class,
                () -> reporting.pointsForUser(TENANT_A, USER_B, FROM, TO));
        assertTrue(ex.getMessage().contains("different tenant"));

        verify(transactions, never()).sumPointsIssuedByUser(any(), any(), any());
        verify(transactions, never()).countByUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void pointsForMerchant_passesGuard_andReturnsReport_whenSameTenant() {
        // requireMerchant does not throw (same tenant) — the report is produced
        // and the data is actually read, proving the guard doesn't block the
        // legitimate path.
        when(transactions.sumPointsIssued(eq(MERCHANT_B), any(), any())).thenReturn(new BigDecimal("100"));
        when(transactions.sumPointsRedeemed(eq(MERCHANT_B), any(), any())).thenReturn(new BigDecimal("40"));

        var report = reporting.pointsForMerchant(TENANT_A, MERCHANT_B, FROM, TO);

        assertNotNull(report);
        verify(merchantService).requireMerchant(TENANT_A, MERCHANT_B);
        verify(transactions).sumPointsIssued(eq(MERCHANT_B), any(), any());
    }
}
