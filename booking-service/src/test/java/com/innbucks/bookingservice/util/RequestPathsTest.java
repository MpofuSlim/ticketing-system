package com.innbucks.bookingservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A logged request path carries neither a full ticket number nor a full phone number. */
class RequestPathsTest {

    private static final String BOOKING = "1f0e9c3a-7b2d-4e5f-9a1b-2c3d4e5f6a7b";

    @Test
    void theHostedQrPath_masksItsTicketNumber() {
        assertThat(RequestPaths.forLog("/bookings/" + BOOKING + "/tickets/20261013-7KQ4M-9XZ2B/qr.png"))
                .isEqualTo("/bookings/" + BOOKING + "/tickets/20261013-*****-**Z2B/qr.png");
    }

    @Test
    void theAttendeeEditPath_masksALegacyTicketNumber() {
        assertThat(RequestPaths.forLog("/bookings/" + BOOKING + "/tickets/20260419-48291X/attendee"))
                .isEqualTo("/bookings/" + BOOKING + "/tickets/20260419-****1X/attendee");
    }

    @Test
    void thePhoneLookups_maskTheNumberToItsLastFour() {
        assertThat(RequestPaths.forLog("/bookings/phone/+263771234567")).isEqualTo("/bookings/phone/****4567");
        assertThat(RequestPaths.forLog("/bookings/public/phone/%2B263771234567?filter=UPCOMING"))
                .isEqualTo("/bookings/public/phone/****4567?filter=UPCOMING");
    }

    @Test
    void theLinkTokenPaths_hideTheTokenWhole_brandAliasIncluded() {
        String token = "0123456789abcdef0123456789abcdef";
        assertThat(RequestPaths.forLog("/bookings/view/" + token)).isEqualTo("/bookings/view/****");
        assertThat(RequestPaths.forLog("/bookings/view/" + token + "/tickets")).isEqualTo("/bookings/view/****/tickets");
        assertThat(RequestPaths.forLog("/bookings/ticket/" + token)).isEqualTo("/bookings/ticket/****");
        assertThat(RequestPaths.forLog("/brand/bookings/ticket/" + token + "/qr.png"))
                .isEqualTo("/brand/bookings/ticket/****/qr.png");
        // A near-miss a caller sent is hidden too: it may be a real token with
        // one character wrong.
        assertThat(RequestPaths.forLog("/bookings/ticket/0123456789ABCDEF?x=1")).isEqualTo("/bookings/ticket/****?x=1");
        // The holder page by item id is a different literal and stays as it was.
        assertThat(RequestPaths.forLog("/bookings/tickets/" + BOOKING)).isEqualTo("/bookings/tickets/" + BOOKING);
    }

    @Test
    void otherPaths_areUnchanged() {
        String path = "/bookings/" + BOOKING + "/resend-ticket";
        assertThat(RequestPaths.forLog(path)).isEqualTo(path);
        assertThat(RequestPaths.forLog("/bookings/confirmation/INN-20261013-ABC123"))
                .isEqualTo("/bookings/confirmation/INN-20261013-ABC123");
        assertThat(RequestPaths.forLog(null)).isNull();
        assertThat(RequestPaths.forLog("")).isEmpty();
    }
}
