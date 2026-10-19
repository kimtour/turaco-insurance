#!/usr/bin/env python3
"""Black-box synthetic rebuild acceptance checks; creates local demo records only."""
import json
from pathlib import Path
import time
from uuid import uuid4
import demo

ROOT=Path(__file__).resolve().parents[1]
POLICY='44444444-4444-4444-4444-444444444444'
CUSTOMER='22222222-2222-2222-2222-222222222222'
TEXT='Synthetic hospitalization. Hospital: Demo Nairobi Hospital. Admitted 2026-10-01 and discharged 2026-10-04.'


def main():
    token=demo.login('approver')
    def api(path,body=None,expected=200,key=None):
        return demo.request(demo.API+'/api/v1'+path,body,token,key,expected=expected)
    def new_claim(language='en-KE'):
        text=TEXT if language=='en-KE' else 'Mfano wa kulazwa. Hospitali: Demo Nairobi Hospital. Nililazwa 2026-10-01 na niliruhusiwa 2026-10-04.'
        c=api('/intakes/from-text',{'policy_id':POLICY,'text':text,'language':language},201,'quality:'+str(uuid4()))
        path='/claims/'+c['id']
        api(path+'/confirmations',{'fields':['incident_type_code','hospital_name','admission_date','discharge_date'],'evidence_reference':'synthetic-confirmation'})
        api(path+'/verifications',{'payee_verified':True,'evidence_reference':'synthetic-assisted-identity'})
        return path
    report={'classification':'synthetic-only','billing':[],'completeness':[]}
    for filename in ['normal.csv','renamed.csv','exceptions.csv']:
        started=time.monotonic();csv=(ROOT/'fixtures/partner-billing'/filename).read_text()
        body={'partner_code':'acceptance_'+uuid4().hex[:12],'period':'2026-10','filename':filename,'csv':csv}
        key='billing:'+str(uuid4());b=api('/billing/batches',body,key=key)
        assert api('/billing/batches',body,key=key)['id']==b['id']
        assert b['stage']=='MAPPING_REVIEW',b
        path='/billing/batches/'+b['id'];mapping=b['mapping'];assert len(mapping)==4,b
        checked=api(path+'/mapping-reviews',{'mapping':mapping,'evidence_reference':'synthetic-mapping-review'})
        invalid=[e['row'] for e in checked['validation']['exceptions']]
        expected='500.00' if filename=='exceptions.csv' else '1100.00'
        assert checked['validation']['total']==expected
        api(path+'/invoice-preparations',{'evidence_reference':'missing-approval'},409)
        api(path+'/approvals',{'excluded_rows':invalid,'evidence_reference':'synthetic-batch-review'})
        complete=api(path+'/invoice-preparations',{'evidence_reference':'synthetic-invoice-review'})
        repeated=api(path+'/invoice-preparations',{'evidence_reference':'synthetic-repeat'})
        assert complete['stage']=='COMPLETED' and complete['invoices'][0]['id']==repeated['invoices'][0]['id']
        assert float(complete['invoices'][0]['amount'])==float(expected)
        report['billing'].append({'fixture':filename,'result':'passed','elapsed_seconds':round(time.monotonic()-started,3),'total':expected,'invalid_rows_explicitly_excluded':invalid})
        print('PASS billing',filename)
    for case in json.loads((ROOT/'fixtures/claims-completeness/golden.json').read_text()):
        started=time.monotonic();path=new_claim(case.get('language','en-KE'))
        requirements={r['document_type']:r['id'] for r in api(path+'/completeness')['requirements']}
        other=new_claim() if case.get('wrong_claim') else None
        for doc in case['documents']:
            text='unreadable synthetic file' if case.get('unreadable')==doc else TEXT.replace('2026-10-04','2026-10-06') if case.get('conflict')==doc else TEXT
            upload_path=other or path
            customer=str(uuid4()) if case.get('wrong_customer') else CUSTOMER
            evidence=api(upload_path+'/evidence',{'synthetic_text':text,'document_type':doc,'customer_id':customer},409 if case.get('wrong_customer') else 200)
            if case.get('wrong_customer'):continue
            if case.get('wrong_claim'):
                api(path+'/document-reviews',{'requirement_id':requirements[doc],'evidence_id':evidence['id'],'decision':'VERIFIED','evidence_reference':'wrong-claim-rejected'},409)
            elif doc in case['verified']:
                api(path+'/document-reviews',{'requirement_id':requirements[doc],'evidence_id':evidence['id'],'decision':'VERIFIED','evidence_reference':'synthetic-document-review'})
        if case.get('correct_after_review'):
            api(path+'/submissions',{})
            api(path+'/reviews',{'decision':'REQUEST_INFORMATION','reason_code':'SYNTHETIC_CORRECTION','evidence_reference':'synthetic-review'})
            extraction=api(path)['extraction'];extraction['hospital_name'].update(value='Changed Synthetic Hospital',evidence_span='Changed Synthetic Hospital',source='authorized_human')
            api(path+'/corrections',{'extraction':extraction,'evidence_reference':'synthetic-correction'})
        result=api(path+'/completeness')
        assert result['complete']==case['complete'],(case['id'],result)
        if not case['complete']:api(path+'/submissions',{},409)
        report['completeness'].append({'case':case['id'],'expected':case['complete'],'actual':result['complete'],'result':'passed','elapsed_seconds':round(time.monotonic()-started,3)})
        print('PASS completeness',case['id'])
    cases=report['completeness'];tp=sum(c['expected'] and c['actual'] for c in cases);fp=sum(not c['expected'] and c['actual'] for c in cases);fn=sum(c['expected'] and not c['actual'] for c in cases)
    report['metrics']={'false_complete_count':fp,'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/(tp+fn) if tp+fn else None,'scope':'12 invented regression scenarios; not production accuracy'}
    report['result']='passed'
    folder=ROOT/'.cache/verification';folder.mkdir(parents=True,exist_ok=True)
    (folder/'rebuild-check.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS rebuild acceptance; no real messages, invoices or transfers')
    return report


if __name__=='__main__':main()
