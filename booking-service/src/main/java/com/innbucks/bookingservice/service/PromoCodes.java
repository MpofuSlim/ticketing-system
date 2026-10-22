package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.PromoCode;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The promo-code rules (V27) that both the booking claim and the public check
 * apply, and their refusals. Every refusal is an {@link ApiProblemException}
 * whose {@code data.errorCode} the client branches on; the message is
 * customer-safe.
 */
final class PromoCodes {

    private PromoCodes() {
    }

    /** Letters, digits, '-' and '_', 3 to 40 of them; stored and matched in upper case. */
    static final Pattern CODE_SHAPE = Pattern.compile("[A-Z0-9_-]{3,40}");

    static final String INVALID = "PROMO_CODE_INVALID";
    static final String NOT_STARTED = "PROMO_CODE_NOT_STARTED";
    static final String EXPIRED = "PROMO_CODE_EXPIRED";
    static final String NOT_APPLICABLE = "PROMO_CODE_NOT_APPLICABLE";
    static final String TOO_LARGE = "PROMO_CODE_TOO_LARGE";
    static final String USED_UP = "PROMO_CODE_USED_UP";
    static final String LIMIT_REACHED = "PROMO_CODE_LIMIT_REACHED";

    /** Upper case, trimmed; null for none. Never throws: a malformed code simply matches nothing. */
    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** One answer for an unknown, inactive or other event's code: no list of codes can be probed. */
    static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, INVALID, "This promo code is not valid for this event.");
    }

    /** Refuse a code outside its window. */
    static void requireOpen(PromoCode code, LocalDateTime nowUtc) {
        if (code.getStartsAt() != null && nowUtc.isBefore(code.getStartsAt())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, NOT_STARTED, "This promo code can't be used yet.");
        }
        if (code.getEndsAt() != null && !nowUtc.isBefore(code.getEndsAt())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, EXPIRED, "This promo code has expired.");
        }
    }

    static ApiProblemException usedUp() {
        return new ApiProblemException(HttpStatus.CONFLICT, USED_UP, "This promo code has been used up.");
    }

    static ApiProblemException limitReached() {
        return new ApiProblemException(HttpStatus.CONFLICT, LIMIT_REACHED,
                "You've already used this promo code as many times as it allows.");
    }

    static ApiProblemException notApplicable() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, NOT_APPLICABLE,
                "This promo code doesn't apply to the tickets you chose.");
    }

    static ApiProblemException tooLarge() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, TOO_LARGE,
                "This promo code takes off more than a ticket costs.");
    }

    /** Whether the code discounts a ticket of this category: no listed categories means all of them. */
    static boolean appliesTo(PromoCode code, UUID categoryId) {
        return code.getCategoryIds() == null || code.getCategoryIds().isEmpty()
                || code.getCategoryIds().contains(categoryId);
    }

    /**
     * The discount on one ticket priced {@code price}: a percentage of it,
     * rounded half-up to the cent, or the fixed amount. Refused when it would
     * leave the ticket at 0.00 or less: payment rails cannot collect nothing,
     * and a free ticket is not something a code may create.
     */
    static BigDecimal discountOn(PromoCode code, BigDecimal price) {
        BigDecimal discount = code.getDiscountType() == PromoCode.DiscountType.PERCENT
                ? price.multiply(code.getDiscountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : code.getDiscountValue().setScale(2, RoundingMode.HALF_UP);
        if (discount.compareTo(price) >= 0) {
            throw tooLarge();
        }
        return discount;
    }
}
