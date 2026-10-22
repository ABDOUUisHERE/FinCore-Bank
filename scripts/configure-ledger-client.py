"""Configure the local technical ledger client on fresh AND persisted realms; no secrets printed."""
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
    roles = []
    for name in ("ledger.provision", "ledger.guard", "ledger.post", "ledger.fund", "ledger.reverse", "ledger.read"):
        current = api("GET", f"/clients/{resource}/roles")
        if not any(role["name"] == name for role in current):
            api("POST", f"/clients/{resource}/roles", {"name": name})
        roles.append(api("GET", f"/clients/{resource}/roles/{name}"))
    operator = client("fincore-ledger-operator", {"protocol": "openid-connect", "enabled": True,
        "publicClient": False, "secret": values["LEDGER_CLIENT_SECRET"], "serviceAccountsEnabled": True,
        "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "fullScopeAllowed": False})
    account = api("GET", f"/clients/{operator}/service-account-user")
    api("POST", f"/users/{account['id']}/role-mappings/clients/{resource}", roles)
    api("POST", f"/clients/{operator}/scope-mappings/clients/{resource}", roles)
    mappers = [
        {"name": "ledger-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper", "config": {"included.custom.audience": "ledger-service", "access.token.claim": "true", "id.token.claim": "false"}},
        {"name": "ledger-roles", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-client-role-mapper", "config": {"usermodel.clientRoleMapping.clientId": "ledger-service", "claim.name": "resource_access.ledger-service.roles", "jsonType.label": "String", "multivalued": "true", "access.token.claim": "true", "id.token.claim": "false"}},
    ]
    existing = api("GET", f"/clients/{operator}/protocol-mappers/models")
    for mapper in mappers:
        match = next((item for item in existing if item["name"] == mapper["name"]), None)
        if match:
            api("PUT", f"/clients/{operator}/protocol-mappers/models/{match['id']}", {"id": match["id"], **mapper})
        else:
            api("POST", f"/clients/{operator}/protocol-mappers/models", mapper)
    print("Ledger technical client configured; public browser clients unchanged.")


if __name__ == "__main__":
    try:
        main()
    except (urllib.error.URLError, KeyError):
        raise SystemExit("Ledger identity configuration failed; check Keycloak readiness and local configuration.") from None
