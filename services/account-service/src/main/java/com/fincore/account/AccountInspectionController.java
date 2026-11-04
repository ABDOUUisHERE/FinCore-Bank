package com.fincore.account;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountInspectionController {
    private final AccountInspection inspection;
    public AccountInspectionController(AccountInspection inspection) { this.inspection = inspection; }
    @GetMapping("/api/v1/admin/accounts/{id}")
    public AccountInspection.Status get(@PathVariable UUID id) { return inspection.inspect(id); }
}
