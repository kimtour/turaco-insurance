from typing import Protocol
from app.schema import ClaimExtraction, ExtractionRequest


class ClaimProvider(Protocol):
    async def extract(self, request: ExtractionRequest) -> ClaimExtraction: ...
