import test from 'node:test';
import assert from 'node:assert/strict';
import worker, {backendURL,handle} from '../src/worker.js';
const env={BACKEND_ORIGIN:'https://example-5173.app.github.dev',CODESPACE_URL:'https://github.com/codespaces/example'};
test('backend origin rejects local, credential-bearing and lookalike destinations',()=>{
 for(const url of ['http://a.app.github.dev','https://localhost','https://a.app.github.dev.evil.test','https://user:password@a.app.github.dev','https://a.app.github.dev:8443'])assert.throws(()=>backendURL(url,'/'));
 assert.equal(backendURL(env.BACKEND_ORIGIN,'/workspace/').pathname,'/');
 assert.equal(backendURL(env.BACKEND_ORIGIN,'/api/v1/claims').pathname,'/api/v1/claims');
});
test('cross-site mutation is denied before forwarding',async()=>{
 const r=await handle(new Request('https://presentation.test/api/v1/claims',{method:'POST',headers:{Origin:'https://evil.test'},body:'{}'}),env);assert.equal(r.status,403);
});
test('large mutation is bounded before forwarding',async()=>{
 const r=await handle(new Request('https://presentation.test/api/v1/claims',{method:'POST',body:'x'.repeat(33000)}),env);assert.equal(r.status,413);
});
test('proxy forwards app credentials but never browser cookies',async()=>{
 const original=globalThis.fetch;let observed;
 globalThis.fetch=async(url,options)=>{observed={url:String(url),options};return Response.json({claims:[]});};
 try{const r=await handle(new Request('https://presentation.test/api/v1/claims?page=2',{headers:{Authorization:'Bearer fixture-only',Cookie:'private-session=never-forward'}}),env);assert.equal(r.status,200);assert.equal(observed.url,env.BACKEND_ORIGIN+'/api/v1/claims?page=2');assert.equal(observed.options.headers.get('Authorization'),'Bearer fixture-only');assert.equal(observed.options.headers.get('Cookie'),null);assert.equal(r.headers.get('Cache-Control'),'no-store');}finally{globalThis.fetch=original;}
});
test('sleeping workspace returns clear error without forwarding identity redirects',async()=>{
 const original=globalThis.fetch;globalThis.fetch=async()=>new Response('private redirect body',{status:302,headers:{Location:'https://github.com/login'}});
 try{const r=await handle(new Request('https://presentation.test/workspace/'),env);assert.equal(r.status,503);assert.match((await r.json()).detail,/sleeping/);assert.equal(r.headers.get('Location'),null);}finally{globalThis.fetch=original;}
});
test('presentation assets work independently of sleeping compute and have security headers',async()=>{
 const r=await worker.fetch(new Request('https://presentation.test/'),{ASSETS:{fetch:async()=>new Response('hub')}});assert.equal(await r.text(),'hub');assert.equal(r.headers.get('X-Content-Type-Options'),'nosniff');assert.match(r.headers.get('Content-Security-Policy'),/frame-ancestors 'none'/);
});
test('cloud readiness requires actual UP response',async()=>{
 const original=globalThis.fetch;globalThis.fetch=async()=>Response.json({status:'UP'});
 try{const r=await handle(new Request('https://presentation.test/demo/status'),env);assert.equal((await r.json()).ready,true);}finally{globalThis.fetch=original;}
});
