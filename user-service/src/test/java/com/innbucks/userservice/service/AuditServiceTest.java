package com.innbucks.userservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.entity.AuditChainHead;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.repository.AuditChainHeadRepository;
import com.innbucks.userservice.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private AuditEventRepository repo;
    private AuditChainHeadRepository chainHeadRepo;
    private PlatformTransactionManager txManager;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        // save echoes the row back (the DB would assign an id); the dbFailure
        // test re-stubs this to throw.
        when(repo.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        chainHeadRepo = mock(AuditChainHeadRepository.class);
        // Head starts at genesis (null chain_hmac), like the migration-seeded row.
        when(chainHeadRepo.lockHead()).thenReturn(Optional.of(new AuditChainHead(1, null, null)));
        // Stub a transaction manager that just runs the callback —
        // we don't need real transaction semantics for these tests.
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AuditService(repo, chainHeadRepo, new ObjectMapper(), txManager, "test-audit-hmac-secret");
    }

    @Test
    void record_writesRow_withAllSuppliedFields() {
        AuditContext ctx = new AuditContext("203.0.113.7", "Mozilla/5.0");

        service.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                Map.of("tokenVersion", 8L), ctx);

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        AuditEvent row = saved.getValue();
        assertEquals("AUTH_LOGIN_SUCCESS", row.getEventType());
        assertEquals("SUCCESS", row.getOutcome());
        assertEquals("42", row.getActorId());
        assertEquals("USER", row.getActorType());
        assertEquals("42", row.getTargetId());
        assertEquals("USER", row.getTargetType());
        assertEquals("203.0.113.7", row.getIpAddress());
        assertEquals("Mozilla/5.0", row.getUserAgent());
        assertNull(row.getFailureReason());
        assertNotNull(row.getOccurredAt());
        assertTrue(row.getMetadata().contains("tokenVersion"));
        assertTrue(row.getMetadata().contains("8"));
    }

    @Test
    void record_failureCarriesReason() {
        service.recordFailure(
                AuditEventType.AUTH_LOGIN_FAILURE,
                null, AuditService.ACTOR_TYPE_ANONYMOUS,
                "alice@example.com", AuditService.TARGET_TYPE_USER,
                "unknown_identifier", null, AuditContext.none());

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        AuditEvent row = saved.getValue();
        assertEquals("FAILURE", row.getOutcome());
        assertEquals("unknown_identifier", row.getFailureReason());
        assertNull(row.getActorId());
        assertEquals("ANONYMOUS", row.getActorType());
    }

    @Test
    void record_dbFailureIsSwallowed_doesNotPropagate() {
        // The audit path must not break the caller's flow. A broken
        // table, a deadlock, a transient connection error — all of
        // them should be logged and absorbed.
        when(repo.save(any(AuditEvent.class)))
                .thenThrow(new DataIntegrityViolationException("simulated DB outage"));

        assertDoesNotThrow(() -> service.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                null, AuditContext.none()));
    }

    @Test
    void record_metadataSerialisationFailureDoesNotDropRow() {
        // A metadata Map that Jackson can't serialise must not dump
        // the whole row — the security signal lands, just with a
        // marker payload pointing at the broken serialisation.
        Map<String, Object> badMap = new java.util.HashMap<>();
        badMap.put("circular", badMap);  // self-reference → StackOverflow / Jackson error

        service.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                badMap, AuditContext.none());

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        assertTrue(saved.getValue().getMetadata().contains("metadata-serialisation-failed"));
    }

    @Test
    void record_truncatesUserAgentLongerThan512() {
        // Defence against a malicious caller sending a giant UA to
        // poison the table. The column is VARCHAR(512); we truncate
        // in the service so a row never refuses to insert.
        String hugeUa = "x".repeat(2000);
        AuditContext ctx = new AuditContext("127.0.0.1", hugeUa);

        service.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                null, ctx);

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        assertEquals(512, saved.getValue().getUserAgent().length());
    }

    @Test
    void record_truncatesFailureReasonLongerThan255() {
        String huge = "x".repeat(2000);
        service.recordFailure(
                AuditEventType.AUTH_LOGIN_FAILURE,
                null, AuditService.ACTOR_TYPE_ANONYMOUS,
                "42", AuditService.TARGET_TYPE_USER,
                huge, null, AuditContext.none());

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        assertEquals(255, saved.getValue().getFailureReason().length());
    }

    @Test
    void record_nullContext_isHandledAsNoNetworkInfo() {
        // Background jobs / tests that don't have an HTTP request
        // can still emit audit rows — null/AuditContext.none() lands
        // null in the network columns.
        service.recordSuccess(
                AuditEventType.AUTH_LOGOUT,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                null, null);

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        assertNull(saved.getValue().getIpAddress());
        assertNull(saved.getValue().getUserAgent());
    }

    @Test
    void record_sealsAndChainsRow_thenAdvancesHead() {
        when(repo.save(any(AuditEvent.class))).thenAnswer(inv -> {
            AuditEvent e = inv.getArgument(0);
            e.setId(77L);                            // DB assigns the id on save
            return e;
        });

        service.recordSuccess(
                AuditEventType.AUTH_LOGIN_SUCCESS,
                "42", AuditService.ACTOR_TYPE_USER,
                "42", AuditService.TARGET_TYPE_USER,
                null, AuditContext.none());

        ArgumentCaptor<AuditEvent> savedRow = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(savedRow.capture());
        AuditEvent row = savedRow.getValue();
        assertNotNull(row.getRowHmac(), "row must be content-sealed");
        assertNotNull(row.getChainHmac(), "row must be chained to its predecessor");
        // Genesis link: prev chain is null (fresh head).
        assertEquals(service.computeChainHmac(null, row.getRowHmac()), row.getChainHmac(),
                "chain_hmac must be HMAC(prevChain, rowHmac) with the seeded genesis predecessor");

        // The head advances to this row's chain tag + id for the next write.
        ArgumentCaptor<AuditChainHead> savedHead = ArgumentCaptor.forClass(AuditChainHead.class);
        verify(chainHeadRepo).save(savedHead.capture());
        assertEquals(row.getChainHmac(), savedHead.getValue().getChainHmac());
        assertEquals(77L, savedHead.getValue().getLastEventId());
    }

    @Test
    void computeChainHmac_bindsPredecessor_soDeletingARowIsDetectable() {
        String rowHmac = "a".repeat(64);
        String genesis = service.computeChainHmac(null, rowHmac);

        assertEquals(genesis, service.computeChainHmac(null, rowHmac), "same inputs -> same tag");
        // A different predecessor MUST change the link — that's what makes a
        // deleted/reordered row break the chain at the next surviving row.
        assertNotEquals(genesis, service.computeChainHmac("deadbeef".repeat(8), rowHmac),
                "a different predecessor chain must yield a different link");
    }
}
