from datetime import date
from enum import Enum
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class FieldStatus(str, Enum):
    EXTRACTED = "extracted"
    CONFIRMED = "confirmed"
    UNVERIFIED = "unverified"
    MISSING = "missing"


class Extracted(BaseModel, Generic[T]):
    model_config = ConfigDict(extra="forbid")
    value: T | None
    status: FieldStatus
    confidence: float | None = Field(ge=0, le=1)
    evidence_span: str | None
    source: str | None


class ClaimExtraction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["2"]
    language: Literal["en-KE", "sw-KE"]
    incident_type_code: Extracted[str]
    hospital_name: Extracted[str]
    admission_date: Extracted[date]
    discharge_date: Extracted[date]
    hospital_nights: Extracted[int]
    requires_confirmation: Literal[True]


class ExtractionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    transcript: str = Field(min_length=1, max_length=20000)
    language: Literal["en-KE", "sw-KE"]
    incident_catalog: list[Literal["HOSPITALIZATION"]] = Field(default_factory=lambda: ["HOSPITALIZATION"], min_length=1, max_length=1)


def enforce_provenance(result: ClaimExtraction, transcript: str, catalog: list[str]) -> ClaimExtraction:
    """A model cannot attest customer confirmation or invent quoted evidence."""
    for name in ("incident_type_code", "hospital_name", "admission_date", "discharge_date", "hospital_nights"):
        field = getattr(result, name)
        if field.value is None:
            field.status = FieldStatus.MISSING
            field.confidence = None
            field.evidence_span = None
            field.source = None
        elif not field.evidence_span or field.evidence_span not in transcript or field.source != "message":
            field.status = FieldStatus.UNVERIFIED
            field.confidence = None
        elif field.status != FieldStatus.UNVERIFIED:
            field.status = FieldStatus.EXTRACTED
    if result.incident_type_code.value not in catalog and result.incident_type_code.value is not None:
        result.incident_type_code.status = FieldStatus.UNVERIFIED
    if (result.admission_date.value and result.discharge_date.value
            and result.discharge_date.value <= result.admission_date.value):
        result.admission_date.status = result.discharge_date.status = FieldStatus.UNVERIFIED
    result.hospital_nights.status = FieldStatus.UNVERIFIED if result.hospital_nights.value is not None else FieldStatus.MISSING
    return result
