package com.example.ecom.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

public class StripeAmounts {

    private StripeAmounts() {}

    public static long toMinorUnits(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }

        return amount.movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static String toStripeCurrency(String currency) {

        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException(
                    "Currency must be a three letter ISO-4217 code");
        }

        return currency.toLowerCase(Locale.ROOT);
    }
}
