package com.innbucks.eventservice.service;

import java.util.Locale;

/**
 * Normalization + derivation of the per-event settlement code — the short
 * {@code [A-Z0-9]{3,12}} tag payment-service embeds in every InnBucks
 * code-generation reference ({@code TKZ-<CODE>-<shortId>}) so the merchant
 * bank statement can be grouped and settled per event.
 *
 * <p>Codes are stored UPPERCASE so statement filtering is case-stable. When
 * the organizer doesn't supply one at creation, a code is derived from the
 * event title (alphanumerics only, first 12); titles too exotic to yield 3
 * usable characters fall back to {@code null} — payment-service then tags
 * references with a short event-id fragment instead, so payments never block
 * on a missing code.
 */
final class SettlementCodes {

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 12;

    private SettlementCodes() {
    }

    /** Uppercases an already pattern-validated organizer-supplied code. */
    static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Creation-path resolution: an explicitly supplied code wins (normalized);
     * otherwise derive one from the title.
     */
    static String normalizeOrDerive(String code, String title) {
        if (code != null && !code.isBlank()) {
            return normalize(code);
        }
        return deriveFromTitle(title);
    }

    /**
     * Derives a code by keeping the title's letters/digits, uppercased, capped
     * at {@value #MAX_LENGTH}. Returns null when fewer than {@value #MIN_LENGTH}
     * usable characters remain (e.g. an all-punctuation or non-Latin title) —
     * better no code than a garbage one.
     */
    static String deriveFromTitle(String title) {
        if (title == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(MAX_LENGTH);
        for (int i = 0; i < title.length() && sb.length() < MAX_LENGTH; i++) {
            char c = title.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() >= MIN_LENGTH ? sb.toString() : null;
    }
}
