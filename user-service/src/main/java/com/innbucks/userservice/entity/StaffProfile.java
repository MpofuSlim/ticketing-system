package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The staff half of an account (V44): present on every account created through
 * {@code POST /admin/staff} and every legacy staff account adopted through
 * {@code /resend-invite}.
 *
 * <p>A profiled account must satisfy three invariants, enforced at every writer
 * and again at the token mint: it holds only staff roles, it belongs to no ACTIVE
 * organization, and — once its invite is accepted — it has no sign-in phone
 * ({@code users.phone_number IS NULL}). The phone the person gave us is
 * {@link #contactPhone}, a contact number only, never an identifier anything
 * signs in or resets a password with.
 *
 * <p>{@link #inviteAcceptedAt} NULL means INVITED: the account cannot sign in
 * (the mint refuses it) and forgot-password is a no-op for it, whatever the
 * identifier, until the person redeems the invite and so proves the mailbox.
 */
@Entity
@Table(name = "staff_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffProfile {

    /** The account's {@code users.id} — one profile per account. */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /** E.164 contact number; display only. */
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    /** True when a legacy staff account was adopted rather than created here. */
    @Column(name = "adopted", nullable = false)
    private boolean adopted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the invite was redeemed; NULL while the account is INVITED. */
    @Column(name = "invite_accepted_at")
    private LocalDateTime inviteAcceptedAt;

    public boolean isInvitePending() {
        return inviteAcceptedAt == null;
    }
}
