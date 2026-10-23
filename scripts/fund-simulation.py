"""Private local simulation funding. No administrator, database or browser token is needed."""
import argparse
from decimal import Decimal
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description="Credit an existing simulated account using the private funding scope.")
    parser.add_argument("account", type=uuid.UUID)
    parser.add_argument("amount")
    parser.add_argument("currency", choices=["MAD", "EUR", "USD"])
    parser.add_argument("--key", default=None, help="Reuse the original printed key after an unconfirmed response.")
    args = parser.parse_args()
    if not re.fullmatch(r"(0|[1-9][0-9]{0,17})(\.[0-9]{1,2})?", args.amount) or Decimal(args.amount) <= 0:
        parser.error("Amount must be positive exact decimal text with at most two fractional digits.")
    key = args.key or str(uuid.uuid4())
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,120}", key):
        parser.error("Invalid request identity.")
    # Print the non-secret identity before any financial request so timeout recovery can reuse it.
    print("Request key: " + key, flush=True)
    identity = os.environ["KEYCLOAK_URL"].rstrip("/")
    payments = os.environ["PAYMENT_URL"].rstrip("/")
    form = urllib.parse.urlencode({"client_id": "fincore-payment-operator", "grant_type": "client_credentials",
                                  "client_secret": os.environ["PAYMENT_OPERATOR_SECRET"]}).encode()
    with urllib.request.urlopen(identity + "/realms/fincore/protocol/openid-connect/token", form, timeout=12) as response:
        token = json.load(response)["access_token"]

    def call(path, body=None):
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": key}
        request = urllib.request.Request(payments + path, None if body is None else json.dumps(body).encode(), headers,
                                         method="GET" if body is None else "POST")
        with urllib.request.urlopen(request, timeout=12) as response:
            return json.load(response)

    payment = call("/internal/v1/payments/funding", {"destinationAccountId": str(args.account),
                   "amount": format(Decimal(args.amount), ".2f"), "currency": args.currency, "reference": "Private local simulation funding"})
    print("Payment reference: " + payment["paymentId"], flush=True)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        payment = call("/internal/v1/payments/" + payment["paymentId"])
        if payment["status"] in ("COMPLETED", "FAILED", "REJECTED", "PENDING_RISK_REVIEW"):
            break
        time.sleep(0.5)
    print("Recorded status: " + payment["status"])
    if payment["status"] == "COMPLETED" and payment.get("journalId"):
        print("Confirmed journal: " + payment["journalId"])
    else:
        print("No completed funding is claimed. Keep the payment reference and original request key.")
        return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, urllib.error.URLError):
        raise SystemExit("Funding response unconfirmed. Check readiness and retry the same account, amount, currency and printed --key.") from None
