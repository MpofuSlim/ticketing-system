package com.innbucks.bookingservice.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The keys of the public ticket pages: one per booking (the buyer's page and
 * the checkout's status poll) and one per ticket (its holder's page and its
 * QR image). 128 random bits from {@link SecureRandom}, as 32 lower-case hex
 * characters.
 *
 * <p>A link token is a credential of its own, apart from the record ids. The
 * booking and item ids are on staff screens, scan reports, support tools,
 * logs and partner payloads; the tokens are only ever in what we send the
 * buyer and the holder, and in the create response the buyer polls with. So a
 * token is never on a staff view, a public view keyed by id, a log line or an
 * S2S payload. A page opened by id never embeds one either: if it did, anyone
 * holding an id could collect the token and keep the page after the id
 * routes close.
 *
 * <p>Stored as plain text (unique-indexed, V31). A token is no stronger a
 * credential than the ticket number it leads to, which is stored the same
 * way, and the page must be found by it.
 */
public final class LinkTokens {

    /** 32 lower-case hex characters: what {@link #newToken} mints and V31 backfilled. */
    public static final String PATTERN = "[0-9a-f]{32}";

    private static final Pattern WELL_FORMED = Pattern.compile(PATTERN);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private LinkTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public static boolean isWellFormed(String token) {
        return token != null && WELL_FORMED.matcher(token).matches();
    }
}
