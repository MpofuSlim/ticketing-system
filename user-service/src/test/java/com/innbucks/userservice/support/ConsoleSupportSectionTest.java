package com.innbucks.userservice.support;

import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.devicesecurity.DeviceSecurityMessages;
import com.innbucks.userservice.devicesecurity.DeviceSecurityProperties;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import com.innbucks.userservice.support.dto.SupportDTOs.StaffAccountStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The console section's two shapes (S1) and its offered actions (S2/C8): a
 * staff account (SUPER_ADMIN included) is a stub carrying nothing about it; a
 * full view offers only the actions the server would accept.
 */
class ConsoleSupportSectionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 10, 0);

    private SupportStaffTargets staffTargets;
    private ConsoleSupportSection section;
    private User merchant;

    @BeforeEach
    void setUp() {
        staffTargets = mock(SupportStaffTargets.class);
        section = new ConsoleSupportSection(mock(OrganizationMemberRepository.class),
                mock(OrganizationRepository.class), mock(OrganizationProductRepository.class),
                mock(ServiceRequestRepository.class), staffTargets,
                new DeviceSecurityMessages(new MarketTimeZone("ZW"), new DeviceSecurityProperties()));
        merchant = User.builder().id(1042L).userUuid(UUID.randomUUID()).firstName("Tariro").lastName("Moyo")
                .email("tariro@example.com").phoneNumber("+263771234567")
                .roles(new LinkedHashSet<>(List.of("MERCHANT_ADMIN"))).active(true).approved(true)
                .failedLoginAttempts(5).lockedUntil(Instant.parse("2026-09-30T10:20:00Z"))
                .mfaEnabled(true).mfaSecret("secret").build();
    }

    private static SupportAgent supervisor() {
        return new SupportAgent("sup@innbucks.co.zw", 7L, UUID.randomUUID(), "sup@innbucks.co.zw", null, null,
                Set.of(PermissionCatalog.SUPPORT_CONSOLE_READ, PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                        PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET));
    }

    @Test
    @DisplayName("S1: a staff account is a stub — staffAccount, guidance, no actions; nothing that describes it")
    void staffAccountIsAStub() throws Exception {
        User staff = User.builder().id(55L).userUuid(UUID.randomUUID()).firstName("Rudo").lastName("Chikwanha")
                .email("rudo@innbucks.co.zw").phoneNumber("+263772000009")
                .roles(new LinkedHashSet<>(List.of("SUPER_ADMIN"))).active(true).approved(true)
                .failedLoginAttempts(2).mfaEnabled(true).mfaSecret("s").build();
        when(staffTargets.isStaffAccount(staff)).thenReturn(true);

        SectionView<ConsoleSectionData> view = section.section(List.of(staff, merchant), "email", supervisor(), NOW);

        assertThat(view.data().accounts()).hasSize(2);
        // Sorted by id: the staff account (55) first, the merchant (1042) second.
        assertThat(view.data().accounts().get(0)).isInstanceOf(StaffAccountStub.class);
        assertThat(view.data().accounts().get(1)).isInstanceOf(ConsoleAccountView.class);
        StaffAccountStub stub = (StaffAccountStub) view.data().accounts().get(0);
        assertThat(stub.staffAccount()).isTrue();
        assertThat(stub.actions()).isEmpty();
        assertThat(stub.agentGuidance()).contains("ask a SUPER_ADMIN");
        assertThat(ConsoleSupportSection.targets(view)).containsExactly("1042");
        assertThat(view.summary()).contains("an InnBucks staff account").doesNotContain("Rudo").doesNotContain("SUPER_ADMIN");

        String json = new ObjectMapper().writeValueAsString(stub);
        assertThat(json).doesNotContain("55").doesNotContain("rudo").doesNotContain("+263772000009")
                .doesNotContain("roles").doesNotContain("mfa").doesNotContain("locked").doesNotContain("SignIn")
                .doesNotContain("userId").doesNotContain("email").doesNotContain("phone");
    }

    @Test
    @DisplayName("a merchant's full view offers unlock, send-password-reset and mfa/reset to a supervisor")
    void activeAccountOffersEverythingThatApplies() {
        ConsoleAccountView v = section.view(merchant, supervisor(), NOW);
        assertThat(v.actions()).containsExactly("unlock", "send-password-reset", "mfa/reset");
        assertThat(v.staffAccount()).isFalse();
    }

    @Test
    @DisplayName("S2/C8: a deactivated or pending account is offered NO action — execute() would refuse every one")
    void inactiveOrPendingOffersNothing() {
        merchant.setActive(false);
        assertThat(section.view(merchant, supervisor(), NOW).actions()).isEmpty();
        merchant.setActive(true);
        merchant.setApproved(false);
        // Active but not approved: send-password-reset used to be offered here and then refused.
        assertThat(section.view(merchant, supervisor(), NOW).actions()).isEmpty();
    }
}
