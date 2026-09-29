package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.service.TokenVersionBumper;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The shared-Redis publish of a {@code token_version} bump happens AFTER the
 * transaction commits, against real Postgres transactions.
 *
 * <p>{@code setRoles} used to publish inside its transaction: when it then
 * rolled back, Redis held a version Postgres did not, and every downstream
 * service rejected tokens user-service still accepted. After commit, a
 * rolled-back bump publishes nothing.
 */
class TokenVersionPublishedAfterCommitIT extends SessionRevocationItSupport {

    @MockitoSpyBean TokenVersionPublisher publisher;
    @Autowired TokenVersionBumper bumper;

    @Test
    void aRolledBackBumpPublishesNothing_andLeavesTheVersionUntouched() {
        User staff = staff("PRODUCT_OFFICER", false);
        long before = liveTokenVersion(staff.getId());

        tx().executeWithoutResult(status -> {
            User managed = users.findById(staff.getId()).orElseThrow();
            bumper.bump(managed);
            status.setRollbackOnly();
        });

        verify(publisher, never()).publish(eq(staff.getUserUuid()), anyLong());
        assertThat(liveTokenVersion(staff.getId())).isEqualTo(before);
    }

    @Test
    void aCommittedBumpPublishesOnlyOnceItHasCommitted() {
        User staff = staff("PRODUCT_OFFICER", false);
        long before = liveTokenVersion(staff.getId());

        tx().executeWithoutResult(status -> {
            User managed = users.findById(staff.getId()).orElseThrow();
            long next = bumper.bump(managed);
            assertThat(next).isEqualTo(before + 1);
            assertThat(managed.getTokenVersion()).isEqualTo(before + 1);
            // Still inside the transaction: nothing published yet.
            verify(publisher, never()).publish(eq(staff.getUserUuid()), anyLong());
        });

        verify(publisher).publish(staff.getUserUuid(), before + 1);
        assertThat(liveTokenVersion(staff.getId())).isEqualTo(before + 1);
    }

    @Test
    void aBumpOutsideAnyTransaction_isRefused() {
        // MANDATORY: a bump is always part of a larger change and must commit
        // or roll back with it.
        User staff = staff("PRODUCT_OFFICER", false);

        assertThatThrownBy(() -> bumper.bump(staff)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
