"""Check public health and direct backend rejection without exposing credentials."""
import json
import subprocess
import urllib.error
import urllib.request


def main() -> None:
    for port, path in [(8080, "/actuator/health/readiness"), (5173, "/health"), (5174, "/health")]:
        with urllib.request.urlopen(f"http://localhost:{port}{path}", timeout=10) as response:
            assert response.status == 200
    try:
        urllib.request.urlopen("http://localhost:8080/api/v1/customers/me", timeout=10)
        raise AssertionError("Unauthenticated gateway request unexpectedly succeeded")
    except urllib.error.HTTPError as error:
        assert error.code == 401
        assert json.load(error)["status"] == 401
    result = subprocess.run([
        "docker", "compose", "exec", "-T", "api-gateway", "wget", "-S", "-O", "-",
        "http://customer-service:8080/api/v1/customers/me",
    ], capture_output=True, text=True, timeout=20)
    assert result.returncode != 0 and "401" in result.stderr, "Direct customer API must reject unauthenticated traffic"
    print("PASS: gateway/portal readiness and independent direct Customer authentication")


if __name__ == "__main__":
    main()
