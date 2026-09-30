package com.innbucks.userservice.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer support reset a Foundry console account's second factor — tell every
 * OWNER of the businesses that account belongs to (design §3.2). The account
 * itself hears through the ordinary {@link AccountSecurityAlertEvent}.
 *
 * <p>Why the owners: the MFA reset is the classic help-desk takeover (a stolen
 * password plus one convincing phone call), and an organization's OWNERs control
 * its payouts, issuance and refunds. The person whose 2FA was reset may be the
 * attacker's victim and unreachable; the owners are the ones who can act.
 *
 * @param ownerEmails distinct, lower-cased, never including the account's own address
 * @param at          UTC, when the reset committed
 */
public record SupportMfaResetNotice(Long userId, String accountName, String accountEmail,
                                    List<String> organizationNames, List<String> ownerEmails, LocalDateTime at) {
}
