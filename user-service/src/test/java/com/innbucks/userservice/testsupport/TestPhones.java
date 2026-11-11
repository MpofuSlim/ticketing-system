package com.innbucks.userservice.testsupport;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fresh, valid Zimbabwe mobile numbers for tests: each call in a JVM returns a
 * number no earlier call returned.
 *
 * <p>Why not a random draw: every Postgres IT shares ONE container per JVM
 * ({@link PostgresIntegrationTestBase}) and nothing is deleted between classes,
 * so the {@code users} rows of the whole run accumulate under
 * {@code uk_users_phone_country}. Five helpers each drew {@code +26377} plus
 * seven random digits (about ten million values); with a few hundred customers
 * per run, two draws meeting is a birthday problem, and a Release run lost it
 * ({@code RoleGrantRaceIT}: duplicate {@code +263778178802}). A counter cannot
 * repeat.
 *
 * <p>The numbers are {@code +263778} plus six digits: no fixed literal in these
 * tests uses the 778 block, so a generated number never meets a hard-coded one
 * either. The counter starts at a random point so that a container reused
 * across runs (Testcontainers {@code withReuse}) is unlikely to see the same
 * block twice; it wraps after a million numbers, far past any run.
 */
public final class TestPhones {

    private static final String PREFIX = "+263778";
    private static final int BLOCK = 1_000_000;
    private static final AtomicInteger NEXT =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(BLOCK));

    private TestPhones() {
    }

    /** A number not handed out before in this JVM, e.g. {@code +263778042917}. */
    public static String unique() {
        int n = Math.floorMod(NEXT.getAndIncrement(), BLOCK);
        return PREFIX + String.format("%06d", n);
    }
}
