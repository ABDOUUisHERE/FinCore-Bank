"""Consume genuine PKCE tokens over stdin, exercise synthetic business APIs; never persist tokens."""
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    values = dict(os.environ)
    env = Path(__file__).resolve().parents[1] / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values.setdefault(key, value)
    identities = json.load(sys.stdin)
    customer, recipient = identities["customer"], identities["recipient"]
    base = values.get("API_URL", "http://127.0.0.1:8280")
    payment = values.get("PAYMENT_URL", "http://127.0.0.1:8084")
    ledger = values.get("LEDGER_URL", "http://127.0.0.1:8082")
    keycloak = values.get("KEYCLOAK_URL", "http://127.0.0.1:8180")

    def machine(client, secret):
        form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": client, "client_secret": values[secret]}).encode()
        with urllib.request.urlopen(keycloak + "/realms/fincore/protocol/openid-connect/token", form, timeout=15) as response:
            return json.load(response)["access_token"]

    operator = machine("fincore-payment-operator", "PAYMENT_OPERATOR_SECRET")
    inspector = machine("fincore-ledger-operator", "LEDGER_CLIENT_SECRET")

    def api(method, path, token, body=None, key=None, expected=200, host=base):
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json", "X-Correlation-ID": "phase3-business-smoke"}
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(host + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status = error.code
            try:
                result = json.load(error)
            except ValueError:
                result = {}
        assert status == expected, f"Unexpected {method} {path}: {status}; code {result.get('code', 'unavailable')}"
        return result

    def unique(): return str(uuid.uuid4())
    def poll(identifier, token, terminal, internal=False):
        path = ("/internal/v1/payments/" if internal else "/api/v1/transfers/") + identifier
        for _ in range(70):
            result = api("GET", path, token, host=payment if internal else base)
            if result["status"] in terminal:
                return result
            time.sleep(0.2)
        raise AssertionError("Payment did not reach the expected known state")
    def account(token):
        result = api("POST", "/api/v1/accounts", token, {"currency": "MAD"}, unique(), expected=201)
        assert result["status"] == "ACTIVE"
        return result
    def balance(identifier, token): return api("GET", f"/api/v1/accounts/{identifier}/balance", token)["booked"]
    def transfer(amount, key=None):
        body = {"sourceAccountId": sender_id, "destinationAccountId": receiver_id, "amount": amount, "currency": "MAD", "reference": "Synthetic transfer"}
        return api("POST", "/api/v1/transfers", customer, body, key or unique(), expected=202), body

    for token, name in ((customer, "Customer"), (recipient, "Recipient")):
        api("POST", "/api/v1/customers/me/onboarding", token, {"displayName": "Synthetic " + name, "countryCode": "MA"})
        profile = api("GET", "/api/v1/customers/me/profile", token)
        assert profile["status"] == "ACTIVE"
    sender, receiver = account(customer), account(recipient)
    sender_id, receiver_id = sender["accountId"], receiver["accountId"]
    api("GET", f"/api/v1/accounts/{sender_id}/balance", recipient, expected=404)
    api("GET", f"/internal/v1/ledger/instructions/{unique()}", customer, expected=403, host=ledger)
    # Technical funding is unavailable through the public Gateway and customer credentials.
    api("POST", "/internal/v1/payments/funding", customer, {}, expected=403, host=payment)
    funding = api("POST", "/internal/v1/payments/funding", operator, {"destinationAccountId": sender_id, "amount": "1000.00", "currency": "MAD", "reference": "Synthetic funding"}, unique(), expected=202, host=payment)
    assert poll(funding["paymentId"], operator, {"COMPLETED"}, internal=True)["journalId"]
    assert balance(sender_id, customer) == "1000.00"
    key = unique()
    accepted, body = transfer("100.00", key)
    assert accepted["status"] == "CREATED"
    completed = poll(accepted["paymentId"], customer, {"COMPLETED"})
    assert completed["journalId"]
    assert api("POST", "/api/v1/transfers", customer, body, key, expected=202) == accepted
    changed = {**body, "amount": "101.00"}
    api("POST", "/api/v1/transfers", customer, changed, key, expected=409)
    api("GET", "/api/v1/transfers/" + accepted["paymentId"], recipient, expected=404)
    api("POST", "/api/v1/transfers", recipient, body, unique(), expected=404)
    assert balance(sender_id, customer) == "900.00" and balance(receiver_id, recipient) == "100.00"
    history = api("GET", f"/api/v1/accounts/{sender_id}/transactions", customer)
    assert len(history["items"]) == 2
    original = api("GET", "/internal/v1/ledger/journals/" + completed["journalId"], inspector, host=ledger)
    # Keep this an insufficient-funds check below the new-beneficiary review threshold.
    insufficient, _ = transfer("4999.00")
    assert poll(insufficient["paymentId"], customer, {"FAILED"})["code"] == "INSUFFICIENT_FUNDS"
    review, _ = transfer("20000.00")
    pending = poll(review["paymentId"], customer, {"PENDING_RISK_REVIEW"})
    api("POST", f"/internal/v1/payments/{review['paymentId']}/decision", operator, {"expectedVersion": pending["version"], "approve": True, "reason": "SYNTHETIC_REVIEW_APPROVED"}, host=payment)
    assert poll(review["paymentId"], customer, {"FAILED"})["code"] == "INSUFFICIENT_FUNDS"
    cancelled, _ = transfer("20001.00")
    pending = poll(cancelled["paymentId"], customer, {"PENDING_RISK_REVIEW"})
    result = api("POST", f"/api/v1/transfers/{cancelled['paymentId']}/cancel", customer, {"expectedVersion": pending["version"]})
    assert result["status"] == "REJECTED" and result["code"] == "CANCELLED"
    blocked, _ = transfer("100000.01")
    assert poll(blocked["paymentId"], customer, {"REJECTED"})["code"] == "HARD_AMOUNT_LIMIT"
    frozen = api("PUT", f"/api/v1/accounts/{sender_id}/guard", customer, {"status": "FROZEN", "expectedVersion": sender["version"]})
    assert frozen["status"] == "FROZEN"
    assert api("POST", "/api/v1/transfers", customer, body, key, expected=202) == accepted
    api("POST", "/api/v1/transfers", customer, body, unique(), expected=422)
    api("PUT", f"/api/v1/accounts/{sender_id}/guard", customer, {"status": "ACTIVE", "expectedVersion": sender["version"]}, expected=409)
    unfrozen = api("PUT", f"/api/v1/accounts/{sender_id}/guard", customer, {"status": "ACTIVE", "expectedVersion": frozen["version"]})
    assert unfrozen["status"] == "ACTIVE"
    reverse_key = unique()
    reverse = api("POST", f"/internal/v1/payments/{accepted['paymentId']}/reversal", operator, {}, reverse_key, expected=202, host=payment)
    poll(reverse["paymentId"], operator, {"COMPLETED"}, internal=True)
    poll(accepted["paymentId"], customer, {"REVERSED"})
    assert api("POST", f"/internal/v1/payments/{accepted['paymentId']}/reversal", operator, {}, reverse_key, expected=202, host=payment) == reverse
    assert balance(sender_id, customer) == "1000.00" and balance(receiver_id, recipient) == "0.00"
    assert api("GET", "/internal/v1/ledger/journals/" + completed["journalId"], inspector, host=ledger) == original
    assert api("GET", "/internal/v1/ledger/reconciliation", inspector, host=ledger) == {"balanceDiscrepancies": [], "invalidJournals": []}
    print("PASS: two real PKCE customers, onboarding, owned accounts, restricted funding, exchanged actor authorization, transfer/replay/conflict, history, risk review/block, cancellation, guards, compensation and reconciliation.")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, KeyError):
        raise SystemExit("Business journey failed: check readiness, generated configuration and identity scopes.") from None
