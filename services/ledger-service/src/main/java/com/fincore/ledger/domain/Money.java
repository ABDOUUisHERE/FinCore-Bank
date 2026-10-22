package com.fincore.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Exact, positive command money. Serialized as decimal text, never floating point. */
public record Money(BigDecimal amount, String currency) {
    public Money {
        if (amount == null || currency == null || !Set.of("MAD", "EUR", "USD").contains(currency)
            || amount.signum() <= 0 || amount.precision() - amount.scale() > 18) {
            throw new IllegalArgumentException("Invalid money");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    public static Money parse(String amount, String currency) {
        if (amount == null || !amount.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Use positive decimal text with at most two fractional digits");
        }
        return new Money(new BigDecimal(amount), currency);
    }
    public Money add(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("Currency mismatch");
        return new Money(amount.add(other.amount), currency);
    }
    public String decimal() { return amount.toPlainString(); }
}
