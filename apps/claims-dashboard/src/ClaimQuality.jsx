import React, {useEffect, useRef, useState} from 'react';
const words=value=>value?.replaceAll('_',' ').toLowerCase()??'';
export default function ClaimQuality({api,selected,can,busy,setBusy,reportError,refresh}) {
  const [quality,setQuality]=useState(null),[reference,setReference]=useState(''),[text,setText]=useState('');
  const [type,setType]=useState('ADMISSION_RECORD');const epoch=useRef(0);
  useEffect(()=>{
    const turn=++epoch.current;setQuality(null);setReference('');
    if(!selected)return;
    const e=selected.extraction;
    setText(`Synthetic hospitalization. Hospital: ${e.hospital_name.value??'Demo Nairobi Hospital'}. Admitted ${e.admission_date.value??'2026-10-01'} and discharged ${e.discharge_date.value??'2026-10-04'}.`);
    api(`/claims/${selected.id}/completeness`).then(q=>{if(turn===epoch.current)setQuality(q);}).catch(e=>reportError(e.message));
    return()=>{epoch.current++;};
  },[selected?.id,selected?.extraction,selected?.intake_status]);
  async function run(action) {
    if(busy)return;setBusy(true);reportError('');const id=selected.id,turn=epoch.current;
    try{await action();const q=await api(`/claims/${id}/completeness`);if(turn===epoch.current){setQuality(q);setReference('');}await refresh(id);}catch(e){reportError(e.message);}finally{setBusy(false);}
  }
  if(!selected||!quality)return null;
  return <details className="panel quality-panel"><summary>Required documents <span className={'status '+(quality.complete?'success':'')}>{quality.historical_without_requirements?'Historical demo rules':quality.complete?'Verified complete':'Incomplete'}</span></summary><div className="billing-content">
    {quality.historical_without_requirements?<p>This September fixture retains its original requirements. Create an October completeness intake to exercise the new catalog.</p>:<>
    <p>Every required type needs current evidence and an authorized review. Upload count alone does not establish completeness.</p>
    <ul className="tool-records">{quality.requirements.map(r=><li key={r.id}><div><strong>{r.label}</strong><span> · {words(r.status)} · catalog v{r.version}</span>
      {(r.evidence??[]).map(e=><div className="quality-evidence" key={e.id}><small>#{e.id.slice(0,8)} · {words(e.verification_status)} · {e.facts_current?'current facts':'stale facts'}</small>
      {can('review')&&e.facts_current&&e.verification_status==='MATCH'&&<button disabled={busy||!reference.trim()||r.status==='VERIFIED'} onClick={()=>run(()=>api(`/claims/${selected.id}/document-reviews`,{requirement_id:r.id,evidence_id:e.id,decision:'VERIFIED',evidence_reference:reference.trim()}))}>Verify document</button>}</div>)}
      {can('approve')&&!['VERIFIED','WAIVED_BY_AUTHORIZED_REVIEWER'].includes(r.status)&&<button disabled={busy||!reference.trim()} onClick={()=>run(()=>api(`/claims/${selected.id}/document-waivers`,{requirement_id:r.id,evidence_reference:reference.trim()}))}>Record authorized waiver</button>}
    </div></li>)}</ul>
    <label>Document review reference<input disabled={busy} value={reference} maxLength={200} placeholder="synthetic-document-review-001" onChange={e=>setReference(e.target.value)}/></label>
    {can('write')&&!['APPROVED','REJECTED','CLOSED'].includes(selected.provider_claim_status)&&<><label>Required document type<select disabled={busy} value={type} onChange={e=>setType(e.target.value)}>{quality.requirements.map(r=><option key={r.id} value={r.document_type}>{r.label}</option>)}</select></label><label>Synthetic document text<textarea disabled={busy} rows={3} maxLength={20000} value={text} onChange={e=>setText(e.target.value)}/></label><button disabled={busy||!text.trim()} onClick={()=>run(()=>api(`/claims/${selected.id}/evidence`,{synthetic_text:text,document_type:type,customer_id:quality.customer_id,extraction_confidence:null}))}>Attach unverified document</button><p className="tools-note">Document text is synthetic. Images and PDFs require human assistance.</p></>}
    <p className="tool-result">{quality.follow_up}</p></>}
  </div></details>;
}
