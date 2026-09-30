const API = 'https://nepseapi.surajrimal.dev';
exports.handler = async function(event) {
  const route = event.queryStringParameters?.route || '/health';
  const q = {...(event.queryStringParameters || {})}; delete q.route; delete q._pulse;
  const qs = new URLSearchParams(q).toString();
  const url = API + (route.startsWith('/') ? route : '/' + route) + (qs ? '?' + qs : '');
  try {
    const r = await fetch(url, {headers:{accept:'application/json'}});
    const body = await r.text();
    return {statusCode:r.status,headers:{'content-type':r.headers.get('content-type') || 'application/json','cache-control':'no-store','access-control-allow-origin':'*'},body};
  } catch (e) {
    return {statusCode:502,headers:{'content-type':'application/json','cache-control':'no-store'},body:JSON.stringify({ok:false,error:'NEPSE upstream unavailable',detail:String(e.message||e)})};
  }
};
