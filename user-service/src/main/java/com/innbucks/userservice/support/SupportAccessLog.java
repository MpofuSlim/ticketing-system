package com.innbucks.userservice.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One support call that reached a customer (V45): a search, a detail read, a
 * write, or a call-center read of {@code /admin/device-security/**}. Written
 * once, never updated. See the migration for why it is not on the audit chain.
 */
@Entity
@Table(name = "support_access_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "lookup_id", length = 16, updatable = false)
    private String lookupId;

    @Column(name = "op", nullable = false, length = 64, updatable = false)
    private String op;

    @Column(name = "outcome", nullable = false, length = 64, updatable = false)
    private String outcome;

    @Column(name = "agent_user_uuid", updatable = false)
    private UUID agentUserUuid;

    @Column(name = "agent_subject", nullable = false, length = 254, updatable = false)
    private String agentSubject;

    @Column(name = "query_kind", length = 32, updatable = false)
    private String queryKind;

    @Column(name = "query_masked", length = 128, updatable = false)
    private String queryMasked;

    /** {@link SupportCustomerKeys} as JSON. */
    @Column(name = "customer_keys", columnDefinition = "TEXT", updatable = false)
    private String customerKeys;

    @Column(name = "sections", length = 255, updatable = false)
    private String sections;

    /** Section name → the target ids a later call may name, as JSON. */
    @Column(name = "section_targets", columnDefinition = "TEXT", updatable = false)
    private String sectionTargets;

    @Column(name = "target", length = 128, updatable = false)
    private String target;

    @Column(name = "staff_account", nullable = false, updatable = false)
    private boolean staffAccount;

    @Column(name = "client_ip_untrusted", length = 64, updatable = false)
    private String clientIpUntrusted;
}
