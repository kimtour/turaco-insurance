#!/usr/bin/env python3
"""Package tracked source plus built deliverables, SBOM and checksums; exclude local secrets."""
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]
if __name__=="__main__":
    release=ROOT/"release/0.2.0";release.mkdir(parents=True,exist_ok=True)
    jar=ROOT/"services/insurance-api/target/insurance-api-0.2.0.jar"
    sbom=ROOT/"services/insurance-api/target/bom.json"
    if not jar.exists() or not sbom.exists():raise SystemExit("Build Java and generate the dependency SBOM first")
    files=subprocess.check_output(["git","ls-files","-z"],cwd=ROOT).decode().split("\0")
    if not any(p.endswith("ClaimsService.java") for p in files):raise SystemExit("Commit the reviewed source before packaging")
    forbidden={".env",".env.local",".cache","node_modules",".venv","target","release"}
    source=release/"turaco-insurance-0.2.0-source.tar.gz"
    with tarfile.open(source,"w:gz") as archive:
        for name in files:
            if not name:continue
            if forbidden.intersection(Path(name).parts):raise SystemExit("Runtime file unexpectedly tracked: "+name)
            archive.add(ROOT/name,arcname="turaco-insurance/"+name,recursive=False)
    import shutil
    shutil.copyfile(jar,release/jar.name);shutil.copyfile(sbom,release/"java-sbom.cdx.json")
    with tarfile.open(release/"claims-dashboard-0.2.0.tar.gz","w:gz") as archive:
        archive.add(ROOT/"apps/claims-dashboard/dist",arcname="claims-dashboard")
    commit=subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip()
    (release/"manifest.json").write_text(json.dumps({"version":"0.2.0","commit":commit,"classification":"synthetic-local-reference","live_payments":False},indent=2)+"\n")
    sums=[]
    for path in sorted(release.iterdir()):
        if path.name=="SHA256SUMS":continue
        sums.append(hashlib.sha256(path.read_bytes()).hexdigest()+"  "+path.name)
    (release/"SHA256SUMS").write_text("\n".join(sums)+"\n")
    print("Release candidate packaged with source attribution, SBOM and SHA256 checksums:",release)
