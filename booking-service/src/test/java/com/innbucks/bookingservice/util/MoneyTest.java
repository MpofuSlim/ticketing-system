package com.innbucks.bookingservice.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void usDollarsReadWithTheDollarSign() {
        assertThat(Money.display(new BigDecimal("0.40"), "USD")).isEqualTo("$0.40");
        assertThat(Money.display(new BigDecimal("150.00"), " usd ")).isEqualTo("$150.00");
        assertThat(Money.display(new BigDecimal("-5.00"), "USD")).isEqualTo("-$5.00");
    }

    @Test
    void anyOtherCurrencyReadsWithItsCode_neverADollarSign() {
        assertThat(Money.display(new BigDecimal("12.50"), "ZWG")).isEqualTo("ZWG 12.50");
        assertThat(Money.display(new BigDecimal("1000"), "kes")).isEqualTo("KES 1000");
    }

    @Test
    void noCurrencyLeavesTheAmountBare_andNoAmountIsEmpty() {
        assertThat(Money.display(new BigDecimal("0.40"), null)).isEqualTo("0.40");
        assertThat(Money.display(new BigDecimal("0.40"), " ")).isEqualTo("0.40");
        assertThat(Money.display(null, "USD")).isEmpty();
    }

    @Test
    void theAmountIsNeverRewritten() {
        assertThat(Money.display(new BigDecimal("1E+3"), "USD")).isEqualTo("$1000");
    }
}
