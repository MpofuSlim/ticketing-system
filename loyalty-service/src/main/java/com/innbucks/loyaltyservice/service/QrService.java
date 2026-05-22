package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import com.innbucks.loyaltyservice.security.CryptoSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class QrService {

    private final QrTokenRepository qrs;
    private final TransactionService transactionService;
    private final TransferService transferService;
    private final FraudService fraud;
    private final CryptoSigner signer;
    private final int defaultTtl;

    public QrService(QrTokenRepository qrs, TransactionService transactionService,
                     TransferService transferService, FraudService fraud,
                     LoyaltyProperties props) {
        this.qrs = qrs;
        this.transactionService = transactionService;
        this.transferService = transferService;
        this.fraud = fraud;
        this.signer = new CryptoSigner(props.qr().secret());
        this.defaultTtl = props.qr().ttlSeconds();
    }

    public Dtos.QrPayload issue(UUID tenantId, Dtos.QrIssueRequest req) {
        QrToken q = new QrToken();
        q.setTenantId(tenantId);
        q.setSourceType(req.sourceType());
        q.setSourceId(req.sourceId());
        q.setTransactionType(req.transactionType());
        q.setAmount(req.amount());
        if (req.currency() != null) q.setCurrency(req.currency());
        int ttl = req.ttlSeconds() == null ? defaultTtl : Math.max(30, req.ttlSeconds());
        q.setExpiresAt(Instant.now().plusSeconds(ttl));
        q.setToken(CryptoSigner.randomToken(24));
        q.setSignature(signer.sign(payload(q)));
        qrs.save(q);
        return new Dtos.QrPayload(q.getToken(), q.getSignature(),
                q.getTenantId().toString(), q.getSourceType().name(),
                q.getSourceId().toString(), q.getTransactionType().name(),
                q.getExpiresAt());
    }

    private String payload(QrToken q) {
        return q.getTenantId() + "|" + q.getSourceType() + "|" + q.getSourceId()
                + "|" + q.getTransactionType() + "|" + q.getToken()
                + "|" + (q.getAmount() == null ? "" : q.getAmount().toPlainString())
                + "|" + q.getExpiresAt().toEpochMilli();
    }

    public Dtos.TransactionResponse consume(UUID tenantId, Dtos.QrConsumeRequest req) {
        QrToken q = qrs.lockByToken(req.token())
                .orElseThrow(() -> {
                    fraud.record(tenantId, req.userId(), null, null,
                            FraudAttempt.Reason.INVALID_CODE, "qr token not found",
                            null, null);
                    return LoyaltyException.notFound("qr token");
                });
        if (!q.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "QR belongs to a different tenant");
        }
        if (!signer.verify(payload(q), req.signature())) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_BAD_SIGNATURE, "qr signature mismatch", null, null);
            throw LoyaltyException.forbidden("BAD_SIGNATURE", "qr signature invalid");
        }
        if (q.getUsedAt() != null) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_REUSED, "qr already used", null, null);
            throw LoyaltyException.conflict("QR_REUSED", "qr token already consumed");
        }
        if (Instant.now().isAfter(q.getExpiresAt())) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_EXPIRED, "qr expired", null, null);
            throw LoyaltyException.badRequest("QR_EXPIRED", "qr token expired");
        }
        q.setUsedAt(Instant.now());

        if (q.getSourceType() == QrToken.SourceType.MERCHANT) {
            // Merchant-issued QR awards points to the scanning user.
            return transactionService.post(tenantId, q.getSourceId(), new Dtos.TransactionRequest(
                    null, req.userId(), null, q.getTransactionType(),
                    q.getAmount() == null ? BigDecimal.ZERO : q.getAmount(),
                    q.getCurrency(), req.reference()));
        } else {
            // User-issued QR initiates a P2P transfer; sourceId is the sender.
            BigDecimal points = q.getAmount() == null ? BigDecimal.ZERO : q.getAmount();
            transferService.transfer(tenantId, new Dtos.TransferRequest(
                    q.getSourceId(), req.userId(), null, points, "qr-transfer"));
            return new Dtos.TransactionResponse(null, TransactionType.TRANSFER, points,
                    points, null, null, null, null, req.reference(), Instant.now());
        }
    }
}
