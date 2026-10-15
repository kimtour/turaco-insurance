#!/usr/bin/env python3
"""All six local phases: real services, signed callbacks, synthetic fixtures only."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timezone, timedelta
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import demo

API = demo.API
SIM = os.environ.get("SIMULATOR_URL", "http://localhost:9000")
POLICY = "44444444-4444-4444-4444-444444444444"
TENANT = "11111111-1111-1111-1111-111111111111"
TEXT = "Synthetic hospitalization. Hospital: Demo Nairobi Hospital. Admitted 2026-09-01 and discharged 2026-09-04."


def signed_webhook(body, secret, expected=200):
    import urllib.request
    import urllib.error
    raw = json.dumps(body).encode()
    signature = "sha256=" + hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
    request = urllib.request.Request(API + "/webhooks/whatsapp", data=raw,
        headers={"Content-Type":"application/json", "X-Hub-Signature-256":signature})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            assert response.status == expected
            return json.load(response)
    except urllib.error.HTTPError as error:
        assert error.code == expected, f"Webhook returned {error.code}, expected {expected}"


def main():
    demo.wait_for(API + "/actuator/health")
    demo.wait_for(SIM + "/health")
    values = demo.local_env()
    token, reviewer = demo.login("approver"), demo.login("reviewer")
    def api(path, body=None, identity=token, expected=200, key=None):
        return demo.request(API+"/api/v1"+path,body,identity,key,expected=expected)
    def post(path, body=None, identity=token, expected=200):
        return api(path,{} if body is None else body,identity,expected)
    conversation=str(uuid4())
    first=post(f"/conversations/{conversation}/turns",{"text":TEXT.split(" and discharged")[0],"language":"en-KE"})
    assert first["route"]=="needs_information"
    second=post(f"/conversations/{conversation}/turns",{"text":"Discharged 2026-09-04.","language":"en-KE"})
    assert second["route"]=="needs_customer_confirmation"
    print("PASS durable conversation follows up and preserves the first turn")

    message="wamid:full:"+str(uuid4())
    envelope={"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"SYNTHETIC_NUMBER"},"messages":[{"id":message,"from":"254700000000","type":"text","text":{"body":TEXT}}]}}]}]}
    signed_webhook(envelope,"invalid-secret",401)
    assert signed_webhook(envelope,values["WHATSAPP_APP_SECRET"])["accepted"]==1
    assert signed_webhook(envelope,values["WHATSAPP_APP_SECRET"])["duplicates"]==1
    receipt=next(r for r in api("/channels/whatsapp/messages") if r["message_id"]==message)
    claim=post(f"/channels/whatsapp/messages/{receipt['id']}/intakes",{"policy_id":POLICY,"language":"en-KE"})
    assert post(f"/channels/whatsapp/messages/{receipt['id']}/intakes",{"policy_id":POLICY,"language":"en-KE"})["id"]==claim["id"]
    base="/claims/"+claim["id"]
    post(base+"/submissions",expected=409)
    risk=post(base+"/risk-assessments")
    assert risk["route"]=="HUMAN_REVIEW" and risk["automatic_decision"] is False
    check=post(base+"/evidence",{"synthetic_text":TEXT})
    assert check["encrypted"] and check["verification_status"]=="MATCH"
    post(base+"/confirmations",{"fields":["incident_type_code","hospital_name","admission_date","discharge_date"],"evidence_reference":"synthetic:customer-confirmation"})
    post(base+"/verifications",{"payee_verified":True,"evidence_reference":"synthetic:independent-assisted-verification"})
    post(base+"/provider-submissions")
    post(base+"/provider-submissions")
    post(base+"/reviews",{"decision":"RECOMMEND_APPROVAL","reason_code":"SYNTHETIC_TERMS_MET","evidence_reference":check["id"]},reviewer)
    post(base+"/approvals",{"evidence_reference":"synthetic:approval"},expected=409)
    provider_url=SIM+"/sim/v1/claims/by-internal/"+claim["id"]+"/status?tenant_id="+TENANT+"&country_code=KE"
    snapshot=demo.request(provider_url,{"status":"APPROVED"},values["SIMULATOR_TOKEN"])
    event={"tenant_id":TENANT,"country_code":"KE","internal_claim_id":claim["id"],"event_id":snapshot["event_id"],"revision":1}
    demo.request(API+"/webhooks/turaco-simulator",event,"wrong-token",expected=401)
    assert demo.request(API+"/webhooks/turaco-simulator",event,values["PROVIDER_CALLBACK_TOKEN"])["status"]=="RECONCILED"
    assert demo.request(API+"/webhooks/turaco-simulator",event,values["PROVIDER_CALLBACK_TOKEN"])["status"]=="DUPLICATE"
    assert api(base)["provider_revision"]==2
    post(base+"/approvals",{"evidence_reference":"synthetic:approval"},reviewer,403)
    approved=post(base+"/approvals",{"evidence_reference":"synthetic:human-authorization"})
    assert float(approved["amount"])==4500
    post(base+"/simulated-payouts",{"outcome":"UNKNOWN"})
    post(base+"/simulated-payouts",{"outcome":"CONFIRMED"},expected=409)
    post(base+"/mock-provider-receipts",{"outcome":"CONFIRMED","evidence_reference":"synthetic:provider-confirmation"})
    assert post(base+"/payout-reconciliations")["payout_status"]=="CONFIRMED"
    assert post(base+"/payout-reconciliations")["obligation_id"]==approved["obligation_id"]
    audit=api(base+"/audit")
    assert any(x["event_type"]=="PAYOUT_RECONCILED" for x in audit)
    print("PASS signed channel, encrypted evidence, provider ownership, callback replay and payout reconciliation")

    notification={"claim_id":claim["id"],"language":"sw-KE","template_key":"claim_updated",
        "last_customer_message":(datetime.now(timezone.utc)-timedelta(hours=25)).isoformat(),"opted_out":False}
    assert post("/notifications/previews",notification)["mode"]=="LOCAL_TEMPLATE_SIMULATOR"
    notification["opted_out"]=True
    assert post("/notifications/previews",notification)["status"]=="SUPPRESSED"
    assert "delivered" in post("/operations/drain")
    assert "unknown_payouts" in api("/operations/metrics")
    print("PASS service-window template routing, opt-out and local queue operations")

    response=demo.request(demo.AI+"/v1/transcribe",{},values["AI_SERVICE_TOKEN"],expected=422)
    assert response is None
    print("PASS unsupported speech fails explicitly; no fabricated transcription")
    print("ALL SIX LOCAL PHASES PASSED. No real provider, medical data, notification or money transfer was used.")
    return claim["id"]


if __name__=="__main__":main()
