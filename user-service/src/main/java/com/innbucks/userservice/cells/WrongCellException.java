package com.innbucks.userservice.cells;

/**
 * Thrown when a request lands on the wrong cell — its MSISDN or JWT
 * {@code homeCountry} claim resolves to a country other than this
 * deployment's {@code INNBUCKS_COUNTRY}. Mapped to {@code 409 wrong_cell}
 * by {@code GlobalExceptionHandler}, with the home cell's base URL when
 * the registry knows it (so the client can switch base URL and retry).
 */
public class WrongCellException extends RuntimeException {

    private final String homeCountry;
    private final String homeBaseUrl;

    public WrongCellException(String homeCountry, String homeBaseUrl) {
        super("Wrong cell — this request belongs to " + homeCountry);
        this.homeCountry = homeCountry;
        this.homeBaseUrl = homeBaseUrl;
    }

    public String getHomeCountry() {
        return homeCountry;
    }

    /** Public base URL of the home cell, or {@code null} if the registry doesn't know it yet. */
    public String getHomeBaseUrl() {
        return homeBaseUrl;
    }
}
