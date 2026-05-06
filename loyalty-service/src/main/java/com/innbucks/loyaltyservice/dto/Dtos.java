package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class Dtos {

    public record TenantRequest(
            @Schema(example = "innbucks", description = "Short unique code for the tenant (URL-safe, no spaces).")
            @NotBlank String code,
            @Schema(example = "Innbucks Financial Services")
            @NotBlank String name
    ) {}

    public record TenantResponse(UUID id, String code, String name, String status) {}

    public record MerchantRequest(
            @Schema(example = "Innbucks Westgate", description = "Display name of the merchant outlet.")
            @NotBlank String name,
            @Schema(example = "Coffee", nullable = true, description = "Business category (e.g. Coffee, Grocery, Fuel).")
            String category,
            @Schema(example = "Westgate Shopping Mall, Harare", nullable = true)
            String location,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency code. Defaults to USD.")
            String currency,
            @Schema(example = "MONTHLY", allowableValues = {"WEEKLY", "MONTHLY"})
            Merchant.BillingCycle billingCycle,
            @Schema(example = "0.001000", nullable = true, description = "Fee charged per loyalty point issued (in currency).")
            BigDecimal feePerPointIssued,
            @Schema(example = "0.050000", nullable = true, description = "Fee charged per voucher issued.")
            BigDecimal feePerVoucherIssued,
            @Schema(example = "0.100000", nullable = true, description = "Fee charged per voucher redeemed.")
            BigDecimal feePerVoucherRedeemed
    ) {}

    public record MerchantResponse(UUID id, UUID tenantId, String name, String category,
                                   String currency, Merchant.BillingCycle billingCycle,
                                   Merchant.Status status) {}

    // Loyalty enrolment is by phone number only — name/email/nationalId belong
    // to user-service. Loyalty validates the phone exists there before
    // creating its local LoyaltyUser projection.
    public record UserEnrolRequest(
            @Schema(example = "+263771234567", description = "Customer's phone number (E.164 format). Must exist in user-service.")
            @NotBlank String phoneNumber,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant this user is attached to (e.g. a cashier). Null for plain customer enrolment.")
            UUID merchantId,
            @Schema(example = "END_USER", allowableValues = {"END_USER", "MERCHANT_ADMIN", "MERCHANT_FINANCE", "TENANT_ADMIN", "PLATFORM_ADMIN", "AUDITOR"})
            LoyaltyUser.Role role
    ) {}

    public record UserResponse(UUID id, UUID tenantId, String phoneNumber,
                               String role, String status) {}

    public record WalletResponse(UUID id, UUID userId, String label, String type,
                                 String pocket, BigDecimal balance, LocalDate lockedUntil) {}

    public record SubWalletRequest(
            @Schema(example = "Holiday Savings", description = "Human-readable wallet label.")
            @NotBlank String label,
            @Schema(example = "SAVINGS", nullable = true, description = "Named pocket within the wallet for rule targeting.")
            String pocket,
            @Schema(example = "LOCKED", nullable = true, allowableValues = {"STANDARD", "LOCKED"},
                    description = "LOCKED wallets cannot be spent until lockedUntil.")
            String type,
            @Schema(example = "2025-12-31", nullable = true,
                    description = "Date until which the wallet is locked (LOCKED type only). ISO-8601 date.")
            LocalDate lockedUntil
    ) {}

    public record RuleRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant scope — null means this rule applies to all merchants in the tenant.")
            UUID merchantId,
            @Schema(example = "PURCHASE")
            @NotNull TransactionType transactionType,
            @Schema(example = "1.000000", description = "Points awarded per 1 unit of currency spent.")
            @NotNull BigDecimal pointsPerUnit,
            @Schema(example = "2.0000", nullable = true, description = "Multiplier applied on top of pointsPerUnit (e.g. 2x during a promo).")
            BigDecimal multiplier,
            @Schema(example = "500.0000", nullable = true, description = "Cap on points earnable in a single transaction.")
            BigDecimal maxPointsPerTxn,
            @Schema(example = "MAIN", nullable = true, description = "Target wallet pocket for earned points.")
            String pocket,
            @Schema(example = "2026-06-01T00:00:00Z", nullable = true, description = "When this rule becomes active (null = immediately).")
            Instant startsAt,
            @Schema(example = "2026-12-31T23:59:59Z", nullable = true, description = "When this rule expires (null = no expiry).")
            Instant endsAt
    ) {}

    public record CampaignRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true)
            UUID merchantId,
            @Schema(example = "Weekend 2x Points")
            @NotBlank String name,
            @Schema(example = "2.0000", description = "Points multiplier during the campaign window.")
            @NotNull BigDecimal multiplier,
            @Schema(example = "PURCHASE", nullable = true)
            TransactionType transactionType,
            @Schema(example = "2026-06-04T00:00:00Z")
            @NotNull Instant startsAt,
            @Schema(example = "2026-06-08T23:59:59Z")
            @NotNull Instant endsAt
    ) {}

    public record TransactionRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789")
            @NotNull UUID merchantId,
            @Schema(example = "11111111-2222-3333-4444-555555555555")
            @NotNull UUID userId,
            @Schema(example = "PURCHASE", allowableValues = {"PURCHASE", "QR_PAY", "REDEMPTION", "REFUND", "ADJUSTMENT", "TRANSFER_IN", "TRANSFER_OUT"})
            @NotNull TransactionType type,
            @Schema(example = "100.00", nullable = true, description = "Transaction amount in the merchant's currency.")
            BigDecimal amount,
            @Schema(example = "USD", nullable = true)
            String currency,
            @Schema(example = "POS-20260504-0001", nullable = true, description = "External reference — must be unique per merchant to prevent duplicates.")
            String reference
    ) {}

    public record TransactionResponse(UUID id, TransactionType type, BigDecimal amount,
                                      BigDecimal pointsDelta, BigDecimal balanceAfter,
                                      UUID ruleId, UUID campaignId, String reference,
                                      Instant createdAt) {}

    public record TransferRequest(
            @Schema(example = "11111111-2222-3333-4444-555555555555", description = "Sender's loyalty user ID.")
            @NotNull UUID fromUserId,
            @Schema(example = "66666666-7777-8888-9999-000000000000", description = "Recipient's loyalty user ID.")
            @NotNull UUID toUserId,
            @Schema(example = "250.0000", description = "Points to transfer.")
            @Positive BigDecimal points,
            @Schema(example = "Birthday gift", nullable = true)
            String reason
    ) {}

    public record RedemptionRequest(
            @Schema(example = "11111111-2222-3333-4444-555555555555")
            @NotNull UUID userId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789")
            @NotNull UUID merchantId,
            @Schema(example = "500.0000", description = "Points to redeem.")
            @Positive BigDecimal points,
            @Schema(example = "Counter redemption by cashier", nullable = true)
            String reason
    ) {}

    public record VoucherTemplateRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant scope — null means tenant-wide template.")
            UUID merchantId,
            @Schema(example = "$5 Off Your Next Coffee")
            @NotBlank String name,
            @Schema(example = "SINGLE_USE", allowableValues = {"SINGLE_USE", "MULTI_USE", "FREE_ITEM"})
            @NotNull VoucherTemplate.VoucherType type,
            @Schema(example = "AMOUNT", allowableValues = {"AMOUNT", "PERCENTAGE", "FREE_ITEM"})
            @NotNull VoucherTemplate.ValueType valueType,
            @Schema(example = "5.0000", nullable = true, description = "Discount value (amount or percentage).")
            BigDecimal value,
            @Schema(example = "USD", nullable = true)
            String currency,
            @Schema(example = "COFFEE-001", nullable = true, description = "SKU of the free item (FREE_ITEM type only).")
            String freeItemSku,
            @Schema(example = "1", description = "How many times this voucher can be used before it expires.")
            @Min(1) int usageLimit,
            @Schema(example = "30", nullable = true, description = "Days from issue until the voucher expires.")
            Integer validityDays,
            @Schema(example = "WESTGATE,EASTGATE", nullable = true,
                    description = "Comma-separated outlet codes where this voucher can be redeemed. Null = all outlets.")
            String applicableOutlets
    ) {}

    public record IssueVoucherRequest(
            @Schema(example = "d6e2f4a5-4567-8901-bcde-f01234567890", description = "Template to issue from.")
            @NotNull UUID templateId,
            @Schema(example = "+263771234567", nullable = true, description = "Recipient phone — used if assignedUserId is null.")
            String assigneePhone,
            @Schema(example = "Alice Moyo", nullable = true)
            String assigneeName,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true,
                    description = "Loyalty user ID of the recipient. Takes priority over assigneePhone.")
            UUID assignedUserId,
            @Schema(example = "SMS", nullable = true, allowableValues = {"SMS", "EMAIL", "PUSH", "NONE"})
            Voucher.DeliveryChannel deliveryChannel,
            @Schema(example = "WINTER_PROMO_2026", nullable = true, description = "Campaign tag for reporting.")
            String campaignSource,
            @Schema(example = "3", nullable = true, description = "Override the template's usageLimit for this issuance only.")
            Integer usesOverride,
            @Schema(example = "14", nullable = true, description = "Override the template's validityDays for this issuance only.")
            Integer validityDaysOverride
    ) {}

    public record BulkIssueRequest(
            @Schema(example = "d6e2f4a5-4567-8901-bcde-f01234567890")
            @NotNull UUID templateId,
            @Schema(example = "100", description = "Number of vouchers to generate.")
            @Min(1) int quantity,
            @Schema(example = "WINTER_PROMO_2026", nullable = true)
            String campaign,
            @Schema(example = "NONE", nullable = true, allowableValues = {"SMS", "EMAIL", "PUSH", "NONE"})
            Voucher.DeliveryChannel deliveryChannel
    ) {}

    public record VoucherResponse(UUID id, String code, String status,
                                  UUID templateId, UUID assignedUserId,
                                  String assigneePhone, int usesRemaining,
                                  Instant issuedAt, Instant expiresAt) {}

    public record RedeemVoucherRequest(
            @Schema(example = "VCH-AB12CD34", description = "Voucher redemption code from the customer.")
            @NotBlank String code,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true)
            UUID userId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", description = "Merchant redeeming the voucher.")
            @NotNull UUID merchantId,
            @Schema(example = "WESTGATE", nullable = true, description = "Outlet code within the merchant.")
            String outletCode,
            @Schema(example = "abc123def456", nullable = true, description = "Device fingerprint for fraud detection.")
            String deviceFingerprint,
            @Schema(example = "192.168.1.100", nullable = true)
            String ipAddress
    ) {}

    public record RedemptionResponse(UUID redemptionId, UUID voucherId, String status,
                                     int usesRemaining, BigDecimal value, String valueType,
                                     Instant redeemedAt) {}

    public record QrIssueRequest(
            @Schema(example = "MERCHANT", allowableValues = {"MERCHANT", "USER"})
            @NotNull com.innbucks.loyaltyservice.entity.QrToken.SourceType sourceType,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", description = "ID of the merchant or user generating the QR.")
            @NotNull UUID sourceId,
            @Schema(example = "QR_PAY", allowableValues = {"QR_PAY", "PURCHASE"})
            @NotNull TransactionType transactionType,
            @Schema(example = "50.00", nullable = true, description = "Pre-encoded amount (optional — for fixed-amount QRs).")
            BigDecimal amount,
            @Schema(example = "USD", nullable = true)
            String currency,
            @Schema(example = "300", nullable = true, description = "Token TTL in seconds. Defaults to 300 (5 minutes).")
            Integer ttlSeconds
    ) {}

    public record QrPayload(String token, String signature, String tenantId,
                            String sourceType, String sourceId, String transactionType,
                            Instant expiresAt) {}

    public record QrConsumeRequest(
            @Schema(example = "qr_2026_e8f7c4d2a1b3", description = "Token from the scanned QR payload.")
            @NotBlank String token,
            @Schema(example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                    description = "HMAC-SHA256 signature from the QR payload.")
            @NotBlank String signature,
            @Schema(example = "11111111-2222-3333-4444-555555555555", description = "Loyalty user ID of the customer scanning the QR.")
            @NotNull UUID userId,
            @Schema(example = "POS-20260504-0042", nullable = true, description = "Merchant's external reference for this transaction.")
            String reference
    ) {}

    public record InvoiceResponse(UUID id, String invoiceNumber, UUID merchantId,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  BigDecimal pointsIssued, BigDecimal pointsRedeemed,
                                  long vouchersIssued, long vouchersRedeemed,
                                  BigDecimal totalAmount, String currency, String status,
                                  Instant paidAt) {}

    public record OperatorDashboard(long totalTenants, long activeMerchants,
                                    long transactionsToday, long vouchersIssuedToday,
                                    long vouchersRedeemedToday, BigDecimal pointsIssuedToday,
                                    BigDecimal pointsRedeemedToday, long fraudAttempts24h,
                                    long invoicesPending, long invoicesPaid,
                                    long expiringIn7Days, long expiringIn30Days) {}

    public record TenantDashboard(UUID tenantId, long merchants, long activeCampaigns,
                                  long vouchersOutstanding, long vouchersExpired,
                                  BigDecimal totalWalletBalance,
                                  long invoicesPending) {}

    public record MerchantDashboard(UUID merchantId, long redemptionsToday,
                                    long vouchersIssued, long vouchersRedeemed,
                                    BigDecimal pointsIssued, BigDecimal pointsRedeemed,
                                    long fraudAlerts24h,
                                    LocalDate nextInvoiceDate, BigDecimal estimatedInvoice) {}

    public record UserDashboard(UUID userId, BigDecimal totalPoints,
                                List<WalletResponse> wallets,
                                List<VoucherResponse> activeVouchers,
                                List<TransactionResponse> recentTransactions) {}

    public record MiniAppManifest(UUID id, String slug, String name,
                                  String description, String iconUrl, String entryUrl) {}

    public record FraudAttemptResponse(UUID id, String voucherCode, UUID merchantId,
                                       String reason, String detail,
                                       String deviceFingerprint, Instant createdAt) {}
}
