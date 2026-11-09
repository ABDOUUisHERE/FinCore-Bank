package com.fincore.customer.configuration;

import com.fincore.customer.adapter.in.web.CustomerSupportController.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerSupportValidationTest {
    @Test void invalidCohortsAndPrefixesNeverReachStorage() {
        var db=mock(JdbcTemplate.class);var store=new Store(db);UUID id=UUID.randomUUID();
        for(Input input:Arrays.asList(null,new Input("Private",null),new Input("Private",List.of()),new Input("Private",List.of(id,id)),new Input("Private",Arrays.asList(id,null)),new Input("Private",java.util.stream.IntStream.range(0,101).mapToObj(n->UUID.randomUUID()).toList()),new Input("ab",List.of(id)),new Input("abc\nprivate",List.of(id))))assertThatThrownBy(()->store.search(input)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(db);
    }
}
