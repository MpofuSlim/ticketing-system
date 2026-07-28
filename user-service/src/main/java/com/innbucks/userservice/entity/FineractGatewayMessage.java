package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One SMS/WhatsApp message accepted from Fineract through the Message Gateway
 * facade ({@code POST /fineract-gateway/sms}), keyed by Fineract's own message
 * id so the delivery-report poll ({@code POST /fineract-gateway/sms/report})
 * can answer by that id.
 *
 * <p>{@code deliveryStatus} deliberately uses Fineract's
 * {@code SmsMessageStatusType} code space (0 INVALID, 100 PENDING, 200 SENT,
 * 400 FAILED — 300 DELIVERED is reserved for a future downstream receipt hook)
 * so reports echo codes without translation.
 *
 * <p>{@code message} is nulled the moment the row reaches a terminal status:
 * the body can carry a Fineract 2FA OTP, and this service never keeps OTPs
 * plaintext at rest (A02). It is also never logged.
 */
@Entity
@Table(name = "fineract_gateway_messages",
        uniqueConstraints = @UniqueConstraint(name = "uq_fineract_gateway_tenant_msg",
                columnNames = {"tenant_id", "fineract_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FineractGatewayMessage {

    /** Fineract SmsMessageStatusType.INVALID — validation reject, never sent. */
    public static final int STATUS_INVALID = 0;
    /** Fineract SmsMessageStatusType.PENDING — accepted, awaiting dispatch. */
    public static final int STATUS_PENDING = 100;
    /** Fineract SmsMessageStatusType.SENT — channel gateway accepted the send. */
    public static final int STATUS_SENT = 200;
    /** Fineract SmsMessageStatusType.FAILED — channel send failed. */
    public static final int STATUS_FAILED = 400;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Fineract's internal m_sms_messages id — the key it polls reports by. */
    @Column(name = "fineract_id", nullable = false)
    private Long fineractId;

    /** Fineract-Platform-TenantId the batch arrived under. */
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "mobile_number", nullable = false, length = 32)
    private String mobileNumber;

    /** Cleared (nulled) once the row is terminal — may carry an OTP (A02). */
    @Column(name = "message")
    private String message;

    /** Fineract campaign provider id; routes the channel (SMS vs WhatsApp). */
    @Column(name = "provider_id")
    private Long providerId;

    /** Resolved channel: {@code SMS} or {@code WHATSAPP}. */
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "delivery_status", nullable = false)
    private int deliveryStatus;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Our reference echoed to Fineract as externalId (FIN-GW-<uuid>). */
    @Column(name = "external_ref", nullable = false, length = 64)
    private String externalRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}
