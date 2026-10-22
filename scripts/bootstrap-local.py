"""Generate ignored local configuration. Never print credentials or overwrite a dataset's secrets."""
import json
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]


def write_private(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    if os.name != "nt":
        path.chmod(0o600)


def main() -> None:
    env_path = ROOT / ".env"
    credentials_path = ROOT / ".local/credentials.json"
    realm_path = ROOT / ".local/realm/fincore-realm.json"
    if any(p.exists() for p in (env_path, credentials_path, realm_path)):
        if all(p.exists() for p in (env_path, credentials_path, realm_path)):
            print("Existing local configuration retained. Credentials: .local/credentials.json")
            return
        raise SystemExit("Incomplete local configuration. Restore its matching files; do not rotate secrets against an existing database volume.")
    values = {key: secrets.token_hex(24) for key in (
        "POSTGRES_PASSWORD", "CUSTOMER_DB_PASSWORD", "CUSTOMER_MIGRATION_PASSWORD",
        "KEYCLOAK_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
    )}
    credentials = {name: {"username": f"{name}.demo", "password": secrets.token_hex(16)} for name in ("customer", "admin")}
    template = json.loads((ROOT / "infrastructure/keycloak/fincore-realm.template.json").read_text(encoding="utf-8"))
    template["users"] = [{
        "username": data["username"], "enabled": True, "emailVerified": True,
        "email": f"{name}@example.invalid", "firstName": "Synthetic", "lastName": name.title(),
        "realmRoles": [name.upper()],
        "credentials": [{"type": "password", "value": data["password"], "temporary": False}],
    } for name, data in credentials.items()]
    write_private(env_path, "".join(f"{key}={value}\n" for key, value in values.items()))
    write_private(credentials_path, json.dumps(credentials, indent=2) + "\n")
    write_private(realm_path, json.dumps(template, indent=2) + "\n")
    print("Generated .env and local realm. Synthetic user credentials: .local/credentials.json")


if __name__ == "__main__":
    main()
