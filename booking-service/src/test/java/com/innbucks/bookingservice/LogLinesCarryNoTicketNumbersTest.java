package com.innbucks.bookingservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No log line in booking-service carries a full ticket number.
 *
 * <p>A ticket number is the QR payload the gate admits on, so a log line holding
 * one is a working entry credential. Logs are read by more people, kept longer
 * and copied further than the database. booking-service used to log full
 * numbers on almost every scan and delivery path. Its access log, JWT filter
 * and idempotency filter also logged request paths, and the hosted QR
 * ({@code /bookings/{id}/tickets/{ticketNumber}/qr.png}, fetched by Twilio for
 * every WhatsApp ticket) carries the number in the path.
 *
 * <p>This test reads every {@code log.trace|debug|info|warn|error(...)} call in
 * {@code src/main}, however many lines it spans. It fails if an argument mentions
 * a ticket number, a QR path, a raw request URI or an upstream's raw error body
 * without passing it through {@code TicketNumbers.forLog},
 * {@code TicketNumbers.redactIn} or {@code RequestPaths.forLog}. The text inside
 * string literals is ignored, so {@code "ticket={}"} in a format string is fine.
 */
class LogLinesCarryNoTicketNumbersTest {

    private static final Path MAIN = Paths.get("src", "main", "java");

    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\s*\\(");

    /** What an argument must not carry raw. */
    private static final Pattern RISKY = Pattern.compile(
            "(?i)ticketnumber|\\btn\\b|qrcodepath|getRequestURI\\s*\\(|getResponseBodyAsString\\s*\\(");

    /** The ways to make it safe. */
    private static final List<String> SAFE_WRAPPERS = List.of(
            "TicketNumbers.forLog(", "TicketNumbers.redactIn(", "RequestPaths.forLog(");

    @Test
    void noLogCallPassesARawTicketNumberPathOrUpstreamBody() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("run from the booking-service module directory (surefire's default)")
                .isTrue();
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (LogCall call : logCalls(source)) {
                    calls++;
                    for (String arg : call.args()) {
                        if (isUnsafe(arg)) {
                            offenders.add(MAIN.relativize(file) + ":" + call.line() + "  " + arg.strip());
                        }
                    }
                }
            }
        }
        assertThat(calls).as("the parser found the module's log calls").isGreaterThan(100);
        assertThat(offenders)
                .as("mask ticket numbers with TicketNumbers.forLog, free text with TicketNumbers.redactIn, "
                        + "and request paths with RequestPaths.forLog")
                .isEmpty();
    }

    @Test
    void theDetector_catchesTheShapesThatLeaked_andPassesTheMaskedOnes() {
        // Guards the guard: the shapes below are exactly the ones that leaked.
        assertThat(offendersIn("log.info(\"scan ticketNumber={}\", ticketNumber);")).hasSize(1);
        assertThat(offendersIn("log.info(\"sent {}\",\n    booking.getId(), item.getTicketNumber());")).hasSize(1);
        assertThat(offendersIn("log.warn(\"failed {} {}\", booking.getId(), tn, ex.getMessage());")).hasSize(1);
        assertThat(offendersIn("log.info(\"sent qrCodePath={}\", MsisdnMasking.mask(to), qrCodePath);")).hasSize(1);
        assertThat(offendersIn("log.warn(\"path={}\", request.getRequestURI());")).hasSize(1);
        assertThat(offendersIn("log.warn(\"body={}\", ex.getResponseBodyAsString());")).hasSize(1);
        assertThat(offendersIn("log.info(\"t=\" + ticketNumber);")).hasSize(1);

        assertThat(offendersIn("log.info(\"scan ticketNumber={}\", TicketNumbers.forLog(ticketNumber));")).isEmpty();
        assertThat(offendersIn("log.warn(\"path={}\", RequestPaths.forLog(request.getRequestURI()));")).isEmpty();
        assertThat(offendersIn("log.warn(\"body={}\", TicketNumbers.redactIn(ex.getResponseBodyAsString()));"))
                .isEmpty();
        assertThat(offendersIn("log.info(\"ticket={} (\\\"x\\\", y)\", item.getId());")).isEmpty();
    }

    private static List<String> offendersIn(String source) {
        List<String> out = new ArrayList<>();
        for (LogCall call : logCalls(source)) {
            for (String arg : call.args()) {
                if (isUnsafe(arg)) out.add(arg.strip());
            }
        }
        return out;
    }

    private static boolean isUnsafe(String arg) {
        String code = withoutStringLiterals(arg).strip();
        if (!RISKY.matcher(code).find()) {
            return false;
        }
        return SAFE_WRAPPERS.stream().noneMatch(code::startsWith);
    }

    private record LogCall(int line, List<String> args) {
    }

    /** Every log call in {@code source}, its arguments split at top-level commas. */
    private static List<LogCall> logCalls(String source) {
        List<LogCall> calls = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(source);
        while (m.find()) {
            if (isInComment(source, m.start())) continue;
            List<String> args = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 1;
            int i = m.end();
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (c == '"' || c == '\'') {
                    int end = endOfLiteral(source, i);
                    current.append(source, i, end);
                    i = end;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') depth++;
                if (c == ')' || c == ']' || c == '}') depth--;
                if (depth == 0) break;
                if (c == ',' && depth == 1) {
                    args.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
                i++;
            }
            args.add(current.toString());
            int line = (int) source.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
            calls.add(new LogCall(line, args));
            m.region(Math.min(i, source.length()), source.length());
        }
        return calls;
    }

    /** Index just past the string or char literal that opens at {@code start}. */
    private static int endOfLiteral(String s, int start) {
        char quote = s.charAt(start);
        if (quote == '"' && s.startsWith("\"\"\"", start)) {
            int end = s.indexOf("\"\"\"", start + 3);
            return end < 0 ? s.length() : end + 3;
        }
        int i = start + 1;
        while (i < s.length() && s.charAt(i) != quote) {
            if (s.charAt(i) == '\\') i++;
            i++;
        }
        return Math.min(i + 1, s.length());
    }

    private static String withoutStringLiterals(String arg) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < arg.length()) {
            char c = arg.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(arg, i);
                out.append("\"\"");
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isInComment(String source, int index) {
        int lineStart = source.lastIndexOf('\n', index) + 1;
        String before = source.substring(lineStart, index).strip();
        return before.startsWith("//") || before.startsWith("*") || before.startsWith("/*");
    }
}
