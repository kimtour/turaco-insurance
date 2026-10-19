import React, {useEffect, useRef, useState} from 'react';

const TARGETS=['customer_reference','policy_number','premium','currency'];
const SAMPLE='customer_reference,policy_number,premium,currency\nMEMBER_001,SYN_POLICY_001,500.00,KES\nMEMBER_002,SYN_POLICY_002,600.00,KES\n';
const words=value=>value?.replaceAll('_',' ').toLowerCase()??'';

export default function BillingWorkspace({api,can,busy,setBusy,reportError}) {
  const [batches,setBatches]=useState([]),[selected,setSelected]=useState(null);
  const [partner,setPartner]=useState('synthetic_partner'),[period,setPeriod]=useState('2026-10');
  const [csv,setCsv]=useState(SAMPLE),[filename,setFilename]=useState('synthetic-month.csv');
  const [mapping,setMapping]=useState({}),[reference,setReference]=useState('');
  const [excluded,setExcluded]=useState(false),[notice,setNotice]=useState('');
  const [metrics,setMetrics]=useState(null);
  const request=useRef({body:'',key:''});
  const epoch=useRef(0);
  useEffect(()=>{load().catch(e=>reportError(e.message));return()=>{epoch.current++;};},[]);
  async function load(id) {
    const turn=++epoch.current;const rows=await api('/billing/batches');
    if(turn!==epoch.current)return;
    setBatches(rows);if(id)await select(id,turn);
    if(can('review')){const m=await api('/billing/batches/metrics');if(turn===epoch.current)setMetrics(m);}
  }
  async function select(id,turn=++epoch.current) {
    const batch=await api(`/billing/batches/${id}`);if(turn!==epoch.current)return;
    setSelected(batch);setMapping(batch.mapping);setReference('');setExcluded(false);
  }
  async function run(action) {
    if(busy)return;setBusy(true);reportError('');setNotice('');
    try{await action();}catch(e){reportError(e.message);}finally{setBusy(false);}
  }
  function reviewed() {if(!reference.trim())throw new Error('Enter a review reference.');return reference.trim();}
  const validation=selected?.validation??{};
  const errors=validation.exceptions??[];
  const headers=selected?.profile?.headers??[];
  const pending=selected?.stage==='MAPPING_REVIEW';
  return <section className="billing-workspace">
    <div className="intro"><div><div className="eyebrow">INTERNAL OPERATIONS</div><h1>Partner billing</h1><p>Review a monthly file, resolve exceptions and prepare a simulated invoice.</p></div><button disabled={busy} onClick={()=>run(()=>load(selected?.id))}>Refresh batches</button></div>
    <div className="notice">Synthetic partner files only. Invoice preparation records a local result. Nothing is dispatched or charged.</div>
    {metrics&&<div className="stats"><div><span>COMPLETED BATCHES</span><strong>{metrics.completed}</strong></div><div><span>AWAITING MAPPING REVIEW</span><strong>{metrics.mapping_reviews}</strong></div><div><span>EXCEPTIONS REVIEW</span><strong>{metrics.exception_reviews}</strong></div><div><span>MEAN RECEIPT TO INVOICE</span><strong>{metrics.mean_receipt_to_invoice_seconds==null?'—':`${Number(metrics.mean_receipt_to_invoice_seconds).toFixed(1)}s`}</strong><small>Observed synthetic runs only</small></div></div>}
    {can('write')&&<details className="panel billing-upload" open={!selected}><summary>Upload monthly partner data</summary><div className="tool-grid">
      <label>Partner code<input disabled={busy} value={partner} maxLength={60} onChange={e=>setPartner(e.target.value)}/></label>
      <label>Billing month<input disabled={busy} type="month" value={period} onChange={e=>setPeriod(e.target.value)}/></label>
      <label>CSV file<input disabled={busy} type="file" accept=".csv,text/csv" onChange={e=>run(async()=>{const f=e.target.files?.[0];if(!f)return;if(f.size>24000)throw new Error('Use a synthetic CSV below 24 KB for this demo.');setFilename(f.name);setCsv(await f.text());})}/></label>
    </div><label htmlFor="billing-csv">Synthetic CSV preview</label><textarea id="billing-csv" disabled={busy} value={csv} rows={6} maxLength={24000} onChange={e=>setCsv(e.target.value)}/>
      <button className="primary" disabled={busy||!csv.trim()||!partner.trim()||!period} onClick={()=>run(async()=>{
        const body=JSON.stringify({partner_code:partner,period,filename,csv});
        if(body!==request.current.body)request.current={body,key:crypto.randomUUID()};
        const batch=await api('/billing/batches',JSON.parse(body),request.current.key);await load(batch.id);
        setNotice('File received. Review its proposed column mapping.');
      })}>Profile file and propose mapping</button></details>}
    <div className="columns"><section className="panel queue"><div className="panel-title"><h2>Monthly batches</h2></div>
      {!batches.length&&<p className="empty">No batches yet. Upload a synthetic monthly file.</p>}
      {batches.map(b=><button disabled={busy} className={'claim-row '+(selected?.id===b.id?'selected':'')} key={b.id} onClick={()=>run(()=>select(b.id))}><div><strong>{b.partner_code} · {b.billing_period}</strong><small>{b.filename}</small><span className="status">{words(b.stage)}</span></div></button>)}
    </section><section className="panel detail">{!selected?<p className="empty">Select a batch to review its mapping, exceptions and timeline.</p>:<>
      <div className="panel-title"><div><div className="eyebrow">{selected.partner_code} · {selected.billing_period}</div><h2>{words(selected.stage)}</h2></div><span className="tag">SIMULATED</span></div>
      <div className="billing-content"><p>{selected.profile.record_count??0} input records · {Number(selected.elapsed_seconds??0).toFixed(1)} seconds from receipt to latest stage</p>
      {headers.length>0&&<><h3>Column mapping</h3><div className="tool-grid">{TARGETS.map(target=><label key={target}>{words(target)}<select value={mapping[target]??''} disabled={busy||!pending||!can('review')} onChange={e=>setMapping({...mapping,[target]:e.target.value})}><option value="">Choose a source column</option>{headers.map(h=><option key={h} value={h}>{h}</option>)}</select></label>)}</div></>}
      <label>Review reference<input disabled={busy} value={reference} maxLength={200} placeholder="synthetic-review-001" onChange={e=>setReference(e.target.value)}/></label>
      {pending&&can('review')&&<button className="primary" disabled={busy||!reference.trim()||TARGETS.some(k=>!mapping[k])} onClick={()=>run(async()=>{await api(`/billing/batches/${selected.id}/mapping-reviews`,{mapping,evidence_reference:reviewed()});await load(selected.id);setNotice('Mapping reviewed. Every row was validated by the billing service.');})}>Accept mapping and validate records</button>}
      {validation.total!==undefined&&<><div className="benefit"><div><small>VALIDATED TOTAL</small><strong>{validation.currency} {validation.total}</strong></div><span>{validation.valid_count} valid records<br/>{errors.length} invalid records</span></div>
      {errors.length>0&&<><h3>Exceptions</h3><ul className="tool-records">{errors.map(e=><li key={e.row}><strong>Row {e.row}</strong><span>{e.codes.map(words).join(', ')}</span></li>)}</ul>{selected.stage==='EXCEPTIONS_REVIEW'&&<label className="tool-check"><input type="checkbox" checked={excluded} disabled={busy} onChange={e=>setExcluded(e.target.checked)}/>I reviewed and explicitly exclude these {errors.length} invalid records from this invoice.</label>}</>}
      <details><summary>Normalized record preview</summary><div className="table-scroll"><table><thead><tr><th>Row</th><th>Customer ref</th><th>Policy</th><th>Premium</th></tr></thead><tbody>{(validation.normalized_records??[]).slice(0,20).map(r=><tr key={r.row}><td>{r.row}</td><td>{r.customer_reference}</td><td>{r.policy_number}</td><td>{r.currency} {r.premium}</td></tr>)}</tbody></table></div></details></>}
      <div className="actions">
      {can('approve')&&['VALIDATED','EXCEPTIONS_REVIEW'].includes(selected.stage)&&<button className="primary" disabled={busy||!reference.trim()||!validation.valid_count||errors.length>0&&!excluded} onClick={()=>run(async()=>{await api(`/billing/batches/${selected.id}/approvals`,{excluded_rows:errors.map(e=>e.row),evidence_reference:reviewed()});await load(selected.id);setNotice('Human approval recorded. The batch is ready for simulated invoice preparation.');})}>Approve valid records</button>}
      {can('approve')&&selected.stage==='APPROVED'&&<button className="primary" disabled={busy||!reference.trim()} onClick={()=>run(async()=>{await api(`/billing/batches/${selected.id}/invoice-preparations`,{evidence_reference:reviewed()});await load(selected.id);setNotice('Simulated invoice prepared. No invoice was dispatched.');})}>Prepare simulated invoice</button>}
      {can('write')&&selected.stage==='FAILED_RETRYABLE'&&<button disabled={busy} onClick={()=>run(()=>api(`/billing/batches/${selected.id}/resumptions`,{}).then(()=>load(selected.id)))}>Resume mapping</button>}
      {can('approve')&&!['APPROVED','INVOICE_PREPARED','COMPLETED','CANCELLED'].includes(selected.stage)&&<button disabled={busy||!reference.trim()} onClick={()=>run(()=>api(`/billing/batches/${selected.id}/cancellations`,{evidence_reference:reviewed()}).then(()=>load(selected.id)))}>Cancel batch</button>}
      </div>
      {(selected.invoices??[]).map(i=><p className="completion" key={i.id}>✓ Invoice preparation #{i.id.slice(0,8)} · {i.currency} {i.amount} · {i.record_count} records</p>)}
      <h3>Workflow timeline</h3><ol className="timeline">{(selected.timeline??[]).map((t,i)=><li key={i}><span className="timeline-dot"/><div><strong>{words(t.stage)}</strong><small>{new Date(t.created_at).toLocaleString('en-KE')} · {t.actor}</small><small>{t.stage_seconds==null?'Received':`${Number(t.stage_seconds).toFixed(2)} seconds since previous stage`}{t.evidence_reference?` · ${t.evidence_reference}`:''}</small></div></li>)}</ol>
      <details><summary>Mapping provenance</summary><p className="tools-note">{selected.model_trace.provider??'Pending'} · {selected.model_trace.model??'No model result'} · {selected.model_trace.prompt_version??''}<br/>Trace: {selected.model_trace.trace_id??'Pending'}<br/>Evaluation: {words(selected.model_trace.evaluation_status)}</p></details>
      </div></>}</section></div>
    {notice&&<p role="status" className="tool-result">{notice}</p>}
  </section>;
}
