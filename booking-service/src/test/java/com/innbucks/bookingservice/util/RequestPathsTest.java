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
    void otherPaths_areUnchanged() {
        String path = "/bookings/" + BOOKING + "/resend-ticket";
        assertThat(RequestPaths.forLog(path)).isEqualTo(path);
        assertThat(RequestPaths.forLog("/bookings/confirmation/INN-20261013-ABC123"))
                .isEqualTo("/bookings/confirmation/INN-20261013-ABC123");
        assertThat(RequestPaths.forLog(null)).isNull();
        assertThat(RequestPaths.forLog("")).isEmpty();
    }
}
