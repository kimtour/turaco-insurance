#!/usr/bin/env python3
"""Synthetic end-to-end demo using real local Keycloak authentication. No live funds."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
API = os.environ.get("API_URL", "http://localhost:8080")
AI = os.environ.get("AI_URL", "http://localhost:8000")
OIDC = os.environ.get("OIDC_URL", "http://localhost:8081/realms/turaco-local")


def local_env():
    path = ROOT / ".env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#") and "=" in line) if path.exists() else {}
    return {**values, **os.environ}


def request(url, body=None, token=None, key=None, form=False, expected=200):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    data = None
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=40) as response:
            assert response.status == expected, f"Unexpected status from {url}: {response.status}"
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == expected:
            return None
        raise RuntimeError(f"Demo request failed at {url} with status {error.code}") from None


def wait_for(url):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            request(url)
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError(f"Service did not become ready: {url}")


def login(username):
    return request(OIDC + "/protocol/openid-connect/token", {
        "grant_type": "password", "client_id": "claims-local", "username": username,
        "password": "local-demo-only"}, form=True)["access_token"]


def main():
    for url in (API + "/actuator/health", AI + "/health", OIDC + "/.well-known/openid-configuration"):
        wait_for(url)
    values = local_env()
    samples = json.loads((ROOT / "datasets/eval/hospital-cash.json").read_text())
    token = login("approver")
    reviewer = login("reviewer")
    result_ids = []
    for sample in samples[:3]:
        extraction = request(AI + "/v1/extract-claim", {"transcript": sample["transcript"], "language": sample["language"]}, values["AI_SERVICE_TOKEN"])
        body = {"policy_id": "44444444-4444-4444-4444-444444444444", "extraction": extraction}
        key = "demo:" + str(uuid.uuid4())
        claim = request(API + "/api/v1/claims", body, token, key, expected=201)
        duplicate = request(API + "/api/v1/claims", body, token, key, expected=201)
        assert duplicate["id"] == claim["id"]
        base = API + "/api/v1/claims/" + claim["id"]
        request(base + "/submissions", {}, token, expected=409)
        ref = "synthetic:" + sample["id"]
        request(base + "/confirmations", {"fields": ["incident_type_code", "hospital_name", "admission_date", "discharge_date"], "evidence_reference": ref}, token)
        request(base + "/verifications", {"evidence_reference": ref, "payee_verified": True}, token)
        request(base + "/submissions", {}, token)
        request(base + "/reviews", {"decision": "RECOMMEND_APPROVAL", "reason_code": "SYNTHETIC_TERMS_MET", "evidence_reference": ref}, reviewer)
        request(base + "/approvals", {"evidence_reference": ref}, reviewer, expected=403)
        approved = request(base + "/approvals", {"evidence_reference": ref}, token)
        again = request(base + "/approvals", {"evidence_reference": ref}, token)
        assert approved["obligation_id"] == again["obligation_id"]
        paid = request(base + "/simulated-payouts", {"outcome": "CONFIRMED"}, token)
        duplicate_paid = request(base + "/simulated-payouts", {"outcome": "CONFIRMED"}, token)
        assert paid["obligation_id"] == duplicate_paid["obligation_id"]
        assert paid["payout_status"] == "CONFIRMED" and float(paid["amount"]) == 4500
        audit = request(base + "/audit", token=token)
        assert any(event["event_type"] == "APPROVED_BY_HUMAN" for event in audit)
        assert any(event["event_type"] == "SIMULATED_PAYOUT_CONFIRMED" for event in audit)
        result_ids.append(claim["id"])
        print(f"PASS {sample['id']}: confirmed simulated KES 4,500; {len(audit)} audit events; claim {claim['id']}")
    print("Synthetic demo passed. No money was transferred. Open http://localhost:5173.")
    return result_ids


if __name__ == "__main__":
    main()
