#!/usr/bin/env python3
"""Opt-in schema-only smoke. Operator must explicitly configure access and budget."""
import asyncio
import json
import os
from pathlib import Path
import sys

if os.environ.get('BEDROCK_LIVE_SMOKE')!='true':
    raise SystemExit('Disabled. Set BEDROCK_LIVE_SMOKE=true only with approved model access, region and token pricing.')
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'services/ai-service'))
os.environ['MAPPING_PROVIDER']='bedrock'
from app.billing import Profile,propose

if __name__=='__main__':
    result=asyncio.run(propose(Profile(headers=['customer_reference','policy_number','premium','currency'])))
    assert result['requires_review'] is True and len(result['mapping'])==4
    print(json.dumps({key:result.get(key) for key in ['provider','model','prompt_version','trace_id','evaluation_status','latency_ms']},indent=2))
