import downloads from '../.generated/downloads.json' with {type:'json'};
const security={'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",'Permissions-Policy':'camera=(), microphone=(), geolocation=()'};
export function backendURL(origin,path){
 const backend=new URL(origin);
 if(backend.protocol!=='https:'||!backend.hostname.endsWith('.app.github.dev')||backend.username||backend.password||backend.port)throw new Error('Invalid cloud backend');
 if(path==='/workspace'||path==='/workspace/')path='/';
 else if(path.startsWith('/workspace/'))path=path.slice('/workspace'.length);
 return new URL(path,backend.origin);
}
const offline=()=>Response.json({detail:'The cloud workspace is sleeping or starting. Use Start cloud workspace in the presentation hub and try again.',cloud_workspace_available:false},{status:503,headers:{'Cache-Control':'no-store'}});
export async function handle(request,env){
 const url=new URL(request.url);
 if(url.pathname==='/demo/status'){
  let ready=false;
  if(env.BACKEND_ORIGIN){try{const r=await fetch(backendURL(env.BACKEND_ORIGIN,'/demo/health'),{redirect:'manual',signal:AbortSignal.timeout(8000)});ready=r.ok&&(await r.json()).status==='UP';}catch{}}
  return Response.json({ready,workspace_url:env.CODESPACE_URL,runtime:'Java + PostgreSQL + Keycloak + Python',hosting:'Cloudflare hub / GitHub Codespaces compute',live_payments:false},{headers:{'Cache-Control':'no-store'}});
 }
 if(url.pathname.startsWith('/downloads/')){
  const file=downloads[url.pathname.slice('/downloads/'.length)];
  if(file){if(!['GET','HEAD'].includes(request.method))return new Response('Method not allowed',{status:405});
   const stream=request.method==='HEAD'?null:new ReadableStream({async start(controller){try{for(const part of file.parts){const response=await env.ASSETS.fetch(new Request(url.origin+part));if(!response.ok)throw new Error('Missing release part');const reader=response.body.getReader();for(;;){const {done,value}=await reader.read();if(done)break;controller.enqueue(value);}}controller.close();}catch(e){controller.error(e);}}});
   return new Response(stream,{headers:{'Content-Type':'application/java-archive','Content-Length':String(file.bytes),'Content-Disposition':`attachment; filename="${file.name}"`,'Cache-Control':'public, max-age=3600'}});
  }
 }
 const proxied=url.pathname==='/workspace'||url.pathname.startsWith('/workspace/')||url.pathname.startsWith('/assets/')||url.pathname.startsWith('/api/')||['/demo/token','/demo/health'].includes(url.pathname);
 if(proxied){
  if(!env.BACKEND_ORIGIN)return offline();
  if(!['GET','HEAD','POST','OPTIONS'].includes(request.method))return new Response('Method not allowed',{status:405});
  if(request.method==='POST'&&request.headers.has('Origin')&&request.headers.get('Origin')!==url.origin)return new Response('Cross-site request rejected',{status:403});
  if(Number(request.headers.get('content-length'))>32768)return new Response('Request too large',{status:413});
  const body=request.method==='POST'?await request.arrayBuffer():undefined;if(body?.byteLength>32768)return new Response('Request too large',{status:413});
  const upstream=backendURL(env.BACKEND_ORIGIN,url.pathname);upstream.search=url.search;
  const headers=new Headers();for(const name of ['Authorization','Content-Type','Idempotency-Key','Accept','X-Correlation-ID'])if(request.headers.has(name))headers.set(name,request.headers.get(name));
  try{const response=await fetch(upstream,{method:request.method,headers,body,redirect:'manual',signal:AbortSignal.timeout(25000)});
   if(response.status>=300&&response.status<400||response.status===502||response.status===503)return offline();
   const result=new Response(response.body,response);result.headers.set('Cache-Control','no-store');result.headers.delete('Set-Cookie');return result;
  }catch{return offline();}
 }
 return env.ASSETS.fetch(request);
}
export default {async fetch(request,env){let response;try{response=await handle(request,env);}catch{response=new Response('Cloud presentation temporarily unavailable',{status:503});}const result=new Response(response.body,response);for(const [key,value]of Object.entries(security))result.headers.set(key,value);return result;}};
