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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Organizations (V39): which business a session speaks for, who may act for
 * it, and what it may use.
 *
 * <p>Backed by small in-memory stores rather than per-test stubs, so the rules
 * are exercised against state that behaves like the tables — a removal really
 * removes, a role change really changes — and each case reads as the scenario
 * it pins.
 */
class OrganizationServiceTest {

    private OrganizationRepository orgRepo;
    private OrganizationMemberRepository memberRepo;
    private OrganizationProductRepository productRepo;
    private UserRepository userRepo;
    private AuditService audit;
    private TokenVersionPublisher tokenVersions;
    private OrganizationService service;

    private final Map<UUID, Organization> orgs = new HashMap<>();
    private final List<OrganizationMember> members = new ArrayList<>();
    private final List<OrganizationProduct> products = new ArrayList<>();
    private final Map<Long, User> users = new HashMap<>();

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        memberRepo = mock(OrganizationMemberRepository.class);
        productRepo = mock(OrganizationProductRepository.class);
        userRepo = mock(UserRepository.class);
        audit = mock(AuditService.class);
        tokenVersions = mock(TokenVersionPublisher.class);

        when(orgRepo.save(any(Organization.class))).thenAnswer(i -> {
            Organization o = i.getArgument(0);
            orgs.put(o.getId(), o);
            return o;
        });
        when(orgRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(orgs.get((UUID) i.getArgument(0))));
        when(orgRepo.findAllById(any())).thenAnswer(i -> {
            List<Organization> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                Organization o = orgs.get((UUID) id);
                if (o != null) out.add(o);
            }
            return out;
        });

        when(memberRepo.save(any(OrganizationMember.class))).thenAnswer(i -> {
            OrganizationMember m = i.getArgument(0);
            members.removeIf(x -> x.getId().equals(m.getId()));
            members.add(m);
            return m;
        });
        doAnswer(i -> {
            OrganizationMember m = i.getArgument(0);
            members.removeIf(x -> x.getId().equals(m.getId()));
            return null;
        }).when(memberRepo).delete(any(OrganizationMember.class));
        when(memberRepo.findByUserId(any())).thenAnswer(i ->
                members.stream().filter(m -> m.getUserId().equals(i.getArgument(0))).toList());
        when(memberRepo.existsByUserId(any())).thenAnswer(i ->
                members.stream().anyMatch(m -> m.getUserId().equals(i.getArgument(0))));
        when(memberRepo.findByOrganizationIdAndUserId(any(), any())).thenAnswer(i ->
                members.stream().filter(m -> m.getOrganizationId().equals(i.getArgument(0))
                        && m.getUserId().equals(i.getArgument(1))).findFirst());
        when(memberRepo.findByOrganizationIdOrderByCreatedAtAsc(any())).thenAnswer(i ->
                members.stream().filter(m -> m.getOrganizationId().equals(i.getArgument(0))).toList());
        when(memberRepo.findByOrganizationIdAndRoleIn(any(), anyCollection())).thenAnswer(i -> {
            Collection<?> roles = i.getArgument(1);
            return members.stream().filter(m -> m.getOrganizationId().equals(i.getArgument(0))
                    && roles.contains(m.getRole())).toList();
        });
        when(memberRepo.countByOrganizationIdAndRole(any(), any())).thenAnswer(i ->
                members.stream().filter(m -> m.getOrganizationId().equals(i.getArgument(0))
                        && m.getRole() == i.getArgument(1)).count());

        when(productRepo.save(any(OrganizationProduct.class))).thenAnswer(i -> {
            OrganizationProduct p = i.getArgument(0);
            products.removeIf(x -> x.getId().equals(p.getId()));
            products.add(p);
            return p;
        });
        when(productRepo.findByOrganizationId(any())).thenAnswer(i ->
                products.stream().filter(p -> p.getOrganizationId().equals(i.getArgument(0))).toList());
        when(productRepo.findByOrganizationIdAndProduct(any(), any())).thenAnswer(i ->
                products.stream().filter(p -> p.getOrganizationId().equals(i.getArgument(0))
                        && p.getProduct().equals(i.getArgument(1))).findFirst());

        when(userRepo.findByUserUuid(any())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getUserUuid().equals(i.getArgument(0))).findFirst());
        when(userRepo.findAllById(any())).thenAnswer(i -> {
            List<User> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                User u = users.get((Long) id);
                if (u != null) out.add(u);
            }
            return out;
        });
        when(userRepo.findAllByEmailIgnoreCase(any())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(i.getArgument(0)))
                .toList());
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service = new OrganizationService(orgRepo, memberRepo, productRepo, userRepo, audit, tokenVersions);
    }

    // ---- fixtures -------------------------------------------------------------

    private User person(long id, String email) {
        User u = User.builder().id(id).userUuid(UUID.randomUUID()).email(email)
                .firstName("First" + id).lastName("Last" + id).phoneNumber("+26377100000" + id)
                .roles(new HashSet<>()).active(true).tokenVersion(3L).build();
        users.put(id, u);
        return u;
    }

    private Organization org(String name, Organization.Status status) {
        Organization o = Organization.builder().id(UUID.randomUUID()).name(name).status(status).build();
        orgs.put(o.getId(), o);
        return o;
    }

    private void join(Organization o, User u, OrganizationMember.Role role) {
        members.add(OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(o.getId()).userId(u.getId()).role(role).build());
    }

    private void grant(Organization o, String product, OrganizationProduct.Status status) {
        products.add(OrganizationProduct.builder().id(UUID.randomUUID())
                .organizationId(o.getId()).product(product).status(status).build());
    }

    private OrganizationMember.Role roleOf(Organization o, User u) {
        return members.stream().filter(m -> m.getOrganizationId().equals(o.getId())
                && m.getUserId().equals(u.getId())).findFirst().map(OrganizationMember::getRole).orElse(null);
    }

    private static OrganizationDTOs.AddMemberRequest add(String email, OrganizationMember.Role role) {
        OrganizationDTOs.AddMemberRequest r = new OrganizationDTOs.AddMemberRequest();
        r.setEmail(email);
        r.setRole(role);
        return r;
    }

    private static void assertCode(Throwable t, String code) {
        assertThat(t).isInstanceOf(OrganizationException.class);
        assertThat(((OrganizationException) t).getErrorCode()).isEqualTo(code);
    }

    // ---- creation -------------------------------------------------------------

    @Nested
    @DisplayName("creation at registration")
    class Creation {

        @Test
        @DisplayName("the owner becomes OWNER and the chosen bundles become ACTIVE products")
        void createsOwnerAndProducts() {
            User rudo = person(1, "rudo@example.com");

            Organization o = service.createForOwner(rudo, "Chikwanha Traders", "rudo@example.com",
                    "+263772123456", null, null, List.of("marketplace", "loyalty", "not-a-bundle"));

            assertThat(o.getName()).isEqualTo("Chikwanha Traders");
            assertThat(roleOf(o, rudo)).isEqualTo(OrganizationMember.Role.OWNER);
            assertThat(service.scopeFor(rudo, o.getId())).get()
                    .extracting(OrgScope::products).asList()
                    .containsExactly("loyalty", "marketplace");
        }

        @Test
        @DisplayName("with no business name, the organization is named after the person — never blank")
        void namesAfterPersonWhenNoBusinessName() {
            User tendai = person(2, "tendai@example.com");

            Organization o = service.createForOwner(tendai, "  ", null, null, null, null, List.of("ticketing"));

            assertThat(o.getName()).isEqualTo("First2 Last2");
        }
    }

    // ---- session scope ------------------------------------------------------

    @Nested
    @DisplayName("which organization a session acts for")
    class SessionScope {

        @Test
        @DisplayName("exactly one organization is chosen automatically")
        void singleOrganizationIsDefault() {
            User rudo = person(1, "rudo@example.com");
            Organization o = org("Chikwanha Traders", Organization.Status.ACTIVE);
            join(o, rudo, OrganizationMember.Role.OWNER);

            assertThat(service.defaultOrganizationFor(rudo)).isEqualTo(o.getId());
            assertThat(service.selectionRequired(rudo, null)).isFalse();
        }

        @Test
        @DisplayName("two organizations are the person's choice, never ours")
        void severalOrganizationsRequireAChoice() {
            User rudo = person(1, "rudo@example.com");
            Organization a = org("A", Organization.Status.ACTIVE);
            Organization b = org("B", Organization.Status.ACTIVE);
            join(a, rudo, OrganizationMember.Role.OWNER);
            join(b, rudo, OrganizationMember.Role.STAFF);

            assertThat(service.defaultOrganizationFor(rudo)).isNull();
            assertThat(service.selectionRequired(rudo, null)).isTrue();
            assertThat(service.selectionRequired(rudo, a.getId())).isFalse();
        }

        @Test
        @DisplayName("a suspended organization is not counted, so the one active one is the default")
        void suspendedOrganizationIsIgnored() {
            User rudo = person(1, "rudo@example.com");
            Organization live = org("Live", Organization.Status.ACTIVE);
            Organization dead = org("Dead", Organization.Status.SUSPENDED);
            join(live, rudo, OrganizationMember.Role.OWNER);
            join(dead, rudo, OrganizationMember.Role.OWNER);

            assertThat(service.defaultOrganizationFor(rudo)).isEqualTo(live.getId());
        }

        @Test
        @DisplayName("refresh keeps the current organization while the person is still a member")
        void revalidateKeepsCurrent() {
            User rudo = person(1, "rudo@example.com");
            Organization a = org("A", Organization.Status.ACTIVE);
            Organization b = org("B", Organization.Status.ACTIVE);
            join(a, rudo, OrganizationMember.Role.OWNER);
            join(b, rudo, OrganizationMember.Role.STAFF);

            assertThat(service.revalidate(rudo, b.getId())).isEqualTo(b.getId());
        }

        @Test
        @DisplayName("refresh drops an organization the person was removed from")
        void revalidateDropsRemovedMembership() {
            User rudo = person(1, "rudo@example.com");
            Organization a = org("A", Organization.Status.ACTIVE);
            Organization gone = org("Gone", Organization.Status.ACTIVE);
            join(a, rudo, OrganizationMember.Role.OWNER);

            assertThat(service.revalidate(rudo, gone.getId())).isEqualTo(a.getId());
        }

        @Test
        @DisplayName("refresh drops an organization that has been suspended")
        void revalidateDropsSuspended() {
            User rudo = person(1, "rudo@example.com");
            Organization o = org("A", Organization.Status.ACTIVE);
            join(o, rudo, OrganizationMember.Role.OWNER);
            o.setStatus(Organization.Status.SUSPENDED);

            assertThat(service.revalidate(rudo, o.getId())).isNull();
        }

        @Test
        @DisplayName("switching into an organization you don't belong to is a 404, not a hint it exists")
        void requireSelectableRefusesNonMember() {
            User rudo = person(1, "rudo@example.com");
            Organization other = org("Other", Organization.Status.ACTIVE);

            assertThatThrownBy(() -> service.requireSelectable(rudo, other.getId()))
                    .satisfies(t -> assertCode(t, "organization_not_found"));
        }

        @Test
        @DisplayName("switching into a suspended organization is refused")
        void requireSelectableRefusesSuspended() {
            User rudo = person(1, "rudo@example.com");
            Organization o = org("A", Organization.Status.SUSPENDED);
            join(o, rudo, OrganizationMember.Role.OWNER);

            assertThatThrownBy(() -> service.requireSelectable(rudo, o.getId()))
                    .satisfies(t -> assertCode(t, "organization_suspended"));
        }

        @Test
        @DisplayName("the token scope carries the role and only ACTIVE products, sorted")
        void scopeCarriesRoleAndActiveProducts() {
            User rudo = person(1, "rudo@example.com");
            Organization o = org("A", Organization.Status.ACTIVE);
            join(o, rudo, OrganizationMember.Role.ADMIN);
            grant(o, "marketplace", OrganizationProduct.Status.ACTIVE);
            grant(o, "loyalty", OrganizationProduct.Status.SUSPENDED);
            grant(o, "ticketing", OrganizationProduct.Status.ACTIVE);

            OrgScope scope = service.scopeFor(rudo, o.getId()).orElseThrow();

            assertThat(scope.orgRole()).isEqualTo("ADMIN");
            assertThat(scope.products()).containsExactly("marketplace", "ticketing");
        }
    }

    // ---- members ----------------------------------------------------------------

    @Nested
    @DisplayName("membership rules")
    class Members {

        private Organization o;
        private User owner;
        private User admin;
        private User staff;
        private User outsider;

        @BeforeEach
        void business() {
            o = org("Chikwanha Traders", Organization.Status.ACTIVE);
            owner = person(1, "rudo@example.com");
            admin = person(2, "tendai@example.com");
            staff = person(3, "farai@example.com");
            outsider = person(4, "chipo@example.com");
            join(o, owner, OrganizationMember.Role.OWNER);
            join(o, admin, OrganizationMember.Role.ADMIN);
            join(o, staff, OrganizationMember.Role.STAFF);
        }

        @Test
        @DisplayName("a non-member gets the same 404 as for an organization that doesn't exist")
        void nonMemberSeesNothing() {
            assertThatThrownBy(() -> service.get(outsider, o.getId()))
                    .satisfies(t -> assertCode(t, "organization_not_found"));
            assertThatThrownBy(() -> service.get(outsider, UUID.randomUUID()))
                    .satisfies(t -> assertCode(t, "organization_not_found"));
        }

        @Test
        @DisplayName("staff can't see the member list — it is colleagues' contact details")
        void staffCannotListMembers() {
            assertThatThrownBy(() -> service.listMembers(staff, o.getId()))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));
            assertThat(service.listMembers(admin, o.getId())).hasSize(3);
        }

        @Test
        @DisplayName("an owner can add any role; the new member exists afterwards")
        void ownerAddsAdmin() {
            service.addMember(owner, o.getId(), add("chipo@example.com", OrganizationMember.Role.ADMIN));

            assertThat(roleOf(o, outsider)).isEqualTo(OrganizationMember.Role.ADMIN);
            verify(audit).recordSuccess(eq(AuditEventType.ORGANIZATION_MEMBER_ADDED), any(), any(),
                    eq(o.getId().toString()), eq(AuditService.TARGET_TYPE_ORGANIZATION), any(), any());
        }

        @Test
        @DisplayName("an admin can add staff but not another admin")
        void adminAddsOnlyStaff() {
            assertThatThrownBy(() -> service.addMember(admin, o.getId(),
                    add("chipo@example.com", OrganizationMember.Role.ADMIN)))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));

            service.addMember(admin, o.getId(), add("chipo@example.com", OrganizationMember.Role.STAFF));
            assertThat(roleOf(o, outsider)).isEqualTo(OrganizationMember.Role.STAFF);
        }

        @Test
        @DisplayName("staff can't add anyone")
        void staffCannotAdd() {
            assertThatThrownBy(() -> service.addMember(staff, o.getId(),
                    add("chipo@example.com", OrganizationMember.Role.STAFF)))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));
        }

        @Test
        @DisplayName("adding someone with no account names the problem rather than creating one")
        void addUnknownEmail() {
            assertThatThrownBy(() -> service.addMember(owner, o.getId(),
                    add("nobody@example.com", OrganizationMember.Role.STAFF)))
                    .satisfies(t -> assertCode(t, "account_not_found"));
        }

        @Test
        @DisplayName("adding an existing member is a conflict, not a silent re-add")
        void addExistingMember() {
            assertThatThrownBy(() -> service.addMember(owner, o.getId(),
                    add("FARAI@example.com", OrganizationMember.Role.ADMIN)))
                    .satisfies(t -> assertCode(t, "already_member"));
        }

        @Test
        @DisplayName("only an owner changes roles")
        void onlyOwnerChangesRoles() {
            assertThatThrownBy(() -> service.changeRole(admin, o.getId(), staff.getUserUuid(),
                    OrganizationMember.Role.ADMIN))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));
        }

        @Test
        @DisplayName("a role change ends the member's current tokens so the change takes effect now")
        void roleChangeEndsSessions() {
            service.changeRole(owner, o.getId(), staff.getUserUuid(), OrganizationMember.Role.ADMIN);

            assertThat(roleOf(o, staff)).isEqualTo(OrganizationMember.Role.ADMIN);
            assertThat(staff.getTokenVersion()).isEqualTo(4L);
            verify(tokenVersions).publish(staff.getUserUuid(), 4L);
        }

        @Test
        @DisplayName("the last owner can't be demoted — nobody could hand out authority again")
        void lastOwnerCannotBeDemoted() {
            assertThatThrownBy(() -> service.changeRole(owner, o.getId(), owner.getUserUuid(),
                    OrganizationMember.Role.ADMIN))
                    .satisfies(t -> assertCode(t, "last_owner"));
            assertThat(roleOf(o, owner)).isEqualTo(OrganizationMember.Role.OWNER);
        }

        @Test
        @DisplayName("with a second owner, the first can step down")
        void ownerStepsDownWhenAnotherExists() {
            service.changeRole(owner, o.getId(), admin.getUserUuid(), OrganizationMember.Role.OWNER);

            service.changeRole(owner, o.getId(), owner.getUserUuid(), OrganizationMember.Role.ADMIN);

            assertThat(roleOf(o, owner)).isEqualTo(OrganizationMember.Role.ADMIN);
        }

        @Test
        @DisplayName("an admin can remove staff but not another admin or an owner")
        void adminRemovesOnlyStaff() {
            assertThatThrownBy(() -> service.removeMember(admin, o.getId(), owner.getUserUuid()))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));

            service.removeMember(admin, o.getId(), staff.getUserUuid());

            assertThat(roleOf(o, staff)).isNull();
            verify(tokenVersions).publish(staff.getUserUuid(), 4L);
        }

        @Test
        @DisplayName("staff can leave on their own")
        void staffCanLeave() {
            service.removeMember(staff, o.getId(), staff.getUserUuid());

            assertThat(roleOf(o, staff)).isNull();
        }

        @Test
        @DisplayName("the last owner can't leave either")
        void lastOwnerCannotLeave() {
            assertThatThrownBy(() -> service.removeMember(owner, o.getId(), owner.getUserUuid()))
                    .satisfies(t -> assertCode(t, "last_owner"));
        }

        @Test
        @DisplayName("staff can't update the organization's details")
        void staffCannotUpdate() {
            OrganizationDTOs.UpdateOrganizationRequest req = new OrganizationDTOs.UpdateOrganizationRequest();
            req.setName("New name");

            assertThatThrownBy(() -> service.update(staff, o.getId(), req))
                    .satisfies(t -> assertCode(t, "organization_role_insufficient"));
            assertThat(service.update(admin, o.getId(), req).name()).isEqualTo("New name");
        }
    }

    // ---- products -------------------------------------------------------------

    @Nested
    @DisplayName("products")
    class Products {

        @Test
        @DisplayName("a request is stamped with the session's organization when the requester runs it")
        void requestStampedWithActiveOrgWhenRunIt() {
            User rudo = person(1, "rudo@example.com");
            Organization a = org("A", Organization.Status.ACTIVE);
            Organization b = org("B", Organization.Status.ACTIVE);
            join(a, rudo, OrganizationMember.Role.OWNER);
            join(b, rudo, OrganizationMember.Role.ADMIN);

            assertThat(service.organizationForRequest(rudo, b.getId())).isEqualTo(b.getId());
        }

        @Test
        @DisplayName("staff don't request products for a business; it falls back to the one they own")
        void staffRequestFallsBackToOwnedOrg() {
            User rudo = person(1, "rudo@example.com");
            Organization mine = org("Mine", Organization.Status.ACTIVE);
            Organization employer = org("Employer", Organization.Status.ACTIVE);
            join(mine, rudo, OrganizationMember.Role.OWNER);
            join(employer, rudo, OrganizationMember.Role.STAFF);

            assertThat(service.organizationForRequest(rudo, employer.getId())).isEqualTo(mine.getId());
        }

        @Test
        @DisplayName("approval grants the product to the organization the request was made for")
        void grantToStampedOrganization() {
            User rudo = person(1, "rudo@example.com");
            User reviewer = person(9, "ops@example.com");
            Organization o = org("A", Organization.Status.ACTIVE);
            join(o, rudo, OrganizationMember.Role.OWNER);

            service.grantProduct(rudo, o.getId(), "Marketplace", reviewer);

            assertThat(service.scopeFor(rudo, o.getId()).orElseThrow().products()).containsExactly("marketplace");
        }

        @Test
        @DisplayName("a requester with no business gets one when a business product is approved")
        void grantCreatesOrganizationWhenNoneOwned() {
            User rudo = person(1, "rudo@example.com");
            User reviewer = person(9, "ops@example.com");

            service.grantProduct(rudo, null, "marketplace", reviewer);

            UUID created = service.defaultOrganizationFor(rudo);
            assertThat(created).isNotNull();
            assertThat(roleOf(orgs.get(created), rudo)).isEqualTo(OrganizationMember.Role.OWNER);
            assertThat(service.scopeFor(rudo, created).orElseThrow().products()).containsExactly("marketplace");
        }

        @Test
        @DisplayName("an owner of several with no stamped target is skipped, never guessed")
        void grantSkipsAmbiguousOwner() {
            User rudo = person(1, "rudo@example.com");
            User reviewer = person(9, "ops@example.com");
            Organization a = org("A", Organization.Status.ACTIVE);
            Organization b = org("B", Organization.Status.ACTIVE);
            join(a, rudo, OrganizationMember.Role.OWNER);
            join(b, rudo, OrganizationMember.Role.OWNER);

            service.grantProduct(rudo, null, "marketplace", reviewer);

            assertThat(products).isEmpty();
        }

        @Test
        @DisplayName("an unknown product name grants nothing")
        void grantIgnoresUnknownProduct() {
            User rudo = person(1, "rudo@example.com");
            Organization o = org("A", Organization.Status.ACTIVE);
            join(o, rudo, OrganizationMember.Role.OWNER);

            service.grantProduct(rudo, o.getId(), "casino", person(9, "ops@example.com"));

            assertThat(products).isEmpty();
        }
    }

    // ---- service-to-service --------------------------------------------------

    @Test
    @DisplayName("admins lists active OWNER and ADMIN members only")
    void adminsAreActiveOwnersAndAdmins() {
        Organization o = org("A", Organization.Status.ACTIVE);
        User owner = person(1, "rudo@example.com");
        User admin = person(2, "tendai@example.com");
        User staff = person(3, "farai@example.com");
        User gone = person(4, "left@example.com");
        gone.setActive(false);
        join(o, owner, OrganizationMember.Role.OWNER);
        join(o, admin, OrganizationMember.Role.ADMIN);
        join(o, staff, OrganizationMember.Role.STAFF);
        join(o, gone, OrganizationMember.Role.ADMIN);

        Set<String> emails = service.admins(o.getId()).stream()
                .map(OrganizationDTOs.OrganizationAdmin::email).collect(Collectors.toSet());

        assertThat(emails).containsExactlyInAnyOrder("rudo@example.com", "tendai@example.com");
        assertThat(service.admins(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("names omits unknown ids rather than failing the batch")
    void namesOmitUnknownIds() {
        Organization o = org("Chikwanha Traders", Organization.Status.ACTIVE);

        assertThat(service.names(List.of(o.getId(), UUID.randomUUID())))
                .extracting(OrganizationDTOs.OrganizationName::name)
                .containsExactly("Chikwanha Traders");
    }
}
