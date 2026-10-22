"""Real Keycloak client-credentials + private Ledger API journey, synthetic amounts only."""
import base64
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    values = dict(os.environ)
    local = Path(__file__).resolve().parents[1] / ".env"
    if local.exists():
        for line in local.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values.setdefault(key, value)
    identity = values.get("KEYCLOAK_URL", "http://localhost:8180")
    base = values.get("LEDGER_URL", "http://localhost:8082") + "/internal/v1/ledger"
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "fincore-ledger-operator", "client_secret": values["LEDGER_CLIENT_SECRET"]}).encode()
    def token():
        with urllib.request.urlopen(identity + "/realms/fincore/protocol/openid-connect/token", form, timeout=20) as response:
            return json.load(response)["access_token"]
    bearer = token()
    claims = json.loads(base64.urlsafe_b64decode(bearer.split(".")[1] + "=="))
    assert "ledger-service" in claims["aud"]
    assert "ledger.post" in claims["resource_access"]["ledger-service"]["roles"]

    def api(method, path, body=None, key=None, expected=200, authorized=True):
        headers = {"Content-Type": "application/json", "X-Correlation-ID": "ledger-smoke"}
        if authorized:
            headers["Authorization"] = "Bearer " + bearer
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, result = error.code, json.load(error)
        assert status == expected, f"Unexpected response: {method} {path} -> {status}, code {result.get('code')}"
        return result

    def unique(): return str(uuid.uuid4())
    def account(kind):
        return api("POST", "/accounts", {"externalReference": unique(), "currency": "MAD", "type": kind})["id"]
    def command(kind, debit, credit, amount, reference=None):
        return {"kind": kind, "businessReference": reference or unique(), "currency": "MAD", "entries": [
            {"accountId": debit, "side": "DEBIT", "amount": amount}, {"accountId": credit, "side": "CREDIT", "amount": amount}]}
    def balance(identifier): return api("GET", f"/accounts/{identifier}/balance")["booked"]

    api("GET", "/reconciliation", authorized=False, expected=401)
    settlement, sender, recipient = account("SETTLEMENT_ASSET"), account("CUSTOMER_LIABILITY"), account("CUSTOMER_LIABILITY")
    api("POST", "/operations", command("DEPOSIT", settlement, sender, "1000.00"), unique())
    transfer = command("TRANSFER", sender, recipient, "100.00")
    key = unique()
    result = api("POST", "/operations", transfer, key)
    assert result["status"] == "POSTED"
    original = api("GET", "/journals/" + result["journalId"])
    assert api("POST", "/operations", transfer, key) == result
    assert api("POST", "/operations", transfer, unique()) == result
    bearer = token()  # Fresh signed token, same stable technical actor and financial identity.
    assert api("GET", "/operations/" + transfer["businessReference"]) == result
    changed = command("TRANSFER", sender, recipient, "101.00", transfer["businessReference"])
    api("POST", "/operations", changed, key, expected=409)
    assert balance(sender) == "900.00" and balance(recipient) == "100.00"
    rejected = api("POST", "/operations", command("TRANSFER", sender, recipient, "9999.00"), unique(), expected=422)
    assert rejected["code"] == "INSUFFICIENT_FUNDS"
    reverse_body, reverse_key = {"businessReference": unique()}, unique()
    reversal = api("POST", "/journals/" + result["journalId"] + "/reversal", reverse_body, reverse_key)
    assert reversal["status"] == "POSTED"
    assert api("POST", "/journals/" + result["journalId"] + "/reversal", reverse_body, reverse_key) == reversal
    assert api("GET", "/journals/" + result["journalId"]) == original
    assert balance(sender) == "1000.00" and balance(recipient) == "0.00"
    api("PUT", f"/accounts/{sender}/guard", {"status": "FROZEN", "expectedRevision": 0})
    assert api("POST", "/operations", command("TRANSFER", sender, recipient, "1.00"), unique(), expected=422)["code"] == "ACCOUNT_RESTRICTED"
    api("PUT", f"/accounts/{sender}/guard", {"status": "ACTIVE", "expectedRevision": 0}, expected=409)
    api("PUT", f"/accounts/{sender}/guard", {"status": "ACTIVE", "expectedRevision": 1})
    reconciliation = api("GET", "/reconciliation")
    assert reconciliation == {"balanceDiscrepancies": [], "invalidJournals": []}
    print("PASS: signed technical identity, deposit, transfer, replay, lookup, conflict, insufficient funds, full reversal, guards, reconciliation.")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, KeyError):
        raise SystemExit("Ledger smoke failed: check service readiness and generated local configuration.") from None
