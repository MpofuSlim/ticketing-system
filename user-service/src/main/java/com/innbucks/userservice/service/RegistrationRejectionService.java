package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.RejectedRegistrationDTO;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.RegistrationRejected;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.exception.RegistrationRejectionException;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.support.SupportMasking;
import com.innbucks.userservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Rejects a registration that is still pending approval
 * ({@code PUT /admin/users/{id}/reject}).
 *
 * <p><b>Why it exists.</b> {@code POST /auth/register} leaves an account that is
 * neither approved nor active, and the only decision the console could take on
 * it was to approve it. A registration nobody wanted stayed pending forever,
 * and its email, phone and TIN stayed taken, so the applicant could not even
 * correct a mistake and register again.
 *
 * <p><b>Owner decision (2026-10-08): rejecting FREES THE DETAILS.</b> The
 * never-approved account is deleted together with the business its
 * registration created — the tenant profile (business details, the unique
 * BPO / tax number), and every organization the applicant OWNS and CREATED
 * (their members and products go with it, V39's cascade). Who rejected whom,
 * when and why is kept on the tamper-evident audit chain, written fail-closed
 * as the transaction's last statement, and the applicant is told the reason
 * after commit.
 *
 * <p><b>Two phases, because InnRewards is asked in between and a remote call
 * never runs inside a transaction:</b>
 * <ol>
 *   <li>a short READ-ONLY transaction assesses the account: everything that
 *       must refuse the rejection, and the organizations it would delete;</li>
 *   <li>with no transaction open, InnRewards is asked — strictly — whether it
 *       holds a loyalty merchant for each of those organizations. Unknown is a
 *       503: "could not ask" is never read as "owns nothing";</li>
 *   <li>one WRITE transaction locks the account row
 *       ({@link UserRepository#lockById}, the lock approval takes too, so an
 *       approval and a rejection of one registration serialise) and assesses
 *       it AGAIN. Anything different from step 1 — gone, approved, now
 *       refused, a different set of organizations — is
 *       {@code 409 registration_changed}, because step 2 vouched for the old
 *       set only. Then it deletes, flushes, publishes the notice and records
 *       the REQUIRED audit row last.</li>
 * </ol>
 *
 * <p><b>The dependents, decided table by table</b> (every reference to
 * {@code users}, {@code organizations} and {@code tenant_profiles} in the
 * migrations):
 * <ul>
 *   <li>deleted here: {@code tenant_profiles}, {@code refresh_tokens},
 *       {@code devices} (no cascade on any of them; a pending account cannot
 *       sign in, so the last two are normally empty), the live
 *       {@code otps} keyed by the account's email and phone (a code requested
 *       before the rejection must not set the password of a re-registration),
 *       the created {@code organizations};</li>
 *   <li>cascaded by the database: {@code user_roles},
 *       {@code user_default_services}, {@code mfa_backup_codes},
 *       {@code service_requests} (as requester; {@code reviewed_by} is
 *       SET NULL), {@code organization_members} (the created organizations'
 *       and the account's memberships elsewhere),
 *       {@code organization_products}, {@code team_member_event_assignment};
 *       {@code organizations.created_by_user_id} is SET NULL for a business
 *       the account created but no longer owns;</li>
 *   <li>refused (409): another member in a business the registration created,
 *       a loyalty merchant in InnRewards, accounts naming this one as their
 *       organizer ({@code ON DELETE RESTRICT}), a customer profile (that is a
 *       super-app customer, not just an applicant), being the only owner of
 *       somebody else's business; a staff account ({@code staff_profiles} /
 *       {@code staff_invites}) is {@code use_staff_endpoints};</li>
 *   <li>kept: {@code audit_events} (the chain), {@code notifications} (V37:
 *       history, deliberately no foreign key), {@code support_access_log} /
 *       {@code support_actions} (who looked at whom), the DTX device-security
 *       tables (keyed by MSISDN, about the phone's owner at the banking core),
 *       {@code otp_retry_attempts} (rate-limit windows that expire),
 *       {@code pending_registrations} (a separate customer sign-up in flight)
 *       and {@code revoked_tokens}.</li>
 * </ul>
 *
 * <p><b>Not reached:</b> marketplace-service has no lookup by organization, so a
 * listing a SUPER_ADMIN created on behalf of a pending registrant's organization
 * keeps the deleted id. And a loyalty merchant created for the organization in
 * the moments between the InnRewards check and the commit is not seen.
 */
@Slf4j
@Service
public class RegistrationRejectionService {

    /** 403 — the same refusal {@link UserAdminService#setActive} gives the platform owner. */
    static final String SUPER_ADMIN_MESSAGE = "The SUPER_ADMIN account cannot be rejected.";

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final TenantProfileRepository tenantProfiles;
    private final CustomerProfileRepository customerProfiles;
    private final RefreshTokenRepository refreshTokens;
    private final DeviceRepository devices;
    private final OtpRepository otps;
    private final StaffEligibility staffEligibility;
    private final LoyaltyServiceClient loyalty;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public RegistrationRejectionService(UserRepository users,
                                        OrganizationRepository organizations,
                                        OrganizationMemberRepository members,
                                        TenantProfileRepository tenantProfiles,
                                        CustomerProfileRepository customerProfiles,
                                        RefreshTokenRepository refreshTokens,
                                        DeviceRepository devices,
                                        OtpRepository otps,
                                        StaffEligibility staffEligibility,
                                        LoyaltyServiceClient loyalty,
                                        AuditService auditService,
                                        ApplicationEventPublisher events,
                                        PlatformTransactionManager transactionManager) {
        this.users = users;
        this.organizations = organizations;
        this.members = members;
        this.tenantProfiles = tenantProfiles;
        this.customerProfiles = customerProfiles;
        this.refreshTokens = refreshTokens;
        this.devices = devices;
        this.otps = otps;
        this.staffEligibility = staffEligibility;
        this.loyalty = loyalty;
        this.auditService = auditService;
        this.events = events;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
    }

    /**
     * Rejects the registration of account {@code id}, removing it and the
     * business it created. Called by the authenticated endpoint only:
     * {@code adminEmail} is the caller, the audit row's actor.
     *
     * @throws NotFoundException             no such account (404)
     * @throws ResponseStatusException       blank reason (400), the SUPER_ADMIN (403)
     * @throws RegistrationRejectionException 409 / 503 — see the class javadoc
     * @throws StaffPolicyException          {@code use_staff_endpoints} (409), or
     *                                       {@code audit_unavailable} (503) when the
     *                                       required audit row cannot be written
     */
    public RejectedRegistrationDTO reject(Long id, String reason, String adminEmail, AuditContext context) {
        // Belt-and-braces behind the DTO's @NotBlank: the reason is the whole
        // message the applicant gets.
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reason is required when rejecting a registration.");
        }

        Plan planned = reads.execute(status -> {
            User user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
            Assessment assessment = assess(user);
            if (assessment.refusal() != null) {
                log.info("Registration rejection refused userId={} refusal={} by={}",
                        id, assessment.refusal(), adminEmail);
                throw assessment.refusal().exception();
            }
            return assessment.plan();
        });

        // No transaction is open here: InnRewards is asked between the phases.
        requireNoLoyaltyMerchant(id, planned.organizations());

        return writes.execute(status -> remove(id, planned, cleanReason, adminEmail, context));
    }

    /**
     * Strict: every organization the rejection would delete must be KNOWN to
     * own no loyalty merchant. An unanswered lookup is a retryable 503 — never
     * a pass.
     */
    private void requireNoLoyaltyMerchant(Long userId, SortedSet<UUID> organizationIds) {
        for (UUID organizationId : organizationIds) {
            Optional<List<UUID>> merchants = loyalty.merchantIdsForOrganizationIfKnown(organizationId);
            if (merchants.isEmpty()) {
                log.warn("Registration rejection refused: InnRewards did not answer userId={} organizationId={}",
                        userId, organizationId);
                throw RegistrationRejectionException.checkUnavailable();
            }
            if (!merchants.get().isEmpty()) {
                log.info("Registration rejection refused: InnRewards holds {} merchant(s) userId={} organizationId={}",
                        merchants.get().size(), userId, organizationId);
                throw RegistrationRejectionException.businessInUse(RegistrationRejectionException.REASON_LOYALTY_MERCHANT);
            }
        }
    }

    /** The write phase, under the account's row lock. */
    private RejectedRegistrationDTO remove(Long id, Plan planned, String reason, String adminEmail,
                                           AuditContext context) {
        User user = users.lockById(id).orElseThrow(RegistrationRejectionException::changed);
        Assessment now = assess(user);
        if (now.refusal() != null || !now.plan().organizations().equals(planned.organizations())) {
            log.info("Registration rejection refused: it changed after it was checked userId={} refusal={} by={}",
                    id, now.refusal(), adminEmail);
            throw RegistrationRejectionException.changed();
        }
        Plan plan = now.plan();

        // Captured BEFORE the delete: the notice and the audit row describe an
        // account that will no longer exist.
        String email = user.getEmail();
        String phone = user.getPhoneNumber();
        String businessName = tenantProfiles.findBusinessNameByUserId(id)
                .filter(name -> !name.isBlank())
                .map(String::strip)
                .orElse(null);
        UUID userUuid = user.getUserUuid();
        LocalDateTime rejectedAt = LocalDateTime.now(ZoneOffset.UTC);

        // The dependents with no cascade go first, then the organizations (their
        // members and products cascade), then the account (its roles, services,
        // backup codes, service requests and remaining memberships cascade).
        int resetCodes = deleteResetCodes(email, phone);
        int refreshRows = refreshTokens.deleteAllForUser(id);
        int deviceRows = devices.deleteAllForUser(id);
        int profileRows = tenantProfiles.deleteAllForUser(id);
        int organizationRows = plan.organizations().isEmpty() ? 0 : organizations.deleteAllWithIds(plan.organizations());
        users.delete(user);
        // Flushed BEFORE the required audit below, so a constraint failure
        // surfaces here — never after an audit row describing it has committed.
        users.flush();

        log.info("Registration rejected userId={} organizationsDeleted={} membershipsRemoved={} tenantProfiles={} "
                        + "refreshTokens={} devices={} resetCodes={} by={}",
                id, organizationRows, plan.membershipsElsewhere().size(), profileRows, refreshRows, deviceRows,
                resetCodes, adminEmail);

        // Delivered AFTER COMMIT only: if the audit row below cannot be written
        // the transaction rolls back and the applicant hears nothing.
        events.publishEvent(new RegistrationRejected(id, email, phone, user.getFirstName(), businessName, reason));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        metadata.put("userId", id);
        metadata.put("organizationsDeleted", plan.organizations().stream().map(UUID::toString).toList());
        metadata.put("membershipsRemoved", plan.membershipsElsewhere().stream().map(UUID::toString).toList());
        if (businessName != null) {
            metadata.put("businessName", businessName);
        }
        // Masked: enough for an operator to recognise the applicant later, without
        // the chain holding the contact details of someone who was turned away.
        metadata.put("email", SupportMasking.email(email));
        metadata.put("phone", MsisdnMasking.mask(phone));
        // REQUIRED and last: a rejection that cannot be recorded is not made
        // (503 audit_unavailable rolls this transaction back).
        auditService.recordRequired(AuditEventType.USER_REGISTRATION_REJECTED,
                adminEmail, AuditService.ACTOR_TYPE_USER,
                String.valueOf(userUuid), AuditService.TARGET_TYPE_USER,
                metadata, context);
        return new RejectedRegistrationDTO(id, email, reason, rejectedAt);
    }

    /**
     * Password-reset codes are keyed by the exact stored email or the E.164
     * phone ({@code PasswordResetService}); a registration pending approval may
     * hold one. Deleted so a code requested before the rejection cannot set the
     * password of a registration made again with the same details.
     */
    private int deleteResetCodes(String email, String phone) {
        int deleted = 0;
        if (email != null && !email.isBlank()) {
            deleted += otps.deleteByPhoneNumber(email);
        }
        if (phone != null && !phone.isBlank()) {
            deleted += otps.deleteByPhoneNumber(phone);
        }
        return deleted;
    }

    /**
     * Everything that decides whether the account may be removed, and what goes
     * with it. Run twice — before the InnRewards check and again under the row
     * lock — so the second run can prove nothing moved.
     */
    private Assessment assess(User user) {
        if (user.hasRole(User.Role.SUPER_ADMIN)) return Assessment.refused(Refusal.SUPER_ADMIN);
        // A decided registration is deactivated, never deleted: an approved
        // account has had a password, sessions and a business that ran.
        if (user.isApproved()) return Assessment.refused(Refusal.ALREADY_APPROVED);
        // Holds a staff role, or has a staff profile (and so its invites).
        if (staffEligibility.isStaffAccount(user)) return Assessment.refused(Refusal.STAFF_ACCOUNT);
        if (customerProfiles.existsByUserId(user.getId())) return Assessment.refused(Refusal.CUSTOMER_ACCOUNT);
        if (users.existsByCreatedByOrganizerUuid(user.getUserUuid())) return Assessment.refused(Refusal.TEAM_MEMBERS);

        // One membership per organization (uq_organization_member).
        Map<UUID, OrganizationMember.Role> roleIn = new HashMap<>();
        for (OrganizationMember membership : members.findByUserId(user.getId())) {
            roleIn.put(membership.getOrganizationId(), membership.getRole());
        }
        SortedSet<UUID> created = new TreeSet<>();
        SortedSet<UUID> elsewhere = new TreeSet<>();
        List<Organization> theirs = organizations.findAllById(roleIn.keySet()).stream()
                .sorted(Comparator.comparing(Organization::getId))
                .toList();
        for (Organization organization : theirs) {
            boolean owner = roleIn.get(organization.getId()) == OrganizationMember.Role.OWNER;
            if (owner && user.getId().equals(organization.getCreatedByUserId())) {
                // The business this registration created: deleted with it, unless
                // somebody else already works there.
                if (hasOtherMembers(organization.getId(), user.getId())) {
                    return Assessment.refused(Refusal.OTHER_MEMBERS);
                }
                created.add(organization.getId());
            } else if (owner && members.countByOrganizationIdAndRole(organization.getId(),
                    OrganizationMember.Role.OWNER) <= 1) {
                // Somebody else's business with this account as its only owner:
                // removing the membership would orphan it (an organization never
                // loses its last owner — OrganizationService's last_owner rule).
                return Assessment.refused(Refusal.SOLE_OWNER_ELSEWHERE);
            } else {
                // A membership in somebody else's business: removed with the account.
                elsewhere.add(organization.getId());
            }
        }
        return Assessment.removable(new Plan(created, elsewhere));
    }

    private boolean hasOtherMembers(UUID organizationId, Long userId) {
        return members.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .anyMatch(member -> !userId.equals(member.getUserId()));
    }

    /** What a rejection removes besides the account: the organizations it created, the memberships elsewhere. */
    record Plan(SortedSet<UUID> organizations, SortedSet<UUID> membershipsElsewhere) {
    }

    /** Either a refusal or a plan. */
    record Assessment(Refusal refusal, Plan plan) {
        static Assessment refused(Refusal refusal) {
            return new Assessment(refusal, null);
        }

        static Assessment removable(Plan plan) {
            return new Assessment(null, plan);
        }
    }

    /** Why a registration cannot be rejected, in the order they are checked. */
    enum Refusal {
        SUPER_ADMIN,
        ALREADY_APPROVED,
        STAFF_ACCOUNT,
        CUSTOMER_ACCOUNT,
        TEAM_MEMBERS,
        OTHER_MEMBERS,
        SOLE_OWNER_ELSEWHERE;

        RuntimeException exception() {
            return switch (this) {
                case SUPER_ADMIN -> new ResponseStatusException(HttpStatus.FORBIDDEN, SUPER_ADMIN_MESSAGE);
                case ALREADY_APPROVED -> RegistrationRejectionException.alreadyDecided();
                case STAFF_ACCOUNT -> StaffPolicyException.useStaffEndpoints();
                case CUSTOMER_ACCOUNT -> RegistrationRejectionException.businessInUse(
                        RegistrationRejectionException.REASON_CUSTOMER_ACCOUNT);
                case TEAM_MEMBERS -> RegistrationRejectionException.businessInUse(
                        RegistrationRejectionException.REASON_TEAM_MEMBERS);
                case OTHER_MEMBERS -> RegistrationRejectionException.businessInUse(
                        RegistrationRejectionException.REASON_OTHER_MEMBERS);
                case SOLE_OWNER_ELSEWHERE -> RegistrationRejectionException.businessInUse(
                        RegistrationRejectionException.REASON_SOLE_OWNER_ELSEWHERE);
            };
        }
    }
}
