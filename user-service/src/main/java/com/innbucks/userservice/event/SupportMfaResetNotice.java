package com.innbucks.userservice.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer support reset a Foundry console account's second factor — tell every
 * OTHER OWNER of the businesses that account belongs to (design §3.2). The
 * account itself hears through the ordinary {@link AccountSecurityAlertEvent}.
 *
 * <p>Why the owners: the MFA reset is the classic help-desk takeover (a stolen
 * password plus one convincing phone call), and an organization's OWNERs control
 * its payouts, issuance and refunds. The person whose 2FA was reset may be the
 * attacker's victim and unreachable; the owners are the ones who can act.
 *
 * <p>Each owner is told about the businesses THEY own and nothing else: the
 * account may belong to several businesses with different owners, and one
 * owner must neither learn the others' businesses nor be told they own them.
 *
 * @param owners one entry per owner address, never the account's own
 * @param at     UTC, when the reset committed
 */
public record SupportMfaResetNotice(Long userId, String accountName, String accountEmail,
                                    List<OwnerNotice> owners, LocalDateTime at) {

    public SupportMfaResetNotice {
        owners = owners == null ? List.of() : List.copyOf(owners);
    }

    /**
     * @param email             lower-cased
     * @param organizationNames the account's businesses THIS owner owns, sorted; may be empty (no name on file)
     */
    public record OwnerNotice(String email, List<String> organizationNames) {
        public OwnerNotice {
            organizationNames = organizationNames == null ? List.of() : List.copyOf(organizationNames);
        }
    }
}
