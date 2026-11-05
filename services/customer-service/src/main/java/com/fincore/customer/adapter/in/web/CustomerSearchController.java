package com.fincore.customer.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, literal prefix lookup. No profile or outbox write occurs. */
@RestController
@RequestMapping("/api/v1/admin/customers/search")
public class CustomerSearchController {
    private final Search search;
    public CustomerSearchController(Search search) { this.search = search; }
    public record Input(String namePrefix, String afterId) {}
    public record Match(UUID customerId, String displayName, String status, String profileVersion, Instant createdAt) {}
    public record Page(List<Match> items, UUID nextAfterId, Instant observedAt) {}

    @PostMapping public ResponseEntity<Page> search(@RequestBody Input input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.find(input));
    }

    @Service public static class Search {
        private final JdbcTemplate db;
        public Search(JdbcTemplate db) { this.db = db; }
        public Page find(Input input) {
            if (input == null || input.namePrefix() == null || input.namePrefix().chars().anyMatch(Character::isISOControl))
                throw invalid("INVALID_SEARCH_PREFIX");
            String prefix = input.namePrefix().strip();
            if (prefix.length() < 3 || prefix.length() > 80) throw invalid("INVALID_SEARCH_PREFIX");
            UUID after = null;
            if (input.afterId() != null) {
                if (!input.afterId().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw invalid("INVALID_SEARCH_CURSOR");
                after = UUID.fromString(input.afterId());
            }
            UUID cursor = after;
            String pattern = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            var rows = db.query(connection -> {
                var statement = connection.prepareStatement("select id,display_name,status,version::text,created_at from customer_profile "
                    + "where lower(display_name) like lower(?) escape '!' and (?::uuid is null or id>?::uuid) order by id limit 21");
                statement.setQueryTimeout(3);
                statement.setString(1, pattern); statement.setObject(2, cursor); statement.setObject(3, cursor);
                return statement;
            }, (rs, n) -> new Match(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
            var items = List.copyOf(rows.subList(0, Math.min(20, rows.size())));
            return new Page(items, rows.size() > 20 ? items.getLast().customerId() : null, Instant.now());
        }
        private static ResponseStatusException invalid(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    }
}
