import React, {useEffect, useRef, useState} from 'react';

const POLICY='44444444-4444-4444-4444-444444444444';
const SAMPLE='Synthetic hospitalization. Hospital: Demo Nairobi Hospital. Admitted 2026-09-01 and discharged 2026-09-04.';
const FIELDS=[['Hospital','hospital_name','text'],['Incident code','incident_type_code','text'],['Admission','admission_date','date'],['Discharge','discharge_date','date']];
const words=value=>value?.replaceAll('_',' ').toLowerCase()??'';

export default function PlatformTools({api, selected, can, refresh, reportError, busy, setBusy, reference, setReference}) {
  const [text,setText]=useState(SAMPLE);
  const [language,setLanguage]=useState('en-KE');
  const [result,setResult]=useState('');
  const [correction,setCorrection]=useState({});
  const [decision,setDecision]=useState('REQUEST_INFORMATION');
  const [evidence,setEvidence]=useState([]);
  const [oldEvidence,setOldEvidence]=useState('');
  const [replacement,setReplacement]=useState('');
  const [receipt,setReceipt]=useState('CONFIRMED');
  const [messages,setMessages]=useState([]);
  const [transcript,setTranscript]=useState(false);
  const [letters,setLetters]=useState([]);
  const [hours,setHours]=useState(1);
  const [template,setTemplate]=useState('claim_updated');
  const [optedOut,setOptedOut]=useState(false);
  const conversation=useRef(crypto.randomUUID());
  const creation=useRef({body:'',key:''});
  useEffect(()=>{
    setResult('');setReference('');setEvidence([]);setOldEvidence('');setReplacement('');
  },[selected?.id]);
  useEffect(()=>{
    setCorrection(Object.fromEntries(FIELDS.map(([,name])=>[name,selected?.extraction?.[name]?.value??''])));
  },[selected?.id,selected?.extraction]);
  async function run(action) {
    if(busy)return;
    setBusy(true);reportError('');setResult('');
    try {await action();} catch(error){reportError(error.message);} finally {setBusy(false);}
  }
  function reviewed() {
    if(!reference.trim())throw new Error('Enter a review reference first. Use an evidence ID, without medical details.');
    return reference.trim();
  }
  async function reloadEvidence() {
    const rows=await api(`/claims/${selected.id}/evidence`);
    setEvidence(rows);setOldEvidence('');setReplacement('');
  }
  const undecided=selected&&!['APPROVED','REJECTED','CLOSED'].includes(selected.provider_claim_status);
  const reviewable=selected?.intake_status==='SUBMITTED'&&selected?.payout_status==='NOT_AUTHORIZED'&&
    (selected.provider_claim_status==='RECEIVED'||selected.provider_kind==='LOCAL_SIMULATOR'&&['PROCESSING','APPROVED'].includes(selected.provider_claim_status));
  return <details className="platform-tools panel"><summary>Intake, evidence & recovery tools <span>Local simulation workspace</span></summary><div className="tools-body">
    <label htmlFor="intake-language">Message language</label><select id="intake-language" disabled={busy} value={language} onChange={e=>setLanguage(e.target.value)}><option value="en-KE">English</option><option value="sw-KE">Swahili</option></select>
    <label htmlFor="intake-text">Synthetic message or document text</label><textarea id="intake-text" disabled={busy} value={text} maxLength={20000} rows={4} onChange={e=>setText(e.target.value)}/>
    <div className="actions">
      {can('write')&&<button disabled={busy||!text.trim()} onClick={()=>run(async()=>{
        const body=JSON.stringify({policy_id:POLICY,text,language});
        if(creation.current.body!==body)creation.current={body,key:crypto.randomUUID()};
        const claim=await api('/intakes/from-text',JSON.parse(body),creation.current.key);
        await refresh(claim.id,0);setResult('Unconfirmed intake created. Review its evidence before submission.');
      })}>Extract & create intake</button>}
      {can('write')&&<button disabled={busy||!text.trim()} onClick={()=>run(async()=>{
        const response=await api(`/conversations/${conversation.current}/turns`,{text,language});
        setResult(response.reply+' Conversation is saved; no claim is approved by this workflow.');
      })}>Continue conversation</button>}
      {can('write')&&<button disabled={busy} onClick={()=>{conversation.current=crypto.randomUUID();setResult('New conversation started. Earlier saved conversations are preserved.');}}>Start a new conversation</button>}
      {can('write')&&selected&&<button disabled={busy||!text.trim()||!undecided} onClick={()=>run(async()=>{
        const response=await api(`/claims/${selected.id}/evidence`,{synthetic_text:text});
        await reloadEvidence();await refresh(selected.id);
        setResult(`Encrypted evidence recorded: ${words(response.verification_status)}. Human review is required.`);
      })}>Check & attach evidence</button>}
      {can('write')&&selected?.intake_status==='READY_TO_SUBMIT'&&<button disabled={busy} onClick={()=>run(async()=>{
        await api(`/claims/${selected.id}/provider-submissions`,{});await refresh(selected.id);
        setResult('Intake submitted to the independent local provider simulator. Its status remains authoritative.');
      })}>Submit to provider simulator</button>}
      {can('review')&&selected?.provider_kind==='LOCAL_SIMULATOR'&&<button disabled={busy} onClick={()=>run(async()=>{await api(`/claims/${selected.id}/provider-reconciliations`,{});await refresh(selected.id);setResult('Latest simulated provider snapshot reconciled.');})}>Reconcile provider status</button>}
      {can('review')&&selected&&<button disabled={busy} onClick={()=>run(async()=>{const r=await api(`/claims/${selected.id}/risk-assessments`,{});await refresh(selected.id);setResult(r.flags.length?`Human review flags: ${r.flags.map(words).join(', ')}.`:'No synthetic rule flags. An authorized human still makes the decision.');})}>Assess review flags</button>}
    </div>
    {selected&&<>
      <label htmlFor="tool-reference">Review reference for the selected claim</label><input id="tool-reference" disabled={busy} value={reference} maxLength={200} onChange={e=>setReference(e.target.value)} placeholder="e.g. synthetic-review-001"/>
      <p className="tools-note">Selected claim #{selected.id.slice(0,8)}. Use reference IDs only; keep medical details out of this field.</p>
      <details className="tool-section"><summary>Correct information or record a decision</summary>
        {can('write')&&selected.intake_status==='NEEDS_CONFIRMATION'&&selected.provider_kind==='STANDALONE'&&<>
          <div className="tool-grid">{FIELDS.map(([label,name,type])=><label key={name}>{label}<input type={type} disabled={busy} value={correction[name]??''} maxLength={name==='hospital_name'?200:100} onChange={event=>setCorrection({...correction,[name]:event.target.value})}/></label>)}</div>
          <button disabled={busy||!reference.trim()} onClick={()=>run(async()=>{
            const extraction={...selected.extraction,requires_confirmation:true};
            for(const [,name] of FIELDS){const value=correction[name]?.trim()||null;extraction[name]={value,status:value?'extracted':'missing',confidence:null,evidence_span:value,source:value?'authorized_human':null};}
            await api(`/claims/${selected.id}/corrections`,{extraction,evidence_reference:reviewed()});await refresh(selected.id);
            setResult('Corrections saved as unconfirmed. Record customer confirmation and resubmit before review. Earlier evidence may need a current matching replacement.');
          })}>Save unconfirmed corrections</button>
        </>}
        {can('review')&&reviewable&&<div className="tool-controls"><label>Review recommendation<select disabled={busy} value={decision} onChange={e=>setDecision(e.target.value)}><option value="REQUEST_INFORMATION">Request more information</option><option value="RECOMMEND_APPROVAL">Recommend approval</option><option value="RECOMMEND_REJECTION">Recommend rejection</option></select></label><button disabled={busy||!reference.trim()} onClick={()=>run(async()=>{
          await api(`/claims/${selected.id}/reviews`,{decision,reason_code:'SYNTHETIC_HUMAN_REVIEW',evidence_reference:reviewed()});await refresh(selected.id);setResult(`Human recommendation recorded: ${words(decision)}.`);
        })}>Record recommendation</button></div>}
        {can('approve')&&selected.provider_kind==='STANDALONE'&&selected.provider_claim_status==='RECEIVED'&&<button disabled={busy||!reference.trim()} onClick={()=>run(async()=>{
          await api(`/claims/${selected.id}/rejections`,{evidence_reference:reviewed()});await refresh(selected.id);setResult('Human rejection recorded. No benefit was reserved.');
        })}>Finalize rejection</button>}
        <p className="tools-note">A rejection recommendation must be recorded before final rejection. Information requests require correction or reconfirmation and resubmission. Decisions cannot change after a benefit is reserved.</p>
      </details>
      <details className="tool-section"><summary>Review evidence and resolve discrepancies</summary>
        <button disabled={busy||!can('read')} onClick={()=>run(reloadEvidence)}>Load evidence for this claim</button>
        {evidence.length>0&&<ul className="tool-records">{evidence.map(row=><li key={row.id}><strong>Evidence #{row.id.slice(0,8)}</strong><span>{words(row.verification_status)} · {row.resolved?'reviewed replacement recorded':row.facts_current?'checked against current facts':'needs a current check'}</span></li>)}</ul>}
        {can('review')&&evidence.length>0&&<div className="tool-grid"><label>Evidence to resolve<select disabled={busy} value={oldEvidence} onChange={e=>setOldEvidence(e.target.value)}><option value="">Choose evidence</option>{evidence.filter(row=>row.verification_status!=='QUARANTINED'&&!row.resolved&&!(row.verification_status==='MATCH'&&row.facts_current)).map(row=><option key={row.id} value={row.id}>#{row.id.slice(0,8)} · {words(row.verification_status)}</option>)}</select></label><label>Current matching replacement<select disabled={busy} value={replacement} onChange={e=>setReplacement(e.target.value)}><option value="">Choose matching evidence</option>{evidence.filter(row=>row.verification_status==='MATCH'&&row.facts_current&&row.id!==oldEvidence).map(row=><option key={row.id} value={row.id}>#{row.id.slice(0,8)}</option>)}</select></label><button disabled={busy||!oldEvidence||!replacement||!reference.trim()} onClick={()=>run(async()=>{
          await api(`/claims/${selected.id}/evidence/${oldEvidence}/resolutions`,{replacement_id:replacement,evidence_reference:reviewed()});await reloadEvidence();setResult('Human evidence resolution recorded. Original evidence and earlier resolutions are preserved.');
        })}>Record reviewed replacement</button></div>}
        <p className="tools-note">Attach fresh matching text above when facts change. Quarantined evidence cannot be overridden.</p>
      </details>
      {can('pay')&&<details className="tool-section"><summary>Simulate and recover a payout</summary>
        {selected.payout_status==='RESERVED'&&<button disabled={busy} onClick={()=>run(async()=>{
          await api(`/claims/${selected.id}/simulated-payouts`,{outcome:'UNKNOWN'});await refresh(selected.id);setResult('Ambiguous fake outcome recorded. Another attempt is blocked; recover it using a separate simulator receipt.');
        })}>Simulate an unknown payout outcome</button>}
        {selected.payout_status==='UNKNOWN'&&<><label>Authoritative simulator receipt<select disabled={busy} value={receipt} onChange={e=>setReceipt(e.target.value)}><option value="CONFIRMED">Confirmed</option><option value="FAILED">Failed</option></select></label><div className="actions"><button disabled={busy||!reference.trim()} onClick={()=>run(async()=>{
          await api(`/claims/${selected.id}/mock-provider-receipts`,{outcome:receipt,evidence_reference:reviewed()});setResult('Separate mock provider receipt recorded. Reconciliation will preserve the original attempt.');
        })}>Record simulator receipt</button><button disabled={busy} onClick={()=>run(async()=>{await api(`/claims/${selected.id}/payout-reconciliations`,{});await refresh(selected.id);setResult('Payout reconciled from the recorded receipt. No transfer was retried.');})}>Reconcile ambiguous payout</button></div></>}
        <p className="tools-note">These controls create synthetic outcomes and receipts only. No real money is transferred.</p>
      </details>}
      {can('read')&&<details className="tool-section"><summary>Preview a customer notification</summary><div className="tool-grid"><label>Hours since the last customer message<input type="number" disabled={busy} min="0" max="8760" value={hours} onChange={e=>setHours(e.target.value)}/></label><label>Template<select disabled={busy} value={template} onChange={e=>setTemplate(e.target.value)}><option value="claim_updated">Claim updated</option><option value="claim_received">Claim received</option></select></label></div><label className="tool-check"><input type="checkbox" disabled={busy} checked={optedOut} onChange={e=>setOptedOut(e.target.checked)}/>Customer has opted out</label><button disabled={busy||hours===''||!Number.isFinite(Number(hours))||Number(hours)<0||Number(hours)>8760} onClick={()=>run(async()=>{
        const response=await api('/notifications/previews',{claim_id:selected.id,language,template_key:template,opted_out:optedOut,last_customer_message:new Date(Date.now()-Number(hours)*3600000).toISOString()});
        setResult(response.status==='SUPPRESSED'?'Notification suppressed because the customer opted out.':`Preview only: ${response.mode==='SERVICE_WINDOW'?'within the service window':'a template would be required'}. No message was sent.`);
      })}>Preview notification rules</button></details>}
    </>}
    {can('read')&&<details className="tool-section"><summary>Process received channel messages</summary><button disabled={busy} onClick={()=>run(async()=>{const rows=await api('/channels/whatsapp/messages');setMessages(rows);setResult(rows.length?`${rows.length} signed channel receipts loaded.`:'No signed channel receipts are waiting.');})}>Load signed message receipts</button><label className="tool-check"><input type="checkbox" disabled={busy} checked={transcript} onChange={e=>setTranscript(e.target.checked)}/>Use the typed text above as an explicit synthetic transcript for unsupported media</label><ul className="tool-records">{messages.map(row=><li key={row.id}><strong>Receipt #{row.id.slice(0,8)} · {words(row.status)}</strong>{can('write')&&row.status==='PENDING'&&<button disabled={busy} onClick={()=>run(async()=>{
      const claim=await api(`/channels/whatsapp/messages/${row.id}/intakes`,{policy_id:POLICY,language,fixture_transcript:transcript?text:null});setMessages(await api('/channels/whatsapp/messages'));await refresh(claim.id,0);setResult('Signed receipt linked to an unconfirmed intake. No claimant identity was inferred from the sender.');
    })}>Process receipt into intake</button>}</li>)}</ul></details>}
    {can('review')&&<details className="tool-section"><summary>Operational health and dead letters</summary><div className="actions">
      {can('approve')&&<button disabled={busy} onClick={()=>run(async()=>{const r=await api('/operations/drain',{});setResult(`Local queue: ${r.published} events published, ${r.delivered} delivered, ${r.dead_lettered} dead letters.`);})}>Process local event queue</button>}
      <button disabled={busy} onClick={()=>run(async()=>{const r=await api('/operations/metrics');setResult(`Pending events: ${r.outbox_pending}. Dead letters: ${r.dead_letters}. Unknown payouts: ${r.unknown_payouts}. Provider exceptions: ${r.provider_sync_unknown}.`);})}>Check operational health</button>
      <button disabled={busy} onClick={()=>run(async()=>{const rows=await api('/operations/dead-letters');setLetters(rows);setResult(rows.length?`${rows.length} dead letters require review.`:'No dead letters require review.');})}>Load dead letters</button>
    </div>{!selected&&<><label htmlFor="queue-reference">Recovery review reference</label><input id="queue-reference" disabled={busy} maxLength={200} value={reference} onChange={e=>setReference(e.target.value)}/></>}
    <ul className="tool-records">{letters.map(row=><li key={row.id}><strong>Delivery #{row.id.slice(0,8)} · {row.attempts} attempts</strong>{can('approve')&&<button disabled={busy||!reference.trim()} onClick={()=>run(async()=>{await api(`/operations/dead-letters/${row.id}/replays`,{evidence_reference:reviewed()});setLetters(await api('/operations/dead-letters'));setResult('Authorized local replay queued. The original event identity is preserved.');})}>Authorize local replay</button>}</li>)}</ul></details>}
    <p className="tools-note">Document checks parse synthetic text. Voice, image and PDF validation require approved external services and human assistance. Notification tools only produce previews.</p>
    {result&&<p className="tool-result" role="status">{result}</p>}
  </div></details>;
}
