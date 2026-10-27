"""Generate ignored local configuration. Never print credentials or overwrite a dataset's secrets."""
import json
import os
from pathlib import Path
import secrets

ROOT = Path(__file__).resolve().parents[1]
LEDGER_KEYS = ("LEDGER_DB_PASSWORD", "LEDGER_MIGRATION_PASSWORD", "LEDGER_CLIENT_SECRET",
    "ACCOUNT_DB_PASSWORD", "ACCOUNT_MIGRATION_PASSWORD", "ACCOUNT_CLIENT_SECRET",
    "PAYMENT_DB_PASSWORD", "PAYMENT_MIGRATION_PASSWORD", "PAYMENT_CLIENT_SECRET", "PAYMENT_OPERATOR_SECRET", "RECIPIENT_PASSWORD",
    "BENEFICIARY_DB_PASSWORD", "BENEFICIARY_MIGRATION_PASSWORD", "BENEFICIARY_CLIENT_SECRET", "NOTIFICATION_DB_PASSWORD", "NOTIFICATION_MIGRATION_PASSWORD", "NOTIFICATION_CLIENT_SECRET", "NOTIFICATION_OPERATOR_SECRET", "KAFKA_BROKER_PASSWORD", "KAFKA_ADMIN_PASSWORD", "KAFKA_CUSTOMER_PASSWORD", "KAFKA_ACCOUNT_PASSWORD", "KAFKA_LEDGER_PASSWORD", "KAFKA_PAYMENT_PASSWORD", "KAFKA_NOTIFICATION_PASSWORD", "FRAUD_DB_PASSWORD", "FRAUD_MIGRATION_PASSWORD", "FRAUD_CLIENT_SECRET", "FRAUD_ANALYST_PASSWORD", "KAFKA_FRAUD_PASSWORD", "LEDGER_RISK_CLIENT_SECRET")


def write_private(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    if os.name != "nt":
        path.chmod(0o600)


def configure_kafka(env_path: Path) -> None:
    values = dict(line.split("=", 1) for line in env_path.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
    users = ("broker", "admin", "customer", "account", "ledger", "payment", "notification", "fraud")
    options = [f'username="broker"', f'password="{values["KAFKA_BROKER_PASSWORD"]}"']
    options += [f'user_{user}="{values["KAFKA_"+user.upper()+"_PASSWORD"]}"' for user in users]
    write_private(ROOT / ".local/kafka/server-jaas.conf", 'KafkaServer { org.apache.kafka.common.security.plain.PlainLoginModule required ' + " ".join(options) + '; };\n')
    for user in users:
        body = 'security.protocol=SASL_PLAINTEXT\nsasl.mechanism=PLAIN\nsasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="' + user + '" password="' + values["KAFKA_"+user.upper()+"_PASSWORD"] + '";\n'
        write_private(ROOT / (".local/kafka/"+user+".properties"),body)


def main() -> None:
    env_path = ROOT / ".env"
    credentials_path = ROOT / ".local/credentials.json"
    realm_path = ROOT / ".local/realm/fincore-realm.json"
    if any(p.exists() for p in (env_path, credentials_path, realm_path)):
        if all(p.exists() for p in (env_path, credentials_path, realm_path)):
            content = env_path.read_text(encoding="utf-8")
            existing = {line.split("=", 1)[0] for line in content.splitlines() if "=" in line}
            missing = [key for key in LEDGER_KEYS if key not in existing]
            if missing:
                write_private(env_path, content.rstrip() + "\n" + "".join(f"{key}={secrets.token_hex(24)}\n" for key in missing))
                print("Added missing service configuration; existing secrets and realm retained.")
            credentials = json.loads(credentials_path.read_text(encoding="utf-8"))
            if "recipient" not in credentials:
                env_values = dict(line.split("=", 1) for line in env_path.read_text().splitlines() if "=" in line and not line.startswith("#"))
                credentials["recipient"] = {"username": "recipient.demo", "password": env_values["RECIPIENT_PASSWORD"]}
                write_private(credentials_path, json.dumps(credentials, indent=2) + "\n")
            if "analyst" not in credentials:
                env_values = dict(line.split("=", 1) for line in env_path.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
                credentials["analyst"] = {"username": "analyst.demo", "password": env_values["FRAUD_ANALYST_PASSWORD"]}
                write_private(credentials_path, json.dumps(credentials, indent=2) + "\n")
            print("Existing local configuration retained. Credentials: .local/credentials.json")
            configure_kafka(env_path)
            return
        raise SystemExit("Incomplete local configuration. Restore its matching files; do not rotate secrets against an existing database volume.")
    values = {key: secrets.token_hex(24) for key in (
        "POSTGRES_PASSWORD", "CUSTOMER_DB_PASSWORD", "CUSTOMER_MIGRATION_PASSWORD",
        "KEYCLOAK_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", *LEDGER_KEYS,
    )}
    credentials = {name: {"username": f"{name}.demo", "password": secrets.token_hex(16)} for name in ("customer", "admin")}
    credentials["recipient"] = {"username": "recipient.demo", "password": values["RECIPIENT_PASSWORD"]}
    credentials["analyst"] = {"username": "analyst.demo", "password": values["FRAUD_ANALYST_PASSWORD"]}
    template = json.loads((ROOT / "infrastructure/keycloak/fincore-realm.template.json").read_text(encoding="utf-8"))
    template["users"] = [{
        "username": data["username"], "enabled": True, "emailVerified": True,
        "email": f"{name}@example.invalid", "firstName": "Synthetic", "lastName": name.title(),
        "realmRoles": ["ADMIN" if name == "admin" else "FRAUD_ANALYST" if name == "analyst" else "CUSTOMER"],
        "credentials": [{"type": "password", "value": data["password"], "temporary": False}],
    } for name, data in credentials.items()]
    write_private(env_path, "".join(f"{key}={value}\n" for key, value in values.items()))
    write_private(credentials_path, json.dumps(credentials, indent=2) + "\n")
    write_private(realm_path, json.dumps(template, indent=2) + "\n")
    configure_kafka(env_path)
    print("Generated .env and local realm. Synthetic user credentials: .local/credentials.json")


if __name__ == "__main__":
    main()
