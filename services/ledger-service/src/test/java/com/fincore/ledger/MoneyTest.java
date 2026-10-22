package com.fincore.ledger;

import com.fincore.ledger.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {
    @Test void normalizesWithoutFloatingPointOrRounding() {
        assertThat(Money.parse("100", "MAD")).isEqualTo(Money.parse("100.00", "MAD"));
        assertThat(Money.parse("0.10", "EUR").add(Money.parse("0.20", "EUR")).decimal()).isEqualTo("0.30");
        assertThat(Money.parse("999999999999999999.99", "USD").decimal()).isEqualTo("999999999999999999.99");
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.001", "1e2", "NaN", "01.00", " 1", "1000000000000000000", ""})
    void rejectsInvalidAmounts(String value) { assertThatThrownBy(() -> Money.parse(value, "MAD")).isInstanceOf(IllegalArgumentException.class); }
    @Test void rejectsUnsupportedAndMixedCurrenciesAndOverflow() {
        assertThatThrownBy(() -> Money.parse("1", "JPY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1", "MAD").add(Money.parse("1", "EUR"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("999999999999999999.99", "USD").add(Money.parse("0.01", "USD"))).isInstanceOf(IllegalArgumentException.class);
    }
}
