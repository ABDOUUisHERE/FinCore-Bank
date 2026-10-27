"""Private browser-test fixture. Secrets/tokens are memory/stdin-only; never run with shell tracing."""
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

identity = os.environ.get("KEYCLOAK_URL", "http://keycloak:8180").rstrip("/")
gateway = os.environ.get("API_URL", "http://api-gateway:8080").rstrip("/")
payments = os.environ.get("PAYMENT_URL", "http://payment-service:8080").rstrip("/")
ledger = os.environ.get("LEDGER_URL", "http://ledger-service:8080").rstrip("/")


def call(method, url, token, body=None, key=None):
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(url, None if body is None else json.dumps(body).encode(), headers, method=method)
    with urllib.request.urlopen(request, timeout=12) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def grant(client, secret, username=None):
    body = {"client_id": client, "grant_type": "client_credentials", "client_secret": secret}
    if username:
        body = {"client_id": client, "grant_type": "password", "username": username, "password": secret}
    with urllib.request.urlopen(identity + ("/realms/master" if username else "/realms/fincore") + "/protocol/openid-connect/token",
                                urllib.parse.urlencode(body).encode(), timeout=12) as response:
        return json.load(response)["access_token"]


def main():
    action = sys.argv[1]
    if action == "identities":
        admin = grant("admin-cli", os.environ["KEYCLOAK_ADMIN_PASSWORD"], "platform-admin")
        base = identity + "/admin/realms/fincore"
        role = call("GET", base + "/roles/CUSTOMER", admin)
        users = {}
        for kind in ("sender", "recipient"):
            username = "ui-" + kind + "-" + uuid.uuid4().hex[:16]
            password = secrets.token_hex(24)
            call("POST", base + "/users", admin, {"username": username, "enabled": True, "emailVerified": True,
                 "email": username + "@example.invalid", "firstName": "Synthetic", "lastName": kind,
                 "credentials": [{"type": "password", "value": password, "temporary": False}]})
            user = call("GET", base + "/users?username=" + username + "&exact=true", admin)[0]
            call("POST", base + "/users/" + user["id"] + "/role-mappings/realm", admin, [role])
            users[kind] = {"username": username, "password": password}
        # Captured by Playwright spawnSync, never forwarded to its reporter or artifacts.
        print(json.dumps(users))
        return
    data = json.load(sys.stdin)
    source, destination = data["source"], data["destination"]
    uuid.UUID(source)
    uuid.UUID(destination)
    customer, recipient = data["customer"], data["recipient"]
    first = call("GET", gateway + "/api/v1/accounts/" + source, customer)
    second = call("GET", gateway + "/api/v1/accounts/" + destination, recipient)
    assert first["currency"] == second["currency"] == "MAD"
    if action in ("fund", "card-fund"):
        operator = grant("fincore-payment-operator", os.environ["PAYMENT_OPERATOR_SECRET"])
        amount = "10000.00" if action == "card-fund" else "8000.00"
        for _ in range(1 if action == "card-fund" else 2):
            accepted = call("POST", payments + "/internal/v1/payments/funding", operator,
                            {"destinationAccountId": source, "amount": amount, "currency": "MAD", "reference": "Synthetic browser fixture"}, str(uuid.uuid4()))
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                result = call("GET", payments + "/internal/v1/payments/" + accepted["paymentId"], operator)
                if result["status"] == "COMPLETED" and result.get("journalId"):
                    break
                assert result["status"] not in ("FAILED", "REJECTED", "PENDING_RISK_REVIEW")
                time.sleep(0.5)
            else:
                raise AssertionError("Funding did not confirm")
        assert call("GET", gateway + "/api/v1/accounts/" + source + "/balance", customer)["booked"] == ("10000.00" if action == "card-fund" else "16000.00")
        print(json.dumps({"funded": True}))
    elif action == "card-assert":
        result = call("GET", gateway + "/api/v1/card-authorizations/" + data["authorizationId"], customer)
        assert result["status"] == data["expectedStatus"]
        balance = call("GET", gateway + "/api/v1/accounts/" + source + "/balance", customer)
        assert balance["booked"] == data["booked"] and balance["reserved"] == data["reserved"] and balance["available"] == data["available"]
        inspector = grant("fincore-ledger-operator", os.environ["LEDGER_CLIENT_SECRET"])
        for field in ("journalId", "refundJournalId"):
            if result.get(field):
                journal = call("GET", ledger + "/internal/v1/ledger/journals/" + result[field], inspector)
                assert len(journal["entries"]) == 2 and {r["side"] for r in journal["entries"]} == {"DEBIT", "CREDIT"}
                assert all(r["amount"] == data["amount"] for r in journal["entries"])
                if field == "refundJournalId":
                    assert journal["reversalOf"] == result["journalId"]
        assert call("GET", ledger + "/internal/v1/ledger/reconciliation", inspector) == {"balanceDiscrepancies": [], "invalidJournals": []}
        print(json.dumps({"verified": True}))
    elif action == "fraud-assert":
        result = call("GET", gateway + "/api/v1/transfers/" + data["paymentId"], customer)
        assert result["status"] == data["expectedStatus"]
        assert call("GET", gateway + "/api/v1/accounts/" + source + "/balance", customer)["booked"] == data["expectedSource"]
        assert call("GET", gateway + "/api/v1/accounts/" + destination + "/balance", recipient)["booked"] == data["expectedDestination"]
        history = call("GET", gateway + "/api/v1/accounts/" + source + "/transactions?kind=TRANSFER", customer)
        assert history["nextCursor"] is None, "Fresh fixture history must be complete"
        matching = [r for r in history["items"] if r["paymentId"] == data["paymentId"]]
        assert len(matching) == data["expectedPostings"]
        if data["expectedPostings"] == 0:
            assert not result.get("journalId")
        else:
            assert data["expectedPostings"] == 1 and result["status"] == "COMPLETED"
            assert matching[0]["journalId"] == result["journalId"] and matching[0]["amount"] == data["amount"]
            uuid.UUID(result["riskAssessmentId"])
            inspector = grant("fincore-ledger-operator", os.environ["LEDGER_CLIENT_SECRET"])
            journal = call("GET", ledger + "/internal/v1/ledger/journals/" + result["journalId"], inspector)
            assert len(journal["entries"]) == 2 and {r["side"] for r in journal["entries"]} == {"DEBIT", "CREDIT"}
            assert all(r["amount"] == data["amount"] for r in journal["entries"])
            assert call("GET", ledger + "/internal/v1/ledger/reconciliation", inspector) == {"balanceDiscrepancies": [], "invalidJournals": []}
        print(json.dumps({"verified": True}))
    elif action == "assert":
        result = call("GET", gateway + "/api/v1/transfers/" + data["paymentId"], customer)
        assert result["status"] == "COMPLETED" and result["journalId"]
        assert call("GET", gateway + "/api/v1/accounts/" + source + "/balance", customer)["booked"] == "15900.00"
        assert call("GET", gateway + "/api/v1/accounts/" + destination + "/balance", recipient)["booked"] == "100.00"
        beneficiaries = call("GET", gateway + "/api/v1/beneficiaries", customer)["items"]
        beneficiary = next(row for row in beneficiaries if row["destinationAccountId"] == destination)
        for path in ("/api/v1/accounts/" + source, "/api/v1/accounts/" + source + "/balance", "/api/v1/accounts/" + source + "/transactions", "/api/v1/accounts/" + source + "/activity", "/api/v1/beneficiaries/" + beneficiary["beneficiaryId"]):
            try:
                call("GET", gateway + path, recipient)
                raise AssertionError("Wrong owner was allowed")
            except urllib.error.HTTPError as error:
                assert error.code == 404
        history = call("GET", gateway + "/api/v1/accounts/" + source + "/transactions?kind=TRANSFER", customer)["items"]
        assert len(history) == 1 and history[0]["amount"] == "100.00" and history[0]["side"] == "DEBIT"
        inspector = grant("fincore-ledger-operator", os.environ["LEDGER_CLIENT_SECRET"])
        journal = call("GET", ledger + "/internal/v1/ledger/journals/" + result["journalId"], inspector)
        assert len(journal["entries"]) == 2
        assert {row["side"] for row in journal["entries"]} == {"DEBIT", "CREDIT"}
        assert all(row["amount"] == "100.00" for row in journal["entries"])
        assert call("GET", ledger + "/internal/v1/ledger/reconciliation", inspector) == {"balanceDiscrepancies": [], "invalidJournals": []}
        print(json.dumps({"verified": True}))
    else:
        raise ValueError("Unknown fixture action")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, AssertionError, urllib.error.URLError):
        raise SystemExit("Customer browser fixture failed; inspect service readiness and scoped identities.") from None
