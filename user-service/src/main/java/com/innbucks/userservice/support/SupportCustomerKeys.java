package com.innbucks.userservice.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A lookup's RESOLVED customer keys — the searched key plus every identifier
 * user-service finds for the same account(s). Every later detail read or write
 * is bound to these: its target must be one of them (design §3.1.5, T27), and
 * the support assertion carries them as {@code ck} so a product can refuse a
 * target outside them.
 *
 * <p>Held in full (not masked): binding compares them, and user-service already
 * stores every value. They are written to {@code support_access_log} and never
 * logged.
 *
 * <p>Canonical form, so two lookups of the same person compare equal and the
 * assertion is stable: phones E.164, emails lower-cased with {@link Locale#ROOT},
 * every set sorted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupportCustomerKeys(
        List<String> phones,
        List<String> emails,
        List<String> userUuids,
        List<Long> userIds,
        String reference) {

    public SupportCustomerKeys {
        phones = sorted(phones);
        emails = sorted(emails == null ? null : emails.stream().map(e -> e.toLowerCase(Locale.ROOT)).toList());
        userUuids = sorted(userUuids);
        userIds = userIds == null ? List.of() : List.copyOf(new TreeSet<>(userIds));
    }

    public static SupportCustomerKeys empty() {
        return new SupportCustomerKeys(List.of(), List.of(), List.of(), List.of(), null);
    }

    public boolean hasPhone(String phone) {
        return phone != null && phones.contains(phone);
    }

    public boolean hasEmail(String email) {
        return email != null && emails.contains(email.toLowerCase(Locale.ROOT));
    }

    public boolean hasUserUuid(UUID uuid) {
        return uuid != null && userUuids.contains(uuid.toString());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return phones.isEmpty() && emails.isEmpty() && userUuids.isEmpty() && userIds.isEmpty() && reference == null;
    }

    private static List<String> sorted(Collection<String> values) {
        if (values == null) return List.of();
        Set<String> out = new TreeSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v);
        }
        return List.copyOf(out);
    }
}
