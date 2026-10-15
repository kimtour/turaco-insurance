"""Deterministic fixture parser. This is not a language model or an ASR claim."""
import re
from app.schema import ClaimExtraction, Extracted, ExtractionRequest, FieldStatus


class MockProvider:
    async def extract(self, request: ExtractionRequest) -> ClaimExtraction:
        text = request.transcript

        def field(pattern: str, cast=str):
            match = re.search(pattern, text, re.IGNORECASE)
            if not match:
                return Extracted(value=None, status=FieldStatus.MISSING, confidence=None, evidence_span=None, source=None)
            try:
                value = cast(match.group(1))
            except ValueError:
                return Extracted(value=None, status=FieldStatus.MISSING, confidence=None, evidence_span=None, source=None)
            return Extracted(value=value, status=FieldStatus.EXTRACTED, confidence=None,
                             evidence_span=match.group(0), source="message")

        incident = field(r"(hospitalization|kulazwa)")
        if incident.value is not None:
            incident.value = "HOSPITALIZATION"
        return ClaimExtraction(
            schema_version="2", language=request.language,
            incident_type_code=incident,
            hospital_name=field(r"(?:hospital|hospitali): ([^.\n]{1,100})"),
            admission_date=field(r"(?:admitted|nililazwa) (\d{4}-\d{2}-\d{2})"),
            discharge_date=field(r"(?:discharged|niliruhusiwa) (\d{4}-\d{2}-\d{2})"),
            hospital_nights=Extracted(value=None, status=FieldStatus.MISSING, confidence=None, evidence_span=None, source=None),
            requires_confirmation=True,
        )
