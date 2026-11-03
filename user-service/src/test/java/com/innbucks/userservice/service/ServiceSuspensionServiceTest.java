package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.SuspendedLoyaltyMerchant;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.OrganizationException;
import com.innbucks.userservice.integration.EventServiceClient;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.SuspendedLoyaltyMerchantRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Suspending and reinstating one service of a business (V53, owner decisions
 * 2026-11-03): the checks, the fail-closed remote questions, what is signed out
 * and stopped, and the audit. The mint side is {@code ServiceSuspensionFilterTest}.
 */
class ServiceSuspensionServiceTest {

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final UUID M1 = UUID.randomUUID();
    private static final UUID M2 = UUID.randomUUID();
    private static final String ADMIN = "owner@innbucks.co.zw";
    private static final String REASON = "Loyalty was approved for this business by mistake (OPS-1204).";

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationProductRepository products = mock(OrganizationProductRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SuspendedLoyaltyMerchantRepository suspended = mock(SuspendedLoyaltyMerchantRepository.class);
    private final TokenVersionBumper tokens = mock(TokenVersionBumper.class);
    private final PosApiKeyService posKeys = mock(PosApiKeyService.class);
    private final EventServiceClient events = mock(EventServiceClient.class);
    private final LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ServiceSuspensionService service = new ServiceSuspensionService(organizations, products, members,
            users, suspended, tokens, posKeys, events, loyalty, audit, publisher, mock(PlatformTransactionManager.class));

    private final User owner = User.builder().id(1L).userUuid(UUID.randomUUID()).email("rudo@moyo.example")
            .password("x").roles(new LinkedHashSet<>(Set.of("MERCHANT_ADMIN", "EVENT_ORGANIZER"))).build();
    private final User colleague = User.builder().id(2L).userUuid(UUID.randomUUID()).email("tafadzwa@moyo.example")
            .password("x").roles(new LinkedHashSet<>(Set.of("CUSTOMER"))).build();

    private OrganizationProduct granted(String product, OrganizationProduct.Status status) {
        when(organizations.findById(ORG)).thenReturn(Optional.of(
                Organization.builder().id(ORG).name("Moyo Fresh Foods").build()));
        OrganizationProduct row = OrganizationProduct.builder().id(UUID.randomUUID()).organizationId(ORG)
                .product(product).status(status).build();
        when(products.findByOrganizationIdAndProduct(ORG, product)).thenReturn(Optional.of(row));
        when(products.lockByOrganizationIdAndProduct(ORG, product)).thenReturn(Optional.of(row));
        when(members.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(
                OrganizationMember.builder().organizationId(ORG).userId(1L).role(OrganizationMember.Role.OWNER).build(),
                OrganizationMember.builder().organizationId(ORG).userId(2L).role(OrganizationMember.Role.ADMIN).build()));
        when(members.findByOrganizationIdAndRoleIn(eq(ORG), any())).thenReturn(List.of(
                OrganizationMember.builder().organizationId(ORG).userId(1L).role(OrganizationMember.Role.OWNER).build()));
        when(users.findAllById(List.of(1L, 2L))).thenReturn(List.of(owner, colleague));
        when(users.findAllById(List.of(1L))).thenReturn(List.of(owner));
        return row;
    }

    private void assertNothingWritten() {
        verify(products, never()).lockByOrganizationIdAndProduct(any(), any());
        verify(products, never()).saveAndFlush(any());
        verifyNoInteractions(tokens, posKeys, audit, publisher);
        verify(suspended, never()).save(any());
    }

    // ---- suspend ------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void loyalty_signsOutMembersAndCashiers_stopsTillKeys_listsItsMerchants_andIsAudited() {
        OrganizationProduct row = granted(Services.LOYALTY, OrganizationProduct.Status.ACTIVE);
        when(loyalty.merchantIdsForOrganizationIfKnown(ORG)).thenReturn(Optional.of(List.of(M1, M2)));
        when(tokens.bumpAllAtMerchants(List.of(M1, M2))).thenReturn(6);
        when(posKeys.stopKeysOfMerchants(List.of(M1, M2))).thenReturn(1);

        OrganizationDTOs.ServiceSuspensionResult result = service.suspend(ORG, " Loyalty ",
                "<b>" + REASON + "</b>", ADMIN, AuditContext.none());

        assertThat(result).isEqualTo(new OrganizationDTOs.ServiceSuspensionResult(ORG, "Moyo Fresh Foods",
                "loyalty", "SUSPENDED", REASON, 2, 6, 1));
        assertThat(row.getStatus()).isEqualTo(OrganizationProduct.Status.SUSPENDED);
        assertThat(row.getSuspendedBy()).isEqualTo(ADMIN);
        assertThat(row.getSuspensionReason()).isEqualTo(REASON);
        assertThat(row.getSuspendedAt()).isNotNull();

        ArgumentCaptor<SuspendedLoyaltyMerchant> listed = ArgumentCaptor.forClass(SuspendedLoyaltyMerchant.class);
        verify(suspended, times(2)).save(listed.capture());
        assertThat(listed.getAllValues()).extracting(SuspendedLoyaltyMerchant::getMerchantId).containsExactly(M1, M2);
        verify(tokens).bump(owner);
        verify(tokens).bump(colleague);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(eq(AuditEventType.ORGANIZATION_PRODUCT_SUSPENDED), eq(ADMIN), any(),
                eq(ORG.toString()), any(), metadata.capture(), any());
        assertThat(metadata.getValue()).containsEntry("product", "loyalty").containsEntry("reason", REASON)
                .containsEntry("membersSignedOut", 2).containsEntry("cashiersSignedOut", 6)
                .containsEntry("tillKeysStopped", 1);
        verify(publisher).publishEvent(new ServiceSuspensionService.ServiceSuspensionChanged(
                ORG, "Moyo Fresh Foods", "loyalty", true, REASON));
        verifyNoInteractions(events);
    }

