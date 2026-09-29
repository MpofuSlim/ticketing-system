package com.innbucks.userservice.service;

import com.innbucks.userservice.security.MfaTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MFA step's transaction rules, read the way Spring reads them (the same
 * {@link AnnotationTransactionAttributeSource} the transaction interceptor
 * consults), so a rule that looks right in the annotation but resolves wrongly
 * — two rules matching one exception, say — fails here and not in production.
 *
 * <p><b>Login step.</b> A wrong code, a lockout and a bad token must COMMIT: the
 * strike counter and the lockout stamp are the brute-force cap, and a refusal
 * that rolled them back would cap nothing. A LOST compare-and-set must ROLL
 * BACK: it is thrown after {@code verifyForLogin}, which may already have
 * consumed a backup code, and that code must not be burned by a refusal that
 * minted nothing.
 *
 * <p><b>Enrolment.</b> Everything rolls back — in particular a wrong code, which
 * is thrown by {@code MfaService.completeEnrollment} through its own proxy and
 * so marks the shared transaction rollback-only: a {@code noRollbackFor} for it
 * here would try to commit a rollback-only transaction and answer the user with
 * an {@code UnexpectedRollbackException} (a 500) instead of "that code didn't
 * match". Rolling back also un-spends the token, so the same one works for the
 * retry.
 */
class MfaStepRollbackRulesTest {

    private final AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

    private TransactionAttribute attributeOf(String name, Class<?>... params) throws Exception {
        Method method = AuthService.class.getMethod(name, params);
        TransactionAttribute attribute = source.getTransactionAttribute(method, AuthService.class);
        assertThat(attribute).as(name + " must be @Transactional").isNotNull();
        return attribute;
    }

    private TransactionAttribute loginStep() throws Exception {
        return attributeOf("completeLoginWithMfa",
                String.class, String.class, String.class, boolean.class, AuditContext.class);
    }

    private TransactionAttribute enrolment() throws Exception {
        return attributeOf("completeEnrollmentAndSignIn",
                String.class, String.class, String.class, AuditContext.class);
    }

    @Test
    void loginStep_aLostCompareAndSet_rollsBack_soNoBackupCodeIsBurned() throws Exception {
        assertThat(loginStep().rollbackOn(new MfaTokenService.MfaTokenSpentException())).isTrue();
    }

    @Test
    void loginStep_refusalsThatCarryTheBruteForceCap_commit() throws Exception {
        TransactionAttribute rules = loginStep();
        assertThat(rules.rollbackOn(new MfaService.MfaException("That code didn't match."))).isFalse();
        assertThat(rules.rollbackOn(new AuthService.AccountLockedException(Instant.now()))).isFalse();
        assertThat(rules.rollbackOn(new AuthService.InvalidCredentialsException())).isFalse();
        assertThat(rules.rollbackOn(
                new MfaTokenService.InvalidMfaTokenException("mfaToken is invalid or expired"))).isFalse();
    }

    @Test
    void enrolment_rollsBackOnEveryRefusal() throws Exception {
        TransactionAttribute rules = enrolment();
        assertThat(rules.rollbackOn(new MfaService.MfaException("That code didn't match."))).isTrue();
        assertThat(rules.rollbackOn(new MfaTokenService.MfaTokenSpentException())).isTrue();
        assertThat(rules.rollbackOn(new AuthService.AccountLockedException(Instant.now()))).isTrue();
        assertThat(rules.rollbackOn(
                new MfaTokenService.InvalidMfaTokenException("mfaToken is invalid or expired"))).isTrue();
    }
}
