package com.innbucks.bookingservice.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Runs one phase of a request in a transaction of its own, so a remote call
 * made between phases (user-service, event-service) runs with no transaction,
 * no pooled connection and no row lock held.
 *
 * <p>Why a template and not {@code @Transactional} on the whole method: a
 * method-level transaction holds its connection from the first read to the
 * commit, so every Feign call made in between holds it too — for as long as
 * the other service takes to answer, or its timeout. Only a call made after
 * {@link TransactionTemplate#execute} has RETURNED is free of it.
 *
 * <p>The services that use this are built with {@code new} in many unit tests
 * and get their templates through a setter; with none set, the phase runs
 * inline — exactly what those plain-Mockito tests exercised before. Same shape
 * as user-service's {@code TransactionPhases}.
 */
final class TransactionPhases {

    private TransactionPhases() {
    }

    static TransactionTemplate readWrite(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
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
