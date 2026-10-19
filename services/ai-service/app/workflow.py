"""Durable local conversation routing. The graph has no financial or business tools."""
import os
import operator
from pathlib import Path
from typing import Annotated, TypedDict, Literal
from uuid import UUID
from pydantic import BaseModel, Field
from langgraph.graph import END, START, StateGraph
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from app.schema import ExtractionRequest, ModelTrace, enforce_provenance
from app.providers.registry import provider
from app.observability import extraction_metadata, trace_task

# Opt out of automatic prompt/state tracing; only observability.py exports approved metadata.
os.environ["LANGSMITH_TRACING"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"


class Turn(BaseModel):
    tenant_id: UUID
    country_code: Literal["KE"]
    conversation_id: UUID
    language: Literal["en-KE", "sw-KE"]
    text: str = Field(min_length=1, max_length=20000)


class State(TypedDict, total=False):
    messages: Annotated[list[str], operator.add]
    language: str
    extraction: dict
    missing_fields: list[str]
    route: str
    reply: str


async def extract(state: State):
    transcript = "\n".join(state["messages"])[-20000:]
    request = ExtractionRequest(transcript=transcript, language=state["language"])
    result = enforce_provenance(await provider().extract(request), transcript, request.incident_catalog)
    result.model_trace = ModelTrace.model_validate(extraction_metadata())
    trace_task("claim-conversation-extraction", result.model_trace.model_dump(exclude_none=True))
    missing = [name for name in ("hospital_name", "incident_type_code", "admission_date", "discharge_date")
               if getattr(result, name).value is None or getattr(result, name).status.value in ("missing", "unverified")]
    return {"extraction": result.model_dump(mode="json"), "missing_fields": missing}


def route(state: State):
    missing = state["missing_fields"]
    if len(state["messages"]) >= 5 and missing:
        return {"route": "human_assistance", "reply": "An authorized person will help complete this intake."}
    if missing:
        return {"route": "needs_information", "reply": "Please provide: " + ", ".join(missing) + "."}
    return {"route": "needs_customer_confirmation",
            "reply": "Please review and confirm the extracted details with an authorized operator. No claim has been approved."}


def graph(checkpointer):
    builder = StateGraph(State)
    builder.add_node("extract", extract)
    builder.add_node("route", route)
    builder.add_edge(START, "extract")
    builder.add_edge("extract", "route")
    builder.add_edge("route", END)
    return builder.compile(checkpointer=checkpointer)


async def turn(request: Turn):
    path = Path(os.environ.get("CONVERSATION_DB", "/tmp/turaco-conversations.sqlite"))
    path.parent.mkdir(parents=True, exist_ok=True)
    # This single-replica local checkpointer persists across service restarts.
    # A production rollout must select its approved encrypted PostgreSQL checkpointer.
    async with AsyncSqliteSaver.from_conn_string(str(path)) as saver:
        workflow = graph(saver)
        thread = f"{request.tenant_id}:{request.country_code}:{request.conversation_id}"
        config = {"configurable": {"thread_id": thread}}
        result = await workflow.ainvoke({"messages": [request.text], "language": request.language}, config)
        return {key: result[key] for key in ("route", "reply", "extraction", "missing_fields")}
