#!/usr/bin/env python3
"""Export contracts from authenticated running services; never print tokens."""
import json
from pathlib import Path
import demo

ROOT=Path(__file__).resolve().parents[1]
if __name__=="__main__":
    token=demo.login("approver")
    targets={"insurance-api.json":demo.API+"/v3/api-docs","ai-service.json":demo.AI+"/openapi.json"}
    for name,url in targets.items():
        specification=demo.request(url,token=token if name.startswith("insurance") else None)
        if not specification.get("paths"):raise RuntimeError("Contract export contained no API routes")
        (ROOT/"docs/openapi"/name).write_text(json.dumps(specification,indent=2)+"\n")
        print(f"Exported {name}: {len(specification['paths'])} documented paths")
