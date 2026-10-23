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
    ledger_roles = {name: ensure_role(resource, name) for name in ("ledger.prepare", "ledger.prepare.fund", "ledger.prepare.reverse", "ledger.execute", "ledger.instructions.read", "ledger.provision", "ledger.guard", "ledger.read")}
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
    users = api("GET", "/users?username=recipient.demo&exact=true")
    if not users:
        api("POST", "/users", {"username": "recipient.demo", "enabled": True, "emailVerified": True,
            "email": "recipient@example.invalid", "firstName": "Synthetic", "lastName": "Recipient",
            "credentials": [{"type": "password", "value": values["RECIPIENT_PASSWORD"], "temporary": False}]})
        users = api("GET", "/users?username=recipient.demo&exact=true")
    api("POST", f"/users/{users[0]['id']}/role-mappings/realm", [api("GET", "/roles/CUSTOMER")])
    print("Account/Payment identities, bounded scopes and standard token exchange configured.")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, KeyError):
        raise SystemExit("Business identity configuration failed; check Keycloak readiness and local configuration.") from None
