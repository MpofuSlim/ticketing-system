package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.OrderType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every enum the ledger stores as a string must match its CHECK constraint
 * exactly, as the LATEST migration defines it.
 *
 * <p>Why a text-level test: {@code LOYALTY_VOUCHER} shipped in {@link OrderType}
 * with no migration widening {@code chk_payment_order_type}, so Postgres refused
 * every voucher payment row on all three rails. The Postgres ITs never built a
 * voucher row, and the unit tests mock the repository, so nothing failed until
 * a real payment was attempted on staging. This runs without Docker, so it
 * fails on a laptop the moment an enum value is added without its migration.
 *
 * <p>Equality, not containment: a value the CHECK allows but the enum no longer
 * has is harmless to writes but is a stale vocabulary someone should decide
 * about, so it fails too.
 */
class LedgerVocabularyMigrationTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Test
    void orderTypeEnum_matchesChkPaymentOrderType() throws Exception {
        assertEquals(names(OrderType.values()), allowedBy("chk_payment_order_type"),
                "OrderType and chk_payment_order_type disagree — add a migration that "
                        + "re-creates the constraint with every OrderType value");
    }

    @Test
    void paymentRailEnum_matchesChkPaymentRail() throws Exception {
        assertEquals(names(PaymentRail.values()), allowedBy("chk_payment_rail"),
                "PaymentRail and chk_payment_rail disagree — add a migration that "
                        + "re-creates the constraint with every PaymentRail value");
    }

    @Test
    void paymentStatusEnum_matchesChkPaymentStatus() throws Exception {
        assertEquals(names(PaymentStatus.values()), allowedBy("chk_payment_status"),
                "PaymentStatus and chk_payment_status disagree — add a migration that "
                        + "re-creates the constraint with every PaymentStatus value");
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The value list of the named CHECK, from the highest-versioned migration that (re)defines it. */
    private static Set<String> allowedBy(String constraint) throws IOException, URISyntaxException {
        Pattern definition = Pattern.compile(
                "CONSTRAINT\\s+" + constraint + "\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        String latest = null;
        for (Path migration : migrationsInVersionOrder()) {
            Matcher m = definition.matcher(Files.readString(migration));
            while (m.find()) {
                latest = m.group(1);
            }
        }
        assertNotNull(latest, "no migration defines " + constraint);
        Set<String> allowed = new LinkedHashSet<>();
        Matcher q = QUOTED.matcher(latest);
        while (q.find()) {
            allowed.add(q.group(1));
        }
        return allowed;
    }

    private static List<Path> migrationsInVersionOrder() throws IOException, URISyntaxException {
        URL dir = LedgerVocabularyMigrationTest.class.getClassLoader().getResource("db/migration");
        assertNotNull(dir, "db/migration not on the test classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(LedgerVocabularyMigrationTest::version))
                    .toList();
        }
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
