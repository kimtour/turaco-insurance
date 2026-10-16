#!/usr/bin/env python3
"""Verify the real stack through its single same-origin cloud gateway, never printing tokens."""
import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def verify(base, seed=False):
    def request(path, body=None, token=None, form=False, key=None):
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if key:
            headers['Idempotency-Key'] = key
        if body is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        req = urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)

    deadline = time.monotonic() + 300
    while True:
        try:
            request('/demo/health')
            token = request('/demo/token', {'grant_type': 'password', 'client_id': 'claims-local', 'username': 'approver', 'password': 'local-demo-only'}, form=True)['access_token']
            break
        except (OSError, KeyError, ValueError):
            if time.monotonic() > deadline:
                raise RuntimeError('Cloud gateway/identity did not become ready') from None
            time.sleep(3)
    catalog = request('/api/v1/catalog', token=token)
    assert catalog['payments'] == 'simulated_only'
    try:
        request('/api/v1/claims')
        raise AssertionError('Anonymous claim access was allowed')
    except urllib.error.HTTPError as error:
        assert error.code in (401, 403)
    claims = request('/api/v1/claims?size=100', token=token)
    if seed and not claims:
        request('/api/v1/intakes/from-text', {
            'policy_id': '44444444-4444-4444-4444-444444444444', 'language': 'en-KE',
            'text': 'Synthetic hospitalization. Hospital: Cloud Demo Hospital. Admitted 2026-09-01 and discharged 2026-09-04.'}, token, key='cloud-initial-fixture-v1')
    if not seed:
        # An independent claim exercises confirmation, review, reservation and UNKNOWN recovery.
        claim = request('/api/v1/intakes/from-text', {'policy_id': '44444444-4444-4444-4444-444444444444', 'language': 'en-KE',
            'text': 'Synthetic hospitalization. Hospital: Presentation Check Hospital. Admitted 2026-09-01 and discharged 2026-09-04.'}, token, key='cloud-check:' + str(uuid.uuid4()))
        path = '/api/v1/claims/' + claim['id']
        ref = {'evidence_reference': 'synthetic-cloud-verification'}
        request(path + '/confirmations', {**ref, 'fields': ['incident_type_code', 'hospital_name', 'admission_date', 'discharge_date']}, token)
        request(path + '/verifications', {**ref, 'payee_verified': True}, token)
        request(path + '/submissions', ref, token)
        request(path + '/reviews', {**ref, 'decision': 'RECOMMEND_APPROVAL', 'reason_code': 'SYNTHETIC_CLOUD_REVIEW'}, token)
        request(path + '/approvals', ref, token)
        before = request(path + '/simulated-payouts', {'outcome': 'UNKNOWN'}, token)
        assert before['payout_status'] == 'UNKNOWN'
        try:
            request(path + '/simulated-payouts', {'outcome': 'CONFIRMED'}, token)
            raise AssertionError('UNKNOWN retry was allowed')
        except urllib.error.HTTPError as error:
            assert error.code == 409
        request(path + '/mock-provider-receipts', {**ref, 'outcome': 'CONFIRMED'}, token)
        after = request(path + '/payout-reconciliations', {}, token)
        assert after['payout_status'] == 'CONFIRMED' and float(after['amount']) == 4500
        assert request(path + '/audit', token=token)
    return {'result': 'passed', 'gateway': base, 'runtime': 'existing-java-postgresql-keycloak-python-stack', 'live_payments': False, 'seed_only': seed}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--seed', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.url, args.seed), indent=2))
