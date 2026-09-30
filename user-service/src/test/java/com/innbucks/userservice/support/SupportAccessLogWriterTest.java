package com.innbucks.userservice.support;

import com.innbucks.userservice.exception.SupportPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The access log's two strengths and their exception mapping (C5): a REQUIRED
 * row that cannot be written is 503 {@code support_log_unavailable} whatever
 * the cause — a constraint violation included — except for the search's
 * lookup-id draw, which alone gets the violation back to draw again; a
 * best-effort row never throws. Each row is its own REQUIRES_NEW transaction.
 */
class SupportAccessLogWriterTest {

    private SupportAccessLogRepository repository;
    private PlatformTransactionManager tm;
    private SupportAccessLogWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(SupportAccessLogRepository.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        writer = new SupportAccessLogWriter(repository, tm);
    }

    private static SupportAccessLog row() {
        return SupportAccessLog.builder().createdAt(LocalDateTime.now(ZoneOffset.UTC)).op("CONSOLE_DETAIL")
                .outcome("OK").agentSubject("agent.one@innbucks.co.zw").build();
    }

    private static String code(Throwable e) {
        return ((SupportPolicyException) e).getErrorCode();
    }

    @Test
    @DisplayName("required: a constraint violation is 503 support_log_unavailable — not rethrown to a caller that can't use it")
    void requiredMapsAConstraintViolationTo503() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("value too long"));
        assertThatThrownBy(() -> writer.required(row()))
                .isInstanceOf(SupportPolicyException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_log_unavailable"))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getStatus().value()).isEqualTo(503));
    }

    @Test
    @DisplayName("required: any other failure is 503 support_log_unavailable")
    void requiredMapsAnOutageTo503() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> writer.required(row()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_log_unavailable"));
    }

    @Test
    @DisplayName("requiredWithUniqueLookupId: a constraint violation comes back as itself (draw another id); an outage is 503")
    void lookupRowRethrowsTheViolationOnly() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_support_access_log_lookup"));
        assertThatThrownBy(() -> writer.requiredWithUniqueLookupId(row()))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down")).when(repository).saveAndFlush(any());
        assertThatThrownBy(() -> writer.requiredWithUniqueLookupId(row()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_log_unavailable"));
    }

    @Test
    @DisplayName("bestEffort never throws; every write runs in its own REQUIRES_NEW transaction")
    void bestEffortNeverThrows() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatCode(() -> writer.bestEffort(row())).doesNotThrowAnyException();
        verify(tm).getTransaction(argThat(d -> d != null
                && d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    @DisplayName("C5: the subject is bounded to the column and never null")
    void subjectIsBounded() {
        assertThat(SupportAccessLogWriter.subject("a".repeat(300))).hasSize(254);
        assertThat(SupportAccessLogWriter.subject("agent@innbucks.co.zw")).isEqualTo("agent@innbucks.co.zw");
        assertThat(SupportAccessLogWriter.subject(null)).isEqualTo("anonymous");
    }
}
