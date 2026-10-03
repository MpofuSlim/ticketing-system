package com.innbucks.userservice.testsupport;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every SQL statement Hibernate prepares ON THE THREAD THAT STARTED THE
 * RECORDING — registered for the {@code it} profile only
 * ({@code hibernate.session_factory.statement_inspector}).
 *
 * <p>Per-thread on purpose: Hibernate's {@code Statistics} counters are
 * SessionFactory-wide, so an after-commit send or an {@code @Async} listener
 * still running from an earlier request lands in them and makes a pinned count
 * flaky. MockMvc dispatches on the test thread, so the statements a request
 * issues are exactly the ones recorded here.
 */
public class SqlRecorder implements StatementInspector {

    private static final ThreadLocal<List<String>> RECORDING = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> sink = RECORDING.get();
        if (sink != null) sink.add(sql);
        return sql;
    }

    /** Starts recording on the current thread, discarding anything recorded before. */
    public static void start() {
        RECORDING.set(new ArrayList<>());
    }

    /** Stops recording on the current thread and returns what it recorded. */
    public static List<String> stop() {
        List<String> recorded = RECORDING.get();
        RECORDING.remove();
        return recorded == null ? List.of() : recorded;
    }
}
