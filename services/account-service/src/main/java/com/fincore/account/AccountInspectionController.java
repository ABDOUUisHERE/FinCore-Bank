package com.fincore.account;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountInspectionController {
    private final AccountInspection inspection;
    public AccountInspectionController(AccountInspection inspection) { this.inspection = inspection; }
    @GetMapping("/api/v1/admin/accounts/{id}")
    public AccountInspection.Status get(@PathVariable UUID id) { return inspection.inspect(id); }
    /** Scope + signed machine client are enforced independently of the broker's grant check. */
    @GetMapping("/internal/v1/accounts/{id}/support-status")
    public SupportStatus support(@PathVariable UUID id,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var view=inspection.inspect(id);
        return new SupportStatus(view.accountId(),view.currency(),view.recordedStatus(),view.accountVersion(),view.pendingStatus(),view.lastGuardError(),
            view.recordedGuardRevision(),view.accountObservedAt(),view.ledgerState(),view.ledger());
    }
    public record SupportStatus(UUID accountId,String currency,String recordedStatus,String accountVersion,String pendingStatus,String lastGuardError,
        String recordedGuardRevision,java.time.Instant accountObservedAt,String ledgerState,AccountInspection.LedgerStatus ledger) {}
}
