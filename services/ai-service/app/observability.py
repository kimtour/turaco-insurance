"""Opt-in metadata-only Langfuse export. Never send transcript, quotes, IDs or prompts."""
import os
import logging


def extraction_metadata(correlation_id=""):
    from uuid import uuid4
    provider = os.environ.get("AI_PROVIDER", "mock")
    model = {"mock": "synthetic-fixture-parser-v1", "openai": os.environ.get("OPENAI_MODEL", ""),
             "anthropic": os.environ.get("ANTHROPIC_MODEL", ""), "bedrock": os.environ.get("BEDROCK_MODEL_ID", "")}.get(provider, "unknown")
    metadata = {"trace_id": str(uuid4()), "provider": provider, "model": model,
        "prompt_version": "hospital-cash-extraction-v2", "evaluation_status": "synthetic_regression" if provider == "mock" else "requires_live_evaluation"}
    if correlation_id:
        metadata["correlation_id"] = correlation_id
    return metadata


def trace_task(task: str, metadata: dict):
    """Metadata-only task trace; never export column names or uploaded records."""
    if os.environ.get("LANGFUSE_ENABLED") != "true":
        return
    allowed = {"trace_id", "correlation_id", "provider", "model", "prompt_version", "latency_ms", "evaluation_status", "cost_estimate_usd"}
    safe = {key: value for key, value in metadata.items() if key in allowed}
    try:
        from langfuse import get_client
        with get_client().start_as_current_observation(name=task, as_type="span") as span:
            span.update(metadata={**safe, "synthetic_only": True, "redaction_version": "task-metadata-v1"})
    except Exception:
        logging.getLogger(__name__).warning("Task metadata export unavailable")


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
