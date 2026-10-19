import hmac
import os
from fastapi import Depends, FastAPI, Header, HTTPException
from app.providers.mock import MockProvider
from app.schema import ClaimExtraction, ExtractionRequest, ModelTrace, enforce_provenance
from app.providers.registry import provider as selected_provider
from app.workflow import Turn, turn
from app.document import DocumentCheck, verify
from app.observability import trace_extraction, trace_task, extraction_metadata
from app.billing import Profile, propose

app = FastAPI(title="Turaco synthetic extraction", version="0.2.0")


def authorize(authorization: str = Header(default="")):
    token = os.environ.get("AI_SERVICE_TOKEN", "")
    if not token or not hmac.compare_digest(authorization, "Bearer " + token):
        raise HTTPException(status_code=401, detail="Service authentication required")


@app.get("/health")
def health():
    return {"status": "ok", "provider": os.environ.get("AI_PROVIDER", "mock"), "data": "synthetic_only"}


@app.get("/v1/capabilities", dependencies=[Depends(authorize)])
def capabilities():
    return {"evaluation_version": "synthetic-fixtures-v1", "capabilities": [
        {"country": "KE", "language": language, "modality": "text_understanding",
         "status": "synthetic_fixture_only", "production_enabled": False}
        for language in ("en-KE", "sw-KE")
    ], "asr": "disabled", "fallback": "authorized_human_assistance"}


@app.post("/v1/extract-claim", response_model=ClaimExtraction, dependencies=[Depends(authorize)])
async def extract(request: ExtractionRequest, x_correlation_id: str = Header(default="")):
    provider = selected_provider()
    try:
        result = await provider.extract(request)
        if result.language != request.language:
            raise ValueError("Provider language mismatch")
        result = enforce_provenance(result, request.transcript, request.incident_catalog)
        from uuid import UUID
        result.model_trace = ModelTrace.model_validate(extraction_metadata(str(UUID(x_correlation_id)) if x_correlation_id else ""))
        trace_task("claim-extraction", result.model_trace.model_dump(exclude_none=True))
        missing = sum(getattr(result, field).value is None for field in ("hospital_name", "incident_type_code", "admission_date", "discharge_date"))
        trace_extraction(request.language, os.environ.get("AI_PROVIDER", "mock"), missing)
        return result
    except Exception:
        # Do not expose provider exceptions: they may contain medical text or credentials.
        raise HTTPException(status_code=502, detail="Extraction unavailable; route to human assistance") from None


@app.post("/v1/conversations/turn", dependencies=[Depends(authorize)])
async def conversation(request: Turn):
    try:
        return await turn(request)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(502, "Conversation unavailable; route to human assistance") from None


@app.post("/v1/verify-document", dependencies=[Depends(authorize)])
async def document(request: DocumentCheck):
    return await verify(request)


@app.post("/v1/transcribe", dependencies=[Depends(authorize)])
def transcribe():
    raise HTTPException(422, "No production-validated speech capability. Use the local fixture-transcript simulator or human assistance.")


@app.post("/v1/billing/propose-mapping", dependencies=[Depends(authorize)])
async def mapping(request: Profile, x_correlation_id: str = Header(default="")):
    try:
        from uuid import UUID
        result = await propose(request)
        if x_correlation_id:
            result["correlation_id"] = str(UUID(x_correlation_id))
        trace_task("partner-mapping", result)
        return result
    except Exception:
        raise HTTPException(502, "Mapping unavailable; retain the batch for human review or safe resumption") from None
