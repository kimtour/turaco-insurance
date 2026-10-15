#!/usr/bin/env python3
"""Back up a local synthetic DB and rehearse restoration into an isolated disposable DB."""
import json
import os
from pathlib import Path
import subprocess
from uuid import uuid4
import demo

ROOT=Path(__file__).resolve().parents[1]
if __name__=="__main__":
    values=demo.local_env()
    # This utility deliberately refuses arbitrary remote hosts and real database names.
    host=os.environ.get("VERIFY_PGHOST","127.0.0.1")
    database=os.environ.get("VERIFY_DATABASE","turaco_dev")
    if host not in ("localhost","127.0.0.1") or database not in ("turaco_dev","turaco_test"):
        raise SystemExit("Backup rehearsal is restricted to the named local synthetic databases")
    env={**os.environ,"PGHOST":host,"PGPORT":os.environ.get("VERIFY_PGPORT","55433"),
         "PGUSER":os.environ.get("VERIFY_PGUSER","turaco_local"),"PGPASSWORD":values["DB_PASSWORD"]}
    folder=ROOT/".cache/verification";folder.mkdir(parents=True,exist_ok=True)
    archive=folder/"synthetic-backup.dump"
    target="turaco_restore_"+uuid4().hex[:10]
    subprocess.run(["pg_dump","-Fc","-d",database,"-f",str(archive)],env=env,check=True)
    subprocess.run(["createdb",target],env=env,check=True)
    try:
        subprocess.run(["pg_restore","--no-owner","--no-privileges","--exit-on-error","-d",target,str(archive)],env=env,check=True)
        sql="SELECT json_build_object('claims',(SELECT count(*) FROM claim),'audit_events',(SELECT count(*) FROM audit_event),'obligations',(SELECT count(*) FROM payment_obligation),'migrations',(SELECT count(*) FROM flyway_schema_history WHERE success))"
        result=subprocess.run(["psql","-d",target,"-tAc",sql],env=env,check=True,capture_output=True,text=True)
        report={"result":"passed","archive_bytes":archive.stat().st_size,"restored":json.loads(result.stdout),"scope":"local_synthetic_restore_rehearsal"}
        (folder/"backup-restore.json").write_text(json.dumps(report,indent=2)+"\n")
        print(json.dumps(report,indent=2))
    finally:
        subprocess.run(["dropdb",target],env=env,check=True)
