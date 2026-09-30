package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportRefLookup;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppPhoneView;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.SearchResult;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The search (design §3.1.1): refusals record the KIND only and resolve
 * nothing; a reference returns only its owning section; a phone or email fans
 * out to the visible sections only; a failing section is UNAVAILABLE without
 * taking the others down; and the lookup is recorded fail-closed.
 */
class SupportSearchServiceTest {

    private SupportAccessLogWriter accessLog;
    private SupportStaffTargets staffTargets;
    private ConsoleSupportSection console;
    private InnbucksAppSupportSection innbucksApp;
    private DeviceSupportService deviceSupport;
    private UserRepository users;
    private CustomerProfileRepository customerProfiles;
    private SupportSearchService search;
    private User tariro;

    @BeforeEach
    void setUp() {
        accessLog = mock(SupportAccessLogWriter.class);
        staffTargets = mock(SupportStaffTargets.class);
        console = mock(ConsoleSupportSection.class);
        innbucksApp = mock(InnbucksAppSupportSection.class);
        deviceSupport = mock(DeviceSupportService.class);
        users = mock(UserRepository.class);
        customerProfiles = mock(CustomerProfileRepository.class);
        SupportProperties props = new SupportProperties();
        props.afterPropertiesSet();
        SupportProperties.Limiter generous = new SupportProperties.Limiter();
        generous.setShortWindowMax(1000);
        generous.setDailyMax(1000);
        search = new SupportSearchService(new SupportQueryClassifier("ZW"),
                new SupportLookupLimiter(null, generous, SupportMetrics.none(), () -> 0L), accessLog, staffTargets,
                SupportMetrics.none(), console, innbucksApp, deviceSupport, users, customerProfiles, props,
                Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));

        tariro = User.builder().id(1042L).userUuid(UUID.fromString("9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40"))
                .firstName("Tariro").lastName("Moyo").email("tariro@example.com").phoneNumber("+263771234567")
                .roles(new LinkedHashSet<>(List.of("MERCHANT_ADMIN"))).active(true).approved(true).build();
        when(users.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(users.findAllByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of());
        when(console.isConsoleAccount(any())).thenReturn(true);
        when(console.section(any(), any(), any(), any())).thenAnswer(inv -> {
            List<User> accounts = inv.getArgument(0);
            List<ConsoleAccountView> views = accounts.stream().map(u -> new ConsoleAccountView(u.getId(),
                    u.getUserUuid(), "Tariro Moyo", u.getEmail(), u.getPhoneNumber(), "ACTIVE", List.of(), false, true,
                    null, null, 0, null, false, null, List.of(), List.of(), "Signs in normally.", List.of())).toList();
            return new SectionView<>(views.isEmpty() ? "NOT_FOUND" : "OK", inv.getArgument(1), "s", "g",
                    new ConsoleSectionData(views));
        });
        when(innbucksApp.section(any(), any())).thenAnswer(inv -> {
            Map<String, Optional<User>> phones = inv.getArgument(0);
            List<InnbucksAppPhoneView> views = phones.keySet().stream()
                    .map(p -> new InnbucksAppPhoneView(p, null, null)).toList();
            return new SectionView<>("OK", inv.getArgument(1), "s", "g", new InnbucksAppSectionData(views));
        });
    }

    private static SupportAgent agent(String... authorities) {
        return new SupportAgent("agent.one@innbucks.co.zw", 7L, UUID.randomUUID(), "agent.one@innbucks.co.zw", null,
                null, Set.of(authorities));
    }

    private static SupportAgent both() {
        return agent(PermissionCatalog.SUPPORT_CONSOLE_READ, PermissionCatalog.DEVICE_SECURITY_READ);
    }

