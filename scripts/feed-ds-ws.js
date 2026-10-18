const http=require('http'),WebSocket=require('ws');
const fs=require('fs'),path=require('path');
const HOST='127.0.0.1',PORT=9222;
const REPLY_DIR='E:\\restart developing doer\\minecraft-numen\\.ds-replies';
if(!fs.existsSync(REPLY_DIR))fs.mkdirSync(REPLY_DIR,{recursive:true});
function reqList(url){return new Promise((ok,no)=>http.get({host:HOST,port:PORT,path:url,timeout:8000},r=>{let b='';r.on('data',d=>b+=d);r.on('end',()=>ok(JSON.parse(b)))}).on('error',no));}
(async()=>{
  const pages=await reqList('/json');
  const ds=pages.find(p=>p.type==='page'&&/deepseek\.com/.test(p.url));
  if(!ds){console.log('NO-DS-TAB');process.exit(1);}
  const ws=new WebSocket(ds.webSocketDebuggerUrl);
  let id=0;const pend=new Map();
  const send=(method,params)=>new Promise((ok,no)=>{const i=++id;pend.set(i,{ok,no});ws.send(JSON.stringify({id:i,method,params}));});
  ws.on('message',d=>{const m=JSON.parse(d);if(m.id&&pend.has(m.id)){const p=pend.get(m.id);pend.delete(m.id);m.error?p.no(new Error(m.error.message)):p.ok(m.result);}});
  await new Promise((ok,no)=>{ws.on('open',ok);ws.on('error',no);});
  await send('Runtime.enable',{});
  // 找输入框并聚焦
  const focus=await send('Runtime.evaluate',{expression:`(()=>{const els=[...document.querySelectorAll('textarea,div[contenteditable=true]')];const vis=els.filter(e=>e.offsetParent!==null);if(!vis.length)return {found:false};const t=vis[0];t.focus();return {found:true,tag:t.tagName};})()`,returnByValue:true});
  console.log('FOCUS',JSON.stringify(focus.result&&focus.result.value||focus));
  const q=fs.readFileSync(process.argv[2]||'','utf8');
  const insText=(text)=>send('Runtime.evaluate',{expression:`document.execCommand('insertText',false,${JSON.stringify(text)})`,returnByValue:true});
  // 分批注入避免超长单次
  const chunks=q.match(/[\s\S]{1,8000}/g)||[];
  for(const c of chunks){await insText(c);await new Promise(r=>setTimeout(r,300));}
  await send('Runtime.evaluate',{expression:`(()=>{const e=document.activeElement;if(!e)return{ok:false};const ke=[];ke.push(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true}));ke.push(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',bubbles:true}));e.dispatchEvent(ke[0]);e.dispatchEvent(ke[1]);return{ok:true,tag:e.tagName};})()`,returnByValue:true});
  console.log('SENT-POLL-STABLE');
  // 轮询等生成结束：body 长度连续 ~6s 不变 且 无停止钮，代替固定等 75s 抓中途快照
  const POLL_TOTAL=300000;
  const pollStart=Date.now();
  let prevLen=-1,stable=0;
  const lenExpr=`(()=>{const e=document.querySelector('button[aria-label*="Stop"],button[aria-label*="停止"]');return (document.body.innerText.length+'|'+(e?'1':'0'));})()`;
  while(Date.now()-pollStart<POLL_TOTAL){
    const r=await send('Runtime.evaluate',{expression:lenExpr,returnByValue:true});
    const s=(r.result&&r.result.value)||'';
    const idx=s.lastIndexOf('|');
    const len=parseInt(s.slice(0,idx),10)||0;
    const stopping=s.slice(idx+1)==='1';
    if(len===prevLen&&!stopping)stable++;else stable=0;
    prevLen=len;
    if(stable>=3){await new Promise(r=>setTimeout(r,1000));break;}
    await new Promise(r=>setTimeout(r,2000));
  }
  console.log('STABLE chars='+prevLen);
  // 取最后一条回复容器全文（不再 slice 截断）
  const body=await send('Runtime.evaluate',{expression:`(()=>{const sels=['.ds-markdown','[data-message-author-role="assistant"]','.markdown','main'];for(const s of sels){const els=document.querySelectorAll(s);if(els.length){const t=(els[els.length-1].innerText||'').trim();if(t)return t;}}return document.body.innerText;})()`,returnByValue:true});
  const txt=(body.result&&body.result.value)||'';
  const f=path.join(REPLY_DIR,'ds-reply-'+Date.now()+'.txt');
  fs.writeFileSync(f,txt,'utf8');
  console.log('REPLY-FILE='+f+' CHARS='+txt.length);
  ws.close();
})().catch(e=>{console.log('ERR '+e.message.split('\n')[0]);process.exit(1);});