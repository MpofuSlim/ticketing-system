package com.innbucks.bookingservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodsTest {

    @Test
    void eachRailPaymentServiceSends_hasAMethodAndWords() {
        assertThat(PaymentMethods.code("INNBUCKS_CODE")).isEqualTo("INNBUCKS");
        assertThat(PaymentMethods.label("INNBUCKS_CODE")).isEqualTo("InnBucks");
        assertThat(PaymentMethods.code("ECOCASH")).isEqualTo("ECOCASH");
        assertThat(PaymentMethods.label("ECOCASH")).isEqualTo("EcoCash");
        assertThat(PaymentMethods.code("ZIMSWITCH_CARD")).isEqualTo("CARD");
        // Not "Visa/Mastercard": ZimSwitch takes ZimSwitch-enabled cards only on our channel.
        assertThat(PaymentMethods.label("ZIMSWITCH_CARD")).isEqualTo("Bank card (ZimSwitch)");
    }

    @Test
    void aRailAddedLater_isShownByItsOwnName_neverHidden() {
        assertThat(PaymentMethods.code("ONEMONEY")).isEqualTo("ONEMONEY");
        assertThat(PaymentMethods.label("ONEMONEY")).isEqualTo("ONEMONEY");
    }

    @Test
    void noRail_isNoMethod() {
        assertThat(PaymentMethods.code(null)).isNull();
        assertThat(PaymentMethods.label(null)).isNull();
    }

    @Test
    void cleanRail_keepsAPlainUpperCaseName_only() {
        assertThat(PaymentMethods.cleanRail(" ECOCASH ")).isEqualTo("ECOCASH");
        assertThat(PaymentMethods.cleanRail("ZIMSWITCH_CARD")).isEqualTo("ZIMSWITCH_CARD");
        assertThat(PaymentMethods.cleanRail("ecocash")).isNull();
        assertThat(PaymentMethods.cleanRail("ECO CASH")).isNull();
        assertThat(PaymentMethods.cleanRail("")).isNull();
        assertThat(PaymentMethods.cleanRail(null)).isNull();
        assertThat(PaymentMethods.cleanRail("A".repeat(33))).isNull();
    }
}
