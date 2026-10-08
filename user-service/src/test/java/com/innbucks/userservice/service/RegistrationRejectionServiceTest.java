package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.RejectedRegistrationDTO;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.RegistrationRejected;
import com.innbucks.userservice.exception.AuditUnavailableException;
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
import com.innbucks.userservice.testsupport.RecordingTransactionManager;
import com.innbucks.userservice.testsupport.RecordingTransactionManager.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RegistrationRejectionService}, with every repository mocked and a
 * {@link RecordingTransactionManager}, so each assertion can also say WHERE a
 * call ran: the assessment in a read-only transaction, InnRewards with none
 * open, the removal in one write transaction that ends with the REQUIRED audit
 * row — and every refusal removes nothing.
 */
class RegistrationRejectionServiceTest {

    private static final long ID = 57L;
    private static final UUID USER_UUID = UUID.fromString("5a7c1e2d-3b4f-4a6b-9c8d-0e1f2a3b4c5d");
    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final UUID OTHER_ORG = UUID.fromString("8c2f3d5e-0a4b-4f6c-9d7e-1b2c3d4e5f60");
    private static final String EMAIL = "rumbi@showtime.co.zw";
    private static final String PHONE = "+263772999000";
    private static final String ADMIN = "ops.lead@innbucks.co.zw";
    private static final String REASON = "We couldn't verify the BPO number you gave.";
    private static final AuditContext CONTEXT = new AuditContext("41.221.10.5", "console");

    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final TenantProfileRepository tenantProfiles = mock(TenantProfileRepository.class);
    private final CustomerProfileRepository customerProfiles = mock(CustomerProfileRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final OtpRepository otps = mock(OtpRepository.class);
    private final StaffEligibility staffEligibility = mock(StaffEligibility.class);
    private final LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();

    /** Whether a transaction was open at each InnRewards call. */
    private final List<Boolean> loyaltyCallsInTransaction = new ArrayList<>();

    private RegistrationRejectionService service;

    private static User pending() {
        return User.builder().id(ID).userUuid(USER_UUID).firstName("Rumbi").lastName("Moyo")
                .email(EMAIL).phoneNumber(PHONE).password("!PENDING")
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER))
                .active(false).approved(false).business(true).build();
    }

    private static Organization organization(UUID id, Long createdBy) {
        return Organization.builder().id(id).name("Showtime Events").createdByUserId(createdBy)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private static OrganizationMember membership(UUID org, long userId, OrganizationMember.Role role) {
        return OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org).userId(userId).role(role)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    /** The account answers both reads: the assessment's and the row lock's. */
    private void account(User user) {
        when(users.findById(ID)).thenReturn(Optional.of(user));
        when(users.lockById(ID)).thenReturn(Optional.of(user));
    }

    /** The account's memberships and the organizations they are in. */
    private void memberships(List<OrganizationMember> mine, Organization... organizationsTheyAreIn) {
        when(members.findByUserId(ID)).thenReturn(mine);
        when(organizations.findAllById(any())).thenReturn(List.of(organizationsTheyAreIn));
    }

    @BeforeEach
    void setUp() {
        service = new RegistrationRejectionService(users, organizations, members, tenantProfiles, customerProfiles,
                refreshTokens, devices, otps, staffEligibility, loyalty, audit, events, transactions);

        // The happy path: a pending organizer who owns the one organization their
        // registration created, nobody else in it, unknown to InnRewards.
        account(pending());
        memberships(List.of(membership(ORG, ID, OrganizationMember.Role.OWNER)), organization(ORG, ID));
        when(members.findByOrganizationIdOrderByCreatedAtAsc(ORG))
                .thenReturn(List.of(membership(ORG, ID, OrganizationMember.Role.OWNER)));
        when(loyalty.merchantIdsForOrganizationIfKnown(any())).thenAnswer(inv -> {
            loyaltyCallsInTransaction.add(RecordingTransactionManager.inTransaction());
            return Optional.of(List.of());
        });
        when(tenantProfiles.findBusinessNameByUserId(ID)).thenReturn(Optional.of(" Showtime Events "));
        when(otps.deleteByPhoneNumber(anyString())).thenReturn(1);
        when(tenantProfiles.deleteAllForUser(ID)).thenReturn(1);
        when(organizations.deleteAllWithIds(anyCollection())).thenAnswer(inv -> ((Collection<?>) inv.getArgument(0)).size());
    }

    private RejectedRegistrationDTO reject() {
        return service.reject(ID, "  " + REASON + "  ", ADMIN, CONTEXT);
    }

    /** A refusal deletes nothing, announces nothing and records nothing. */
    private void assertNothingRemoved() {
        verify(otps, never()).deleteByPhoneNumber(anyString());
        verify(refreshTokens, never()).deleteAllForUser(anyLong());
        verify(devices, never()).deleteAllForUser(anyLong());
        verify(tenantProfiles, never()).deleteAllForUser(anyLong());
        verify(organizations, never()).deleteAllWithIds(anyCollection());
        verify(users, never()).delete(any());
        verify(events, never()).publishEvent(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata() {
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(eq(AuditEventType.USER_REGISTRATION_REJECTED), eq(ADMIN),
                eq(AuditService.ACTOR_TYPE_USER), eq(USER_UUID.toString()), eq(AuditService.TARGET_TYPE_USER),
                metadata.capture(), eq(CONTEXT));
        return metadata.getValue();
    }

    private RegistrationRejected event() {
        ArgumentCaptor<RegistrationRejected> event = ArgumentCaptor.forClass(RegistrationRejected.class);
        verify(events).publishEvent(event.capture());
        return event.getValue();
    }

    // ---------------------------------------------------------------------
    // The rejection
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("removes the account and its business, in order, and records the REQUIRED audit row last")
    void removesEverything_andAuditsLast() {
        doAnswer(inv -> {
            assertThat(RecordingTransactionManager.inTransaction()).as("the delete runs in the write phase").isTrue();
            return null;
        }).when(users).delete(any(User.class));

        RejectedRegistrationDTO result = reject();

        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.reason()).as("stripped, otherwise verbatim").isEqualTo(REASON);
        assertThat(result.rejectedAt()).isCloseTo(LocalDateTime.now(ZoneOffset.UTC),
                org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));

        InOrder order = inOrder(otps, refreshTokens, devices, tenantProfiles, organizations, users, events, audit);
        order.verify(otps).deleteByPhoneNumber(EMAIL);
        order.verify(otps).deleteByPhoneNumber(PHONE);
        order.verify(refreshTokens).deleteAllForUser(ID);
        order.verify(devices).deleteAllForUser(ID);
        order.verify(tenantProfiles).deleteAllForUser(ID);
        order.verify(organizations).deleteAllWithIds(Set.of(ORG));
        order.verify(users).delete(any(User.class));
        order.verify(users).flush();
        order.verify(events).publishEvent(any(RegistrationRejected.class));
        order.verify(audit).recordRequired(any(), any(), any(), any(), any(), any(), any());
        order.verifyNoMoreInteractions();

        assertThat(transactions.readOnlyFlags()).as("assessment read-only, removal read-write").containsExactly(true, false);
        assertThat(transactions.outcomes()).containsExactly(Outcome.COMMITTED, Outcome.COMMITTED);
    }

    @Test
    @DisplayName("InnRewards is asked between the phases, with no transaction open")
    void loyaltyIsAskedOutsideAnyTransaction() {
        reject();

        verify(loyalty).merchantIdsForOrganizationIfKnown(ORG);
        assertThat(loyaltyCallsInTransaction).containsExactly(false);
    }

    @Test
    @DisplayName("the audit row: reason, ids, organizations, business name, and the contact details MASKED")
    void auditMetadata_isMasked() {
        reject();

        assertThat(auditMetadata())
                .containsEntry("reason", REASON)
                .containsEntry("userId", ID)
                .containsEntry("organizationsDeleted", List.of(ORG.toString()))
                .containsEntry("membershipsRemoved", List.of())
                .containsEntry("businessName", "Showtime Events")
                .containsEntry("email", "r****@showtime.co.zw")
                .containsEntry("phone", "****9000")
                .doesNotContainValue(EMAIL)
                .doesNotContainValue(PHONE);
    }

    @Test
    @DisplayName("the notice carries the contact details captured BEFORE the delete")
    void theNoticeIsPublishedWithTheApplicantsDetails() {
        reject();

        assertThat(event()).isEqualTo(new RegistrationRejected(ID, EMAIL, PHONE, "Rumbi", "Showtime Events", REASON));
    }

    @Test
    @DisplayName("a personal registration: no business name on the notice or the audit row")
    void personalRegistration_hasNoBusinessName() {
        when(tenantProfiles.findBusinessNameByUserId(ID)).thenReturn(Optional.empty());

        reject();

        assertThat(event().businessName()).isNull();
        assertThat(auditMetadata()).doesNotContainKey("businessName");
    }

    @Test
    @DisplayName("a blank business name counts as none")
    void blankBusinessName_isNone() {
        when(tenantProfiles.findBusinessNameByUserId(ID)).thenReturn(Optional.of("   "));

        reject();

        assertThat(event().businessName()).isNull();
    }

    @Test
    @DisplayName("an account with no organization: InnRewards is never asked and no organization is deleted")
    void noOrganization() {
        memberships(List.of());

        reject();

        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(any());
        verify(organizations, never()).deleteAllWithIds(anyCollection());
        verify(users).delete(any(User.class));
        assertThat(auditMetadata()).containsEntry("organizationsDeleted", List.of());
    }

    @Test
    @DisplayName("a membership in somebody else's business is removed with the account, the business kept")
    void membershipElsewhere_isRemovedWithTheAccount() {
        memberships(List.of(membership(ORG, ID, OrganizationMember.Role.OWNER),
                        membership(OTHER_ORG, ID, OrganizationMember.Role.STAFF)),
                organization(ORG, ID), organization(OTHER_ORG, 99L));

        reject();

        verify(organizations).deleteAllWithIds(Set.of(ORG));
        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(OTHER_ORG);
        assertThat(auditMetadata()).containsEntry("membershipsRemoved", List.of(OTHER_ORG.toString()));
    }

    @Test
    @DisplayName("a co-owner of somebody else's business: the business keeps its other owner")
    void coOwnerElsewhere_isRemovedWithTheAccount() {
        memberships(List.of(membership(ORG, ID, OrganizationMember.Role.OWNER),
                        membership(OTHER_ORG, ID, OrganizationMember.Role.OWNER)),
                organization(ORG, ID), organization(OTHER_ORG, 99L));
        when(members.countByOrganizationIdAndRole(OTHER_ORG, OrganizationMember.Role.OWNER)).thenReturn(2L);

        reject();

        verify(organizations).deleteAllWithIds(Set.of(ORG));
        assertThat(auditMetadata()).containsEntry("membershipsRemoved", List.of(OTHER_ORG.toString()));
    }

    @Test
    @DisplayName("an account with no phone: only the email's reset code is deleted")
    void noPhone_onlyTheEmailKey() {
        User noPhone = pending();
        noPhone.setPhoneNumber(null);
        account(noPhone);

        reject();

        verify(otps).deleteByPhoneNumber(EMAIL);
        verify(otps, never()).deleteByPhoneNumber(PHONE);
        assertThat(auditMetadata()).containsEntry("phone", "****");
    }

    // ---------------------------------------------------------------------
    // Refusals before anything is removed
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a blank reason is a 400 before anything is read")
    void blankReason() {
        assertThatThrownBy(() -> service.reject(ID, "   ", ADMIN, CONTEXT))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("A reason is required when rejecting a registration.");
                });
        assertThatThrownBy(() -> service.reject(ID, null, ADMIN, CONTEXT)).isInstanceOf(ResponseStatusException.class);
        verify(users, never()).findById(anyLong());
        assertNothingRemoved();
    }

    @Test
    @DisplayName("no such account: 404, the same message as approving")
    void unknownAccount() {
        when(users.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::reject).isInstanceOf(NotFoundException.class).hasMessage("User not found: 57");
        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(any());
        assertNothingRemoved();
    }

    @Test
    @DisplayName("the SUPER_ADMIN: 403")
    void superAdmin() {
        User owner = pending();
        owner.setRoles(User.roleNames(User.Role.SUPER_ADMIN));
        owner.setApproved(true);
        account(owner);

        assertThatThrownBy(this::reject).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getReason()).isEqualTo("The SUPER_ADMIN account cannot be rejected.");
        });
        assertNothingRemoved();
    }

    @Test
    @DisplayName("an approved account: 409 registration_already_decided")
    void alreadyApproved() {
        User approved = pending();
        approved.setApproved(true);
        approved.setActive(true);
        account(approved);

        assertRefused(HttpStatus.CONFLICT, "registration_already_decided",
                "This account has already been approved. Deactivate it instead of rejecting it.", null);
    }

    @Test
    @DisplayName("a staff account: 409 use_staff_endpoints")
    void staffAccount() {
        when(staffEligibility.isStaffAccount(any())).thenReturn(true);

        assertRefused(HttpStatus.CONFLICT, "use_staff_endpoints",
                "Reactivate staff with POST /admin/staff/{id}/reactivate.", null);
    }

    @Test
    @DisplayName("an account that is also an app customer: 409 registration_business_in_use / customer_account")
    void customerAccount() {
        when(customerProfiles.existsByUserId(ID)).thenReturn(true);

        assertRefused(HttpStatus.CONFLICT, "registration_business_in_use",
                "This account is also an InnBucks app customer, so the registration can't be rejected.",
                "customer_account");
    }

    @Test
    @DisplayName("an account with team members of its own: 409 registration_business_in_use / team_members")
    void teamMembers() {
        when(users.existsByCreatedByOrganizerUuid(USER_UUID)).thenReturn(true);

        assertRefused(HttpStatus.CONFLICT, "registration_business_in_use",
                "This account already has team members of its own, so the registration can't be rejected.",
                "team_members");
    }

    @Test
    @DisplayName("somebody else already belongs to the business: 409 registration_business_in_use / other_members")
    void otherMembers() {
        when(members.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(
                membership(ORG, ID, OrganizationMember.Role.OWNER),
                membership(ORG, 88L, OrganizationMember.Role.STAFF)));

        assertRefused(HttpStatus.CONFLICT, "registration_business_in_use",
                "This business already has other members, so the registration can't be rejected. Approve it, or "
                        + "remove the other members first.", "other_members");
        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(any());
    }

    @Test
    @DisplayName("the only owner of somebody else's business: 409 registration_business_in_use / sole_owner_elsewhere")
    void soleOwnerElsewhere() {
        memberships(List.of(membership(ORG, ID, OrganizationMember.Role.OWNER),
                        membership(OTHER_ORG, ID, OrganizationMember.Role.OWNER)),
                organization(ORG, ID), organization(OTHER_ORG, 99L));
        when(members.countByOrganizationIdAndRole(OTHER_ORG, OrganizationMember.Role.OWNER)).thenReturn(1L);

        assertRefused(HttpStatus.CONFLICT, "registration_business_in_use",
                "This account is the only owner of another business, so the registration can't be rejected. Make "
                        + "someone else an owner of that business first.", "sole_owner_elsewhere");
    }

    @Test
    @DisplayName("InnRewards holds a loyalty merchant for the business: 409 registration_business_in_use / loyalty_merchant")
    void loyaltyMerchant() {
        when(loyalty.merchantIdsForOrganizationIfKnown(ORG)).thenReturn(Optional.of(List.of(UUID.randomUUID())));

        assertRefused(HttpStatus.CONFLICT, "registration_business_in_use",
                "This business already has a loyalty merchant in InnRewards. Remove it there first, or approve the "
                        + "registration.", "loyalty_merchant");
        assertThat(transactions.outcomes()).as("no write phase began").hasSize(1);
    }

    @Test
    @DisplayName("InnRewards cannot be asked: 503 registration_check_unavailable, never a pass")
    void loyaltyUnknown() {
        when(loyalty.merchantIdsForOrganizationIfKnown(ORG)).thenReturn(Optional.empty());

        assertRefused(HttpStatus.SERVICE_UNAVAILABLE, "registration_check_unavailable",
                "We couldn't confirm this business isn't already set up in InnRewards. Try again in a minute.", null);
        assertThat(transactions.outcomes()).as("no write phase began").hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Under the row lock: anything that moved since the check
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("approved between the check and the lock: 409 registration_changed, rolled back")
    void approvedMeanwhile() {
        User approvedNow = pending();
        approvedNow.setApproved(true);
        when(users.lockById(ID)).thenReturn(Optional.of(approvedNow));

        assertChanged();
    }

    @Test
    @DisplayName("gone by the time of the lock (a concurrent rejection won): 409 registration_changed")
    void goneMeanwhile() {
        when(users.lockById(ID)).thenReturn(Optional.empty());

        assertChanged();
    }

    @Test
    @DisplayName("a second business appeared after InnRewards vouched for the first: 409 registration_changed")
    void organizationsChangedMeanwhile() {
        UUID second = UUID.fromString("9d3a4e6f-1b5c-4a7d-8e9f-2c3d4e5f6a7b");
        when(members.findByUserId(ID)).thenReturn(
                List.of(membership(ORG, ID, OrganizationMember.Role.OWNER)),
                List.of(membership(ORG, ID, OrganizationMember.Role.OWNER),
                        membership(second, ID, OrganizationMember.Role.OWNER)));
        when(organizations.findAllById(any())).thenReturn(
                List.of(organization(ORG, ID)),
                List.of(organization(ORG, ID), organization(second, ID)));
        when(members.findByOrganizationIdOrderByCreatedAtAsc(second))
                .thenReturn(List.of(membership(second, ID, OrganizationMember.Role.OWNER)));

        assertChanged();
        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(second);
    }

    @Test
    @DisplayName("a member joined after the check: 409 registration_changed")
    void memberJoinedMeanwhile() {
        when(members.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(
                List.of(membership(ORG, ID, OrganizationMember.Role.OWNER)),
                List.of(membership(ORG, ID, OrganizationMember.Role.OWNER),
                        membership(ORG, 88L, OrganizationMember.Role.ADMIN)));

        assertChanged();
    }

    @Test
    @DisplayName("the required audit row cannot be written: 503 audit_unavailable, and the removal rolls back")
    void auditFailure_rollsTheRemovalBack() {
        doThrow(new AuditUnavailableException(new IllegalStateException("audit store down")))
                .when(audit).recordRequired(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(this::reject).isInstanceOfSatisfying(AuditUnavailableException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.getErrorCode()).isEqualTo("audit_unavailable");
        });
        // The deletes ran inside the write transaction, which rolled back with
        // them; the notice was only ever registered for after a commit.
        assertThat(transactions.outcomes()).containsExactly(Outcome.COMMITTED, Outcome.ROLLED_BACK);
    }

    private void assertRefused(HttpStatus status, String errorCode, String message, String reason) {
        assertThatThrownBy(this::reject).isInstanceOfSatisfying(StaffPolicyException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getErrorCode()).isEqualTo(errorCode);
            assertThat(ex.getMessage()).isEqualTo(message);
            if (reason == null) {
                assertThat(ex.getExtra()).doesNotContainKey("reason");
            } else {
                assertThat(ex.getExtra()).containsEntry("reason", reason);
            }
        });
        assertNothingRemoved();
    }

    private void assertChanged() {
        assertThatThrownBy(this::reject).isInstanceOfSatisfying(RegistrationRejectionException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getErrorCode()).isEqualTo("registration_changed");
            assertThat(ex.getMessage())
                    .isEqualTo("This registration changed while it was being rejected. Refresh and try again.");
        });
        assertNothingRemoved();
        assertThat(transactions.outcomes()).containsExactly(Outcome.COMMITTED, Outcome.ROLLED_BACK);
    }

    @Test
    @DisplayName("the refusal precedence: the SUPER_ADMIN and a decided account are answered before anything else")
    void precedence() {
        User approvedStaff = pending();
        approvedStaff.setApproved(true);
        account(approvedStaff);
        when(staffEligibility.isStaffAccount(any())).thenReturn(true);
        when(customerProfiles.existsByUserId(ID)).thenReturn(true);

        assertRefused(HttpStatus.CONFLICT, "registration_already_decided",
                "This account has already been approved. Deactivate it instead of rejecting it.", null);
        verify(customerProfiles, never()).existsByUserId(anyLong());
    }

    @Test
    @DisplayName("every refusal reason has its own sentence")
    void everyReasonHasItsSentence() {
        Set<String> messages = new LinkedHashSet<>();
        for (String reason : List.of("other_members", "loyalty_merchant", "team_members", "customer_account",
                "sole_owner_elsewhere")) {
            RegistrationRejectionException ex = RegistrationRejectionException.businessInUse(reason);
            assertThat(ex.getExtra()).containsEntry("reason", reason);
            messages.add(ex.getMessage());
        }
        assertThat(messages).hasSize(5);
        assertThatThrownBy(() -> RegistrationRejectionException.businessInUse("made_up"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
