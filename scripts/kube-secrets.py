#!/usr/bin/env python3
"""Send selected local-only secrets to the explicit synthetic namespace via stdin."""
import argparse
import base64
import json
import subprocess
import demo

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--context",required=True);args=parser.parse_args()
    if not args.context.startswith("kind-"):raise SystemExit("This helper is restricted to explicitly named local kind contexts")
    values=demo.local_env()
    keys=["DB_PASSWORD","KEYCLOAK_ADMIN_PASSWORD","PAYLOAD_ENCRYPTION_KEY","AI_SERVICE_TOKEN","SIMULATOR_TOKEN","PROVIDER_CALLBACK_TOKEN","WHATSAPP_APP_SECRET","WHATSAPP_VERIFY_TOKEN"]
    payload={"apiVersion":"v1","kind":"Secret","metadata":{"name":"turaco-local","namespace":"turaco-synthetic"},"type":"Opaque","data":{key:base64.b64encode(values[key].encode()).decode() for key in keys}}
    subprocess.run(["kubectl","--context",args.context,"apply","-f","-"],input=json.dumps(payload),text=True,check=True)
