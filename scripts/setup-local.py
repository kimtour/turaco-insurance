#!/usr/bin/env python3
"""Create or extend private local configuration; preserve existing values."""
from pathlib import Path
import base64
import os
import secrets

root = Path(__file__).resolve().parents[1]
target = root / ".env"
existing = target.read_text() if target.exists() else "# Local synthetic development only\n"
keys = {line.split("=",1)[0] for line in existing.splitlines() if "=" in line and not line.startswith("#")}
values = {key: secrets.token_urlsafe(32) for key in (
    "DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "AI_SERVICE_TOKEN", "SIMULATOR_TOKEN",
    "PROVIDER_CALLBACK_TOKEN", "WHATSAPP_APP_SECRET", "WHATSAPP_VERIFY_TOKEN")}
values["PAYLOAD_ENCRYPTION_KEY"] = base64.b64encode(secrets.token_bytes(32)).decode()
values.update(AI_PROVIDER="mock", REAL_PROVIDER_ENABLED="false", OPENAI_MODEL="", OPENAI_API_KEY="",
              ANTHROPIC_MODEL="", ANTHROPIC_API_KEY="", LANGFUSE_ENABLED="false")
missing = {key:value for key,value in values.items() if key not in keys}
if missing:
    if not existing.endswith("\n"): existing += "\n"
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd,"w") as stream:
        stream.write(existing)
        stream.writelines(f"{key}={value}\n" for key,value in missing.items())
    os.chmod(target,0o600)
    print(f"Configured {len(missing)} missing local settings; existing values preserved")
else:
    print("Existing local configuration is complete and was preserved")
