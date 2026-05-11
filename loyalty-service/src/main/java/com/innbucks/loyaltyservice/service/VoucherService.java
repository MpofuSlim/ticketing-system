package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherBatch;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CryptoSigner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class VoucherService {

    private final VoucherRepository vouchers;
    private final VoucherBatchRepository batches;
    private final VoucherRedemptionRepository redemptions;
    private final VoucherTemplateService templateService;
    private final MerchantService merchants;
    private final LoyaltyUserRepository users;
    private final NotificationGateway notifications;
    private final FraudService fraud;
    private final CryptoSigner signer;

    public VoucherService(VoucherRepository vouchers,
                          VoucherBatchRepository batches,
                          VoucherRedemptionRepository redemptions,
                          VoucherTemplateService templateService,
                          MerchantService merchants,
                          LoyaltyUserRepository users,
                          NotificationGateway notifications,
                          FraudService fraud,
                          LoyaltyProperties props) {
        this.vouchers = vouchers;
        this.batches = batches;
        this.redemptions = redemptions;
        this.templateService = templateService;
        this.merchants = merchants;
        this.users = users;
        this.notifications = notifications;
        this.fraud = fraud;
        this.signer = new CryptoSigner(props.voucher().secret());
    }

    public Dtos.VoucherResponse issue(UUID tenantId, Dtos.IssueVoucherRequest req) {
        VoucherTemplate tpl = templateService.require(tenantId, req.templateId());
        Voucher v = createFromTemplate(tenantId, tpl, null,
                req.assignedUserId(), req.assigneePhone(), req.assigneeName(),
                req.deliveryChannel(), req.campaignSource(),
                req.usesOverride(), req.validityDaysOverride());
        vouchers.save(v);
        notifications.deliver(v, v.getDeliveryChannel());
        if (v.getDeliveryChannel() != null && v.getDeliveryChannel() != Voucher.DeliveryChannel.NONE) {
            v.setStatus(Voucher.Status.DELIVERED);
            v.setDeliveredAt(Instant.now());
        }
        return toResponse(v);
    }

    public List<Dtos.VoucherResponse> issueBulk(UUID tenantId, Dtos.BulkIssueRequest req) {
        VoucherTemplate tpl = templateService.require(tenantId, req.templateId());
        VoucherBatch batch = new VoucherBatch();
        batch.setTenantId(tenantId);
        batch.setTemplateId(tpl.getId());
        batch.setQuantity(req.quantity());
        batch.setCampaign(req.campaign());
        batches.save(batch);

        List<Dtos.VoucherResponse> result = new ArrayList<>(req.quantity());
        for (int i = 0; i < req.quantity(); i++) {
            Voucher v = createFromTemplate(tenantId, tpl, batch.getId(),
                    null, null, null, req.deliveryChannel(),
                    req.campaign(), null, null);
            vouchers.save(v);
            result.add(toResponse(v));
        }
        return result;
    }

    private Voucher createFromTemplate(UUID tenantId, VoucherTemplate tpl, UUID batchId,
                                       UUID assignedUserId, String assigneePhone, String assigneeName,
                                       Voucher.DeliveryChannel channel, String campaign,
                                       Integer usesOverride, Integer validityOverride) {
        if (assignedUserId != null) {
            LoyaltyUser u = users.findById(assignedUserId)
                    .orElseThrow(() -> LoyaltyException.notFound("user"));
            if (!u.getTenantId().equals(tenantId)) {
                throw LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant");
            }
            if (assigneePhone == null) assigneePhone = u.getPhoneNumber();
            // assigneeName is supplied by caller — loyalty-service does not
            // duplicate identity from user-service.
        }

        String code = uniqueCode();
        Voucher v = new Voucher();
        v.setTenantId(tenantId);
        v.setMerchantId(tpl.getMerchantId());
        v.setTemplateId(tpl.getId());
        v.setBatchId(batchId);
        v.setCode(code);
        v.setSignature(signer.sign(tenantId + ":" + tpl.getId() + ":" + code));
        v.setAssignedUserId(assignedUserId);
        v.setAssigneePhone(assigneePhone);
        v.setAssigneeName(assigneeName);
        v.setDeliveryChannel(channel);
        v.setCampaignSource(campaign);
        int uses = usesOverride != null ? usesOverride : tpl.getUsageLimit();
        v.setUsesRemaining(Math.max(1, uses));
        Integer validity = validityOverride != null ? validityOverride : tpl.getValidityDays();
        if (validity != null && validity > 0) {
            v.setExpiresAt(Instant.now().plus(validity, ChronoUnit.DAYS));
        }
        return v;
    }

    private String uniqueCode() {
        for (int i = 0; i < 8; i++) {
            String code = CryptoSigner.randomVoucherCode(12);
            if (vouchers.findByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Failed to allocate unique voucher code");
    }

    public void markDelivered(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        v.setStatus(Voucher.Status.DELIVERED);
        v.setDeliveredAt(Instant.now());
    }

    public void markViewed(String code) {
        vouchers.findByCode(code).ifPresent(v -> {
            if (v.getViewedAt() == null) {
                v.setViewedAt(Instant.now());
                if (v.getStatus() == Voucher.Status.ISSUED || v.getStatus() == Voucher.Status.DELIVERED) {
                    v.setStatus(Voucher.Status.VIEWED);
                }
            }
        });
    }

    public Dtos.RedemptionResponse redeem(UUID tenantId, UUID merchantId, Dtos.RedeemVoucherRequest req) {
        Voucher v = vouchers.lockByCode(req.code()).orElse(null);
        if (v == null || !v.getTenantId().equals(tenantId)) {
            fraud.record(tenantId, req.userId(), merchantId, req.code(),
                    FraudAttempt.Reason.INVALID_CODE, "voucher not found",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.notFound("voucher");
        }

        String expectedSig = signer.sign(tenantId + ":" + v.getTemplateId() + ":" + v.getCode());
        if (!expectedSig.equals(v.getSignature())) {
            fraud.record(tenantId, req.userId(), merchantId, req.code(),
                    FraudAttempt.Reason.BAD_SIGNATURE, "tampered signature",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.forbidden("BAD_SIGNATURE", "voucher signature invalid");
        }

        if (v.getExpiresAt() != null && Instant.now().isAfter(v.getExpiresAt())) {
            v.setStatus(Voucher.Status.EXPIRED);
            VoucherRedemption rj = recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "expired");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.EXPIRED, "redemption after expiry",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.badRequest("EXPIRED", "voucher has expired");
        }

        if (v.getStatus() == Voucher.Status.REDEEMED || v.getUsesRemaining() <= 0) {
            recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "already redeemed");
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.ALREADY_REDEEMED, "duplicate redemption attempt",
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.conflict("ALREADY_REDEEMED", "voucher already fully redeemed");
        }
        if (v.getStatus() == Voucher.Status.REVOKED) {
            recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "revoked");
            throw LoyaltyException.conflict("REVOKED", "voucher has been revoked");
        }

        if (v.getMerchantId() != null && !v.getMerchantId().equals(merchantId)) {
            fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                    FraudAttempt.Reason.WRONG_MERCHANT,
                    "expected " + v.getMerchantId() + " got " + merchantId,
                    req.deviceFingerprint(), req.ipAddress());
            throw LoyaltyException.forbidden("WRONG_MERCHANT", "voucher not redeemable at this merchant");
        }

        if (req.userId() != null) {
            LoyaltyUser u = users.findById(req.userId()).orElse(null);
            if (u != null && u.getStatus() == LoyaltyUser.Status.BLOCKED) {
                recordRedemption(v, merchantId, req, VoucherRedemption.Result.REJECTED, "user blocked");
                fraud.record(tenantId, req.userId(), merchantId, v.getCode(),
                        FraudAttempt.Reason.BLOCKED_USER, "blocked user attempted redemption",
                        req.deviceFingerprint(), req.ipAddress());
                throw LoyaltyException.forbidden("USER_BLOCKED", "user is blocked");
            }
        }

        // ensure merchant belongs to the tenant
        merchants.requireMerchant(tenantId, merchantId);

        v.setUsesRemaining(v.getUsesRemaining() - 1);
        if (v.getUsesRemaining() <= 0) {
            v.setStatus(Voucher.Status.REDEEMED);
            v.setRedeemedAt(Instant.now());
        } else {
            v.setStatus(Voucher.Status.PARTIALLY_USED);
        }

        VoucherRedemption r = recordRedemption(v, merchantId, req, VoucherRedemption.Result.SUCCESS, null);
        VoucherTemplate tpl = templateService.require(tenantId, v.getTemplateId());
        return new Dtos.RedemptionResponse(r.getId(), v.getId(), v.getStatus().name(),
                v.getUsesRemaining(), tpl.getValue(), tpl.getValueType().name(), r.getRedeemedAt());
    }

    private VoucherRedemption recordRedemption(Voucher v, UUID merchantId, Dtos.RedeemVoucherRequest req,
                                               VoucherRedemption.Result result, String reason) {
        VoucherRedemption r = new VoucherRedemption();
        r.setTenantId(v.getTenantId());
        r.setVoucherId(v.getId());
        r.setUserId(req.userId());
        r.setMerchantId(merchantId);
        r.setOutletCode(req.outletCode());
        r.setIpAddress(req.ipAddress());
        r.setDeviceFingerprint(req.deviceFingerprint());
        r.setResult(result);
        r.setReason(reason);
        return redemptions.save(r);
    }

    public void revoke(UUID tenantId, UUID voucherId) {
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        if (!v.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        v.setStatus(Voucher.Status.REVOKED);
    }

    @Transactional(readOnly = true)
    public List<Dtos.VoucherResponse> activeForUser(UUID userId) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, List.of(
                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                Voucher.Status.PARTIALLY_USED))
                .stream().map(VoucherService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> activeForUser(UUID userId, Pageable pageable) {
        return vouchers.findByAssignedUserIdAndStatusIn(userId, List.of(
                Voucher.Status.ISSUED, Voucher.Status.DELIVERED, Voucher.Status.VIEWED,
                Voucher.Status.PARTIALLY_USED), pageable)
                .map(VoucherService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<Dtos.VoucherResponse> findByStatus(UUID tenantId, Voucher.Status status) {
        return vouchers.findByTenantIdAndStatus(tenantId, status).stream()
                .map(VoucherService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.VoucherResponse> findByStatus(UUID tenantId, Voucher.Status status, Pageable pageable) {
        return vouchers.findByTenantIdAndStatus(tenantId, status, pageable)
                .map(VoucherService::toResponse);
    }

    public static Dtos.VoucherResponse toResponse(Voucher v) {
        return new Dtos.VoucherResponse(v.getId(), v.getCode(), v.getStatus().name(),
                v.getTemplateId(), v.getAssignedUserId(), v.getAssigneePhone(),
                v.getUsesRemaining(), v.getIssuedAt(), v.getExpiresAt());
    }
}
