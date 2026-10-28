package com.innbucks.userservice.support;

import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.support.SupportQueryClassifier.Kind;
import com.innbucks.userservice.support.SupportQueryClassifier.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search box's classifier (design §3.1.1), row by row — including every
 * refusal and the T15 collisions: {@code SupportRefs.normalise} prepending
 * {@code SEC-} to anything, MKT/VCH sharing a body shape, card-shaped digits
 * that look like a phone, and 12-character codes.
 */
class SupportQueryClassifierTest {

    private final SupportQueryClassifier classifier = new SupportQueryClassifier("ZW");

    private Query c(String raw) {
        return classifier.classify(raw);
    }

    @Test
    @DisplayName("row 1: anything with an @ is an email, lower-cased and stripped")
    void email() {
        assertThat(c("  Tariro@Example.COM ")).isEqualTo(new Query(Kind.EMAIL, "tariro@example.com"));
        // Even an odd one: the @ decides, and the lookup simply finds nothing.
        assertThat(c("not really@")).extracting(Query::kind).isEqualTo(Kind.EMAIL);
    }

    @ParameterizedTest
    @CsvSource({
            "SEC-8F2KQ7, SEC-8F2KQ7",
            "sec-8f2kq7, SEC-8F2KQ7",
            "SEC8F2KQ7, SEC-8F2KQ7",
            "sec 8f2 kq7, SEC-8F2KQ7",
            // Crockford forgiveness happens AFTER the prefix is proven.
            "SEC-8F2KQO, SEC-8F2KQ0",
            "SEC-1L2I3O, SEC-112130"
    })
    @DisplayName("row 2: a SEC- reference, normalised only once the prefix is proven")
    void dtxReference(String typed, String expected) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.DTX_REFERENCE, expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8F2KQ7", "XYZ-8F2KQ7", "SE-8F2KQ7", "SEC-8F2KQ", "SEC-8F2KQ77", "SEC--8F2KQ7"})
    @DisplayName("T15: SupportRefs.normalise would make these SEC- references; the classifier never does")
    void notAReferenceWithoutThePrefix(String typed) {
        assertThat(c(typed).kind()).isNotEqualTo(Kind.DTX_REFERENCE);
    }

    @Test
    @DisplayName("rows 3 and 5: MKT- and VCH- share a body shape; only the prefix decides")
    void mktAndVchAreDistinctByPrefix() {
        assertThat(c("MKT-0A1B2C3D4E5F")).isEqualTo(new Query(Kind.MARKETPLACE_ORDER, "MKT-0A1B2C3D4E5F"));
        assertThat(c("mkt0a1b2c3d4e5f")).isEqualTo(new Query(Kind.MARKETPLACE_ORDER, "MKT-0A1B2C3D4E5F"));
        assertThat(c("VCH-0A1B2C3D4E5F")).isEqualTo(new Query(Kind.VOUCHER_ORDER, "VCH-0A1B2C3D4E5F"));
        assertThat(c("vch0a1b2c3d4e5f")).isEqualTo(new Query(Kind.VOUCHER_ORDER, "VCH-0A1B2C3D4E5F"));
        // The bare body is neither — and, being a 12-character code with a letter, refused.
        assertThat(c("0A1B2C3D4E5F").kind()).isEqualTo(Kind.NOT_ACCEPTED);
    }

    @Test
    @DisplayName("row 4: a parcel needs its TRK prefix")
    void parcel() {
        assertThat(c("TRK-7K2M9Q4X8Z")).isEqualTo(new Query(Kind.PARCEL, "TRK-7K2M9Q4X8Z"));
        assertThat(c("trk7k2m9q4x8z")).isEqualTo(new Query(Kind.PARCEL, "TRK-7K2M9Q4X8Z"));
        assertThat(c("7K2M9Q4X8Z").kind()).isNotEqualTo(Kind.PARCEL);
    }

