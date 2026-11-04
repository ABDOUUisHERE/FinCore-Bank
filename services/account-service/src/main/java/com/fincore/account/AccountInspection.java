package com.fincore.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.ApiFailure;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Two independent observations, never a distributed snapshot or a recovery command. */
@Service
public class AccountInspection {
    private final AccountStore accounts;
    private final AccountCoordinator coordinator;
    public AccountInspection(AccountStore accounts, AccountCoordinator coordinator) {
        this.accounts = accounts; this.coordinator = coordinator;
    }
    public record LedgerStatus(String status, String guardRevision, Instant observedAt) {}
    public record Status(UUID accountId, UUID customerId, String currency, String recordedStatus,
                         String accountVersion, String pendingStatus, String lastGuardError,
                         String recordedGuardRevision, Instant accountObservedAt,
                         String ledgerState, LedgerStatus ledger) {}

    public Status inspect(UUID id) {
        var account = accounts.get(id);
        var observed = Instant.now();
        if (account.ledgerId() == null) {
            if (!account.status().equals("PROVISIONING")) throw invalidSource();
            return view(account, observed, "NOT_LINKED", null);
        }
        JsonNode source;
        try { source = coordinator.ledger("GET", "/accounts/" + account.ledgerId() + "/operational-status", null); }
        catch (ApiFailure failure) {
            if (failure.status >= 500) return view(account, observed, "UNAVAILABLE", null);
            throw invalidSource();
        }
        if (source == null || !source.isObject()
            || !source.path("ledgerAccountId").asText().equals(account.ledgerId().toString())
            || !source.path("externalReference").asText().equals("account:" + account.id())
            || !source.path("ownerSubject").asText().equals(account.owner())
            || !source.path("currency").asText().equals(account.currency())
            || !source.path("type").asText().equals("CUSTOMER_LIABILITY")
            || !Set.of("ACTIVE", "FROZEN", "CLOSED").contains(source.path("status").asText())
            || !source.path("guardRevision").isTextual()
            || !source.path("guardRevision").asText().matches("0|[1-9][0-9]{0,18}")
            || !source.path("observedAt").isTextual()) throw invalidSource();
        try {
            String revision = source.path("guardRevision").asText();
            Long.parseLong(revision);
            var ledger = new LedgerStatus(source.path("status").asText(), revision,
                Instant.parse(source.path("observedAt").asText()));
            return view(account, observed, "OBSERVED", ledger);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) { throw invalidSource(); }
    }
    private static Status view(AccountStore.Account a, Instant observed, String state, LedgerStatus ledger) {
        String error = a.guardError() != null && Set.of("ACCOUNT_NOT_EMPTY", "GUARD_CONFLICT").contains(a.guardError()) ? a.guardError() : null;
        return new Status(a.id(), a.customerId(), a.currency(), a.status(), Long.toString(a.version()),
            a.target(), error, Long.toString(a.guardRevision()), observed, state, ledger);
    }
    private static ApiFailure invalidSource() { return new ApiFailure(503, "INVALID_ACCOUNT_STATUS_SOURCE"); }
}
