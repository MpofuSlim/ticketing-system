package com.innbucks.userservice.service;

import com.innbucks.userservice.exception.PosApiKeyException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The addresses a POS API key may be used from: IPv4/IPv6 addresses and CIDR
 * ranges. Parsing never resolves a name: an entry that is not an address
 * literal is refused, so {@link InetAddress#getByName} only ever sees a
 * literal and can never make a DNS query.
 */
final class PosIpAllowList {

    static final int MAX_ENTRIES = 20;

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private record Range(byte[] network, int prefix) {}

    private final List<Range> ranges;

    private PosIpAllowList(List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * Normalise and validate operator input. Returns the entries as stored
     * (trimmed, lower-case, de-duplicated); an empty list means "any address".
     */
    static List<String> normalise(List<String> entries) {
        List<String> out = new ArrayList<>();
        if (entries == null) return out;
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.trim().toLowerCase(Locale.ROOT);
            parseEntry(e);
            if (!out.contains(e)) out.add(e);
        }
        if (out.size() > MAX_ENTRIES) {
            throw PosApiKeyException.badRequest("pos_key_bad_ip", "At most " + MAX_ENTRIES + " allowed addresses.");
        }
        return out;
    }

    /** The stored column ({@code a,b,c}) back into a matcher. Null or blank allows any address. */
    static PosIpAllowList fromColumn(String column) {
        List<Range> ranges = new ArrayList<>();
        if (column != null) {
            for (String e : column.split(",")) {
                if (!e.isBlank()) ranges.add(parseEntry(e.trim()));
            }
        }
        return new PosIpAllowList(ranges);
    }

    boolean allowsAny() {
        return ranges.isEmpty();
    }

    /** False for a null, unparseable or out-of-list address. */
    boolean allows(String clientIp) {
        if (ranges.isEmpty()) return true;
        byte[] addr = literal(clientIp);
        if (addr == null) return false;
        for (Range r : ranges) {
            if (r.network().length == addr.length && inRange(addr, r)) return true;
        }
        return false;
    }

    private static Range parseEntry(String entry) {
        String address = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            address = entry.substring(0, slash);
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw bad(entry);
            }
        }
        byte[] network = literal(address);
        if (network == null) throw bad(entry);
        int max = network.length * 8;
        int bits = prefix == null ? max : prefix;
        if (bits < 0 || bits > max) throw bad(entry);
        return new Range(network, bits);
    }

    /** The bytes of an IPv4/IPv6 literal, or null. Never resolves a name. */
    private static byte[] literal(String s) {
        if (s == null || s.isBlank() || s.length() > 45) return null;
        var m = IPV4.matcher(s);
        if (m.matches()) {
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(m.group(i + 1));
                if (v > 255) return null;
                b[i] = (byte) v;
            }
            return b;
        }
        if (s.indexOf(':') >= 0 && IPV6_CHARS.matcher(s).matches()) {
            try {
                // A string containing ':' is parsed as an IPv6 literal, never looked up.
                return InetAddress.getByName(s).getAddress();
            } catch (UnknownHostException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean inRange(byte[] addr, Range r) {
        int full = r.prefix() / 8;
        for (int i = 0; i < full; i++) {
            if (addr[i] != r.network()[i]) return false;
        }
        int rest = r.prefix() % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (addr[full] & mask) == (r.network()[full] & mask);
    }

    private static PosApiKeyException bad(String entry) {
        String shown = entry.length() > 50 ? entry.substring(0, 50) : entry;
        return PosApiKeyException.badRequest("pos_key_bad_ip", "Not an IP address or CIDR range: " + shown);
    }
}