    @Test
    @DisplayName("rows 6-8: booking confirmation, ticket number, payment references")
    void ticketingAndPaymentReferences() {
        assertThat(c("INN-20260930-A1B2C3")).isEqualTo(new Query(Kind.BOOKING_CONFIRMATION, "INN-20260930-A1B2C3"));
        assertThat(c("20260930-00012K")).isEqualTo(new Query(Kind.TICKET_NUMBER, "20260930-00012K"));
        assertThat(c("20261013-7KQ4M-9XZ2B")).isEqualTo(new Query(Kind.TICKET_NUMBER, "20261013-7KQ4M-9XZ2B"));
        assertThat(c("TKZ-MKT-0A1B2C3D4E5F").kind()).isEqualTo(Kind.PAYMENT_REFERENCE);
        assertThat(c("TKZ-BOOKING-0A1B2C3D4E5F").kind()).isEqualTo(Kind.PAYMENT_REFERENCE);
        assertThat(c("TKT-PMT-9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40").kind()).isEqualTo(Kind.PAYMENT_REFERENCE);
        assertThat(SupportQueryClassifier.isMarketplacePaymentRef("TKZ-MKT-0A1B2C3D4E5F")).isTrue();
        assertThat(SupportQueryClassifier.isMarketplacePaymentRef("TKZ-BOOKING-0A1B2C3D4E5F")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20261013-7KQ4M-9XZ2B",
            "20261013-7kq4m-9xz2b",            // lower case
            "  20261013-7KQ4M-9XZ2B  ",        // pasted with spaces around
            "20261013 7KQ4M 9XZ2B",            // read out in groups
            "202610137KQ4M9XZ2B",              // no dashes
            "20261013\u20137KQ4M\u20139XZ2B",   // en dashes from a document
            "20261013\u00A07kq4m\u00A09xz2b"    // no-break spaces from a chat
    })
    @DisplayName("row 7: a current ticket number, however it was typed, is a TICKET_NUMBER in its issued form")
    void currentTicketNumbers(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.TICKET_NUMBER, "20261013-7KQ4M-9XZ2B"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260930-00012K", "20260930-00012k", " 20260930-00012K ", "2026093000012K"})
    @DisplayName("row 7: a legacy ticket number is still a TICKET_NUMBER")
    void legacyTicketNumbers(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.TICKET_NUMBER, "20260930-00012K"));
    }

    @ParameterizedTest
    @CsvSource({
            "20260930-00012I, 20260930-00012I",
            "20260930-00012l, 20260930-00012L",
            "2026093000012O,  20260930-00012O",
            "20260930 00012u, 20260930-00012U"
    })
    @DisplayName("row 7: a legacy ticket ending in I, L, O or U is still a TICKET_NUMBER — its letter is never Crockford-aliased")
    void legacyTicketNumbersEndingInNonCrockfordLetters(String typed, String issued) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.TICKET_NUMBER, issued));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111 1111 1111 1111 AB",
            "5500 0000 0000 0004 CV",
            "41111111111111111X",
            "4111111111111X",
            "4111111111111111ab"
    })
    @DisplayName("row 7: a card number with letters typed after it is not a ticket — its digits are not an issue date")
    void cardNumbersWithLettersAfterThemAreNotTickets(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.NOT_RECOGNISED, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20261013-12345-67890", "201610131234567890", "2026 1013 1234 5678 90"})
    @DisplayName("row 7: the ticket shape without a letter is never issued — 18 digits stay row 9's card refusal")
    void allDigitTicketShapesAreStillRefusedAsCards(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.NOT_ACCEPTED, null));
    }

    @Test
    @DisplayName("row 7: I, L, O and U are never in a current ticket number, and none is read as another symbol")
    void ticketShapesWithNonCrockfordLettersAreNotTickets() {
        for (String typed : new String[] {"20261013-7KQ4M-9XZ2O", "20261013-7KQ4I-9XZ2B",
                "20261013-7KQ4L-9XZ2B", "20261013-7KQ4U-9XZ2B"}) {
            assertThat(c(typed).kind()).as(typed).isEqualTo(Kind.NOT_RECOGNISED);
        }
    }

    @Test
    @DisplayName("rows 3-8 name sections this build does not have, so none is searchable yet")
    void unbuiltReferenceKindsAreNotSupported() {
        for (Kind k : new Kind[] {Kind.MARKETPLACE_ORDER, Kind.PARCEL, Kind.VOUCHER_ORDER,
                Kind.BOOKING_CONFIRMATION, Kind.TICKET_NUMBER, Kind.PAYMENT_REFERENCE}) {
            assertThat(k.supported()).as("%s", k).isFalse();
            assertThat(k.isReference()).as("%s", k).isTrue();
        }
        assertThat(Kind.DTX_REFERENCE.supported()).isTrue();
        assertThat(Kind.DTX_REFERENCE.permission()).isEqualTo("device-security:read");
        assertThat(Kind.PHONE.permission()).isNull();
        assertThat(Kind.EMAIL.permission()).isNull();
    }

    @Test
    @DisplayName("every reference permission is a catalog code (Ticketize's pending one aside), so none is "
            + "silently wrong until its section ships")
    void referencePermissionsAreRealCodes() {
        // An unbuilt kind is refused query_not_supported BEFORE its permission
        // is read, so a misspelt code hides until the section is switched on,
        // and then refuses every agent. They read "support-marketplace:read"
        // (and so on) until this test existed; the catalog says
        // "marketplace-support:read".
        assertThat(Kind.MARKETPLACE_ORDER.permission()).isEqualTo(PermissionCatalog.MARKETPLACE_SUPPORT_READ);
        assertThat(Kind.PARCEL.permission()).isEqualTo(PermissionCatalog.MARKETPLACE_SUPPORT_READ);
        assertThat(Kind.VOUCHER_ORDER.permission()).isEqualTo(PermissionCatalog.LOYALTY_SUPPORT_READ);
        assertThat(Kind.BOOKING_CONFIRMATION.permission()).isEqualTo("ticketing-support:read");
        assertThat(Kind.TICKET_NUMBER.permission()).isEqualTo("ticketing-support:read");
        for (Kind k : Kind.values()) {
            String code = k.permission();
            if (code == null || code.equals(SupportQueryClassifier.TICKETING_SUPPORT_READ)) continue;
            assertThat(PermissionCatalog.isKnown(code)).as("%s needs %s", k, code).isTrue();
        }
        // The pending code is not in the catalog yet. When the Ticketize section
        // adds it, this fails: move the constant into PermissionCatalog then.
        assertThat(PermissionCatalog.isKnown(SupportQueryClassifier.TICKETING_SUPPORT_READ)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111111111111111",          // a card number
            "4111 1111 1111 1111",
            "4111-1111-1111-1111",
            "9087 8765 9876 4566",       // a 16-digit voucher code, as the customer reads it
            "9087876598764566",
            "3782822463100051234",       // 19 digits
            "(0771) 234-567-890-12"      // brackets and dashes are removed before the digit test
    })
    @DisplayName("row 9: 13-19 digits are a card or voucher number — refused, whatever else they resemble")
    void cardAndVoucherDigitsAreRefused(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.NOT_ACCEPTED, null));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "00263771234567, +263771234567",       // 14 digits: the international prefix, not a card
            "00263 77 123 4567, +263771234567",
            "0027712345678, +27712345678",         // 13 digits (South Africa)
            "00-254-712-345678, +254712345678"
    })
    @DisplayName("C3: a leading 00 is the international prefix — rewritten to + BEFORE the card rule, so it's a phone")
    void internationalPrefixIsAPhone(String typed, String e164) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.PHONE, e164));
    }

    @Test
    @DisplayName("C3: the card refusal tells the agent to type a phone number with its country code")
    void cardRefusalSaysHowToTypeAPhone() {
        assertThat(com.innbucks.userservice.exception.SupportPolicyException.QUERY_NOT_ACCEPTED_MESSAGE)
                .contains("+263771234567").contains("country code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB12CD34EF56", "ab12-cd34-ef56", "ABCDEFGHJKMN", "A00000000000"})
    @DisplayName("row 9: a 12-character code with a letter (legacy voucher / collection code) is refused")
    void twelveCharacterCodesAreRefused(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.NOT_ACCEPTED, null));
    }

    @ParameterizedTest
    @CsvSource({
            "+263771234567, +263771234567",
            "0771234567, +263771234567",
            "077 123 4567, +263771234567",
            "263771234567, +263771234567",
            "+263 77 123 4567, +263771234567"
    })
    @DisplayName("row 10: a phone in any common spelling, to E.164")
    void phone(String typed, String expected) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.PHONE, expected));
    }

    @Test
    @DisplayName("12 digits are a phone, not a code: the code shape needs a letter")
    void twelveDigitsAreNotACode() {
        assertThat(c("263771234567").kind()).isEqualTo(Kind.PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tariro Moyo", "hello", "   ", "12345", "SEC-", "INN-2026"})
    @DisplayName("row 11: anything else is not recognised")
    void notRecognised(String typed) {
        assertThat(c(typed)).isEqualTo(new Query(Kind.NOT_RECOGNISED, null));
    }

    @Test
    @DisplayName("a refusal carries no value: nothing typed survives classification to be logged")
    void refusalsCarryNoText() {
        assertThat(c("4111111111111111").value()).isNull();
        assertThat(c("AB12CD34EF56").value()).isNull();
        assertThat(c("Tariro Moyo").value()).isNull();
        assertThat(SupportMasking.query(c("4111111111111111"))).isNull();
        assertThat(SupportMasking.query(c("Tariro Moyo"))).isNull();
    }

    @Test
    @DisplayName("null and blank are not recognised")
    void nullAndBlank() {
        assertThat(c(null).kind()).isEqualTo(Kind.NOT_RECOGNISED);
        assertThat(c("").kind()).isEqualTo(Kind.NOT_RECOGNISED);
    }
}
