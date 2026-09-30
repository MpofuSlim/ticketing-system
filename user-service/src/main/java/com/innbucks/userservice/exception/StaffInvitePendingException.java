package com.innbucks.userservice.exception;

/**
 * A session was about to be minted, refreshed or continued for a staff account
 * whose invite has not been redeemed (V44) — a profile with
 * {@code invite_accepted_at IS NULL}.
 *
 * <p>An INVITED account cannot sign in: that is what makes adopting a legacy
 * account lock a squatter out at once, and what keeps a freshly created account
 * inert until the mailbox is proven. Thrown by the password step, the refresh
 * rotation and the mint chokepoint ({@code AuthService.buildResponse}), and
 * rendered 401 {@code staff_invite_pending}. The message is a typed constant —
 * safe to pass through.
 */
public class StaffInvitePendingException extends RuntimeException {

    public static final String ERROR_CODE = "staff_invite_pending";
    public static final String MESSAGE =
            "This account hasn't been set up yet. Use the invite link we emailed you to set a password.";

    public StaffInvitePendingException() {
        super(MESSAGE);
    }
}
