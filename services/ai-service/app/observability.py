"""Opt-in metadata-only Langfuse export. Never send transcript, quotes, IDs or prompts."""
import os
import logging


def trace_extraction(language: str, provider: str, missing_fields: int):
    if os.environ.get("LANGFUSE_ENABLED") != "true":
        return
    try:
        from langfuse import get_client
        with get_client().start_as_current_observation(name="claim-extraction", as_type="span") as span:
            span.update(input={"schema_version": "2", "language": language, "provider": provider},
                        output={"missing_critical_fields": missing_fields},
                        metadata={"synthetic_only": True, "redaction_version": "metadata-v1"})
    except Exception:
        logging.getLogger(__name__).warning("Metadata trace export unavailable")
