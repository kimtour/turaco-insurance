import React, {useState} from 'react';
export default function ChannelRehearsal({api,selected,can,busy,setBusy,reportError}) {
  const [text,setText]=useState('What documents are missing?'),[result,setResult]=useState(null),[consent,setConsent]=useState(false),[optOut,setOptOut]=useState(false),[reference,setReference]=useState('');
  if(!selected)return null;
  async function run(action){if(busy)return;setBusy(true);reportError('');setResult(null);try{setResult(await action());}catch(e){reportError(e.message);}finally{setBusy(false);}}
  return <details className="panel quality-panel"><summary>Future-channel rehearsal</summary><div className="billing-content">
    <p>Test a typed voice transcript and an approved notification preview. No audio, call or message is sent.</p>
    <label>Synthetic transcript<textarea disabled={busy} value={text} maxLength={2000} rows={2} onChange={e=>setText(e.target.value)}/></label>
    <div className="actions">{can('write')&&<button disabled={busy||!text.trim()||!selected.claimant_verified} onClick={()=>run(()=>api('/voice/simulations',{claim_id:selected.id,language:selected.extraction.language,synthetic_transcript:text}))}>Rehearse typed voice response</button>}
    {can('read')&&<button disabled={busy||!selected.claimant_verified} onClick={()=>run(()=>api(`/claims/${selected.id}/channel-summary`))}>Inspect verified channel context</button>}</div>
    {!selected.claimant_verified&&<p>Record assisted identity verification before accessing channel context.</p>}
    {can('approve')&&<><label className="tool-check"><input type="checkbox" disabled={busy} checked={consent} onChange={e=>setConsent(e.target.checked)}/>Synthetic customer consent recorded</label><label className="tool-check"><input type="checkbox" disabled={busy} checked={optOut} onChange={e=>setOptOut(e.target.checked)}/>Customer opted out</label><label>Notification approval reference<input disabled={busy} value={reference} maxLength={200} onChange={e=>setReference(e.target.value)}/></label><button disabled={busy||!reference.trim()} onClick={()=>run(()=>api('/notifications/simulations',{claim_id:selected.id,language:selected.extraction.language,template_key:'missing_documents',consent,opted_out:optOut,last_customer_message:new Date(Date.now()-25*3600000).toISOString(),evidence_reference:reference.trim()},crypto.randomUUID()))}>Record notification simulation</button></>}
    {result&&<div className="tool-result" role="status">{result.reply??result.preview??(result.status?result.status.replaceAll('_',' ').toLowerCase():`Claim: ${result.claim_status}. Missing documents: ${(result.missing_documents??[]).join(', ')||'none'}.`)}<p className="tools-note">Simulation only. {result.asr_enabled===false?'Speech recognition and speech generation are disabled.':''}</p></div>}
  </div></details>;
}
