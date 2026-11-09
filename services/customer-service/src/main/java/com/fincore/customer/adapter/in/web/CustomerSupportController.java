package com.fincore.customer.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Restricted machine projection. Audit supplies the authorized set, never a browser. */
@RestController
@RequestMapping("/internal/v1/customers")
public class CustomerSupportController {
    private final Store store;
    public CustomerSupportController(Store store) {this.store=store;}
    public record Input(String namePrefix,List<UUID> customerIds) {}
    public record Profile(UUID customerId,String displayName,String status,String profileVersion,Instant createdAt,Instant observedAt) {}
    public record Results(List<Profile> items,boolean hasMore) {}
    @GetMapping("/{id}/support-profile") public Profile profile(@PathVariable UUID id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return store.profile(id);
    }
    @PostMapping("/support-search") public Results search(@RequestBody Input input,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return store.search(input);
    }
    @Service public static class Store {
        private final JdbcTemplate db;
        public Store(JdbcTemplate db) {this.db=db;}
        private static final String FIELDS="id,display_name,status,version::text,created_at,statement_timestamp()";
        private static final org.springframework.jdbc.core.RowMapper<Profile> PROFILE=(rs,n)->new Profile(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant());
        @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Profile profile(UUID id) {
            var rows=db.query(c->{var s=c.prepareStatement("select "+FIELDS+" from customer_profile where id=?");s.setQueryTimeout(3);s.setObject(1,id);return s;},PROFILE);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CUSTOMER_NOT_FOUND");
            return rows.getFirst();
        }
        @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Results search(Input input) {
            if(input==null||input.customerIds()==null||input.customerIds().isEmpty()||input.customerIds().size()>100
                ||input.customerIds().stream().anyMatch(Objects::isNull)||new HashSet<>(input.customerIds()).size()!=input.customerIds().size())throw invalid();
            String prefix=input.namePrefix();
            if(prefix==null||prefix.chars().anyMatch(Character::isISOControl)||prefix.strip().length()<3||prefix.strip().length()>80)throw invalid();
            String pattern=prefix.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            var rows=db.query(c->{var s=c.prepareStatement("select "+FIELDS+" from customer_profile where id=any(?::uuid[]) and lower(display_name) like lower(?) escape '!' order by id limit 21");
                s.setQueryTimeout(3);s.setArray(1,c.createArrayOf("uuid",input.customerIds().toArray(UUID[]::new)));s.setString(2,pattern);return s;},PROFILE);
            return new Results(List.copyOf(rows.subList(0,Math.min(20,rows.size()))),rows.size()>20);
        }
        private static ResponseStatusException invalid() {return new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SCOPED_CUSTOMER_SEARCH");}
    }
}
