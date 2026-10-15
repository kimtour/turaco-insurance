import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.schema import ClaimExtraction, ExtractionRequest
from app.providers.mock import MockProvider

DATA = json.loads((Path(__file__).resolve().parents[3] / "datasets/eval/hospital-cash.json").read_text())


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", "synthetic-test-token")
    monkeypatch.setenv("AI_PROVIDER", "mock")
    return TestClient(app)


HEADERS = {"Authorization": "Bearer synthetic-test-token"}


@pytest.mark.parametrize("sample", DATA, ids=lambda s: s["id"])
def test_synthetic_regression(client, sample):
    response = client.post("/v1/extract-claim", headers=HEADERS,
                           json={"transcript": sample["transcript"], "language": sample["language"]})
    assert response.status_code == 200
    data = response.json()
    for field, expected in sample["expected"].items():
        assert data[field]["value"] == expected
        assert data[field]["status"] == ("missing" if expected is None else "extracted")
        if expected is not None:
            assert data[field]["evidence_span"] in sample["transcript"]
    assert data["requires_confirmation"] is True


def test_auth_and_unsupported_language(client):
    assert client.post("/v1/extract-claim", json={}).status_code == 401
    assert client.post("/v1/extract-claim", headers=HEADERS, json={"transcript": "test", "language": "lg-UG"}).status_code == 422


def test_real_provider_is_explicitly_disabled(client, monkeypatch):
    monkeypatch.setenv("AI_PROVIDER", "openai")
    monkeypatch.delenv("REAL_PROVIDER_ENABLED", raising=False)
    assert client.post("/v1/extract-claim", headers=HEADERS, json={"transcript": "synthetic", "language": "en-KE"}).status_code == 503


def test_invented_evidence_and_confirmation_are_downgraded(client):
    original = MockProvider.extract

    async def malicious(self, request):
        result = await original(self, request)
        result.hospital_name.status = "confirmed"
        result.hospital_name.evidence_span = "invented quote"
        result.incident_type_code.status = "confirmed"
        return result

    with patch.object(MockProvider, "extract", malicious):
        data = client.post("/v1/extract-claim", headers=HEADERS, json={k: DATA[0][k] for k in ("transcript", "language")}).json()
    assert data["hospital_name"]["status"] == "unverified"
    assert data["incident_type_code"]["status"] == "extracted"


def test_provider_failure_is_redacted(client):
    with patch.object(MockProvider, "extract", AsyncMock(side_effect=ValueError("secret patient content"))):
        response = client.post("/v1/extract-claim", headers=HEADERS, json={"transcript": "synthetic", "language": "en-KE"})
    assert response.status_code == 502
    assert "secret" not in response.text


def test_openai_adapter_uses_structured_parse_without_live_request(client, monkeypatch):
    import asyncio
    from app.providers.openai_provider import OpenAIProvider
    monkeypatch.setenv("OPENAI_MODEL", "synthetic-model-under-test")
    request = ExtractionRequest(**{k: DATA[0][k] for k in ("transcript", "language")})
    result = asyncio.run(MockProvider().extract(request))
    sdk = MagicMock()
    sdk.responses.parse = AsyncMock(return_value=MagicMock(output_parsed=result))
    context = MagicMock()
    context.__aenter__ = AsyncMock(return_value=sdk)
    context.__aexit__ = AsyncMock(return_value=False)
    with patch("app.providers.openai_provider.AsyncOpenAI", return_value=context):
        assert asyncio.run(OpenAIProvider().extract(request)) == result
    kwargs = sdk.responses.parse.call_args.kwargs
    assert kwargs["store"] is False
    assert kwargs["text_format"] is ClaimExtraction
