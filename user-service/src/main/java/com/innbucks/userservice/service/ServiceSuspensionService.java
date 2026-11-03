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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Suspends and reinstates ONE service (product) of a business (V53, owner
 * decisions 2026-11-03), SUPER_ADMIN only ({@code organizations:manage}). Not a
 * delete: the grant stays, with who suspended it and why, and a reinstate puts
 * it back.
 *
 * <p><b>What a suspension reaches.</b> Every member of the business is signed
 * out (token version bumped), and their next token is minted without:
 * <ul>
 *   <li>the product in {@code products} (loyalty and marketplace scope a
 *       business's admins on it; already true of any non-ACTIVE product);</li>
 *   <li>for ticketing, the OWNER's {@code EVENT_ORGANIZER} role, which is what
 *       event, booking and seat-service authorise on
 *       ({@code ServiceSuspensionFilter});</li>
 *   <li>for loyalty, the shop roles of every cashier of the business's
 *       merchants (signed out too), and its till keys are refused and their
 *       live tokens stopped.</li>
 * </ul>
 *
 * <p><b>Order.</b> A read-only phase checks the business, then the remote
 * questions are asked with NO transaction open (CLAUDE.md "No remote call while
 * a transaction is open"), both fail closed: ticketing is refused while the
 * owners have a published event that has not ended, or when event-service
 * cannot say (503); loyalty needs InnRewards' list of the business's merchants,
 * or it is a 503. Then one write transaction locks the product row, re-checks
 * it, writes, signs out, and seals a REQUIRED audit as its last statement. The
 * owners and admins are told after commit.
 *
 * <p><b>Residuals, stated.</b> An organizer could publish an event between the
 * live-event check and the commit; it stays live and they cannot manage it
 * until reinstated. A loyalty merchant InnRewards creates for the business after
 * the suspension is not listed, so its cashiers are not stopped (InnRewards
 * refuses the business's admins, who would create it).
 */
@Service
@Slf4j
public class ServiceSuspensionService {

    static final int MAX_REASON = 500;

    private final OrganizationRepository organizations;
    private final OrganizationProductRepository products;
    private final OrganizationMemberRepository members;
    private final UserRepository users;
    private final SuspendedLoyaltyMerchantRepository suspendedMerchants;
    private final TokenVersionBumper tokenVersions;
    private final PosApiKeyService posKeys;
    private final EventServiceClient events;
    private final LoyaltyServiceClient loyalty;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public ServiceSuspensionService(OrganizationRepository organizations,
                                    OrganizationProductRepository products,
                                    OrganizationMemberRepository members,
                                    UserRepository users,
                                    SuspendedLoyaltyMerchantRepository suspendedMerchants,
                                    TokenVersionBumper tokenVersions,
                                    PosApiKeyService posKeys,
                                    EventServiceClient events,
                                    LoyaltyServiceClient loyalty,
                                    AuditService audit,
                                    ApplicationEventPublisher publisher,
                                    PlatformTransactionManager transactionManager) {
        this.organizations = organizations;
        this.products = products;
        this.members = members;
        this.users = users;
        this.suspendedMerchants = suspendedMerchants;
        this.tokenVersions = tokenVersions;
        this.posKeys = posKeys;
        this.events = events;
        this.loyalty = loyalty;
        this.audit = audit;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** After commit: tell the business's owners and admins. */
    public record ServiceSuspensionChanged(UUID organizationId, String organizationName, String product,
                                           boolean suspended, String reason) {}

    /** What the read phase found. */
    private record Plan(Organization organization, List<UUID> organizerUuids) {}

    // ------------------------------------------------------------------
    // Suspend
    // ------------------------------------------------------------------

    public OrganizationDTOs.ServiceSuspensionResult suspend(UUID orgId, String productIn, String reasonIn,
                                                            String admin, AuditContext context) {
        String product = product(productIn);
        String reason = reason(reasonIn);
        Plan plan = readTx.execute(status -> {
            Organization org = activeOrganization(orgId);
            OrganizationProduct row = products.findByOrganizationIdAndProduct(orgId, product)
                    .orElseThrow(() -> notGranted(product));
            if (row.getStatus() != OrganizationProduct.Status.ACTIVE) {
                throw alreadySuspended(product);
            }
            return new Plan(org, Services.TICKETING.equals(product) ? ownerOrganizers(orgId) : List.of());
        });

        // Remote questions, no transaction open, both fail closed.
        if (Services.TICKETING.equals(product) && !plan.organizerUuids().isEmpty()) {
            OptionalLong live = events.liveEventCount(plan.organizerUuids());
            if (live.isEmpty()) {
                throw new OrganizationException(HttpStatus.SERVICE_UNAVAILABLE, "ticketing_check_unavailable",
                        "We couldn't check this business's events, so ticketing wasn't suspended. Try again.");
            }
            if (live.getAsLong() > 0) {
                throw new OrganizationException(HttpStatus.CONFLICT, "events_on_sale",
                        "This business has events on sale. Ticketing can be suspended once they have ended.",
                        Map.of("liveEvents", live.getAsLong()));
            }
        }
        List<UUID> merchants = List.of();
        if (Services.LOYALTY.equals(product)) {
            merchants = loyalty.merchantIdsForOrganizationIfKnown(orgId).orElseThrow(() ->
                    new OrganizationException(HttpStatus.SERVICE_UNAVAILABLE, "loyalty_check_unavailable",
                            "We couldn't get this business's loyalty merchants, so loyalty wasn't suspended. "
                                    + "Try again."));
        }
        List<UUID> merchantIds = merchants;

        return tx.execute(status -> {
            Organization org = activeOrganization(orgId);
            OrganizationProduct row = products.lockByOrganizationIdAndProduct(orgId, product)
                    .orElseThrow(() -> notGranted(product));
            if (row.getStatus() != OrganizationProduct.Status.ACTIVE) {
                throw alreadySuspended(product);
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            row.setStatus(OrganizationProduct.Status.SUSPENDED);
            row.setSuspendedAt(now);
            row.setSuspendedBy(admin);
            row.setSuspensionReason(reason);
            row.setUpdatedAt(now);
            products.saveAndFlush(row);

            int cashiers = 0;
            int tillKeys = 0;
            if (!merchantIds.isEmpty()) {
                Set<UUID> already = new LinkedHashSet<>(suspendedMerchants.findSuspendedAmong(merchantIds));
                for (UUID merchant : merchantIds) {
                    if (!already.contains(merchant)) {
                        suspendedMerchants.save(SuspendedLoyaltyMerchant.builder()
                                .merchantId(merchant).organizationId(orgId).suspendedAt(now).build());
                    }
                }
                suspendedMerchants.flush();
                cashiers = tokenVersions.bumpAllAtMerchants(merchantIds);
                tillKeys = posKeys.stopKeysOfMerchants(merchantIds);
            }
            int signedOut = signOutMembers(orgId);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("product", product);
            metadata.put("reason", reason);
            metadata.put("membersSignedOut", signedOut);
            if (Services.LOYALTY.equals(product)) {
                metadata.put("merchants", merchantIds.size());
                metadata.put("cashiersSignedOut", cashiers);
                metadata.put("tillKeysStopped", tillKeys);
            }
            publisher.publishEvent(new ServiceSuspensionChanged(orgId, org.getName(), product, true, reason));
            log.info("Service suspended organizationId={} product={} members={} cashiers={} tillKeys={} by={}",
                    orgId, product, signedOut, cashiers, tillKeys, admin);
            audit.recordRequired(AuditEventType.ORGANIZATION_PRODUCT_SUSPENDED, actor(admin), actorType(admin),
                    orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION, metadata, orNone(context));
            return new OrganizationDTOs.ServiceSuspensionResult(orgId, org.getName(), product,
                    OrganizationProduct.Status.SUSPENDED.name(), reason, signedOut, cashiers, tillKeys);
        });
    }

    // ------------------------------------------------------------------
    // Reinstate
    // ------------------------------------------------------------------

    /**
     * Puts a suspended service back. Signs nobody out: the product (and the
     * organizer or shop roles) reach the business's people at their next sign-in
     * or refresh, at most the access token's 15 minutes; till keys work again at
     * once.
     */
    public OrganizationDTOs.ServiceSuspensionResult reinstate(UUID orgId, String productIn, String noteIn,
                                                              String admin, AuditContext context) {
        String product = product(productIn);
        String note = MfaService.cleanNote(noteIn);
        return tx.execute(status -> {
            Organization org = activeOrganization(orgId);
            OrganizationProduct row = products.lockByOrganizationIdAndProduct(orgId, product)
                    .orElseThrow(() -> notGranted(product));
            if (row.getStatus() != OrganizationProduct.Status.SUSPENDED) {
                throw new OrganizationException(HttpStatus.CONFLICT, "product_not_suspended",
                        "This business's " + Services.displayName(product) + " service is not suspended.");
            }
            String wasReason = row.getSuspensionReason();
            row.setStatus(OrganizationProduct.Status.ACTIVE);
            row.setSuspendedAt(null);
            row.setSuspendedBy(null);
            row.setSuspensionReason(null);
            row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            products.saveAndFlush(row);
            int merchants = Services.LOYALTY.equals(product) ? suspendedMerchants.deleteByOrganization(orgId) : 0;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("product", product);
            if (wasReason != null) metadata.put("suspensionReason", wasReason);
            if (note != null) metadata.put("note", note);
            if (Services.LOYALTY.equals(product)) metadata.put("merchants", merchants);
            publisher.publishEvent(new ServiceSuspensionChanged(orgId, org.getName(), product, false, null));
            log.info("Service reinstated organizationId={} product={} by={}", orgId, product, admin);
            audit.recordRequired(AuditEventType.ORGANIZATION_PRODUCT_REINSTATED, actor(admin), actorType(admin),
                    orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION, metadata, orNone(context));
            return new OrganizationDTOs.ServiceSuspensionResult(orgId, org.getName(), product,
                    OrganizationProduct.Status.ACTIVE.name(), null, 0, 0, 0);
        });
    }

    // ------------------------------------------------------------------

    /** The business's OWNERs who hold EVENT_ORGANIZER: whose events decide a ticketing suspension. */
    private List<UUID> ownerOrganizers(UUID orgId) {
        List<Long> ownerIds = members.findByOrganizationIdAndRoleIn(orgId, java.util.EnumSet.of(
                OrganizationMember.Role.OWNER)).stream().map(OrganizationMember::getUserId).toList();
        if (ownerIds.isEmpty()) {
            return List.of();
        }
        return users.findAllById(ownerIds).stream()
                .filter(u -> u.hasRole(User.Role.EVENT_ORGANIZER))
                .map(User::getUserUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private int signOutMembers(UUID orgId) {
        List<OrganizationMember> everyone = members.findByOrganizationIdOrderByCreatedAtAsc(orgId);
        List<User> people = users.findAllById(everyone.stream().map(OrganizationMember::getUserId).toList());
        for (User person : people) {
            tokenVersions.bump(person);
        }
        return people.size();
    }

    private Organization activeOrganization(UUID orgId) {
        Organization org = organizations.findById(orgId).orElseThrow(OrganizationException::notFound);
        if (org.getStatus() != Organization.Status.ACTIVE) {
            throw new OrganizationException(HttpStatus.CONFLICT, "organization_not_active",
                    "This organization is suspended, so its services can't be changed.");
        }
        return org;
    }

    private static String product(String raw) {
        String product = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!Services.isKnownBundle(product)) {
            throw new OrganizationException(HttpStatus.BAD_REQUEST, "unknown_product",
                    "Unknown product. Use one of: " + String.join(", ", Services.ALL_BUNDLES) + ".");
        }
        return product;
    }

    private static String reason(String raw) {
        String cleaned = MfaService.cleanNote(raw);
        if (cleaned == null || cleaned.isBlank()) {
            throw new OrganizationException(HttpStatus.BAD_REQUEST, "reason_required",
                    "Please say why the service is being suspended. The business's owners will see it.");
        }
        return cleaned.length() <= MAX_REASON ? cleaned : cleaned.substring(0, MAX_REASON);
    }

    private static OrganizationException notGranted(String product) {
        return new OrganizationException(HttpStatus.CONFLICT, "product_not_granted",
                "This business doesn't have the " + Services.displayName(product) + " service.");
    }

    private static OrganizationException alreadySuspended(String product) {
        return new OrganizationException(HttpStatus.CONFLICT, "product_already_suspended",
                "This business's " + Services.displayName(product) + " service is already suspended.");
    }

    private static String actor(String admin) {
        return admin == null ? "system" : admin;
    }

    private static String actorType(String admin) {
        return admin == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER;
    }

    private static AuditContext orNone(AuditContext context) {
        return context == null ? AuditContext.none() : context;
    }
}
