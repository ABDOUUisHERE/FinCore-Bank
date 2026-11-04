package com.fincore.customer.configuration;

import com.fincore.customer.adapter.in.web.CustomerSearchController.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerSearchTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final Search search = new Search(db);

    @Test void invalidPrefixesNeverReachStorage() {
        for (String prefix : new String[]{null,"","  ","ab","  ab  ","x".repeat(81),"abc\nprivate","\u0000abc"})
            assertThatThrownBy(() -> search.find(new Input(prefix,null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INVALID_SEARCH_PREFIX");
        assertThatThrownBy(() -> search.find(null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(db);
    }
    @Test void cursorsRequireCanonicalUuidShapeBeforeStorage() {
        for (String cursor : new String[]{"","1-1-1-1-1","not-a-reference","' OR TRUE --","0".repeat(37)})
            assertThatThrownBy(() -> search.find(new Input("Valid",cursor)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INVALID_SEARCH_CURSOR");
        verifyNoInteractions(db);
    }
}
