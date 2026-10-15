from typing import Literal
from pydantic import BaseModel, Field
from app.schema import ClaimExtraction, ExtractionRequest
from app.providers.mock import MockProvider


class EvidenceExtraction(ClaimExtraction):
    requires_confirmation: bool


class DocumentCheck(BaseModel):
    synthetic_text: str = Field(min_length=1, max_length=20000)
    language: Literal["en-KE", "sw-KE"]
    extraction: EvidenceExtraction


async def verify(request: DocumentCheck):
    document = await MockProvider().extract(ExtractionRequest(transcript=request.synthetic_text, language=request.language))
    missing, conflicts = [], []
    for name in ("hospital_name", "admission_date", "discharge_date"):
        observed, expected = getattr(document, name).value, getattr(request.extraction, name).value
        if observed is None or expected is None:
            missing.append(name)
        elif str(observed).casefold() != str(expected).casefold():
            conflicts.append(name)
    return {"engine": "synthetic-text-parser", "production_validated": False,
            "status": "CONFLICT" if conflicts else "UNVERIFIED" if missing else "MATCH",
            "conflicting_fields": conflicts, "missing_fields": missing, "requires_human_review": True}
