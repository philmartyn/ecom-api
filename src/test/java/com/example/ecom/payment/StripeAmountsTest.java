package com.example.ecom.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The boundary between the Order's scale-2 money and Stripe's integer minor
 * units.
 * <p>
 * Orders always carry scale 2, so the conversion must be exact: no floating
 * point, no rounding surprise, and no silent truncation. Stripe also wants the
 * currency in lower case, while the domain keeps ISO-4217 upper case.
 */
class StripeAmountsTest {

    @Test
    void convertsAmountsToMinorUnits() {
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("179.98"))).isEqualTo(17_998L);
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("0.00"))).isZero();
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("1234567.89")))
                .isEqualTo(123_456_789L);
    }

    @Test
    void acceptsAmountsWrittenWithFewerOrMoreDecimals() {
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("19"))).isEqualTo(1_900L);
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("19.9"))).isEqualTo(1_990L);
        assertThat(StripeAmounts.toMinorUnits(new BigDecimal("19.990"))).isEqualTo(1_999L);
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> StripeAmounts.toMinorUnits(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lowercasesTheCurrency() {
        assertThat(StripeAmounts.toStripeCurrency("USD")).isEqualTo("usd");
        assertThat(StripeAmounts.toStripeCurrency("EUR")).isEqualTo("eur");
        assertThat(StripeAmounts.toStripeCurrency("usd")).isEqualTo("usd");
    }

    @Test
    void rejectsACurrencyThatIsNotThreeLetters() {
        for (String currency : new String[] { null, "", "US", "USDD", "U1D" }) {
            assertThatThrownBy(() -> StripeAmounts.toStripeCurrency(currency))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
