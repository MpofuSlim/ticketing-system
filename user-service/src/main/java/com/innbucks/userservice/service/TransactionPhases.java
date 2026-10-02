package com.innbucks.userservice.service;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs one phase of a request in a transaction of its own, so a network call
 * that follows it (an SMS, a WhatsApp message, a loyalty webhook) runs with no
 * transaction, no pooled connection and no row lock held.
 *
 * <p>Why a template and not {@code @Transactional} + an after-commit callback:
 * a synchronous {@code afterCommit} still runs while the connection is bound to
 * the thread (Hibernate's {@code DELAYED_ACQUISITION_AND_HOLD}), so the remote
 * call would still hold a pooled connection. Only a call made after
 * {@link TransactionTemplate#execute} has RETURNED is free of it.
 *
 * <p>The services that use this are unit-constructed with {@code new} in many
 * tests and get their template through a setter; with none set, the phase runs
 * inline — exactly what those plain-Mockito tests exercised before.
 */
final class TransactionPhases {

    private TransactionPhases() {
    }

    static <T> T inTransaction(TransactionTemplate tx, Supplier<T> work) {
        return tx == null ? work.get() : tx.execute(status -> work.get());
    }

    static void runInTransaction(TransactionTemplate tx, Runnable work) {
        if (tx == null) {
            work.run();
        } else {
            tx.executeWithoutResult(status -> work.run());
        }
    }
}
