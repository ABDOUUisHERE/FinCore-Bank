"""Print bounded Compose diagnostics after redacting the generated local secrets."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
secret_values = []
env_path = root / ".env"
if env_path.exists():
    secret_values.extend(line.split("=", 1)[1] for line in env_path.read_text().splitlines() if "=" in line and not line.startswith("#"))
credentials_path = root / ".local/credentials.json"
if credentials_path.exists():
    secret_values.extend(user["password"] for user in json.loads(credentials_path.read_text()).values())

for arguments in [["ps", "-a"], ["logs", "--no-color", "--tail", "100", "realm-config", "keycloak", "postgres", "customer-migrations", "customer-service", "api-gateway", "ledger-bootstrap", "ledger-migrations", "ledger-identity", "ledger-service", "business-bootstrap", "business-identity", "account-migrations", "account-service", "payment-migrations", "payment-service", "beneficiary-bootstrap", "beneficiary-migrations", "beneficiary-service", "notification-bootstrap", "notification-migrations", "notification-service", "fraud-bootstrap", "fraud-migrations", "fraud-service", "kafka", "kafka-init"]]:
    result = subprocess.run(["docker", "compose", *arguments], cwd=root, capture_output=True, text=True, timeout=30)
    output = result.stdout + result.stderr
    for secret in secret_values:
        if secret:
            output = output.replace(secret, "[REDACTED]")
    print(output)
