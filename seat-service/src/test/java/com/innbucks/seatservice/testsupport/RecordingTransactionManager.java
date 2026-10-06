package com.innbucks.seatservice.testsupport;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A transaction manager with no database behind it, for plain-Mockito tests
 * that need to know WHERE a call ran relative to a transaction.
 *
 * <p>It is a real {@link AbstractPlatformTransactionManager}, so inside a
 * {@code TransactionTemplate} callback
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} is true
 * and outside it is false — exactly what a mock's answer can assert on. It joins
 * an enclosing transaction for REQUIRED (like a real one) and records each
 * top-level outcome, so a test can also pin "this phase committed" / "that one
 * rolled back".
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    public enum Outcome { COMMITTED, ROLLED_BACK }

    private final List<Outcome> outcomes = new ArrayList<>();
    private final List<Boolean> readOnly = new ArrayList<>();

    private static final class Tx {
        final boolean existing = TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    protected Object doGetTransaction() {
        return new Tx();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Tx) transaction).existing;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        readOnly.add(definition.isReadOnly());
    }

    @Override
    protected Object doSuspend(Object transaction) {
        return new Object();
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        outcomes.add(Outcome.COMMITTED);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        outcomes.add(Outcome.ROLLED_BACK);
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
    }

    /** Top-level transactions, in the order they finished. */
    public List<Outcome> outcomes() {
        return outcomes;
    }

    /** Whether each top-level transaction was begun read-only, in begin order. */
    public List<Boolean> readOnlyFlags() {
        return readOnly;
    }

    /** True when the calling code is inside a transaction right now. */
    public static boolean inTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
