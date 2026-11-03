package com.innbucks.bookingservice.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate's typed-number fallback: what a person types from the buyer's
 * WhatsApp message reaches the scan as the number that was issued, and a
 * camera scan or a value that is not a ticket reaches it unchanged.
 */
class TypedTicketNumberTest {

    @Test
    void aTypedCurrentNumber_inAnyCaseWithOrWithoutDashesOrSpaces_isReadAsIssued() {
        assertThat(TicketScanController.issuedForm("20261030ch2f05v7cb")).isEqualTo("20261030-CH2F0-5V7CB");
        assertThat(TicketScanController.issuedForm("20261030 ch2f0 5v7cb")).isEqualTo("20261030-CH2F0-5V7CB");
        assertThat(TicketScanController.issuedForm("20261030-ch2f0-5v7cb")).isEqualTo("20261030-CH2F0-5V7CB");
    }

    @Test
    void aTypedLegacyNumber_isReadAsIssued() {
        assertThat(TicketScanController.issuedForm("2026102890100n")).isEqualTo("20261028-90100N");
    }

    @Test
    void aScannedValue_passesThroughUnchanged() {
        assertThat(TicketScanController.issuedForm("20261030-CH2F0-5V7CB")).isEqualTo("20261030-CH2F0-5V7CB");
        assertThat(TicketScanController.issuedForm("20261028-90100N")).isEqualTo("20261028-90100N");
    }

    @Test
    void noLetterIsSwapped_soALookalikeIsNotATicket_andStaysAsSent() {
        // O, I, L and U are not in the alphabet: typing O for 0 finds nothing,
        // it never finds a different ticket.
        assertThat(TicketScanController.issuedForm("20261030-CH2FO-5V7CB")).isEqualTo("20261030-CH2FO-5V7CB");
    }

    @Test
    void somethingThatIsNotATicket_isLookedUpExactlyAsSent() {
        assertThat(TicketScanController.issuedForm("BOGUS-12345")).isEqualTo("BOGUS-12345");
        assertThat(TicketScanController.issuedForm("4111 1111 1111 1111")).isEqualTo("4111 1111 1111 1111");
        assertThat(TicketScanController.issuedForm(null)).isNull();
    }
}
