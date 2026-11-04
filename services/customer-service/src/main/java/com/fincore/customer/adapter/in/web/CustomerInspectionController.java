package com.fincore.customer.adapter.in.web;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Operational status only. Personal profile fields never cross this interface. */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class CustomerInspectionController {
    private final Inspection store;
    public CustomerInspectionController(Inspection store) { this.store = store; }

    public record Status(UUID customerId, String status, String profileVersion, String createdAt, String observedAt) {}

    @GetMapping("/{id}") public Status inspect(@PathVariable UUID id) { return store.get(id); }

    @Service public static class Inspection {
        private final JdbcTemplate db;
        public Inspection(JdbcTemplate db) { this.db = db; }
        public Status get(UUID id) {
            var rows = db.query("select id,status,version::text,created_at,statement_timestamp() from customer_profile where id=?",
                (rs, n) -> new Status(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getTimestamp(4).toInstant().toString(), rs.getTimestamp(5).toInstant().toString()), id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
            return rows.getFirst();
        }
    }
}
