"""Independent local provider contract. These routes are NOT Turaco's API."""
import hashlib
import hmac
import json
import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Local provider simulator — not Turaco API", version="0.2.0")


def auth(authorization: str = Header(default="")):
    secret = os.environ.get("SIMULATOR_TOKEN", "")
    if not secret or not hmac.compare_digest(authorization, "Bearer " + secret):
        raise HTTPException(401, "Simulator authentication required")


@contextmanager
def database():
    path = Path(os.environ.get("SIMULATOR_DB", "/tmp/turaco-provider.sqlite"))
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=15)
    connection.row_factory = sqlite3.Row
    connection.execute("CREATE TABLE IF NOT EXISTS provider_claim(id TEXT PRIMARY KEY, tenant TEXT NOT NULL, country TEXT NOT NULL, internal_id TEXT NOT NULL, digest TEXT NOT NULL, status TEXT NOT NULL, revision INTEGER NOT NULL, UNIQUE(tenant,country,internal_id))")
    try:
        with connection:
            yield connection
    finally:
        connection.close()


class Claim(BaseModel):
    tenant_id: UUID
    country_code: Literal["KE"]
    internal_claim_id: UUID
    policy_id: UUID


class Status(BaseModel):
    status: Literal["RECEIVED", "PROCESSING", "APPROVED", "REJECTED", "CLOSED"]


def view(row):
    return {"id": row["id"], "internal_claim_id": row["internal_id"], "tenant_id": row["tenant"],
            "country_code": row["country"], "status": row["status"], "revision": row["revision"],
            "provider": "LOCAL_SIMULATOR", "live": False}


def find(db, internal, tenant, country):
    row = db.execute("SELECT * FROM provider_claim WHERE tenant=? AND country=? AND internal_id=?",
                     (str(tenant), country, str(internal))).fetchone()
    if row is None:
        raise HTTPException(404, "Scoped simulated claim not found")
    return row


@app.get("/health")
def health():
    return {"status": "ok", "provider": "LOCAL_SIMULATOR", "live": False}


@app.post("/sim/v1/claims", dependencies=[Depends(auth)])
def create(request: Claim):
    digest = hashlib.sha256(request.model_dump_json().encode()).hexdigest()
    with database() as db:
        db.execute("INSERT INTO provider_claim VALUES(?,?,?,?,?,'RECEIVED',1) ON CONFLICT(tenant,country,internal_id) DO NOTHING",
                   (str(uuid4()), str(request.tenant_id), request.country_code, str(request.internal_claim_id), digest))
        row = find(db, request.internal_claim_id, request.tenant_id, request.country_code)
        if row["digest"] != digest:
            raise HTTPException(409, "Idempotency conflict")
        return view(row)


@app.get("/sim/v1/claims/by-internal/{internal_id}", dependencies=[Depends(auth)])
def get(internal_id: UUID, tenant_id: UUID, country_code: Literal["KE"]):
    with database() as db:
        return view(find(db, internal_id, tenant_id, country_code))


@app.post("/sim/v1/claims/by-internal/{internal_id}/status", dependencies=[Depends(auth)])
def update(internal_id: UUID, request: Status, tenant_id: UUID, country_code: Literal["KE"]):
    allowed = {"RECEIVED": {"PROCESSING", "APPROVED", "REJECTED"}, "PROCESSING": {"APPROVED", "REJECTED"},
               "APPROVED": {"CLOSED"}, "REJECTED": {"CLOSED"}, "CLOSED": set()}
    with database() as db:
        row = find(db, internal_id, tenant_id, country_code)
        if request.status != row["status"]:
            if request.status not in allowed[row["status"]]:
                raise HTTPException(409, "Invalid simulated provider transition")
            db.execute("UPDATE provider_claim SET status=?,revision=revision+1 WHERE id=?", (request.status, row["id"]))
        snapshot = view(find(db, internal_id, tenant_id, country_code))
        snapshot["event_id"] = str(uuid4())
        return snapshot
