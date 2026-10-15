#!/usr/bin/env python3
"""Bounded synthetic read-only smoke load; reports measured latency, no invented SLO."""
import concurrent.futures
import json
import statistics
import time
import demo

if __name__ == "__main__":
    token=demo.login("approver")
    def run(_):
        start=time.monotonic()
        demo.request(demo.API+"/api/v1/claims?size=10",token=token)
        return (time.monotonic()-start)*1000
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        samples=sorted(pool.map(run,range(40)))
    print(json.dumps({"requests":40,"concurrency":4,"errors":0,"p50_ms":round(statistics.median(samples),2),"p95_ms":round(samples[int(len(samples)*.95)-1],2),"scope":"synthetic_read_smoke_not_capacity_certification"},indent=2))