    @Test
    void loyalty_whenInnRewardsCannotSayWhichMerchants_isA503_andNothingChanges() {
        granted(Services.LOYALTY, OrganizationProduct.Status.ACTIVE);
        when(loyalty.merchantIdsForOrganizationIfKnown(ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspend(ORG, "loyalty", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("loyalty_check_unavailable");
                });
        assertNothingWritten();
    }

    @Test
    void ticketing_isRefusedWhileTheOwnersHaveEventsOnSale_andWhenEventServiceCannotSay() {
        granted(Services.TICKETING, OrganizationProduct.Status.ACTIVE);
        when(events.liveEventCount(List.of(owner.getUserUuid()))).thenReturn(OptionalLong.of(2));

        assertThatThrownBy(() -> service.suspend(ORG, "ticketing", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("events_on_sale");
                    assertThat(e.getExtra()).containsEntry("liveEvents", 2L);
                });

        when(events.liveEventCount(List.of(owner.getUserUuid()))).thenReturn(OptionalLong.empty());
        assertThatThrownBy(() -> service.suspend(ORG, "ticketing", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ticketing_check_unavailable"));
        assertNothingWritten();
        verifyNoInteractions(loyalty);
    }

    @Test
    void ticketing_withNothingOnSale_isSuspended_andSignsTheMembersOut() {
        OrganizationProduct row = granted(Services.TICKETING, OrganizationProduct.Status.ACTIVE);
        when(events.liveEventCount(List.of(owner.getUserUuid()))).thenReturn(OptionalLong.of(0));

        OrganizationDTOs.ServiceSuspensionResult result = service.suspend(ORG, "ticketing", REASON, ADMIN, null);

        assertThat(row.getStatus()).isEqualTo(OrganizationProduct.Status.SUSPENDED);
        assertThat(result.membersSignedOut()).isEqualTo(2);
        assertThat(result.cashiersSignedOut()).isZero();
        verify(tokens, times(2)).bump(any(User.class));
        verify(tokens, never()).bumpAllAtMerchants(anyCollection());
        verifyNoInteractions(posKeys, loyalty);
    }

    @Test
    void ticketing_whenNoOwnerIsAnOrganizer_asksEventServiceNothing() {
        owner.getRoles().remove("EVENT_ORGANIZER");
        granted(Services.TICKETING, OrganizationProduct.Status.ACTIVE);

        service.suspend(ORG, "ticketing", REASON, ADMIN, null);

        verifyNoInteractions(events);
    }

    @Test
    void marketplace_needsNoRemoteQuestion() {
        granted(Services.MARKETPLACE, OrganizationProduct.Status.ACTIVE);

        assertThat(service.suspend(ORG, "marketplace", REASON, ADMIN, null).status()).isEqualTo("SUSPENDED");

        verifyNoInteractions(events, loyalty, posKeys);
    }

    @Test
    void theRefusals_comeBeforeAnyRemoteQuestionOrWrite() {
        assertThatThrownBy(() -> service.suspend(ORG, "payroll", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("unknown_product"));
        assertThatThrownBy(() -> service.suspend(ORG, "loyalty", "  <i> </i> ", ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reason_required"));

        granted(Services.LOYALTY, OrganizationProduct.Status.SUSPENDED);
        assertThatThrownBy(() -> service.suspend(ORG, "loyalty", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("product_already_suspended"));

        when(products.findByOrganizationIdAndProduct(ORG, "marketplace")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.suspend(ORG, "marketplace", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("product_not_granted"));

        when(organizations.findById(ORG)).thenReturn(Optional.of(Organization.builder().id(ORG).name("Moyo")
                .status(Organization.Status.SUSPENDED).build()));
        assertThatThrownBy(() -> service.suspend(ORG, "loyalty", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("organization_not_active"));

        when(organizations.findById(ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.suspend(ORG, "loyalty", REASON, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertNothingWritten();
        verifyNoInteractions(events, loyalty);
    }

    // ---- reinstate ----------------------------------------------------------

    @Test
    void reinstate_putsItBack_clearsTheSuspension_andListsTheMerchantsNoLonger() {
        OrganizationProduct row = granted(Services.LOYALTY, OrganizationProduct.Status.SUSPENDED);
        row.setSuspensionReason(REASON);
        row.setSuspendedBy(ADMIN);
        when(suspended.deleteByOrganization(ORG)).thenReturn(2);

        OrganizationDTOs.ServiceSuspensionResult result = service.reinstate(ORG, "loyalty",
                "Agreement signed (OPS-1219).", ADMIN, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.membersSignedOut()).isZero();
        assertThat(row.getStatus()).isEqualTo(OrganizationProduct.Status.ACTIVE);
        assertThat(row.getSuspensionReason()).isNull();
        assertThat(row.getSuspendedBy()).isNull();
        verify(suspended).deleteByOrganization(ORG);
        verify(tokens, never()).bump(any(User.class));
        verify(audit).recordRequired(eq(AuditEventType.ORGANIZATION_PRODUCT_REINSTATED), eq(ADMIN), any(),
                eq(ORG.toString()), any(), anyMap(), any());
        verify(publisher).publishEvent(new ServiceSuspensionService.ServiceSuspensionChanged(
                ORG, "Moyo Fresh Foods", "loyalty", false, null));
    }

    @Test
    void reinstate_ofAnActiveService_isA409() {
        granted(Services.MARKETPLACE, OrganizationProduct.Status.ACTIVE);

        assertThatThrownBy(() -> service.reinstate(ORG, "marketplace", null, ADMIN, null))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("product_not_suspended"));
        verifyNoInteractions(audit, publisher);
    }
}
