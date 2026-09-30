const PULSE_FEED_CONFIG = {
  apiBase: window.NEPSE_PULSE_BACKEND_URL || 'https://nepseapi.surajrimal.dev',
  proxyBase: '/api/nepse',
  wsUrl: 'wss://nepseapiws.surajrimal.dev/',
  pollMs: 30000,
  timeoutMs: 12000,
  cacheKey: 'nepsePulseCentralFeedV24',
  cacheTtlMs: 6 * 60 * 60 * 1000,
  listedSymbols: 461
};

const PulseFeedEngine = (()=>{
  const state={status:'CONNECTING',updatedAt:null,lastError:null,summary:null,index:null,live:[],gainers:[],losers:[],companies:[],floorsheet:[],health:null,source:'NEPSE API',providerType:'Unofficial NEPSE REST API',marketOpen:null,diagnostics:{listedSymbols:461,coveredRows:0,api:'nepseapi.surajrimal.dev'}};
  const subscribers=new Set(); let pollTimer=null; let ws=null; let wsTimer=null;
  const emit=()=>subscribers.forEach(fn=>{try{fn({...state})}catch(e){}});
  const num=v=>{if(v===null||v===undefined||v==='')return null;const n=Number(String(v).replace(/,/g,''));return Number.isFinite(n)?n:null};
  const pick=(o,keys)=>{if(!o||typeof o!=='object')return null;const m={};Object.keys(o).forEach(k=>m[k.toLowerCase().replace(/[^a-z0-9]/g,'')]=o[k]);for(const k of keys){const v=m[k.toLowerCase().replace(/[^a-z0-9]/g,'')];if(v!==undefined&&v!==null)return v}return null};
  const arr=v=>{if(Array.isArray(v))return v;if(!v||typeof v!=='object')return[];for(const k of ['data','result','results','content','records','rows','body','items','liveMarket','stocks','companies'])if(Array.isArray(v[k]))return v[k];for(const k of Object.keys(v))if(Array.isArray(v[k]))return v[k];return[]};
  const first=v=>{const a=arr(v);return a.length?a[0]:(v&&typeof v==='object'?v:{})};
  const normalizeLive=rows=>arr(rows).map(o=>{
    const ltp=num(pick(o,['ltp','lasttradedprice','close','closingprice','price','currentprice']));
    const pct=num(pick(o,['percentchange','percentagechange','changepercent','perchange','pct']));
    const prev=num(pick(o,['previousclose','previousclosingprice','prevclose','previousprice']));
    const change=num(pick(o,['change','pointchange','changevalue'])) ?? (ltp!==null&&prev!==null?ltp-prev:null);
    return {...o,symbol:String(pick(o,['symbol','stocksymbol','securitysymbol','ticker','code'])||'').trim().toUpperCase(),company:String(pick(o,['name','companyname','company','securityname'])||''),ltp,pct,prev,volume:num(pick(o,['volume','tradedshares','quantity','totaltradedshares'])),turnover:num(pick(o,['turnover','totalturnover','amount'])),transactions:num(pick(o,['transactions','totaltransactions','numberoftransactions','nooftransactions'])),change};
  }).filter(x=>x.symbol);
  const normalizeIndex=v=>arr(v).length?arr(v):[v].filter(x=>x&&typeof x==='object');
  const withTimeout=async(promise,label)=>{const c=new AbortController();const t=setTimeout(()=>c.abort(),PULSE_FEED_CONFIG.timeoutMs);try{return await promise(c.signal)}catch(e){if(e.name==='AbortError')throw new Error(label+' timeout');throw e}finally{clearTimeout(t)}};
  const directFetch=async(path,query='')=>withTimeout(async(signal)=>{
    const url=PULSE_FEED_CONFIG.apiBase+path+(query?((path.includes('?')?'&':'?')+query):'');
    const r=await fetch(url,{cache:'no-store',signal,headers:{Accept:'application/json'}});
    if(!r.ok)throw new Error('NEPSE API HTTP '+r.status+' '+path);
    return await r.json();
  },path);
  const proxyFetch=async(path,query='')=>withTimeout(async(signal)=>{
    const qs=new URLSearchParams({route:path,...(query?Object.fromEntries(new URLSearchParams(query)):{}),_pulse:String(Date.now())});
    const r=await fetch(PULSE_FEED_CONFIG.proxyBase+'?'+qs.toString(),{cache:'no-store',signal,headers:{Accept:'application/json'}});
    if(!r.ok)throw new Error('Feed proxy HTTP '+r.status+' '+path);
    const d=await r.json(); if(d&&d.ok===false)throw new Error(d.error||'Feed proxy error'); return d.data!==undefined?d.data:d;
  },path);
  const fetchRoute=async(path,query='')=>{
    // HTTPS deployments use the server-side proxy first; standalone content:// uses direct API.
    const isFile=location.protocol==='file:'||location.protocol==='content:';
    if(!isFile){try{return await proxyFetch(path,query)}catch(_e){}}
    try{return await directFetch(path,query)}catch(e){
      if(isFile) throw e;
      try{return await proxyFetch(path,query)}catch(e2){throw new Error(e2.message+'; direct: '+e.message)}
    }
  };
  const fetchJSON=async(path='/market')=>{
    /* V20 self-hosted backend: GitHub Pages must use Render directly.
       The old /api/nepse Netlify proxy is not present in this deployment. */
    if(path==='/market' && window.NEPSE_PULSE_BACKEND_URL){
      const base=String(window.NEPSE_PULSE_BACKEND_URL).replace(/\/$/,'');
      const c=await withTimeout(async(signal)=>{
        const r=await fetch(base+'/api/market?force=true',{cache:'no-store',signal,headers:{Accept:'application/json'}});
        if(!r.ok) throw new Error('NEPSE Pulse backend HTTP '+r.status);
        return await r.json();
      },'/api/market');
      if(!c || c.ok!==true) throw new Error(c && c.error ? c.error : 'Backend returned no market data');
      return c;
    }
    if(path==='/market'){
      const routes=[
        ['summary','/Summary'],['index','/NepseIndex'],['live','/LiveMarket'],['gainers','/TopGainers'],['losers','/TopLosers'],
        ['companies','/CompanyList'],['status','/IsNepseOpen'],['subindices','/NepseSubIndices'],['turnover','/TopTenTurnoverScrips'],['transactions','/TopTenTransactionScrips'],['trades','/TopTenTradeScrips']
      ];
      const settled=await Promise.allSettled(routes.map(([,p])=>fetchRoute(p)));
      const data={}; let failures=[];
      routes.forEach(([k,p],i)=>{if(settled[i].status==='fulfilled')data[k]=settled[i].value;else failures.push(p+': '+settled[i].reason.message)});
      const rows=normalizeLive(data.live||data.companies||data.priceVolume||[]);
      if(!rows.length && !data.index && !data.summary)throw new Error('NEPSE API returned no market data'+(failures.length?' — '+failures.join(' | '):''));
      const idx=normalizeIndex(data.index);
      const summary=first(data.summary);
      const statusObj=first(data.status);
      const marketOpen=pick(statusObj,['isopen','isnepseopen','open','marketopen']);
      const listed=arr(data.companies).length || 461;
      return {ok:true,source:'NEPSE API',providerType:'Unofficial NEPSE REST API',updatedAt:new Date().toISOString(),marketOpen:marketOpen===true?true:marketOpen===false?false:null,index:idx,summary,live:rows,companies:arr(data.companies).length?data.companies:rows,gainers:arr(data.gainers),losers:arr(data.losers),subindices:arr(data.subindices),turnover:arr(data.turnover),transactions:arr(data.transactions),trades:arr(data.trades),diagnostics:{listedSymbols:Math.max(461,listed),coveredRows:rows.length,failures,successfulRoutes:routes.length-failures.length,apiBase:PULSE_FEED_CONFIG.apiBase}};
    }
    const m=path.match(/^\/([^?]+)(?:\?(.+))?$/); const route='/'+(m?m[1]:''); const query=m&&m[2]?m[2]:'';
    const data=await fetchRoute(route,query); return {ok:true,data};
  };
  const persist=()=>{try{localStorage.setItem(PULSE_FEED_CONFIG.cacheKey,JSON.stringify({version:24,savedAt:Date.now(),...state}))}catch(e){}};
  const restore=()=>{try{const c=JSON.parse(localStorage.getItem(PULSE_FEED_CONFIG.cacheKey)||'null');if(c&&Array.isArray(c.live)&&c.live.length){Object.assign(state,c,{status:'CACHED'});state.cacheAgeMs=Date.now()-Number(c.savedAt||0);return true}}catch(e){}return false};
  const setStatus=(s,e)=>{state.status=s;state.lastError=e||null;window.PULSE_FEED_STATUS=s;window.PULSE_FEED_TIMESTAMP=state.updatedAt||'—';emit()};
  const loadCore=async()=>{
    setStatus('CONNECTING','Connecting to NEPSE API…');
    try{
      const d=await fetchJSON('/market');
      const rows=normalizeLive(d.live);
      if(!rows.length)throw new Error('NEPSE API returned 0 stock rows');
      state.live=rows; state.companies=arr(d.companies).length?d.companies:rows; state.gainers=normalizeLive(d.gainers); state.losers=normalizeLive(d.losers); state.index=normalizeIndex(d.index); state.summary=d.summary||{}; state.health=d.status||null; state.marketOpen=d.marketOpen; state.updatedAt=d.updatedAt||new Date().toISOString(); state.source='NEPSE API'; state.providerType='Unofficial NEPSE REST API'; state.diagnostics=d.diagnostics||{}; state.diagnostics.listedSymbols=Math.max(461,Number(state.diagnostics.listedSymbols)||461); state.diagnostics.coveredRows=rows.length; state.diagnostics.apiBase=PULSE_FEED_CONFIG.apiBase; state.lastError=state.diagnostics.failures?.length?state.diagnostics.failures.join(' | '):null;
      persist(); setStatus('LIVE','NEPSE API · '+rows.length+' market rows · '+new Date(state.updatedAt).toLocaleTimeString()); return true;
    }catch(e){state.lastError=e.message;if(!restore())setStatus('OFFLINE','NEPSE API unavailable · '+e.message);else setStatus('CACHED','Using last saved NEPSE API data · '+e.message);return false}
  };
  const refresh=()=>loadCore();
  const loadCompanies=async()=>{if(!state.companies.length)await refresh();return state.companies};
  const loadFloorsheet=async()=>{try{const d=await fetchRoute('/Floorsheet');state.floorsheet=arr(d);emit();return state.floorsheet}catch(e){state.lastError=e.message;emit();return[]}};
  const checkHealth=async()=>{try{const d=await fetchRoute('/health');state.health=d;return true}catch(e){return false}};
  const applySocketPayload=(payload)=>{
    try{
      const d=typeof payload==='string'?JSON.parse(payload):payload;
      const rows=normalizeLive(d?.live||d?.data||d?.stocks||d);
      if(!rows.length)return false;
      state.live=rows; state.updatedAt=d.updatedAt||new Date().toISOString();
      if(d.index)state.index=normalizeIndex(d.index);
      if(d.summary)state.summary=d.summary;
      if(d.gainers)state.gainers=normalizeLive(d.gainers);
      if(d.losers)state.losers=normalizeLive(d.losers);
      state.status='LIVE'; state.source='NEPSE WebSocket'; state.providerType='WebSocket'; state.lastError=null;
      persist(); emit(); window.dispatchEvent(new CustomEvent('pulse-central-snapshot',{detail:{source:'websocket',updatedAt:state.updatedAt}}));
      return true;
    }catch(e){state.lastError=e.message;return false}
  };
  const connectWebSocket=()=>{
    if(!PULSE_FEED_CONFIG.wsUrl || !('WebSocket' in window))return;
    try{
      ws=new WebSocket(PULSE_FEED_CONFIG.wsUrl);
      ws.onopen=()=>{state.lastError=null;emit();};
      ws.onmessage=e=>applySocketPayload(e.data);
      ws.onerror=()=>{};
      ws.onclose=()=>{clearTimeout(wsTimer);wsTimer=setTimeout(connectWebSocket,5000);};
    }catch(e){clearTimeout(wsTimer);wsTimer=setTimeout(connectWebSocket,5000)}
  };
  const start=async()=>{if(restore())setStatus('CACHED','Using saved market snapshot while connecting');await refresh();clearInterval(pollTimer);pollTimer=setInterval(refresh,PULSE_FEED_CONFIG.pollMs);connectWebSocket()};
  const subscribe=fn=>{subscribers.add(fn);return()=>subscribers.delete(fn)}; const get=()=>({...state});
  return {state:get,subscribe,start,refresh,loadCompanies,loadFloorsheet,checkHealth,fetchJSON,normalizeLive,num,pick,arr,connectWebSocket};
})();
