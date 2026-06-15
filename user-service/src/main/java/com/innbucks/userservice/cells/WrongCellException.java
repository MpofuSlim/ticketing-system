package com.innbucks.userservice.cells;

import com.innbucks.userservice.util.MsisdnCountryResolver;

/**
 * Thrown when a request lands on the wrong cell — its MSISDN or JWT
 * {@code homeCountry} claim resolves to a country other than this
 * deployment's {@code INNBUCKS_COUNTRY}. Mapped to {@code 409 wrong_cell}
 * by {@code GlobalExceptionHandler}, with the home cell's base URL when
 * the registry knows it (so the client can switch base URL and retry).
 *
 * <p>The message is user-facing and avoids the "wrong cell" internal
 * jargon — the FE keys off the stable {@code errorCode:"wrong_cell"} in
 * the response body, not the human text. Phrasing varies on whether we
 * know where to send them.
 */
public class WrongCellException extends RuntimeException {

    private final String homeCountry;
    private final String homeBaseUrl;

    public WrongCellException(String homeCountry, String homeBaseUrl) {
        super(friendlyMessage(homeCountry, homeBaseUrl));
        this.homeCountry = homeCountry;
        this.homeBaseUrl = homeBaseUrl;
    }

    private static String friendlyMessage(String iso, String homeBaseUrl) {
        String name = MsisdnCountryResolver.countryName(iso);
        if (homeBaseUrl != null && !homeBaseUrl.isBlank()) {
            return "Your InnBucks account is in " + name
                    + " — please continue on the " + name + " app to proceed.";
        }
        return "InnBucks isn't available in " + name
                + " yet — we'll let you know when we launch there.";
    }

    public String getHomeCountry() {
        return homeCountry;
    }

    /** Public base URL of the home cell, or {@code null} if the registry doesn't know it yet. */
    public String getHomeBaseUrl() {
        return homeBaseUrl;
    }
}
