import asyncio
import json
from pathlib import Path
from uuid import uuid4
from unittest.mock import AsyncMock, MagicMock, patch
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.simulator import app as simulator
from app.schema import ExtractionRequest
from app.providers.mock import MockProvider

SAMPLE = "Synthetic hospitalization. Hospital: Demo Nairobi Hospital. Admitted 2026-09-01"
HEADERS = {"Authorization": "Bearer synthetic-test-token"}


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "synthetic-test-token")
    monkeypatch.setenv("SIMULATOR_TOKEN", "synthetic-test-token")
    monkeypatch.setenv("AI_PROVIDER", "mock")
    monkeypatch.setenv("CONVERSATION_DB", str(tmp_path / "conversations.db"))
    monkeypatch.setenv("SIMULATOR_DB", str(tmp_path / "provider.db"))
    return TestClient(app)


def test_conversation_recovers_after_reopening_checkpoint_and_is_scoped(client):
    body = {"tenant_id": str(uuid4()), "country_code": "KE", "conversation_id": str(uuid4()),
            "language": "en-KE", "text": SAMPLE}
    first = client.post("/v1/conversations/turn", headers=HEADERS, json=body)
    assert first.status_code == 200, first.text
    assert first.json()["missing_fields"] == ["discharge_date"]
    body["text"] = "Discharged 2026-09-04."
    recovered = TestClient(app).post("/v1/conversations/turn", headers=HEADERS, json=body)
    assert recovered.status_code == 200, recovered.text
    assert recovered.json()["route"] == "needs_customer_confirmation"
    assert recovered.json()["extraction"]["admission_date"]["value"] == "2026-09-01"
    body["tenant_id"] = str(uuid4())
    isolated = client.post("/v1/conversations/turn", headers=HEADERS, json=body).json()
    assert "admission_date" in isolated["missing_fields"]


def test_document_conflicts_and_speech_fallback(client):
    extraction = asyncio.run(MockProvider().extract(ExtractionRequest(transcript=SAMPLE+" and discharged 2026-09-04.", language="en-KE")))
    response = client.post("/v1/verify-document", headers=HEADERS, json={"synthetic_text": SAMPLE+" and discharged 2026-09-06.", "language": "en-KE", "extraction": extraction.model_dump(mode="json")})
    assert response.status_code == 200
    assert response.json()["conflicting_fields"] == ["discharge_date"]
    assert response.json()["production_validated"] is False
    assert client.post("/v1/transcribe", headers=HEADERS).status_code == 422


def test_local_provider_is_durable_idempotent_scoped_and_validates_transitions(client):
    local = TestClient(simulator)
    body = {"tenant_id": str(uuid4()), "country_code": "KE", "internal_claim_id": str(uuid4()), "policy_id": str(uuid4())}
    assert local.post("/sim/v1/claims", json=body).status_code == 401
    first = local.post("/sim/v1/claims", headers=HEADERS, json=body).json()
    second = TestClient(simulator).post("/sim/v1/claims", headers=HEADERS, json=body).json()
    assert first == second
    url = "/sim/v1/claims/by-internal/"+body["internal_claim_id"]
    params = {"tenant_id":body["tenant_id"], "country_code":"KE"}
    approved = local.post(url+"/status", params=params, headers=HEADERS, json={"status":"APPROVED"}).json()
    assert approved["revision"] == 2
    assert local.post(url+"/status", params=params, headers=HEADERS, json={"status":"RECEIVED"}).status_code == 409
    params["tenant_id"] = str(uuid4())
    assert local.get(url, params=params, headers=HEADERS).status_code == 404


def test_anthropic_tool_extraction_without_live_calls(client, monkeypatch):
    from app.providers.anthropic_provider import AnthropicProvider
    monkeypatch.setenv("ANTHROPIC_MODEL", "synthetic-model")
    request = ExtractionRequest(transcript=SAMPLE, language="en-KE")
    result = asyncio.run(MockProvider().extract(request))
    sdk = MagicMock()
    sdk.messages.create = AsyncMock(return_value=MagicMock(content=[MagicMock(type="tool_use", name="extract_claim", input=result.model_dump(mode="json"))]))
    # MagicMock's constructor name is its debug label, so set the provider field explicitly.
    sdk.messages.create.return_value.content[0].name = "extract_claim"
    context = MagicMock()
    context.__aenter__ = AsyncMock(return_value=sdk)
    context.__aexit__ = AsyncMock(return_value=False)
    with patch("app.providers.anthropic_provider.AsyncAnthropic", return_value=context):
        assert asyncio.run(AnthropicProvider().extract(request)) == result
    assert sdk.messages.create.call_args.kwargs["tool_choice"]["name"] == "extract_claim"


def test_telemetry_never_contains_medical_text_or_identifiers(monkeypatch):
    from app.observability import trace_extraction
    monkeypatch.setenv("LANGFUSE_ENABLED", "true")
    exported = MagicMock()
    with patch("langfuse.get_client", return_value=exported):
        trace_extraction("sw-KE", "mock", 1)
    span = exported.start_as_current_observation.return_value.__enter__.return_value
    payload = json.dumps(span.update.call_args.kwargs)
    assert set(span.update.call_args.kwargs["input"]) == {"schema_version", "language", "provider"}
    assert "transcript" not in payload and "hospital" not in payload and "tenant" not in payload