    private SupportAccessLog requiredRow() {
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).requiredWithUniqueLookupId(row.capture());
        return row.getValue();
    }

    @ParameterizedTest
    @CsvSource({
            "4111111111111111, query_not_accepted, NOT_ACCEPTED",
            "AB12CD34EF56, query_not_accepted, NOT_ACCEPTED",
            "Tariro Moyo, query_not_recognised, NOT_RECOGNISED",
            "MKT-0A1B2C3D4E5F, query_not_supported, MARKETPLACE_ORDER",
            "VCH-0A1B2C3D4E5F, query_not_supported, VOUCHER_ORDER",
            "TRK-7K2M9Q4X8Z, query_not_supported, PARCEL",
            "INN-20260930-A1B2C3, query_not_supported, BOOKING_CONFIRMATION",
            "20260930-00012K, query_not_supported, TICKET_NUMBER",
            "TKZ-MKT-0A1B2C3D4E5F, query_not_supported, PAYMENT_REFERENCE"
    })
    @DisplayName("a refused query is logged by KIND only — never its text — and nothing is resolved")
    void refusalsLogTheKindOnly(String q, String errorCode, String kind) {
        assertThatThrownBy(() -> search.search(both(), q, "41.221.147.12"))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo(errorCode));
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOp()).isEqualTo("SEARCH_REFUSED");
        assertThat(row.getValue().getOutcome()).isEqualTo(errorCode);
        assertThat(row.getValue().getQueryKind()).isEqualTo(kind);
        assertThat(row.getValue().getQueryMasked()).isNull();
        assertThat(row.getValue().getCustomerKeys()).isNull();
        assertThat(row.getValue().getLookupId()).isNull();
        verifyNoInteractions(users, deviceSupport, console, innbucksApp);
        verify(accessLog, never()).requiredWithUniqueLookupId(any());
    }

    @Test
    @DisplayName("a SEC- reference without device-security:read is 403 query_not_permitted, and is not resolved")
    void referenceNeedsItsSectionsPermission() {
        assertThatThrownBy(() -> search.search(agent(PermissionCatalog.SUPPORT_CONSOLE_READ), "SEC-8F2KQ7", null))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo("query_not_permitted"));
        verifyNoInteractions(deviceSupport);
    }

    @Test
    @DisplayName("a reference returns ONLY its owning section, plus focus — the console is never built")
    void referenceReturnsOnlyTheOwningSection() {
        when(deviceSupport.bySupportRef("SEC-8F2KQ7"))
                .thenReturn(new SupportRefLookup("SEC-8F2KQ7", "+263771234567", null, List.of()));
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));

        SearchResult r = search.search(both(), "sec 8f2kq7", null);

        assertThat(r.sections()).containsOnlyKeys("innbucksApp");
        assertThat(r.focus().reference()).isEqualTo("SEC-8F2KQ7");
        assertThat(r.focus().note()).isEqualTo("To see this customer's other products, search by their phone or email.");
        assertThat(r.notShown()).isEmpty();
        assertThat(r.customer().email()).as("built from the owning section alone").isNull();
        verify(console, never()).section(any(), any(), any(), any());
        SupportAccessLog row = requiredRow();
        assertThat(row.getSections()).isEqualTo("innbucksApp");
        assertThat(row.getQueryMasked()).isEqualTo("SEC-8F2KQ7");
        assertThat(SupportAccessLogWriter.keys(row.getCustomerKeys()).reference()).isEqualTo("SEC-8F2KQ7");
    }

    @Test
    @DisplayName("an unknown reference is a NOT_FOUND section, not an error")
    void unknownReference() {
        when(deviceSupport.bySupportRef("SEC-8F2KQ9")).thenThrow(new DeviceSecurityException(HttpStatus.NOT_FOUND,
                "support_ref_not_found", "No block."));
        SearchResult r = search.search(both(), "SEC-8F2KQ9", null);
        assertThat(r.sections().get("innbucksApp").status()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("a phone fans out to the VISIBLE sections only; the rest are listed in notShown and never built")
    void phoneFansOutToVisibleSectionsOnly() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));

        SearchResult r = search.search(agent(PermissionCatalog.SUPPORT_CONSOLE_READ), "0771234567", "41.221.147.12");

        assertThat(r.sections()).containsOnlyKeys("console");
        assertThat(r.notShown()).containsExactly("innbucksApp");
        verifyNoInteractions(innbucksApp, deviceSupport);
        assertThat(r.customer().phone()).isEqualTo("+263771234567");
        assertThat(r.customer().email()).isEqualTo("tariro@example.com");
        assertThat(r.lookupId()).matches("^SLK-[0-9A-Z]{6}$");

        SupportAccessLog row = requiredRow();
        assertThat(row.getLookupId()).isEqualTo(r.lookupId());
        assertThat(row.getOp()).isEqualTo("SEARCH");
        assertThat(row.getQueryKind()).isEqualTo("PHONE");
        assertThat(row.getQueryMasked()).isEqualTo("****4567");
        assertThat(row.getSections()).isEqualTo("console");
        assertThat(row.getClientIpUntrusted()).isEqualTo("41.221.147.12");
        SupportCustomerKeys keys = SupportAccessLogWriter.keys(row.getCustomerKeys());
        assertThat(keys.phones()).containsExactly("+263771234567");
        assertThat(keys.emails()).containsExactly("tariro@example.com");
        assertThat(keys.userIds()).containsExactly(1042L);
        assertThat(SupportAccessLogWriter.targets(row.getSectionTargets())).containsEntry("console", List.of("1042"));
    }

    @Test
    @DisplayName("an email reaches the account's phone for the InnBucks app section, matched by email")
    void emailReachesThePhone() {
        when(users.findAllByEmailIgnoreCase("tariro@example.com")).thenReturn(List.of(tariro));
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        SearchResult r = search.search(both(), "Tariro@Example.com", null);
        assertThat(r.sections()).containsOnlyKeys("console", "innbucksApp");
        verify(innbucksApp).section(eq(Map.of("+263771234567", Optional.of(tariro))), eq("email"));
        assertThat(r.identityWarnings()).isEmpty();
    }

    @Test
    @DisplayName("a phone and an email on different accounts raise an identity warning")
    void identityWarning() {
        User other = User.builder().id(2000L).userUuid(UUID.randomUUID()).email("tariro@example.com")
                .roles(new LinkedHashSet<>(List.of("CUSTOMER"))).build();
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        when(users.findAllByEmailIgnoreCase("tariro@example.com")).thenReturn(List.of(tariro, other));
        SearchResult r = search.search(both(), "+263771234567", null);
        assertThat(r.identityWarnings()).extracting("code").containsExactly("phone_email_different_accounts");
    }

    @Test
    @DisplayName("a staff match sets staffAccount and is stored on the lookup")
    void staffFlag() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(UUID.randomUUID()));
        SearchResult r = search.search(both(), "+263771234567", null);
        assertThat(r.staffAccount()).isTrue();
        assertThat(requiredRow().isStaffAccount()).isTrue();
    }

    @Test
    @DisplayName("a failing section is UNAVAILABLE; the other still renders (soft on availability)")
    void failingSectionIsUnavailable() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        doThrow(new IllegalStateException("db")).when(innbucksApp).section(any(), any());
        SearchResult r = search.search(both(), "+263771234567", null);
        assertThat(r.sections().get("innbucksApp").status()).isEqualTo("UNAVAILABLE");
        assertThat(r.sections().get("innbucksApp").data()).isNull();
        assertThat(r.sections().get("console").status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a lookup that cannot be recorded is not shown (fail-closed)")
    void logFailureRefuses() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        doThrow(SupportPolicyException.logUnavailable()).when(accessLog).requiredWithUniqueLookupId(any());
        assertThatThrownBy(() -> search.search(both(), "+263771234567", null))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode())
                        .isEqualTo("support_log_unavailable"));
    }

    @Test
    @DisplayName("support switched off: 404 before anything is counted or resolved")
    void disabled() {
        SupportProperties off = new SupportProperties();
        off.setEnabled(false);
        off.afterPropertiesSet();
        SupportSearchService offSearch = new SupportSearchService(new SupportQueryClassifier("ZW"),
                new SupportLookupLimiter(null, new SupportProperties.Limiter(), SupportMetrics.none(), () -> 0L),
                accessLog, staffTargets, SupportMetrics.none(), console, innbucksApp, deviceSupport, users,
                customerProfiles, off, Clock.systemUTC());
        assertThatThrownBy(() -> offSearch.search(both(), "+263771234567", null))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getStatus().value()).isEqualTo(404));
        verifyNoInteractions(users, accessLog);
    }

    // ---- staff accounts and what an agent may learn -------------------------------------------

    @Test
    @DisplayName("S1: a staff stub in the console section is never a TARGET — only full views are")
    void staffStubIsNeverATarget() {
        User colleague = User.builder().id(77L).userUuid(UUID.randomUUID()).email("tariro@example.com")
                .roles(new LinkedHashSet<>(List.of("CALL_CENTER_AGENT"))).build();
        when(users.findAllByEmailIgnoreCase("tariro@example.com")).thenReturn(List.of(tariro, colleague));
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(colleague.getUserUuid()));
        org.mockito.Mockito.doReturn(new SectionView<>("OK", "email", "s", "g",
                new ConsoleSectionData(List.of(ConsoleSupportSection.staffStub(), new ConsoleAccountView(1042L,
                        tariro.getUserUuid(), "Tariro Moyo", "tariro@example.com", "+263771234567", "ACTIVE",
                        List.of(), false, true, null, null, 0, null, false, null, List.of(), List.of(), "g",
                        List.of()))))).when(console).section(any(), any(), any(), any());

        SearchResult r = search.search(both(), "tariro@example.com", null);

        assertThat(r.staffAccount()).isTrue();
        assertThat(SupportAccessLogWriter.targets(requiredRow().getSectionTargets()))
                .containsEntry("console", List.of("1042"));
    }

    @Test
    @DisplayName("S1: an emailed STAFF account lends the app section nothing — its sign-in phone is not listed")
    void staffAccountsPhoneIsNotListedForTheAppSection() {
        User staff = User.builder().id(77L).userUuid(UUID.randomUUID()).email("rudo@innbucks.co.zw")
                .phoneNumber("+263772000009").roles(new LinkedHashSet<>(List.of("CALL_CENTER_AGENT"))).build();
        when(users.findAllByEmailIgnoreCase("rudo@innbucks.co.zw")).thenReturn(List.of(staff));
        when(staffTargets.isStaffAccount(staff)).thenReturn(true);
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(staff.getUserUuid()));
        org.mockito.Mockito.doReturn(new SectionView<>("OK", "email", "s", "g",
                new ConsoleSectionData(List.of(ConsoleSupportSection.staffStub()))))
                .when(console).section(any(), any(), any(), any());

        SearchResult r = search.search(both(), "rudo@innbucks.co.zw", null);

        verify(innbucksApp).section(eq(Map.of()), eq("email"));
        assertThat(r.customer().phone()).isNull();
        // Still recorded, so binding and the write-time staff check see it.
        assertThat(SupportAccessLogWriter.keys(requiredRow().getCustomerKeys()).phones()).contains("+263772000009");
    }

    @Test
    @DisplayName("T7: an agent with ONLY device-security:read learns nothing about console accounts — no warnings, no staff flag")
    void deviceOnlyAgentLearnsNothingAboutConsoleAccounts() {
        User other = User.builder().id(2000L).userUuid(UUID.randomUUID()).email("tariro@example.com")
                .roles(new LinkedHashSet<>(List.of("MERCHANT_ADMIN"))).build();
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        when(users.findAllByEmailIgnoreCase("tariro@example.com")).thenReturn(List.of(tariro, other));
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(UUID.randomUUID()));

        SearchResult r = search.search(agent(PermissionCatalog.DEVICE_SECURITY_READ), "+263771234567", null);

        assertThat(r.sections()).containsOnlyKeys("innbucksApp");
        assertThat(r.identityWarnings()).isEmpty();
        assertThat(r.staffAccount()).isFalse();
        assertThat(r.customer().email()).isNull();
        verify(console, never()).section(any(), any(), any(), any());
        verify(users, never()).findAllByEmailIgnoreCase(anyString());
        // The alert still records the match: only the RESPONSE follows the console section.
        assertThat(requiredRow().isStaffAccount()).isTrue();

        // The same search by a console reader shows both.
        SearchResult both = search.search(both(), "+263771234567", null);
        assertThat(both.staffAccount()).isTrue();
        assertThat(both.identityWarnings()).extracting("code").containsExactly("phone_email_different_accounts");
    }

    // ---- C4: reads that fail ------------------------------------------------------------------

    @Test
    @DisplayName("C4: when the query can't be resolved at all, the search is 503 support_search_unavailable — and logged")
    void resolutionFailureIs503WithALogRow() {
        when(users.findByPhoneNumber("+263771234567")).thenThrow(new org.springframework.dao
                .DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> search.search(both(), "0771234567", "41.221.147.12"))
                .satisfies(e -> {
                    assertThat(((SupportPolicyException) e).getErrorCode()).isEqualTo("support_search_unavailable");
                    assertThat(((SupportPolicyException) e).getStatus().value()).isEqualTo(503);
                });
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOp()).isEqualTo("SEARCH_FAILED");
        assertThat(row.getValue().getOutcome()).isEqualTo("support_search_unavailable");
        assertThat(row.getValue().getQueryKind()).isEqualTo("PHONE");
        assertThat(row.getValue().getQueryMasked()).isEqualTo("****4567");
        assertThat(row.getValue().getCustomerKeys()).isNull();
        verify(accessLog, never()).requiredWithUniqueLookupId(any());
        verifyNoInteractions(console, innbucksApp);
    }

    @Test
    @DisplayName("C4: a staff check that can't be read is 503 too — the flag drives an alert, it is never guessed")
    void staffCheckFailureIs503() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        when(staffTargets.staffAccounts(any())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> search.search(both(), "+263771234567", null))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode())
                        .isEqualTo("support_search_unavailable"));
        verify(accessLog, never()).requiredWithUniqueLookupId(any());
    }

    @Test
    @DisplayName("C4: the console-account filter failing renders the console section UNAVAILABLE, with no targets")
    void consoleFilterFailureIsUnavailable() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        when(console.isConsoleAccount(any())).thenThrow(new IllegalStateException("db"));
        SearchResult r = search.search(both(), "+263771234567", null);
        assertThat(r.sections().get("console").status()).isEqualTo("UNAVAILABLE");
        assertThat(r.sections().get("innbucksApp").status()).isEqualTo("OK");
        assertThat(SupportAccessLogWriter.targets(requiredRow().getSectionTargets())).containsEntry("console", List.of());
    }

    @Test
    @DisplayName("C4: identity warnings that can't be read say so; a profile name that can't be read falls back")
    void warningsAndCustomerDegrade() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        // resolve() and the staff check pass; the warnings' email probe fails.
        when(users.findAllByEmailIgnoreCase("tariro@example.com")).thenThrow(new IllegalStateException("db"));
        when(customerProfiles.findByUserId(1042L)).thenThrow(new IllegalStateException("db"));
        SearchResult r = search.search(both(), "+263771234567", null);
        assertThat(r.identityWarnings()).extracting("code").containsExactly("identity_check_unavailable");
        assertThat(r.customer().name()).isEqualTo("Tariro Moyo");
        requiredRow();
    }

    // ---- the lookup id --------------------------------------------------------------------------

    @Test
    @DisplayName("T7: a lookup id that collides is drawn again; three collisions refuse the lookup 503")
    void lookupIdCollisionRetryThen503() {
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(tariro));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("uk_support_access_log_lookup"))
                .doThrow(new org.springframework.dao.DataIntegrityViolationException("uk_support_access_log_lookup"))
                .doNothing()
                .when(accessLog).requiredWithUniqueLookupId(any());
        SearchResult ok = search.search(both(), "+263771234567", null);
        ArgumentCaptor<SupportAccessLog> rows = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog, org.mockito.Mockito.times(3)).requiredWithUniqueLookupId(rows.capture());
        assertThat(rows.getAllValues().get(2).getLookupId()).isEqualTo(ok.lookupId());

        org.mockito.Mockito.reset(accessLog);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("uk_support_access_log_lookup"))
                .when(accessLog).requiredWithUniqueLookupId(any());
        assertThatThrownBy(() -> search.search(both(), "+263771234567", null))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getErrorCode())
                        .isEqualTo("support_log_unavailable"));
        verify(accessLog, org.mockito.Mockito.times(3)).requiredWithUniqueLookupId(any());
    }
}
