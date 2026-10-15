#!/usr/bin/env python3
"""Reproducible host-run synthetic stack; no Docker or external account required."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tarfile
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT/".cache/dev"
STATE=RUNTIME/"processes.json"


def values():
    return dict(line.split("=",1) for line in (ROOT/".env").read_text().splitlines() if line and not line.startswith("#") and "=" in line)


def run(command,cwd=ROOT,env=None):
    subprocess.run(command,cwd=cwd,env=env,check=True)


def state():return json.loads(STATE.read_text()) if STATE.exists() else []


def spawn(name,command,cwd,env,marker):
    with (RUNTIME/(name+".log")).open("ab") as log:
        process=subprocess.Popen(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    current=state();current.append({"name":name,"pid":process.pid,"marker":marker});STATE.write_text(json.dumps(current,indent=2))


def wait(url,seconds=180):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen(url,timeout=3) as response:
                if response.status==200:return
        except Exception:pass
        time.sleep(1)
    raise RuntimeError("Service did not become ready: "+url+"; inspect .cache/dev logs")


def ensure_port(port):
    with socket.socket() as sock:
        if sock.connect_ex(("127.0.0.1",port))==0:
            raise RuntimeError(f"Port {port} is already in use. Stop the earlier local stack before starting this one.")


def stop():
    recorded=state()
    for item in reversed(recorded):
        result=subprocess.run(["ps","-p",str(item["pid"]),"-o","command="],capture_output=True,text=True)
        if item["marker"] in result.stdout:
            try:os.kill(item["pid"],signal.SIGTERM)
            except ProcessLookupError:pass
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        running=[]
        for item in recorded:
            result=subprocess.run(["ps","-p",str(item["pid"]),"-o","command="],capture_output=True,text=True)
            if item["marker"] in result.stdout:running.append(item["name"])
        if not running:break
        time.sleep(.2)
    else:
        raise RuntimeError("Managed processes are still shutting down: "+", ".join(running))
    # PostgreSQL is started by pg_ctl, which records its own PID in the isolated cluster.
    cluster=RUNTIME/"postgres"
    if (cluster/"postmaster.pid").exists():
        subprocess.run([shutil.which("pg_ctl") or "/opt/homebrew/bin/pg_ctl","-D",str(cluster),"-m","fast","stop"],check=False)
    STATE.unlink(missing_ok=True)
    print("Managed local processes stopped. Data and configuration preserved.")


def start(skip_build=False):
    if state():raise RuntimeError("A managed stack is already recorded; run dev.py status or stop first")
    RUNTIME.mkdir(parents=True,exist_ok=True)
    RUNTIME.chmod(0o700)
    run(["python3","scripts/setup-local.py"])
    config=values();env={**os.environ,**config}
    for name in ["java","uv","node","npm","initdb","pg_ctl","createdb"]:
        if not shutil.which(name):raise RuntimeError("Missing host dependency: "+name)
    for port in [55433,18080,8081,8000,9000,5173]:ensure_port(port)
    if not skip_build:
        run(["./mvnw","-B","-DskipTests","package"],ROOT/"services/insurance-api")
        run(["uv","sync","--frozen"],ROOT/"services/ai-service")
        run(["npm","ci"],ROOT/"apps/claims-dashboard")
    jar=ROOT/"services/insurance-api/target/insurance-api-0.2.0.jar"
    if not jar.exists():raise RuntimeError("Build the 0.2.0 Java service before --skip-build")
    home=Path(os.environ.get("KEYCLOAK_HOME",str(RUNTIME/"keycloak-26.4.0"))).resolve()
    if not (home/"bin/kc.sh").exists():
        archive=RUNTIME/"keycloak.tar.gz"
        print("Downloading official pinned Keycloak 26.4.0 for local identity fixtures",flush=True)
        urllib.request.urlretrieve("https://github.com/keycloak/keycloak/releases/download/26.4.0/keycloak-26.4.0.tar.gz",archive)
        with tarfile.open(archive) as source:source.extractall(RUNTIME,filter="data")
        archive.unlink()
    imports=home/"data/import";imports.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(ROOT/"infrastructure/keycloak/turaco-local-realm.json",imports/"turaco-local-realm.json")
    cluster=RUNTIME/"postgres"
    if not (cluster/"PG_VERSION").exists():
        password=RUNTIME/"init-password";password.write_text(config["DB_PASSWORD"]);password.chmod(0o600)
        try:run(["initdb","-D",str(cluster),"-U","turaco_local","--auth=scram-sha-256","--encoding=UTF8","--pwfile",str(password)],env=env)
        finally:password.unlink(missing_ok=True)
    run(["pg_ctl","-D",str(cluster),"-l",str(RUNTIME/"postgres.log"),"-o","-h 127.0.0.1 -p 55433 -c shared_buffers=16MB -c max_connections=30","start"],env=env)
    pg_env={**env,"PGPASSWORD":config["DB_PASSWORD"]}
    exists=subprocess.run(["psql","-h","127.0.0.1","-p","55433","-U","turaco_local","-d","postgres","-tAc","SELECT 1 FROM pg_database WHERE datname='turaco_dev'"],env=pg_env,capture_output=True,text=True,check=True)
    if exists.stdout.strip()!="1":run(["createdb","-h","127.0.0.1","-p","55433","-U","turaco_local","turaco_dev"],env=pg_env)
    spawn("keycloak",[str(home/"bin/kc.sh"),"start-dev","--import-realm","--http-host=127.0.0.1","--http-port=8081","--hostname=http://localhost:8081"],ROOT,
          {**env,"KC_BOOTSTRAP_ADMIN_USERNAME":"local-admin","KC_BOOTSTRAP_ADMIN_PASSWORD":config["KEYCLOAK_ADMIN_PASSWORD"],"JAVA_OPTS_APPEND":"-Xms64m -Xmx384m"},str(home))
    python=ROOT/"services/ai-service/.venv/bin/python"
    shared={**env,"CONVERSATION_DB":str(RUNTIME/"conversations.sqlite"),"SIMULATOR_DB":str(RUNTIME/"provider.sqlite")}
    spawn("ai",[str(python),"-m","uvicorn","app.main:app","--host","127.0.0.1","--port","8000","--app-dir",str(ROOT/"services/ai-service")],ROOT/"services/ai-service",shared,str(ROOT/"services/ai-service"))
    spawn("simulator",[str(python),"-m","uvicorn","app.simulator:app","--host","127.0.0.1","--port","9000","--app-dir",str(ROOT/"services/ai-service")],ROOT/"services/ai-service",shared,str(ROOT/"services/ai-service"))
    api_env={**env,"SPRING_PROFILES_ACTIVE":"local","LOCAL_SEED":"true","SERVER_ADDRESS":"127.0.0.1","SERVER_PORT":"18080","DB_URL":"jdbc:postgresql://127.0.0.1:55433/turaco_dev","DB_USER":"turaco_local","QUEUE_WORKER_ENABLED":"true"}
    spawn("api",["java","-Xms64m","-Xmx384m","-jar",str(jar)],ROOT,api_env,str(jar))
    dashboard=ROOT/"apps/claims-dashboard"
    (dashboard/".env.local").write_text("API_PROXY_TARGET=http://127.0.0.1:18080\n")
    vite=dashboard/"node_modules/vite/bin/vite.js"
    spawn("dashboard",["node",str(vite),"--host","127.0.0.1"],dashboard,env,str(vite))
    for url in ["http://127.0.0.1:18080/actuator/health","http://127.0.0.1:8000/health","http://127.0.0.1:9000/health","http://localhost:8081/realms/turaco-local/.well-known/openid-configuration","http://localhost:5173"]:wait(url)
    print("Local synthetic stack ready: http://localhost:5173",flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("action",choices=["start","stop","status"]);parser.add_argument("--skip-build",action="store_true");args=parser.parse_args()
    if args.action=="stop":stop()
    elif args.action=="status":print(json.dumps(state(),indent=2))
    else:
        if state():raise SystemExit("Managed stack already recorded; inspect status or stop it explicitly")
        try:start(args.skip_build)
        except Exception:
            stop()
            raise
