import React, {useRef, useState} from 'react';

const POLICY='44444444-4444-4444-4444-444444444444';
const SAMPLE='Synthetic hospitalization. Hospital: Demo Nairobi Hospital. Admitted 2026-09-01 and discharged 2026-09-04.';

export default function PlatformTools({api, selected, can, refresh, reportError}) {
  const [text,setText]=useState(SAMPLE);
  const [language,setLanguage]=useState('en-KE');
  const [busy,setBusy]=useState(false);
  const [result,setResult]=useState('');
  const conversation=useRef(crypto.randomUUID());
  const creation=useRef({body:'',key:''});
  async function run(action) {
    setBusy(true);reportError('');setResult('');
    try {await action();} catch(error){reportError(error.message);} finally {setBusy(false);}
  }
  return <details className="platform-tools panel"><summary>Intake, evidence & recovery tools <span>Local simulation workspace</span></summary><div className="tools-body">
    <label htmlFor="intake-language">Message language</label><select id="intake-language" value={language} onChange={e=>setLanguage(e.target.value)}><option value="en-KE">English</option><option value="sw-KE">Swahili</option></select>
    <label htmlFor="intake-text">Synthetic message or document text</label><textarea id="intake-text" value={text} maxLength={20000} rows={4} onChange={e=>setText(e.target.value)}/>
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
      {can('write')&&selected&&<button disabled={busy||!text.trim()||['APPROVED','REJECTED','CLOSED'].includes(selected.provider_claim_status)} onClick={()=>run(async()=>{
        const response=await api(`/claims/${selected.id}/evidence`,{synthetic_text:text});
        setResult(`Encrypted evidence recorded: ${response.verification_status.toLowerCase()}. Human review is required.`);
        await refresh(selected.id);
      })}>Check & attach evidence</button>}
      {can('review')&&selected?.provider_kind==='LOCAL_SIMULATOR'&&<button disabled={busy} onClick={()=>run(async()=>{await api(`/claims/${selected.id}/provider-reconciliations`,{});await refresh(selected.id);setResult('Latest simulated provider snapshot reconciled.');})}>Reconcile provider status</button>}
      {can('pay')&&selected?.payout_status==='UNKNOWN'&&<button disabled={busy} onClick={()=>run(async()=>{await api(`/claims/${selected.id}/payout-reconciliations`,{});await refresh(selected.id);setResult('Payout reconciled from the recorded mock provider receipt.');})}>Reconcile ambiguous payout</button>}
      {can('approve')&&<button disabled={busy} onClick={()=>run(async()=>{const r=await api('/operations/drain',{});setResult(`Local queue: ${r.published} events published, ${r.delivered} delivered, ${r.dead_lettered} dead letters.`);})}>Process local event queue</button>}
      {can('review')&&<button disabled={busy} onClick={()=>run(async()=>{const r=await api('/operations/metrics');setResult(`Pending events: ${r.outbox_pending}. Dead letters: ${r.dead_letters}. Unknown payouts: ${r.unknown_payouts}. Provider exceptions: ${r.provider_sync_unknown}.`);})}>Check operational health</button>}
      {can('review')&&selected&&<button disabled={busy} onClick={()=>run(async()=>{const r=await api(`/claims/${selected.id}/risk-assessments`,{});setResult(r.flags.length?`Human review flags: ${r.flags.join(', ')}.`:'No synthetic rule flags. An authorized human still makes the decision.');await refresh(selected.id);})}>Assess review flags</button>}
    </div><p className="tools-note">Document checks parse synthetic text. Voice, image and PDF validation require approved external services and human assistance. Notification tools only produce previews.</p>
    {result&&<p className="tool-result" role="status">{result}</p>}
  </div></details>;
}
