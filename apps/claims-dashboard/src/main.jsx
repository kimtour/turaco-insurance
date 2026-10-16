import React, {useEffect, useRef, useState} from 'react';
import {createRoot} from 'react-dom/client';
import PlatformTools from './PlatformTools';
import './styles.css';

const HOSTED = import.meta.env.VITE_PRESENTATION_MODE === 'true';
const POLICY = '44444444-4444-4444-4444-444444444444';
const pretty = value => value?.replaceAll('_', ' ').toLowerCase() ?? '—';
function auditDescription(event) {
  try {
    const detail = JSON.parse(event.detail);
    if (detail.machine) return `${pretty(detail.machine)}: ${pretty(detail.from)} → ${pretty(detail.to)}`;
    if (detail.evidence_reference) return `Evidence: ${detail.evidence_reference}`;
    if (detail.obligation_id) return `Obligation: ${detail.obligation_id}`;
  } catch { /* Older audit entries may not include structured details. */ }
  return '';
}
function sample() {
  const field = (value, quote) => ({value, status:'extracted', confidence:null, evidence_span:quote, source:'message'});
  return {policy_id:POLICY, extraction:{schema_version:'2', language:'en-KE', requires_confirmation:true,
    incident_type_code:field('HOSPITALIZATION','Synthetic hospitalization.'), hospital_name:field('Demo Nairobi Hospital','Hospital: Demo Nairobi Hospital.'),
    admission_date:field('2026-09-01','Admitted 2026-09-01'), discharge_date:field('2026-09-04','discharged 2026-09-04'),
    hospital_nights:{value:null,status:'missing',confidence:null,evidence_span:null,source:null}}};
}
function App() {
  const [token,setToken] = useState('');
  const [username,setUsername] = useState('approver');
  const [password,setPassword] = useState('');
  const [claims,setClaims] = useState([]);
  const [selected,setSelected] = useState(null);
  const [audit,setAudit] = useState([]);
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  const [reference,setReference] = useState('');
  const [page,setPage] = useState(0);
  const [catalog,setCatalog] = useState(null);
  const [loading,setLoading] = useState(false);
  const selectionEpoch = useRef(0);
  const session = useRef(token);
  session.current = token;
  const roles = token ? JSON.parse(atob(token.split('.')[1].replaceAll('-','+').replaceAll('_','/'))).realm_access?.roles ?? [] : [];
  const can = role => roles.includes('claims:'+role);
  async function api(path, body, key) {
    const response = await fetch('/api/v1'+path, {method:body === undefined ? 'GET':'POST',
      headers:{Authorization:'Bearer '+token, ...(body === undefined?{}:{'Content-Type':'application/json'}), ...(key?{'Idempotency-Key':key}:{})},
      ...(body === undefined?{}:{body:JSON.stringify(body)})});
    const data = await response.json().catch(()=>({}));
    if (session.current !== token) throw new Error('The account changed. Repeat the action in the current session.');
    if (!response.ok) {
      if (response.status === 401) {signOut();throw new Error('Your session ended. Sign in again.');}
      throw new Error(typeof data.detail === 'string' ? data.detail : `Request could not be completed (${response.status}).`);
    }
    return data;
  }
  async function load(id=selected?.id, requestedPage=page) {
    const epoch=++selectionEpoch.current;setLoading(true);
    try {
    const rows = await api(`/claims?page=${requestedPage}&size=10`);
    if(epoch!==selectionEpoch.current)return;
    setPage(requestedPage);setClaims(rows);
    const match = rows.find(c=>c.id===id) ?? rows[0] ?? null;
    setSelected(match);setReference('');
    const events=match ? await api(`/claims/${match.id}/audit`) : [];
    if(epoch===selectionEpoch.current)setAudit(events);
    } finally {if(epoch===selectionEpoch.current)setLoading(false);}
  }
  useEffect(()=>{
    if(token) {setError('');load().catch(e=>setError(e.message));api('/catalog').then(setCatalog).catch(e=>setError(e.message));}
    return ()=>{selectionEpoch.current++;};
  },[token,page]);
  function signOut() {
    session.current='';selectionEpoch.current++;
    setToken('');setClaims([]);setSelected(null);setAudit([]);setCatalog(null);setPage(0);setReference('');setBusy(false);setError('');
  }
  async function login(event) {
    event.preventDefault();setBusy(true);setError('');
    try {
      const response = await fetch(import.meta.env.VITE_OIDC_URL || 'http://localhost:8081/realms/turaco-local/protocol/openid-connect/token',{
        method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams({grant_type:'password',client_id:'claims-local',username,password})});
      if(!response.ok) throw new Error('Sign-in failed. Check your demo account or try again after the service starts.');
      const next=(await response.json()).access_token;session.current=next;setToken(next);setPassword('');
    } catch(e) {setError(e.message);} finally {setBusy(false);}
  }
  async function select(claim) {const epoch=++selectionEpoch.current;setSelected(claim);setReference('');setError('');setAudit([]);try {const events=await api(`/claims/${claim.id}/audit`);if(epoch===selectionEpoch.current)setAudit(events);} catch(e){if(epoch===selectionEpoch.current)setError(e.message);}}
  async function create() {
    setBusy(true);setError('');
    try {const claim=await api('/claims',sample(),crypto.randomUUID());await load(claim.id,0);}
    catch(e) {setError(e.message);} finally {setBusy(false);}
  }
  async function act(path,body) {
    if(!reference.trim()) {setError('Add an evidence reference before recording an action.');return;}
    setBusy(true);setError('');
    try {await api(`/claims/${selected.id}/${path}`,body);await load();setReference('');}
    catch(e) {setError(e.message);} finally {setBusy(false);}
  }
  const e = selected?.extraction;
  const actionable = selected && !['APPROVED','REJECTED','CLOSED'].includes(selected.provider_claim_status);
  const readyForDecision = selected?.intake_status==='SUBMITTED' && selected?.payout_status==='NOT_AUTHORIZED';
  const reviewable = readyForDecision && (selected?.provider_claim_status==='RECEIVED'||selected?.provider_kind==='LOCAL_SIMULATOR'&&['PROCESSING','APPROVED'].includes(selected?.provider_claim_status));
  const approvable = readyForDecision && (selected?.provider_kind==='LOCAL_SIMULATOR'?selected?.provider_claim_status==='APPROVED':selected?.provider_claim_status==='RECEIVED');
  return <div className="workspace">
    <aside className="sidebar">
      <div className="brand"><span className="brand-icon">t</span> turaco<span className="brand-dot">.</span></div>
      <div className="sidebar-label">CLAIMS WORKSPACE</div>
      <div className="nav-active"><span>▦</span> Hospital Cash <span className="nav-count">{claims.length}</span></div>
      <div className="sidebar-note">{HOSTED?'Cloud presentation':'Standalone prototype'}<br/><small>Independent reference implementation</small></div>
      <div className="sidebar-bottom"><div className="avatar">{token?username.slice(0,2).toUpperCase():'TC'}</div><div>{token?username:HOSTED?'Cloud workspace':'Local workspace'}<small>Kenya · Synthetic data</small></div></div>
      {token && <button className="sign-out" onClick={signOut}>Sign out</button>}
    </aside>
    <main>
      <header><span>Claims / <b>Hospital Cash</b></span><span className="environment">{HOSTED?'CLOUD DEMO':'LOCAL DEMO'}</span></header>
      <section className="intro"><div><div className="eyebrow">KENYA · SYNTHETIC HOSPITAL CASH</div><h1>Hospital Cash claims</h1><p>Review intake details, record a decision and reconcile simulated payouts.</p>{HOSTED&&<p className="cloud-notice"><a href="/">← Presentation hub</a> · Live Java/Python reference stack in GitHub Codespaces · Synthetic inputs only. Source and verification evidence are available in the presentation hub.</p>}</div>
        {token && can('write') && <button className="primary" disabled={busy} onClick={create}>＋ Create sample intake</button>}
      </section>
      <div className="notice"><span>◈</span><div><strong>Synthetic data only.</strong> All customers and policy terms are synthetic. Every payout is simulated.</div><span className="tag">REFERENCE BUILD</span></div>
      {error && <div role="alert" className="error">{error}</div>}
      {!token ? <section className="login-card"><div className="eyebrow">{HOSTED?'CLOUD WORKSPACE':'YOUR LOCAL WORKSPACE'}</div><h2>Sign in to review claims</h2><p>Use a seeded demo account. Permissions are checked by the claims service.</p><form onSubmit={login}>
        <label>Account<select value={username} onChange={e=>setUsername(e.target.value)}><option value="approver">Approver · Full demo journey</option><option value="reviewer">Reviewer · Review only</option><option value="officer">Claims officer · Intake</option></select></label>
        <label>Password<input type="password" value={password} onChange={e=>setPassword(e.target.value)} autoComplete="current-password" required placeholder="Local demo password"/></label>
        <button className="primary" disabled={busy}>{busy?'Signing in…':'Open workspace →'}</button>
      </form><small>{HOSTED?'Demo fixture password:':'Local fixture password:'} <code>local-demo-only</code>. {HOSTED?'Synthetic presentation only. Data is retained while this cloud workspace exists. No real transfers or messages.':'This sign-in is for synthetic development only.'}</small></section> : <>
        <div className="stats"><div><span>THIS PAGE</span><strong>{claims.length}<small> claims</small></strong></div><div><span>AWAITING A DECISION</span><strong>{claims.filter(c=>c.provider_claim_status==='RECEIVED').length}<small> to review</small></strong></div><div><span>SIMULATED PAYOUTS</span><strong>{claims.filter(c=>c.payout_status==='CONFIRMED').length}<small> confirmed</small></strong></div><div className="stat-mode"><span>CONNECTED MODE</span><strong>{catalog?.mode==='standalone'?'Standalone':'Connecting…'}</strong><small>Human authorization required</small></div></div>
        <PlatformTools api={api} selected={selected} can={can} refresh={load} reportError={setError} busy={busy} setBusy={setBusy} reference={reference} setReference={setReference}/>
        <div className="columns"><section className="queue panel"><div className="panel-title"><h2>Claims inbox</h2><button className="text-button" disabled={busy||loading} onClick={()=>load().catch(e=>setError(e.message))}>↻ Refresh</button></div>
          {loading&&<p className="empty" role="status">Loading claims…</p>}
          {!loading&&!claims.length && <div className="empty"><span>◇</span><h3>Your inbox is ready</h3><p>Create a sample intake to walk through the claim journey, or run the documented end-to-end demo.</p></div>}
          {claims.map(claim=><button disabled={busy} key={claim.id} className={'claim-row '+(selected?.id===claim.id?'selected':'')} onClick={()=>select(claim)}><div className="claim-icon">HC</div><div><strong>{claim.extraction.hospital_name.value??'Hospital not provided'}</strong><small>#{claim.id.slice(0,8)} · {claim.extraction.language}</small><span className={'status '+(claim.payout_status==='CONFIRMED'?'success':'')}>{pretty(claim.payout_status==='CONFIRMED'?'SIMULATED_PAID':claim.provider_claim_status==='NOT_SUBMITTED'?claim.intake_status:claim.provider_claim_status)}</span></div><span className="arrow">›</span></button>)}
          <div className="pagination"><button disabled={page===0||busy} onClick={()=>setPage(page-1)}>← Previous</button><span>Page {page+1}</span><button disabled={claims.length<10||busy} onClick={()=>setPage(page+1)}>Next →</button></div>
        </section>
        {selected ? <section className="detail panel"><div className="panel-title"><div><div className="eyebrow">HOSPITAL CASH · #{selected.id.slice(0,8)}</div><h2>Claim details</h2></div><span className="tag">SYNTHETIC</span></div>
          <div className="state-strip">{[['Intake',selected.intake_status],['Decision',selected.provider_claim_status],['Payout',selected.payout_status]].map(([label,status])=><div key={label}><small>{label}</small><strong>{pretty(status)}</strong></div>)}</div>
          <div className="section-title">Extracted information <span>{['incident_type_code','hospital_name','admission_date','discharge_date'].every(name=>e[name].status==='confirmed')?'All critical fields confirmed':'Customer confirmation required'}</span></div>
          <div className="fields">{[['Hospital','hospital_name'],['Incident','incident_type_code'],['Admission','admission_date'],['Discharge','discharge_date']].map(([label,name])=><div key={name}><label>{label}</label><strong>{name==='incident_type_code'?pretty(e[name].value):e[name].value??'Needs information'}</strong><small className={e[name].status==='confirmed'?'confirmed':''}>{e[name].status==='confirmed'?'✓ Confirmed':'○ '+pretty(e[name].status)}</small>{e[name].evidence_span && <blockquote>{e[name].evidence_span}</blockquote>}</div>)}</div>
          {selected.amount && <div className="benefit"><div><small>DETERMINISTIC BENEFIT</small><strong>{selected.currency} {Number(selected.amount).toLocaleString('en-KE',{minimumFractionDigits:2})}</strong></div><span>Calculated by the claims service<br/>Synthetic Hospital Cash terms</span></div>}
          <div className="checks"><span className={selected.claimant_verified?'checked':''}>{selected.claimant_verified?'✓':'○'} Claimant verified</span><span className={selected.payee_verified?'checked':''}>{selected.payee_verified?'✓':'○'} Payee verified</span></div>
          <div className="action-area"><label htmlFor="reference">Evidence reference</label><input id="reference" disabled={busy} value={reference} onChange={e=>setReference(e.target.value)} maxLength={200} placeholder="e.g. synthetic-assisted-review-001"/><small>Record an evidence ID only. Keep medical details out of this field.</small>
            <div className="actions">
              {can('write') && ['NEEDS_CONFIRMATION','VERIFIED'].includes(selected.intake_status) && <button disabled={busy} onClick={()=>act('confirmations',{fields:['incident_type_code','hospital_name','admission_date','discharge_date'],evidence_reference:reference})}>Record sample confirmation</button>}
              {can('review') && actionable && !selected.payee_verified && <button disabled={busy} onClick={()=>act('verifications',{evidence_reference:reference,payee_verified:true})}>Record assisted verification</button>}
              {can('write') && selected.intake_status==='READY_TO_SUBMIT' && <button className="primary" disabled={busy} onClick={()=>act('submissions',{})}>Submit for review →</button>}
              {can('review') && reviewable && <button disabled={busy} onClick={()=>act('reviews',{decision:'RECOMMEND_APPROVAL',reason_code:'SYNTHETIC_TERMS_MET',evidence_reference:reference})}>Recommend approval</button>}
              {can('approve') && approvable && <button className="primary" disabled={busy} onClick={()=>act('approvals',{evidence_reference:reference})}>Approve & reserve benefit</button>}
              {can('pay') && selected.payout_status==='RESERVED' && <button className="primary" disabled={busy} onClick={()=>act('simulated-payouts',{outcome:'CONFIRMED'})}>Run simulated payout →</button>}
            </div>{selected.payout_status==='UNKNOWN' && <p className="error">The provider outcome is unknown. Reconciliation is required before any retry.</p>}
            {selected.payout_status==='CONFIRMED' && <p className="completion">✓ Simulated payout confirmed. No money was transferred.</p>}
          </div>
          <div className="section-title">Activity trail <span>{audit.length} recorded events</span></div><ol className="timeline">{audit.slice().reverse().map(event=><li key={event.id}><span className="timeline-dot"/><div><strong>{pretty(event.event_type)}</strong><small>{auditDescription(event)}</small><small>{new Date(event.created_at).toLocaleString('en-KE')} · {event.actor}</small></div></li>)}</ol>
        </section> : <section className="panel detail empty"><span>↗</span><h2>No claim selected</h2><p>Select a claim to see its evidence, decisions and activity trail.</p></section>}
      </div></>}
      <footer><span>Turaco Insurance · Independent reference implementation</span><span>Human approval required.</span></footer>
    </main>
  </div>;
}
const root = import.meta.hot?.data.root ?? createRoot(document.getElementById('root'));
if (import.meta.hot) import.meta.hot.data.root = root;
root.render(<App/>);
