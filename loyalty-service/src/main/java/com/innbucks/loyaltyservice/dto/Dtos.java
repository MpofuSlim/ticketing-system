package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
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

    public record TenantRequest(@NotBlank String code, @NotBlank String name) {}
    public record TenantResponse(UUID id, String code, String name, String status) {}

    public record MerchantRequest(@NotBlank String name, String category, String location,
                                  String currency, Merchant.BillingCycle billingCycle,
                                  BigDecimal feePerPointIssued,
                                  BigDecimal feePerVoucherIssued,
                                  BigDecimal feePerVoucherRedeemed) {}
    public record MerchantResponse(UUID id, UUID tenantId, String name, String category,
                                   String currency, Merchant.BillingCycle billingCycle,
                                   Merchant.Status status) {}

    public record UserRequest(@NotBlank String phone, String email, String fullName,
                              String nationalId, String country,
                              UUID merchantId, LoyaltyUser.Role role) {}
    public record UserResponse(UUID id, UUID tenantId, String phone, String email,
                               String fullName, String role, String status) {}

    public record WalletResponse(UUID id, UUID userId, String label, String type,
                                 String pocket, BigDecimal balance, LocalDate lockedUntil) {}

    public record SubWalletRequest(@NotBlank String label, String pocket,
                                   String type, LocalDate lockedUntil) {}

    public record RuleRequest(UUID merchantId,
                              @NotNull TransactionType transactionType,
                              @NotNull BigDecimal pointsPerUnit,
                              BigDecimal multiplier,
                              BigDecimal maxPointsPerTxn,
                              String pocket,
                              Instant startsAt, Instant endsAt) {}

    public record CampaignRequest(UUID merchantId,
                                  @NotBlank String name,
                                  @NotNull BigDecimal multiplier,
                                  TransactionType transactionType,
                                  @NotNull Instant startsAt, @NotNull Instant endsAt) {}

    public record TransactionRequest(@NotNull UUID merchantId,
                                     @NotNull UUID userId,
                                     @NotNull TransactionType type,
                                     BigDecimal amount,
                                     String currency,
                                     String reference) {}

    public record TransactionResponse(UUID id, TransactionType type, BigDecimal amount,
                                      BigDecimal pointsDelta, BigDecimal balanceAfter,
                                      UUID ruleId, UUID campaignId, String reference,
                                      Instant createdAt) {}

    public record TransferRequest(@NotNull UUID fromUserId,
                                  @NotNull UUID toUserId,
                                  @Positive BigDecimal points,
                                  String reason) {}

    public record RedemptionRequest(@NotNull UUID userId,
                                    @NotNull UUID merchantId,
                                    @Positive BigDecimal points,
                                    String reason) {}

    public record VoucherTemplateRequest(UUID merchantId,
                                         @NotBlank String name,
                                         @NotNull VoucherTemplate.VoucherType type,
                                         @NotNull VoucherTemplate.ValueType valueType,
                                         BigDecimal value,
                                         String currency,
                                         String freeItemSku,
                                         @Min(1) int usageLimit,
                                         Integer validityDays,
                                         String applicableOutlets) {}

    public record IssueVoucherRequest(@NotNull UUID templateId,
                                      String assigneePhone,
                                      String assigneeName,
                                      UUID assignedUserId,
                                      Voucher.DeliveryChannel deliveryChannel,
                                      String campaignSource,
                                      Integer usesOverride,
                                      Integer validityDaysOverride) {}

    public record BulkIssueRequest(@NotNull UUID templateId,
                                   @Min(1) int quantity,
                                   String campaign,
                                   Voucher.DeliveryChannel deliveryChannel) {}

    public record VoucherResponse(UUID id, String code, String status,
                                  UUID templateId, UUID assignedUserId,
                                  String assigneePhone, int usesRemaining,
                                  Instant issuedAt, Instant expiresAt) {}

    public record RedeemVoucherRequest(@NotBlank String code,
                                       UUID userId,
                                       @NotNull UUID merchantId,
                                       String outletCode,
                                       String deviceFingerprint,
                                       String ipAddress) {}

    public record RedemptionResponse(UUID redemptionId, UUID voucherId, String status,
                                     int usesRemaining, BigDecimal value, String valueType,
                                     Instant redeemedAt) {}

    public record QrIssueRequest(@NotNull com.innbucks.loyaltyservice.entity.QrToken.SourceType sourceType,
                                 @NotNull UUID sourceId,
                                 @NotNull TransactionType transactionType,
                                 BigDecimal amount,
                                 String currency,
                                 Integer ttlSeconds) {}

    public record QrPayload(String token, String signature, String tenantId,
                            String sourceType, String sourceId, String transactionType,
                            Instant expiresAt) {}

    public record QrConsumeRequest(@NotBlank String token,
                                   @NotBlank String signature,
                                   @NotNull UUID userId,
                                   String reference) {}

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
