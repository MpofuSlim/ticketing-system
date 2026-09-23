package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.OrganizationException;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.OrgScope;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.util.HtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Organizations: the business a person works for (V39).
 *
 * <p>Three kinds of question are answered here.
 * <ul>
 *   <li><b>Session scope</b> — which organization a session acts for, and
 *       what rides the token for it ({@link OrgScope}). A session speaks for
 *       ONE organization, chosen by the person: automatic when they belong to
 *       exactly one, their pick when they belong to several, never a guess.</li>
 *   <li><b>Membership</b> — who may act for a business and in what role, with
 *       the guards that keep a business from ending up with nobody in charge.</li>
 *   <li><b>Products</b> — what a business may use, granted by the existing
 *       service-request approval rather than a second workflow.</li>
 * </ul>
 *
 * <p>Every endpoint scopes to the caller by membership. A caller who is not a
 * member gets the same 404 as for an organization that does not exist, so
 * nothing here is an oracle for which businesses are on the platform.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationProductRepository products;
    private final UserRepository users;
    private final AuditService auditService;
    private final TokenVersionPublisher tokenVersions;

    // ----------------------------------------------------------------------
    // Creation
    // ----------------------------------------------------------------------

    /**
     * Creates the organization a newly registered business owner runs, with
     * them as OWNER and their chosen bundles as its products.
     *
     * <p>Products are ACTIVE immediately, mirroring the bundles: the account
     * itself is created pending approval and cannot sign in until a SUPER_ADMIN
     * approves it, so approving the account IS approving what it registered
     * for — exactly as it has always been for {@code users.default_services}.
     */
    @Transactional
    public Organization createForOwner(User owner, String name, String contactEmail,
                                       String contactPhone, String address,
                                       String registrationNumber, Collection<String> bundles) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Organization org = organizations.save(Organization.builder()
                .id(UUID.randomUUID())
                .name(nameOrFallback(name, owner))
                .contactEmail(blankToNull(contactEmail))
                .contactPhone(blankToNull(contactPhone))
                .address(blankToNull(HtmlSanitizer.stripAll(address)))
                .registrationNumber(blankToNull(HtmlSanitizer.stripAll(registrationNumber)))
                .status(Organization.Status.ACTIVE)
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());
        members.save(OrganizationMember.builder()
                .id(UUID.randomUUID())
                .organizationId(org.getId())
                .userId(owner.getId())
                .role(OrganizationMember.Role.OWNER)
                .createdAt(now)
                .build());
        if (bundles != null) {
            for (String bundle : new TreeSet<>(bundles)) {
                if (Services.isKnownBundle(bundle)) {
                    upsertActive(org.getId(), bundle.trim().toLowerCase(Locale.ROOT), now);
                }
            }
        }
        log.info("Organization created organizationId={} ownerUserId={}", org.getId(), owner.getId());
        auditService.recordSuccess(AuditEventType.ORGANIZATION_CREATED,
                actorId(owner), AuditService.ACTOR_TYPE_USER,
                org.getId().toString(), AuditService.TARGET_TYPE_ORGANIZATION,
                Map.of("ownerUserUuid", String.valueOf(owner.getUserUuid()),
                        "products", String.valueOf(activeProducts(org.getId()))),
                null);
        return org;
    }

    // ----------------------------------------------------------------------
    // Session scope
    // ----------------------------------------------------------------------

    /** The caller's memberships in ACTIVE organizations. */
    @Transactional(readOnly = true)
    public List<OrganizationMember> activeMemberships(User user) {
        if (user == null || user.getId() == null) return List.of();
        List<OrganizationMember> mine = members.findByUserId(user.getId());
        if (mine.isEmpty()) return List.of();
        Set<UUID> active = organizations.findAllById(
                        mine.stream().map(OrganizationMember::getOrganizationId).toList()).stream()
                .filter(o -> o.getStatus() == Organization.Status.ACTIVE)
                .map(Organization::getId)
                .collect(Collectors.toSet());
        return mine.stream().filter(m -> active.contains(m.getOrganizationId())).toList();
    }

    /**
     * The organization a new session acts for without being asked: the only
     * one when there is exactly one, otherwise none. Two or more is the
     * person's choice ({@code POST /auth/organization-context}), never ours —
     * picking one would silently attribute their actions to a business they
     * did not choose.
     */
    @Transactional(readOnly = true)
    public UUID defaultOrganizationFor(User user) {
        List<OrganizationMember> active = activeMemberships(user);
        return active.size() == 1 ? active.get(0).getOrganizationId() : null;
    }

    /**
     * Re-checked on every refresh: the organization a session carries stays
     * only while the person is still a member of it and it is still ACTIVE.
     * Removed from it, or the business suspended, and the session falls back
     * to the default — so a removal takes effect at the next refresh even if
     * nobody bumped a token version.
     */
    @Transactional(readOnly = true)
    public UUID revalidate(User user, UUID current) {
        if (current != null && isActiveMember(user, current)) {
            return current;
        }
        return defaultOrganizationFor(user);
    }

    /** The organization a person may switch INTO: they must belong to it and it must be ACTIVE. */
    @Transactional(readOnly = true)
    public UUID requireSelectable(User user, UUID target) {
        if (target == null) {
            throw OrganizationException.notFound();
        }
        OrganizationMember membership = members.findByOrganizationIdAndUserId(target, user.getId())
                .orElseThrow(OrganizationException::notFound);
        Organization org = organizations.findById(membership.getOrganizationId())
                .orElseThrow(OrganizationException::notFound);
        if (org.getStatus() != Organization.Status.ACTIVE) {
            throw new OrganizationException(HttpStatus.FORBIDDEN, "organization_suspended",
                    "This organization is suspended. Contact support.");
        }
        return target;
    }

    /** What rides the token for {@code orgId}; empty when the person may no longer act for it. */
    @Transactional(readOnly = true)
    public Optional<OrgScope> scopeFor(User user, UUID orgId) {
        if (user == null || user.getId() == null || orgId == null) return Optional.empty();
        Optional<OrganizationMember> membership = members.findByOrganizationIdAndUserId(orgId, user.getId());
        if (membership.isEmpty()) return Optional.empty();
        Optional<Organization> org = organizations.findById(orgId);
        if (org.isEmpty() || org.get().getStatus() != Organization.Status.ACTIVE) return Optional.empty();
        return Optional.of(new OrgScope(orgId, membership.get().getRole().name(), activeProducts(orgId)));
    }

    /** True when the person has several organizations and the session has not chosen one. */
    @Transactional(readOnly = true)
    public boolean selectionRequired(User user, UUID chosen) {
        return chosen == null && activeMemberships(user).size() > 1;
    }

    // ----------------------------------------------------------------------
    // Products
    // ----------------------------------------------------------------------

    /**
     * The organization a product request is made FOR, stamped when it is
     * submitted: the one the session is acting for, provided the requester
     * runs it (OWNER or ADMIN); otherwise the one organization they own, if
     * exactly one; otherwise none. STAFF never request products for a business.
     */
    @Transactional(readOnly = true)
    public UUID organizationForRequest(User requester, UUID activeOrg) {
        if (activeOrg != null) {
            Optional<OrganizationMember> m = members.findByOrganizationIdAndUserId(activeOrg, requester.getId());
            if (m.isPresent() && m.get().getRole() != OrganizationMember.Role.STAFF) {
                return activeOrg;
            }
        }
        List<OrganizationMember> owned = members.findByUserId(requester.getId()).stream()
                .filter(m -> m.getRole() == OrganizationMember.Role.OWNER)
                .toList();
        return owned.size() == 1 ? owned.get(0).getOrganizationId() : null;
    }

    /**
     * Grants {@code product} when its request is approved. Target: the
     * organization the request was made for; else the one the requester owns;
     * else — someone with no business yet asking for a business product — a new
     * organization for them, since approving the request is what makes them a
     * business. An owner of several with no stamped target is ambiguous and is
     * skipped loudly rather than guessed; the user-level bundle is still granted.
     */
    @Transactional
    public void grantProduct(User requester, UUID requestedFor, String product, User reviewer) {
        String normalised = product == null ? "" : product.trim().toLowerCase(Locale.ROOT);
        if (!Services.isKnownBundle(normalised)) {
            return;
        }
        UUID target = requestedFor;
        if (target == null) {
            List<OrganizationMember> owned = members.findByUserId(requester.getId()).stream()
                    .filter(m -> m.getRole() == OrganizationMember.Role.OWNER)
                    .toList();
            if (owned.size() == 1) {
                target = owned.get(0).getOrganizationId();
            } else if (owned.isEmpty()) {
                target = createForOwner(requester, null, requester.getEmail(), requester.getPhoneNumber(),
                        null, null, List.of()).getId();
            } else {
                log.warn("Product not granted to an organization — requester owns {} and the request named none "
                        + "userId={} product={}", owned.size(), requester.getId(), normalised);
                return;
            }
        }
        if (organizations.findById(target).isEmpty()) {
            log.warn("Product not granted — organization no longer exists organizationId={}", target);
            return;
        }
        upsertActive(target, normalised, LocalDateTime.now(ZoneOffset.UTC));
        auditService.recordSuccess(AuditEventType.ORGANIZATION_PRODUCT_GRANTED,
                actorId(reviewer), AuditService.ACTOR_TYPE_USER,
                target.toString(), AuditService.TARGET_TYPE_ORGANIZATION,
                Map.of("product", normalised, "requesterUserUuid", String.valueOf(requester.getUserUuid())),
                null);
        log.info("Organization product granted organizationId={} product={}", target, normalised);
    }

    // ----------------------------------------------------------------------
    // The caller's organizations
    // ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OrganizationDTOs.OrganizationSummary> listMine(User caller) {
        List<OrganizationMember> mine = members.findByUserId(caller.getId());
        if (mine.isEmpty()) return List.of();
        Map<UUID, Organization> byId = organizations.findAllById(
                        mine.stream().map(OrganizationMember::getOrganizationId).toList()).stream()
                .collect(Collectors.toMap(Organization::getId, o -> o));
        return mine.stream()
                .filter(m -> byId.containsKey(m.getOrganizationId()))
                .map(m -> {
                    Organization o = byId.get(m.getOrganizationId());
                    return new OrganizationDTOs.OrganizationSummary(o.getId(), o.getName(),
                            m.getRole().name(), o.getStatus().name(), activeProducts(o.getId()));
                })
                .sorted(Comparator.comparing(OrganizationDTOs.OrganizationSummary::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationDTOs.OrganizationResponse get(User caller, UUID orgId) {
        OrganizationMember me = membershipOf(caller, orgId);
        return toResponse(requireOrganization(orgId), me);
    }

    @Transactional
    public OrganizationDTOs.OrganizationResponse update(User caller, UUID orgId,
                                                        OrganizationDTOs.UpdateOrganizationRequest req) {
        OrganizationMember me = membershipOf(caller, orgId);
        requireAtLeast(me, OrganizationMember.Role.ADMIN,
                "Only an owner or admin can change the organization's details.");
        Organization org = requireOrganization(orgId);
        String name = HtmlSanitizer.stripAll(req.getName());
        if (name == null || name.isBlank()) {
            throw new OrganizationException(HttpStatus.BAD_REQUEST, "organization_name_required",
                    "The organization needs a name.");
        }
        org.setName(name.trim());
        org.setContactEmail(blankToNull(req.getContactEmail()));
        org.setContactPhone(blankToNull(HtmlSanitizer.stripAll(req.getContactPhone())));
        org.setAddress(blankToNull(HtmlSanitizer.stripAll(req.getAddress())));
        org.setRegistrationNumber(blankToNull(HtmlSanitizer.stripAll(req.getRegistrationNumber())));
        org.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        organizations.save(org);
        auditService.recordSuccess(AuditEventType.ORGANIZATION_UPDATED,
                actorId(caller), AuditService.ACTOR_TYPE_USER,
                orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION, Map.of(), null);
        return toResponse(org, me);
    }

    // ----------------------------------------------------------------------
    // Members
    // ----------------------------------------------------------------------

    /** OWNER and ADMIN only: a member list is colleagues' contact details. */
    @Transactional(readOnly = true)
    public List<OrganizationDTOs.MemberResponse> listMembers(User caller, UUID orgId) {
        OrganizationMember me = membershipOf(caller, orgId);
        requireAtLeast(me, OrganizationMember.Role.ADMIN, "Only an owner or admin can see the member list.");
        List<OrganizationMember> all = members.findByOrganizationIdOrderByCreatedAtAsc(orgId);
        Map<Long, User> people = users.findAllById(all.stream().map(OrganizationMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        return all.stream()
                .filter(m -> people.containsKey(m.getUserId()))
                .map(m -> toMember(m, people.get(m.getUserId())))
                .toList();
    }

    /**
     * Adds an EXISTING account. OWNER may add any role; ADMIN may add STAFF.
     * Creating accounts for people who have none is deliberately not done here —
     * that is an onboarding flow with its own credential delivery, not a
     * membership change.
     */
    @Transactional
    public OrganizationDTOs.MemberResponse addMember(User caller, UUID orgId,
                                                     OrganizationDTOs.AddMemberRequest req) {
        OrganizationMember me = membershipOf(caller, orgId);
        requireAtLeast(me, OrganizationMember.Role.ADMIN, "Only an owner or admin can add members.");
        OrganizationMember.Role role = req.getRole();
        if (me.getRole() != OrganizationMember.Role.OWNER && role != OrganizationMember.Role.STAFF) {
            throw OrganizationException.roleInsufficient("Only an owner can add an owner or an admin.");
        }
        User person = resolveAccount(req.getEmail());
        if (members.findByOrganizationIdAndUserId(orgId, person.getId()).isPresent()) {
            throw new OrganizationException(HttpStatus.CONFLICT, "already_member",
                    "That person is already a member of this organization.");
        }
        OrganizationMember added = members.save(OrganizationMember.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .userId(person.getId())
                .role(role)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
        auditService.recordSuccess(AuditEventType.ORGANIZATION_MEMBER_ADDED,
                actorId(caller), AuditService.ACTOR_TYPE_USER,
                orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION,
                Map.of("memberUserUuid", String.valueOf(person.getUserUuid()), "role", role.name()),
                null);
        log.info("Organization member added organizationId={} userId={} role={}", orgId, person.getId(), role);
        return toMember(added, person);
    }

    /**
     * OWNER only. The last OWNER cannot be demoted — a business with nobody
     * able to hand out authority can never be repaired from inside.
     */
    @Transactional
    public OrganizationDTOs.MemberResponse changeRole(User caller, UUID orgId, UUID memberUserUuid,
                                                      OrganizationMember.Role role) {
        OrganizationMember me = membershipOf(caller, orgId);
        if (me.getRole() != OrganizationMember.Role.OWNER) {
            throw OrganizationException.roleInsufficient("Only an owner can change roles.");
        }
        User person = users.findByUserUuid(memberUserUuid).orElseThrow(MemberNotFound::member);
        OrganizationMember target = members.findByOrganizationIdAndUserId(orgId, person.getId())
                .orElseThrow(MemberNotFound::member);
        OrganizationMember.Role previous = target.getRole();
        if (previous == role) {
            return toMember(target, person);
        }
        if (previous == OrganizationMember.Role.OWNER
                && members.countByOrganizationIdAndRole(orgId, OrganizationMember.Role.OWNER) <= 1) {
            throw lastOwner();
        }
        target.setRole(role);
        members.save(target);
        endSessionsOf(person);
        auditService.recordSuccess(AuditEventType.ORGANIZATION_MEMBER_ROLE_CHANGED,
                actorId(caller), AuditService.ACTOR_TYPE_USER,
                orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION,
                Map.of("memberUserUuid", String.valueOf(person.getUserUuid()),
                        "from", previous.name(), "to", role.name()),
                null);
        log.info("Organization member role changed organizationId={} userId={} from={} to={}",
                orgId, person.getId(), previous, role);
        return toMember(target, person);
    }

    /**
     * OWNER removes anyone; ADMIN removes STAFF; anyone may leave. The last
     * OWNER can do neither — see {@link #changeRole}.
     */
    @Transactional
    public void removeMember(User caller, UUID orgId, UUID memberUserUuid) {
        OrganizationMember me = membershipOf(caller, orgId);
        User person = users.findByUserUuid(memberUserUuid).orElseThrow(MemberNotFound::member);
        OrganizationMember target = members.findByOrganizationIdAndUserId(orgId, person.getId())
                .orElseThrow(MemberNotFound::member);
        boolean leaving = Objects.equals(person.getId(), caller.getId());
        if (!leaving) {
            if (me.getRole() == OrganizationMember.Role.STAFF) {
                throw OrganizationException.roleInsufficient("Only an owner or admin can remove members.");
            }
            if (me.getRole() == OrganizationMember.Role.ADMIN && target.getRole() != OrganizationMember.Role.STAFF) {
                throw OrganizationException.roleInsufficient("An admin can only remove staff.");
            }
        }
        if (target.getRole() == OrganizationMember.Role.OWNER
                && members.countByOrganizationIdAndRole(orgId, OrganizationMember.Role.OWNER) <= 1) {
            throw lastOwner();
        }
        members.delete(target);
        endSessionsOf(person);
        auditService.recordSuccess(AuditEventType.ORGANIZATION_MEMBER_REMOVED,
                actorId(caller), AuditService.ACTOR_TYPE_USER,
                orgId.toString(), AuditService.TARGET_TYPE_ORGANIZATION,
                Map.of("memberUserUuid", String.valueOf(person.getUserUuid()),
                        "role", target.getRole().name(), "leftVoluntarily", leaving),
                null);
        log.info("Organization member removed organizationId={} userId={} leaving={}",
                orgId, person.getId(), leaving);
    }

    // ----------------------------------------------------------------------
    // Service-to-service
    // ----------------------------------------------------------------------

    /** Display names for a batch of organizations; unknown ids are simply absent. */
    @Transactional(readOnly = true)
    public List<OrganizationDTOs.OrganizationName> names(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return organizations.findAllById(new LinkedHashSet<>(ids)).stream()
                .map(o -> new OrganizationDTOs.OrganizationName(o.getId(), o.getName()))
                .toList();
    }

    /**
     * The people who may act for an organization (OWNER and ADMIN) with an
     * active account. Empty for an unknown organization — the same answer as
     * one with nobody to tell, so this is no existence oracle either.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDTOs.OrganizationAdmin> admins(UUID orgId) {
        List<OrganizationMember> runners = members.findByOrganizationIdAndRoleIn(orgId,
                EnumSet.of(OrganizationMember.Role.OWNER, OrganizationMember.Role.ADMIN));
        if (runners.isEmpty()) return List.of();
        return users.findAllById(runners.stream().map(OrganizationMember::getUserId).toList()).stream()
                .filter(User::isActive)
                .map(u -> new OrganizationDTOs.OrganizationAdmin(u.getUserUuid(), u.getEmail()))
                .toList();
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private boolean isActiveMember(User user, UUID orgId) {
        return members.findByOrganizationIdAndUserId(orgId, user.getId()).isPresent()
                && organizations.findById(orgId)
                        .map(o -> o.getStatus() == Organization.Status.ACTIVE)
                        .orElse(false);
    }

    private OrganizationMember membershipOf(User caller, UUID orgId) {
        if (caller == null || caller.getId() == null || orgId == null) {
            throw OrganizationException.notFound();
        }
        return members.findByOrganizationIdAndUserId(orgId, caller.getId())
                .orElseThrow(OrganizationException::notFound);
    }

    private Organization requireOrganization(UUID orgId) {
        return organizations.findById(orgId).orElseThrow(OrganizationException::notFound);
    }

    private static void requireAtLeast(OrganizationMember me, OrganizationMember.Role needed, String message) {
        if (me.getRole().ordinal() > needed.ordinal()) {
            throw OrganizationException.roleInsufficient(message);
        }
    }

    private List<String> activeProducts(UUID orgId) {
        return products.findByOrganizationId(orgId).stream()
                .filter(p -> p.getStatus() == OrganizationProduct.Status.ACTIVE)
                .map(OrganizationProduct::getProduct)
                .sorted()
                .toList();
    }

    private void upsertActive(UUID orgId, String product, LocalDateTime now) {
        Optional<OrganizationProduct> existing = products.findByOrganizationIdAndProduct(orgId, product);
        if (existing.isPresent()) {
            OrganizationProduct p = existing.get();
            if (p.getStatus() != OrganizationProduct.Status.ACTIVE) {
                p.setStatus(OrganizationProduct.Status.ACTIVE);
                p.setUpdatedAt(now);
                products.save(p);
            }
            return;
        }
        products.save(OrganizationProduct.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .product(product)
                .status(OrganizationProduct.Status.ACTIVE)
                .createdAt(now)
                .build());
    }

    /**
     * Ends the person's current access tokens, so a narrowed or removed
     * membership stops working now rather than at the token's expiry. Their
     * refresh token survives: the next refresh re-reads their memberships
     * ({@link #revalidate}) and mints a token that reflects the change — the
     * same lever {@code UserAdminService.setRoles} pulls for a role change.
     */
    private void endSessionsOf(User person) {
        person.setTokenVersion(person.getTokenVersion() + 1);
        User saved = users.save(person);
        tokenVersions.publish(saved.getUserUuid(), saved.getTokenVersion());
    }

    private User resolveAccount(String email) {
        String wanted = email == null ? "" : email.trim();
        List<User> matches = users.findAllByEmailIgnoreCase(wanted);
        Optional<User> exact = matches.stream().filter(u -> wanted.equals(u.getEmail())).findFirst();
        if (exact.isPresent()) return exact.get();
        if (matches.size() == 1) return matches.get(0);
        throw new OrganizationException(HttpStatus.NOT_FOUND, "account_not_found",
                "There's no account with that email. Ask them to register first, then add them.");
    }

    private OrganizationDTOs.OrganizationResponse toResponse(Organization o, OrganizationMember me) {
        return new OrganizationDTOs.OrganizationResponse(o.getId(), o.getName(), o.getContactEmail(),
                o.getContactPhone(), o.getAddress(), o.getRegistrationNumber(), o.getStatus().name(),
                activeProducts(o.getId()), me.getRole().name());
    }

    private static OrganizationDTOs.MemberResponse toMember(OrganizationMember m, User u) {
        return new OrganizationDTOs.MemberResponse(u.getUserUuid(), u.getFirstName(), u.getLastName(),
                u.getEmail(), m.getRole().name(), m.getCreatedAt());
    }

    private static OrganizationException lastOwner() {
        return new OrganizationException(HttpStatus.CONFLICT, "last_owner",
                "An organization must keep at least one owner. Make someone else an owner first.");
    }

    private static String nameOrFallback(String name, User owner) {
        String cleaned = HtmlSanitizer.stripAll(name);
        if (cleaned != null && !cleaned.isBlank()) return cleaned.trim();
        String person = ((owner.getFirstName() == null ? "" : owner.getFirstName()) + " "
                + (owner.getLastName() == null ? "" : owner.getLastName())).trim();
        if (!person.isEmpty()) return person;
        return owner.getEmail() != null ? owner.getEmail() : String.valueOf(owner.getPhoneNumber());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String actorId(User u) {
        return u == null ? null : String.valueOf(u.getId());
    }

    /** "No such member" answers identically for an unknown account and a non-member. */
    private static final class MemberNotFound {
        static OrganizationException member() {
            return new OrganizationException(HttpStatus.NOT_FOUND, "member_not_found",
                    "That person isn't a member of this organization.");
        }
    }
}
