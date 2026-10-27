"""Configure account/payment clients and standard token exchange on fresh AND persisted realms; no secrets printed."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


def main():
    values = dict(os.environ)
    local = Path(__file__).resolve().parents[1] / ".env"
    if local.exists():
        for line in local.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values.setdefault(key, value)
    base = values.get("KEYCLOAK_URL", "http://localhost:8180").rstrip("/")
    form = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password", "username": "platform-admin", "password": values["KEYCLOAK_ADMIN_PASSWORD"]}).encode()
    with urllib.request.urlopen(base + "/realms/master/protocol/openid-connect/token", form, timeout=15) as response:
        token = json.load(response)["access_token"]

    def api(method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base + "/admin/realms/fincore" + path, data, {"Authorization": "Bearer " + token, "Content-Type": "application/json"}, method=method)
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def client(client_id, config):
        existing = api("GET", "/clients?clientId=" + client_id)
        if not existing:
            api("POST", "/clients", {"clientId": client_id, **config})
            existing = api("GET", "/clients?clientId=" + client_id)
        identifier = existing[0]["id"]
        api("PUT", "/clients/" + identifier, {"clientId": client_id, **config})
        return identifier

    resource = client("ledger-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    def ensure_role(target, name):
        existing = api("GET", f"/clients/{target}/roles")
        if not any(item["name"] == name for item in existing):
            api("POST", f"/clients/{target}/roles", {"name": name})
        return api("GET", f"/clients/{target}/roles/{name}")
    ledger_roles = {name: ensure_role(resource, name) for name in ("ledger.prepare", "ledger.prepare.fund", "ledger.prepare.reverse", "ledger.execute", "ledger.instructions.read", "ledger.provision", "ledger.guard", "ledger.read", "ledger.card.manage", "ledger.card.inspect", "ledger.card.reserve", "ledger.statement.read", "ledger.reconcile.read")}
    account_resource = client("account-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    account_lookup = ensure_role(account_resource, "account.lookup")
    payment_resource = client("payment-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    operator_roles = [ensure_role(payment_resource, name) for name in ("payment.fund", "payment.reverse", "payment.review", "payment.inspect")]
    # CUSTOMER is the only realm role that can prepare a customer transfer.
    api("POST", "/roles/CUSTOMER/composites", [ledger_roles["ledger.prepare"]])
    def mapper(target, spec):
        existing = api("GET", f"/clients/{target}/protocol-mappers/models")
        found = next((item for item in existing if item["name"] == spec["name"]), None)
        if found:
            api("PUT", f"/clients/{target}/protocol-mappers/models/{found['id']}", {"id": found["id"], **spec})
        else:
            api("POST", f"/clients/{target}/protocol-mappers/models", spec)
    def audience(target, name):
        mapper(target, {"name": "audience-" + name, "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper", "config": {"included.custom.audience": name, "access.token.claim": "true", "id.token.claim": "false"}})
    def role_claim(target, client_id):
        mapper(target, {"name": "roles-" + client_id, "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-client-role-mapper", "config": {"usermodel.clientRoleMapping.clientId": client_id, "claim.name": "resource_access." + client_id + ".roles", "jsonType.label": "String", "multivalued": "true", "access.token.claim": "true", "id.token.claim": "false"}})
    def machine(client_id, secret, exchange=False):
        return client(client_id, {"protocol": "openid-connect", "enabled": True, "publicClient": False,
            "secret": values[secret], "serviceAccountsEnabled": True, "standardFlowEnabled": False,
            "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "fullScopeAllowed": False,
            "attributes": {"standard.token.exchange.enabled": "true" if exchange else "false"}})
    def assign(target, resource_id, roles):
        user = api("GET", f"/clients/{target}/service-account-user")
        api("POST", f"/users/{user['id']}/role-mappings/clients/{resource_id}", roles)
        api("POST", f"/clients/{target}/scope-mappings/clients/{resource_id}", roles)
    account_client = machine("fincore-account", "ACCOUNT_CLIENT_SECRET")
    assign(account_client, resource, [ledger_roles[name] for name in ("ledger.provision", "ledger.guard", "ledger.read")])
    audience(account_client, "ledger-service")
    role_claim(account_client, "ledger-service")
    beneficiary_client = machine("fincore-beneficiary", "BENEFICIARY_CLIENT_SECRET")
    assign(beneficiary_client, account_resource, [account_lookup])
    audience(beneficiary_client, "account-service")
    role_claim(beneficiary_client, "account-service")
    notification_client = machine("fincore-notification", "NOTIFICATION_CLIENT_SECRET")
    assign(notification_client, account_resource, [account_lookup])
    audience(notification_client, "account-service")
    role_claim(notification_client, "account-service")
    notification_resource = client("notification-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    notification_roles = [ensure_role(notification_resource, name) for name in ("notification.inspect", "notification.replay")]
    notification_operator = machine("fincore-notification-operator", "NOTIFICATION_OPERATOR_SECRET")
    assign(notification_operator, notification_resource, notification_roles)
    audience(notification_operator, "notification-service")
    role_claim(notification_operator, "notification-service")
    payment_client = machine("fincore-payment", "PAYMENT_CLIENT_SECRET", exchange=True)
    assign(payment_client, resource, [ledger_roles[name] for name in ("ledger.execute", "ledger.instructions.read")])
    assign(payment_client, account_resource, [account_lookup])
    # Exchange scope can carry the subject's prepare roles, without granting them to the worker.
    api("POST", f"/clients/{payment_client}/scope-mappings/clients/{resource}", [ledger_roles[name] for name in ("ledger.prepare", "ledger.prepare.fund", "ledger.prepare.reverse")])
    api("POST", f"/clients/{payment_client}/scope-mappings/realm", [api("GET", "/roles/CUSTOMER")])
    audience(payment_client, "ledger-service")
    audience(payment_client, "account-service")
    for client_id in ("ledger-service", "account-service"):
        role_claim(payment_client, client_id)
    operator = machine("fincore-payment-operator", "PAYMENT_OPERATOR_SECRET")
    assign(operator, payment_resource, operator_roles)
    assign(operator, resource, [ledger_roles[name] for name in ("ledger.prepare.fund", "ledger.prepare.reverse")])
    audience(operator, "payment-service")
    audience(operator, "fincore-payment")
    for client_id in ("ledger-service", "payment-service"):
        role_claim(operator, client_id)
    for browser in ("fincore-customer", "fincore-admin"):
        target = api("GET", "/clients?clientId=" + browser)[0]["id"]
        audience(target, "fincore-payment")
    fraud_resource = client("fraud-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    fraud_assess = ensure_role(fraud_resource, "fraud.assess")
    fraud_review = ensure_role(fraud_resource, "fraud.review")
    fraud_validate = ensure_role(fraud_resource, "fraud.validate")
    ledger_risk_client = machine("fincore-ledger-risk", "LEDGER_RISK_CLIENT_SECRET")
    assign(ledger_risk_client, fraud_resource, [fraud_validate])
    audience(ledger_risk_client, "fraud-service")
    role_claim(ledger_risk_client, "fraud-service")
    assign(payment_client, fraud_resource, [fraud_assess])
    audience(payment_client, "fraud-service")
    role_claim(payment_client, "fraud-service")
    assign(operator, fraud_resource, [fraud_review])
    audience(operator, "fraud-service")
    role_claim(operator, "fraud-service")
    fraud_client = machine("fincore-fraud", "FRAUD_CLIENT_SECRET")
    card_resource = client("card-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    card_risk = ensure_role(card_resource, "card.risk.read")
    assign(fraud_client, card_resource, [card_risk])
    audience(fraud_client, "card-service")
    role_claim(fraud_client, "card-service")
    for name, secret, worker_roles, delegated in [
            ("card", "CARD_CLIENT_SECRET", ["ledger.card.inspect", "ledger.card.reserve"], "ledger.card.manage"),
            ("reporting", "REPORTING_CLIENT_SECRET", ["ledger.reconcile.read"], "ledger.statement.read")]:
        target = machine("fincore-" + name, secret, exchange=True)
        assign(target, resource, [ledger_roles[r] for r in worker_roles])
        assign(target, account_resource, [account_lookup])
        api("POST", "/roles/CUSTOMER/composites", [ledger_roles[delegated]])
        api("POST", f"/clients/{target}/scope-mappings/clients/{resource}", [ledger_roles[delegated]])
        api("POST", f"/clients/{target}/scope-mappings/realm", [api("GET", "/roles/CUSTOMER")])
        for resource_name in ("ledger-service", "account-service"):
            audience(target, resource_name)
            role_claim(target, resource_name)
        for browser in ("fincore-customer", "fincore-admin"):
            audience(api("GET", "/clients?clientId=" + browser)[0]["id"], "fincore-" + name)
        if name == "card":
            assign(target, fraud_resource, [ensure_role(fraud_resource, "fraud.card.assess")])
            audience(target, "fraud-service")
            role_claim(target, "fraud-service")
    finance_users = api("GET", "/users?username=finance.demo&exact=true")
    if not finance_users:
        api("POST", "/users", {"username": "finance.demo", "enabled": True, "emailVerified": True,
            "email": "finance@example.invalid", "firstName": "Synthetic", "lastName": "Finance",
            "credentials": [{"type": "password", "value": values["FINANCE_PASSWORD"], "temporary": False}]})
        finance_users = api("GET", "/users?username=finance.demo&exact=true")
    api("POST", f"/users/{finance_users[0]['id']}/role-mappings/realm", [api("GET", "/roles/FINANCE_OPERATOR")])
    customer_resource = client("customer-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    beneficiary_resource = client("beneficiary-service", {"protocol": "openid-connect", "bearerOnly": True, "enabled": True})
    for target, client_id, scope in [(payment_resource, "payment-service", "payment.risk.read"),
            (customer_resource, "customer-service", "customer.risk.read"),
            (beneficiary_resource, "beneficiary-service", "beneficiary.risk.read")]:
        assign(fraud_client, target, [ensure_role(target, scope)])
        audience(fraud_client, client_id)
        role_claim(fraud_client, client_id)
    analysts = api("GET", "/users?username=analyst.demo&exact=true")
    if not analysts:
        api("POST", "/users", {"username": "analyst.demo", "enabled": True, "emailVerified": True,
            "email": "analyst@example.invalid", "firstName": "Synthetic", "lastName": "Analyst",
            "credentials": [{"type": "password", "value": values["FRAUD_ANALYST_PASSWORD"], "temporary": False}]})
        analysts = api("GET", "/users?username=analyst.demo&exact=true")
    api("POST", f"/users/{analysts[0]['id']}/role-mappings/realm", [api("GET", "/roles/FRAUD_ANALYST")])
    users = api("GET", "/users?username=recipient.demo&exact=true")
    if not users:
        api("POST", "/users", {"username": "recipient.demo", "enabled": True, "emailVerified": True,
            "email": "recipient@example.invalid", "firstName": "Synthetic", "lastName": "Recipient",
            "credentials": [{"type": "password", "value": values["RECIPIENT_PASSWORD"], "temporary": False}]})
        users = api("GET", "/users?username=recipient.demo&exact=true")
    api("POST", f"/users/{users[0]['id']}/role-mappings/realm", [api("GET", "/roles/CUSTOMER")])
    print("Business identities, Card/Reporting delegation and bounded service scopes configured.")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, KeyError):
        raise SystemExit("Business identity configuration failed; check Keycloak readiness and local configuration.") from None
