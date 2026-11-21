/* NEPSE Pulse v10 - core: market overview dashboard, advanced charting
   controls, tool routing and shared helpers.
   Data is read only from the existing NEPSE Pulse Render backend. */
(function () {
  'use strict';
  if (window.NPV10) return;

  var API = String(window.NEPSE_PULSE_BACKEND_URL || 'https://nepse-pulse-1.onrender.com').replace(/\/$/, '');
  var $ = function (id, root) { return (root || document).getElementById(id); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  function num(v) {
    if (v === null || v === undefined) return null;
    var raw = String(v).trim();
    if (!raw) return null;
    var n = Number(raw.replace(/,/g, ''));
    return Number.isFinite(n) ? n : null;
  }
  function normKey(k) { return String(k).toLowerCase().replace(/[^a-z0-9]/g, ''); }
  function pick(o, keys) {
    if (!o || typeof o !== 'object') return null;
    var map = {};
    Object.keys(o).forEach(function (k) { map[normKey(k)] = o[k]; });
    for (var i = 0; i < keys.length; i++) {
      var v = map[normKey(keys[i])];
      if (v !== undefined && v !== null && v !== '') return v;
    }
    return null;
  }
  function esc(s) {
    return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmt(v, d) {
    var n = num(v);
    if (n === null) return '--';
    return n.toLocaleString(undefined, { maximumFractionDigits: d === undefined ? 2 : d });
  }
  function money(v) {
    var n = num(v);
    if (n === null) return '--';
    var a = Math.abs(n);
    if (a >= 1e9) return (n / 1e9).toFixed(2) + ' Ar';
    if (a >= 1e7) return (n / 1e7).toFixed(2) + ' Cr';
    if (a >= 1e5) return (n / 1e5).toFixed(2) + ' L';
    return fmt(n, 0);
  }
  function pct(v, d) {
    var n = num(v);
    if (n === null) return '--';
    return (n > 0 ? '+' : '') + n.toFixed(d === undefined ? 2 : d) + '%';
  }
  function cls(v) { var n = num(v); return n === null ? '' : n > 0 ? 'npv10-up' : n < 0 ? 'npv10-down' : 'npv10-flat'; }
  function dateKey(ms) {
    var d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }
  function store(key, fallback) {
    try { var raw = localStorage.getItem(key); return raw ? JSON.parse(raw) : fallback; } catch (e) { return fallback; }
  }
  function save(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) {} }

  async function fetchJSON(path) {
    var res = await fetch(API + path, { cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(path + ' HTTP ' + res.status);
    return res.json();
  }

  /* ---------------------------------------------------------------- market */
  var MARKET = { at: 0, data: null, loading: null, tx: {}, txAt: 0 };

  function normalizeMarket(d) {
    var idxList = Array.isArray(d.index) ? d.index : (d.index ? [d.index] : []);
    var idx = idxList.filter(function (x) { return /nepse/i.test(String(x.index || x.name || '')); })[0] || idxList[0] || {};
    var summaryMap = {};
    (Array.isArray(d.summary) ? d.summary : (d.summary ? [d.summary] : [])).forEach(function (row) {
      var label = String(pick(row, ['detail', 'label', 'name', 'title']) || '').toLowerCase();
      summaryMap[label] = num(pick(row, ['value', 'amount', 'total']));
    });
    function summaryOf(needle) {
      var keys = Object.keys(summaryMap);
      for (var i = 0; i < keys.length; i++) if (keys[i].indexOf(needle) !== -1) return summaryMap[keys[i]];
      return null;
    }
    var rows = (Array.isArray(d.live) ? d.live : []).map(function (r) {
      var symbol = String(pick(r, ['symbol', 'stockSymbol', 'securitySymbol']) || '').trim().toUpperCase();
      if (!symbol) return null;
      var ltp = num(pick(r, ['lastTradedPrice', 'ltp', 'close', 'price']));
      var prev = num(pick(r, ['previousClose', 'previousClosingPrice', 'prevClose']));
      var pctv = num(pick(r, ['percentageChange', 'percentChange', 'changePercent', 'perChange']));
      var chg = num(pick(r, ['change', 'pointChange']));
      if (chg === null && ltp !== null && prev !== null) chg = ltp - prev;
      if (pctv === null && chg !== null && prev) pctv = (chg / prev) * 100;
      return {
        symbol: symbol,
        name: String(pick(r, ['securityName', 'companyName', 'name']) || symbol),
        ltp: ltp, prev: prev, change: chg, pct: pctv,
        volume: num(pick(r, ['totalTradeQuantity', 'volume', 'tradedShares'])),
        turnover: num(pick(r, ['totalTradeValue', 'turnover', 'amount'])),
        transactions: num(pick(r, ['transactions', 'totalTransactions', 'tradeCount'])),
        open: num(pick(r, ['openPrice'])), high: num(pick(r, ['highPrice'])), low: num(pick(r, ['lowPrice']))
      };
    }).filter(Boolean);
    var breadth = d.breadth || {};
    var advancing = num(breadth.advancing), declining = num(breadth.declining), unchanged = num(breadth.unchanged);
    if (advancing === null) advancing = rows.filter(function (r) { return r.change > 0; }).length;
    if (declining === null) declining = rows.filter(function (r) { return r.change < 0; }).length;
    if (unchanged === null) unchanged = rows.filter(function (r) { return r.change === 0; }).length;
    var totals = {
      turnover: summaryOf('turnover') || rows.reduce(function (a, r) { return a + (r.turnover || 0); }, 0),
      shares: summaryOf('traded shares') || summaryOf('tradedshares') || rows.reduce(function (a, r) { return a + (r.volume || 0); }, 0),
      transactions: summaryOf('transactions') || rows.reduce(function (a, r) { return a + (r.transactions || 0); }, 0),
      scrips: summaryOf('scrip') || rows.length,
      marketCap: summaryOf('market capital'),
      floatCap: summaryOf('float')
    };
    return {
      raw: d,
      updatedAt: d.updatedAt || null,
      marketOpen: d.marketOpen === true ? true : d.marketOpen === false ? false : null,
      status: d.status || null,
      index: {
        name: String(idx.index || idx.name || 'NEPSE Index'),
        value: num(idx.currentValue) !== null ? num(idx.currentValue) : num(idx.close),
        close: num(idx.close), prevClose: num(idx.previousClose),
        change: num(idx.change), pct: num(idx.perChange) !== null ? num(idx.perChange) : num(idx.percentageChange),
        high: num(idx.high), low: num(idx.low)
      },
      summary: totals,
      breadth: { advancing: advancing, declining: declining, unchanged: unchanged },
      rows: rows,
      stale: d.stale === true,
      source: d.source || 'NEPSE Pulse backend'
    };
  }

  async function loadTransactions(force) {
    if (!force && Date.now() - MARKET.txAt < 5 * 60 * 1000 && Object.keys(MARKET.tx).length) return MARKET.tx;
    try {
      var d = await fetchJSON('/api/market/transactions');
      var map = {};
      (d.data || d.transactions || []).forEach(function (r) {
        var s = String(r.symbol || '').toUpperCase();
        if (s) map[s] = num(r.transactions || r.transactionCount || r.totalTransactions);
      });
      MARKET.tx = map; MARKET.txAt = Date.now();
    } catch (e) { /* transactions are optional; never fabricate them */ }
    return MARKET.tx;
  }

  function loadMarket(force) {
    if (MARKET.loading) return MARKET.loading;
    if (!force && MARKET.data && Date.now() - MARKET.at < 60 * 1000) return Promise.resolve(MARKET.data);
    MARKET.loading = (async function () {
      var raw = await fetchJSON('/api/market' + (force ? '?force=true' : ''));
      var snap = normalizeMarket(raw);
      var tx = await loadTransactions(force);
      snap.rows.forEach(function (r) {
        if (r.transactions === null && tx[r.symbol] !== undefined) r.transactions = tx[r.symbol];
      });
      MARKET.data = snap; MARKET.at = Date.now();
      return snap;
    })();
    MARKET.loading.then(function () { MARKET.loading = null; }, function () { MARKET.loading = null; });
    return MARKET.loading;
  }

  function marketOpenNow() {
    try {
      var parts = new Intl.DateTimeFormat('en-US', {
        timeZone: 'Asia/Kathmandu', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false
      }).formatToParts(new Date());
      var get = function (k) { var p = parts.filter(function (x) { return x.type === k; })[0]; return p ? p.value : ''; };
      var mins = Number(get('hour')) * 60 + Number(get('minute'));
      return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu'].indexOf(get('weekday')) !== -1 && mins >= 660 && mins < 900;
    } catch (e) { return false; }
  }

  function stampText(snap) {
    var at = snap.updatedAt ? new Date(snap.updatedAt) : null;
    var time = at ? at.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'unavailable';
    var open = snap.marketOpen === null ? marketOpenNow() : snap.marketOpen;
    return { open: open, text: (open ? 'Data as of ' : 'Market closed - last data ') + time };
  }

  /* --------------------------------------------------------------- styling */
  var CSS = [
    '#pulse-market-overview-v10{--npv-bg:#0b1220;--npv-panel:#111c2e;--npv-panel2:#152238;--npv-line:#22314a;--npv-ink:#e8eefc;--npv-mut:#8b9cb6;--npv-up:#34d399;--npv-down:#f87171;margin:18px 16px 8px;padding:18px;border-radius:22px;background:linear-gradient(160deg,#0b1220,#101c30 55%,#0b1424);color:var(--npv-ink);box-shadow:0 18px 42px rgba(8,15,30,.28);border:1px solid #1c2a42;position:relative;overflow:hidden}',
    '#pulse-market-overview-v10 *{box-sizing:border-box}',
    '#pulse-market-overview-v10 .npv10-head{display:flex;justify-content:space-between;gap:14px;align-items:flex-start;flex-wrap:wrap;margin-bottom:14px}',
    '#pulse-market-overview-v10 h2{margin:0;font-size:20px;letter-spacing:-.02em;color:#fff}',
    '#pulse-market-overview-v10 .npv10-sub{font-size:11px;color:var(--npv-mut);margin-top:4px;letter-spacing:.04em}',
    '#pulse-market-overview-v10 .npv10-badge{display:inline-flex;align-items:center;gap:7px;padding:7px 11px;border-radius:999px;font-size:10px;font-weight:800;letter-spacing:.08em;background:#132036;border:1px solid #24344f;color:#c7d5ee}',
    '#pulse-market-overview-v10 .npv10-badge i{width:7px;height:7px;border-radius:50%;background:#f59e0b}',
    '#pulse-market-overview-v10 .npv10-badge.open i{background:#34d399;box-shadow:0 0 0 4px rgba(52,211,153,.15)}',
    '#pulse-market-overview-v10 .npv10-refresh{border:1px solid #2a3b57;background:#152238;color:#c9d8f0;border-radius:10px;padding:8px 12px;font-size:11px;font-weight:800;cursor:pointer}',
    '#pulse-market-overview-v10 .npv10-hero{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(0,2fr);gap:12px;margin-bottom:12px}',
    '#pulse-market-overview-v10 .npv10-index{background:linear-gradient(140deg,#13243c,#0f1b2e);border:1px solid #23344e;border-radius:16px;padding:16px}',
    '#pulse-market-overview-v10 .npv10-index small{color:var(--npv-mut);font-size:10px;font-weight:800;letter-spacing:.1em}',
    '#pulse-market-overview-v10 .npv10-index .v{font-size:30px;font-weight:900;letter-spacing:-.03em;margin:6px 0 2px;color:#fff}',
    '#pulse-market-overview-v10 .npv10-index .c{font-size:13px;font-weight:800}',
    '#pulse-market-overview-v10 .npv10-index .p{font-size:10px;color:var(--npv-mut);margin-top:8px}',
    '#pulse-market-overview-v10 .npv10-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}',
    '#pulse-market-overview-v10 .npv10-metric{background:var(--npv-panel);border:1px solid #1e2d45;border-radius:13px;padding:11px 12px;min-width:0}',
    '#pulse-market-overview-v10 .npv10-metric label{display:block;font-size:9px;letter-spacing:.08em;text-transform:uppercase;color:var(--npv-mut);font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '#pulse-market-overview-v10 .npv10-metric b{display:block;font-size:15px;margin-top:5px;color:#fff;font-weight:800;letter-spacing:-.01em}',
    '#pulse-market-overview-v10 .npv10-breadth{background:var(--npv-panel);border:1px solid #1e2d45;border-radius:14px;padding:12px 14px;margin-bottom:14px}',
    '#pulse-market-overview-v10 .npv10-breadth .row{display:flex;justify-content:space-between;font-size:11px;color:var(--npv-mut);font-weight:800;margin-bottom:7px}',
    '#pulse-market-overview-v10 .npv10-bbar{display:flex;height:9px;border-radius:999px;overflow:hidden;background:#1b2a42}',
    '#pulse-market-overview-v10 .npv10-bbar i{display:block;height:100%}',
    '#pulse-market-overview-v10 .npv10-lists{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}',
    '#pulse-market-overview-v10 .npv10-panel{background:var(--npv-panel);border:1px solid #1e2d45;border-radius:16px;padding:13px 13px 8px;min-width:0}',
    '#pulse-market-overview-v10 .npv10-panel h3{margin:0 0 2px;font-size:12px;letter-spacing:.03em;color:#fff}',
    '#pulse-market-overview-v10 .npv10-panel .hint{font-size:9.5px;color:var(--npv-mut);margin-bottom:8px}',
    '#pulse-market-overview-v10 .npv10-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:8px;align-items:center;padding:8px 4px;border-top:1px solid #1b2941;cursor:pointer}',
    '#pulse-market-overview-v10 .npv10-row:first-of-type{border-top:0}',
    '#pulse-market-overview-v10 .npv10-row:hover{background:#16243a}',
    '#pulse-market-overview-v10 .npv10-sym{font-size:12px;font-weight:800;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '#pulse-market-overview-v10 .npv10-co{font-size:9px;color:var(--npv-mut);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px}',
    '#pulse-market-overview-v10 .npv10-val{font-size:11px;font-weight:800;color:#dfe9fb;text-align:right}',
    '#pulse-market-overview-v10 .npv10-chg{font-size:10.5px;font-weight:800;text-align:right;min-width:56px}',
    '#pulse-market-overview-v10 .npv10-sectors{display:flex;gap:8px;overflow-x:auto;padding:4px 2px 8px;scrollbar-width:none}',
    '#pulse-market-overview-v10 .npv10-sectors::-webkit-scrollbar{display:none}',
    '#pulse-market-overview-v10 .npv10-sector{flex:0 0 auto;background:var(--npv-panel2);border:1px solid #23344e;border-radius:11px;padding:9px 12px;min-width:118px}',
    '#pulse-market-overview-v10 .npv10-sector small{display:block;font-size:9px;color:var(--npv-mut);max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '#pulse-market-overview-v10 .npv10-sector b{font-size:12px}',
    '#pulse-market-overview-v10 .npv10-news{margin-top:12px;background:var(--npv-panel);border:1px solid #1e2d45;border-radius:16px;padding:12px 14px}',
    '#pulse-market-overview-v10 .npv10-news li{font-size:11px;color:#c4d2e8;padding:6px 0;border-top:1px solid #1b2941;list-style:none}',
    '#pulse-market-overview-v10 .npv10-news li:first-child{border-top:0}',
    '#pulse-market-overview-v10 .npv10-empty{font-size:11px;color:var(--npv-mut);padding:12px 4px}',
    '.npv10-up{color:#16a34a}#pulse-market-overview-v10 .npv10-up{color:#34d399}.npv10-down{color:#dc2626}#pulse-market-overview-v10 .npv10-down{color:#f87171}#pulse-market-overview-v10 .npv10-flat{color:#94a3b8}',
    '#pulse-market-overview-v10 .npv10-pull{text-align:center;font-size:10px;color:var(--npv-mut);height:0;overflow:hidden;transition:height .15s}',
    '#pulse-market-overview-v10.pulling .npv10-pull{height:26px;padding-top:4px}',
    '@media(max-width:820px){#pulse-market-overview-v10{margin:14px 8px 6px;padding:14px;border-radius:18px}#pulse-market-overview-v10 .npv10-hero{grid-template-columns:1fr}#pulse-market-overview-v10 .npv10-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}#pulse-market-overview-v10 .npv10-lists{grid-template-columns:1fr}#pulse-market-overview-v10 .npv10-index .v{font-size:26px}}',
    /* shared tool UI */
    '.npv10-tool{background:#f7f9fc;color:#14233b;font-family:inherit;min-height:100%;padding:2px}',
    '.npv10-tool-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start;flex-wrap:wrap;margin-bottom:14px}',
    '.npv10-tool-head h2{margin:0;font-size:22px;letter-spacing:-.02em;color:#0f1f38}',
    '.npv10-tool-head p{margin:5px 0 0;font-size:12px;color:#6b7b93;max-width:640px;line-height:1.5}',
    '.npv10-kicker{font-size:9px;font-weight:900;letter-spacing:.14em;color:#2563eb;text-transform:uppercase}',
    '.npv10-card{background:#fff;border:1px solid #e2e8f2;border-radius:16px;padding:14px;box-shadow:0 4px 16px rgba(20,35,59,.04)}',
    '.npv10-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}',
    '.npv10-grid.two{grid-template-columns:minmax(0,1fr) minmax(0,1.6fr)}',
    '.npv10-label{display:block;font-size:10px;font-weight:900;letter-spacing:.07em;text-transform:uppercase;color:#64748b;margin:0 0 5px}',
    '.npv10-input,.npv10-select{width:100%;height:42px;border:1px solid #d7e0ec;border-radius:10px;background:#fff;color:#0f1f38;padding:0 11px;font:700 13px inherit;outline:none}',
    '.npv10-input:focus,.npv10-select:focus{border-color:#2563eb;box-shadow:0 0 0 3px rgba(37,99,235,.13)}',
    '.npv10-field{margin-bottom:10px;min-width:0}',
    '.npv10-btn{border:0;border-radius:10px;background:#1d4ed8;color:#fff;font:800 12px inherit;padding:10px 14px;min-height:40px;cursor:pointer}',
    '.npv10-btn:disabled{opacity:.5;cursor:not-allowed}',
    '.npv10-btn.alt{background:#fff;color:#1e3a5f;border:1px solid #d7e0ec}',
    '.npv10-btn.dark{background:#0f172a}',
    '.npv10-btn.danger{background:#be123c}',
    '.npv10-btn.sm{padding:7px 11px;min-height:34px;font-size:11px}',
    '.npv10-pills{display:flex;gap:6px;flex-wrap:wrap;align-items:center}',
    '.npv10-pill{border:1px solid #dbe3ee;background:#fff;color:#334155;border-radius:8px;padding:7px 10px;font:800 10.5px inherit;cursor:pointer}',
    '.npv10-pill.active{background:#0f172a;color:#fff;border-color:#0f172a}',
    '.npv10-note{font-size:10.5px;color:#64748b;line-height:1.55;background:#eff4fb;border:1px solid #e0e9f5;border-radius:10px;padding:9px 11px}',
    '.npv10-result{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:9px}',
    '.npv10-stat{background:#f4f7fc;border:1px solid #e4ebf5;border-radius:12px;padding:10px 11px}',
    '.npv10-stat small{display:block;font-size:9px;letter-spacing:.06em;text-transform:uppercase;color:#6b7b93;font-weight:900}',
    '.npv10-stat b{display:block;margin-top:4px;font-size:15px;color:#0f1f38}',
    '.npv10-toast{position:fixed;left:50%;bottom:96px;transform:translateX(-50%);background:#0f172a;color:#fff;font:700 12px system-ui;padding:10px 16px;border-radius:10px;z-index:120000;opacity:0;pointer-events:none;transition:opacity .18s}',
    '.npv10-toast.show{opacity:1}',
    '.npv10-tools-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:11px}',
    '.npv10-tool-card{display:flex;gap:12px;align-items:flex-start;text-align:left;background:linear-gradient(140deg,#ffffff,#f3f7ff);border:1px solid #e0e8f4;border-radius:16px;padding:14px;cursor:pointer;box-shadow:0 4px 14px rgba(20,35,59,.045);transition:transform .15s,box-shadow .15s}',
    '.npv10-tool-card:hover{transform:translateY(-2px);box-shadow:0 10px 24px rgba(20,35,59,.1)}',
    '.npv10-tool-card .ico{width:38px;height:38px;flex:0 0 38px;border-radius:11px;display:flex;align-items:center;justify-content:center;font-size:19px;background:#e7efff;color:#1d4ed8}',
    '.npv10-tool-card b{display:block;font-size:13px;color:#0f1f38;margin-bottom:3px}',
    '.npv10-tool-card small{display:block;font-size:10.5px;color:#6b7b93;line-height:1.45}',
    '.npv10-tbl-wrap{overflow:auto;border:1px solid #e4ebf5;border-radius:12px}',
    '.npv10-tbl{width:100%;border-collapse:collapse;font-size:11.5px;min-width:620px}',
    '.npv10-tbl th{position:sticky;top:0;background:#f1f5fb;color:#51637d;text-align:left;font-size:9px;letter-spacing:.07em;text-transform:uppercase;padding:8px 9px;border-bottom:1px solid #e2e8f2;white-space:nowrap}',
    '.npv10-tbl td{padding:8px 9px;border-bottom:1px solid #eef2f8;color:#1e2b3f;white-space:nowrap}',
    '.npv10-tbl tr:last-child td{border-bottom:0}',
    '.npv10-tbl tbody tr{cursor:pointer}.npv10-tbl tbody tr:hover{background:#f6f9ff}',
    '#npv10-adv{background:#fff;border:1px solid #dbe3ee;border-radius:16px;margin:0 16px 14px;padding:12px 14px}',
    '#npv10-adv .npv10-adv-head{display:flex;justify-content:space-between;gap:10px;align-items:flex-start;flex-wrap:wrap;margin-bottom:8px}',
    '#npv10-adv .npv10-adv-head b{display:block;font-size:13px;color:#0f1f38}',
    '#npv10-adv .npv10-adv-head span{display:block;font-size:10.5px;color:#6b7b93;margin-top:2px}',
    '#npv10-adv .npv10-glabel{font-size:9px;font-weight:900;letter-spacing:.1em;color:#64748b;margin-right:2px}',
    '#npv10-adv .npv10-adv-meta{font-size:10.5px;color:#51637d;font-weight:700;margin:0 0 6px}',
    '#npv10-adv .npv10-adv-host{position:relative;border:1px solid #e6edf6;border-radius:12px;overflow:hidden;background:#fff}',
    '#npv10-adv .npv10-adv-note{font-size:10px;color:#7f8ea6;margin-top:7px}',
    '@media(max-width:820px){#npv10-adv{margin:0 8px 10px}}',
    '.npv10-chartbox{position:relative;background:#fff;border:1px solid #e2e8f2;border-radius:14px;overflow:hidden;height:440px}',
    '.npchart-host{background:#fff}.npchart-legend{position:absolute;left:10px;top:8px;right:10px;display:flex;gap:10px;flex-wrap:wrap;font:700 10px system-ui,sans-serif;color:#475569;pointer-events:none;z-index:2;background:rgba(255,255,255,.82);border-radius:8px;padding:5px 7px}',
    '.npchart-legend i{display:inline-block;width:8px;height:8px;border-radius:2px;margin-right:4px}',
    '.npchart-legend b{color:#0f172a}.npch-up{color:#16a34a}.npch-down{color:#dc2626}.npch-sym{font-weight:900;color:#0f172a;letter-spacing:.04em}.npch-date{color:#64748b;margin-left:auto}',
    '@media(max-width:820px){.npv10-grid.two{grid-template-columns:1fr}.npv10-chartbox{height:360px}.npv10-tool-head h2{font-size:19px}}'
  ].join('\n');

  function injectStyle() {
    if ($('npv10-styles')) return;
    var st = document.createElement('style');
    st.id = 'npv10-styles';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  function toast(msg) {
    var el = $('npv10-toast');
    if (!el) { el = document.createElement('div'); el.id = 'npv10-toast'; el.className = 'npv10-toast'; document.body.appendChild(el); }
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.remove('show'); }, 2400);
  }

  /* ------------------------------------------------------------ open stock */
  function openStock(symbol) {
    var sym = String(symbol || '').trim().toUpperCase();
    if (!sym) return;
    try {
      if (typeof window.npOpenCompany === 'function') { window.npOpenCompany(sym); return; }
      if (typeof window.openCompanyPage === 'function') { window.openCompanyPage(sym); return; }
      if (typeof window.openStockXRay === 'function') {
        var input = $('xrSymbol');
        if (input) input.value = sym;
        window.openStockXRay();
        if (typeof window.loadStockXRay === 'function') window.loadStockXRay();
        return;
      }
    } catch (e) { /* fall through to search */ }
    var box = $('searchBox');
    if (box) { box.value = sym; if (typeof window.searchPulse === 'function') window.searchPulse(); }
  }

  /* ------------------------------------------------------------- dashboard */
  function buildDashboard() {
    if ($('pulse-market-overview-v10')) return;
    var section = document.createElement('section');
    section.id = 'pulse-market-overview-v10';
    section.setAttribute('aria-label', 'NEPSE market overview');
    section.innerHTML =
      '<div class="npv10-pull" id="npv10Pull">Pull to refresh</div>' +
      '<div class="npv10-head">' +
        '<div><div class="npv10-kicker" style="color:#7dd3fc">MARKET OVERVIEW</div><h2>NEPSE Market Pulse</h2>' +
        '<div class="npv10-sub" id="npv10MarketStamp">Loading verified market data...</div></div>' +
        '<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">' +
          '<span class="npv10-badge" id="npv10MarketState"><i></i><span>CONNECTING</span></span>' +
          '<button type="button" class="npv10-refresh" id="npv10Refresh">Refresh</button>' +
        '</div>' +
      '</div>' +
      '<div class="npv10-hero">' +
        '<div class="npv10-index"><small>NEPSE INDEX</small><div class="v" id="npv10IndexValue">--</div>' +
          '<div class="c" id="npv10IndexChange">--</div>' +
          '<div class="p" id="npv10IndexPrev">Previous close --</div></div>' +
        '<div class="npv10-metrics" id="npv10Metrics"></div>' +
      '</div>' +
      '<div class="npv10-breadth"><div class="row"><span id="npv10BreadthLabel">Market breadth</span><span id="npv10BreadthText">--</span></div>' +
        '<div class="npv10-bbar" id="npv10BreadthBar"></div></div>' +
      '<div class="npv10-lists">' +
        '<div class="npv10-panel"><h3>Top Volume</h3><div class="hint">Highest traded share quantity</div><div id="npv10TopVolume"></div></div>' +
        '<div class="npv10-panel"><h3>Top Transactions</h3><div class="hint">Most executed contracts</div><div id="npv10TopTransactions"></div></div>' +
        '<div class="npv10-panel"><h3>Top Turnover</h3><div class="hint">Highest traded value</div><div id="npv10TopTurnover"></div></div>' +
        '<div class="npv10-panel"><h3>Gainers &amp; Losers</h3><div class="hint">Session price movement</div><div id="npv10Movers"></div></div>' +
      '</div>' +
      '<div class="npv10-panel" style="margin-top:12px"><h3>Sector Performance</h3><div class="hint">NEPSE sub-index movement</div><div class="npv10-sectors" id="npv10Sectors"></div></div>' +
      '<div class="npv10-news"><h3 style="margin:0 0 4px;font-size:12px;color:#fff">Market News &amp; Notices</h3><ul id="npv10News" style="margin:0;padding:0"><li class="npv10-empty">Loading notices...</li></ul></div>';

    var anchor = $('pulse-home-market-dashboard');
    if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(section, anchor.nextSibling);
    else {
      var main = document.querySelector('main');
      if (main) main.appendChild(section); else document.body.appendChild(section);
    }
    $('npv10Refresh').addEventListener('click', function () { refreshDashboard(true); });
    bindPullToRefresh(section);
    section.addEventListener('click', function (e) {
      var row = e.target.closest('[data-npv-symbol]');
      if (row) openStock(row.getAttribute('data-npv-symbol'));
    });
  }

  function bindPullToRefresh(section) {
    var startY = null, armed = false, busy = false;
    section.addEventListener('touchstart', function (e) {
      if (window.scrollY > 4 || e.touches.length !== 1) { startY = null; return; }
      startY = e.touches[0].clientY; armed = false;
    }, { passive: true });
    section.addEventListener('touchmove', function (e) {
      if (startY === null || busy) return;
      var dy = e.touches[0].clientY - startY;
      if (dy > 20) section.classList.add('pulling');
      if (dy > 80 && !armed) { armed = true; var lbl = $('npv10Pull'); if (lbl) lbl.textContent = 'Release to refresh'; }
    }, { passive: true });
    section.addEventListener('touchend', function () {
      if (armed && !busy) { busy = true; refreshDashboard(true).finally(function () { busy = false; }); }
      var lbl = $('npv10Pull'); if (lbl) lbl.textContent = 'Pull to refresh';
      section.classList.remove('pulling'); startY = null; armed = false;
    });
  }

  function listHtml(rows, valueFn, label) {
    if (!rows.length) return '<div class="npv10-empty">No verified rows returned for this list.</div>';
    return rows.slice(0, 8).map(function (r) {
      return '<div class="npv10-row" data-npv-symbol="' + esc(r.symbol) + '">' +
        '<div><div class="npv10-sym">' + esc(r.symbol) + '</div><div class="npv10-co">' + esc(r.name) + '</div></div>' +
        '<div class="npv10-val">' + valueFn(r) + '</div>' +
        '<div class="npv10-chg ' + cls(r.pct) + '">' + (label === 'pct' ? pct(r.pct) : fmt(r.ltp)) + '</div></div>';
    }).join('');
  }

  function renderDashboard(snap) {
    if (!snap || !$('pulse-market-overview-v10')) return;
    var s = stampText(snap);
    var state = $('npv10MarketState');
    state.classList.toggle('open', !!s.open);
    state.querySelector('span').textContent = s.open ? 'MARKET OPEN' : 'MARKET CLOSED';
    state.querySelector('i').style.background = s.open ? '#34d399' : '#f59e0b';
    $('npv10MarketStamp').textContent = s.text + ' - ' + snap.source;

    var idx = snap.index;
    $('npv10IndexValue').textContent = fmt(idx.value);
    var chg = idx.change !== null ? idx.change : (idx.value !== null && idx.prevClose !== null ? idx.value - idx.prevClose : null);
    var chgPct = idx.pct !== null ? idx.pct : (chg !== null && idx.prevClose ? (chg / idx.prevClose) * 100 : null);
    $('npv10IndexChange').innerHTML = '<span class="' + cls(chg) + '">' + (chg === null ? '--' : (chg > 0 ? '+' : '') + fmt(chg) + ' (' + pct(chgPct) + ')') + '</span>';
    $('npv10IndexPrev').textContent = 'Previous close ' + fmt(idx.prevClose) + ' - Session high ' + fmt(idx.high) + ' / low ' + fmt(idx.low);

    var m = snap.summary;
    var metrics = [
      ['Total Turnover', money(m.turnover)],
      ['Traded Shares', fmt(m.shares, 0)],
      ['Transactions', fmt(m.transactions, 0)],
      ['Scrips Traded', fmt(m.scrips, 0)],
      ['Advances', fmt(snap.breadth.advancing, 0)],
      ['Declines', fmt(snap.breadth.declining, 0)],
      ['Unchanged', fmt(snap.breadth.unchanged, 0)],
      ['Market Cap', m.marketCap ? money(m.marketCap) : '--'],
      ['Float Cap', m.floatCap ? money(m.floatCap) : '--']
    ];
    $('npv10Metrics').innerHTML = metrics.map(function (x) {
      return '<div class="npv10-metric"><label>' + x[0] + '</label><b>' + x[1] + '</b></div>';
    }).join('');

    var total = snap.breadth.advancing + snap.breadth.declining + snap.breadth.unchanged;
    var ap = total ? (snap.breadth.advancing / total) * 100 : 0;
    var dp = total ? (snap.breadth.declining / total) * 100 : 0;
    var up = total ? (snap.breadth.unchanged / total) * 100 : 0;
    $('npv10BreadthText').innerHTML = '<span class="npv10-up">' + snap.breadth.advancing + ' adv</span> / <span class="npv10-down">' + snap.breadth.declining + ' dec</span> / ' + snap.breadth.unchanged + ' unch';
    $('npv10BreadthBar').innerHTML =
      '<i style="width:' + ap + '%;background:#34d399"></i><i style="width:' + dp + '%;background:#f87171"></i>' +
      '<i style="width:' + up + '%;background:#64748b"></i>';

    var rows = snap.rows.slice();
    var byVolume = rows.slice().sort(function (a, b) { return (b.volume || 0) - (a.volume || 0); });
    var byTurnover = rows.slice().sort(function (a, b) { return (b.turnover || 0) - (a.turnover || 0); });
    var byTx = rows.filter(function (r) { return r.transactions !== null; }).sort(function (a, b) { return (b.transactions || 0) - (a.transactions || 0); });
    $('npv10TopVolume').innerHTML = listHtml(byVolume, function (r) { return fmt(r.volume, 0) + ' sh'; }, 'pct');
    $('npv10TopTurnover').innerHTML = listHtml(byTurnover, function (r) { return money(r.turnover); }, 'pct');
    $('npv10TopTransactions').innerHTML = byTx.length
      ? listHtml(byTx, function (r) { return fmt(r.transactions, 0) + ' tx'; }, 'pct')
      : '<div class="npv10-empty">Transaction counts are unavailable from the feed right now.</div>';

    var gainers = rows.filter(function (r) { return r.pct !== null && r.pct > 0; }).sort(function (a, b) { return b.pct - a.pct; }).slice(0, 4);
    var losers = rows.filter(function (r) { return r.pct !== null && r.pct < 0; }).sort(function (a, b) { return a.pct - b.pct; }).slice(0, 4);
    $('npv10Movers').innerHTML =
      (gainers.length ? gainers.map(moverRow).join('') : '<div class="npv10-empty">No advancing scrips reported.</div>') +
      (losers.length ? losers.map(moverRow).join('') : '<div class="npv10-empty">No declining scrips reported.</div>');

    loadSectors();
    loadNews();
  }

  function moverRow(r) {
    return '<div class="npv10-row" data-npv-symbol="' + esc(r.symbol) + '">' +
      '<div><div class="npv10-sym">' + esc(r.symbol) + '</div><div class="npv10-co">' + esc(r.name) + '</div></div>' +
      '<div class="npv10-val">' + fmt(r.ltp) + '</div>' +
      '<div class="npv10-chg ' + cls(r.pct) + '">' + pct(r.pct) + '</div></div>';
  }

  var sectorsAt = 0;
  async function loadSectors() {
    if (Date.now() - sectorsAt < 120000) return;
    try {
      var d = await fetchJSON('/api/sectors');
      sectorsAt = Date.now();
      var list = (d.data || d.sectors || []).filter(function (x) { return x && (x.sector || x.name); });
      var box = $('npv10Sectors');
      if (!box) return;
      if (!list.length) { box.innerHTML = '<div class="npv10-empty">Sector data unavailable.</div>'; return; }
      box.innerHTML = list.slice(0, 18).map(function (s) {
        var p = num(s.changePercent) !== null ? num(s.changePercent) : num(s.change);
        return '<div class="npv10-sector"><small>' + esc(s.sector || s.name) + '</small>' +
          '<b class="' + cls(p) + '">' + pct(p) + '</b></div>';
      }).join('');
    } catch (e) {
      var b = $('npv10Sectors');
      if (b) b.innerHTML = '<div class="npv10-empty">Sector data unavailable.</div>';
    }
  }

  var newsAt = 0;
  async function loadNews() {
    if (Date.now() - newsAt < 300000) return;
    var box = $('npv10News');
    if (!box) return;
    var routes = ['/api/production/notices', '/api/production/disclosures', '/api/production/events'];
    for (var i = 0; i < routes.length; i++) {
      try {
        var d = await fetchJSON(routes[i]);
        var list = d.data || d.notices || d.disclosures || d.events || [];
        if (Array.isArray(list) && list.length) {
          newsAt = Date.now();
          box.innerHTML = list.slice(0, 6).map(function (n) {
            var title = n.title || n.subject || n.heading || n.notice || n.description || n.message || '';
            var when = n.date || n.publishedAt || n.noticeDate || n.addedDate || '';
            return '<li>' + esc(String(title).slice(0, 180)) + (when ? '<span style="float:right;color:#7f8ea6;margin-left:12px">' + esc(String(when).slice(0, 10)) + '</span>' : '') + '</li>';
          }).join('');
          return;
        }
      } catch (e) { /* try the next notices route */ }
    }
    box.innerHTML = '<li class="npv10-empty">No notices returned by the backend right now.</li>';
  }

  function refreshDashboard(force) {
    var btn = $('npv10Refresh');
    if (btn) { btn.disabled = true; btn.textContent = 'Refreshing...'; }
    return loadMarket(!!force).then(function (snap) {
      renderDashboard(snap);
      try { document.dispatchEvent(new CustomEvent('npv10-market', { detail: { at: snap.updatedAt } })); } catch (e) {}
      return snap;
    }).catch(function (e) {
      var stamp = $('npv10MarketStamp');
      if (stamp) stamp.textContent = 'Market data unavailable: ' + e.message;
      var st = $('npv10MarketState');
      if (st) { st.classList.remove('open'); st.querySelector('span').textContent = 'FEED OFFLINE'; st.querySelector('i').style.background = '#f87171'; }
      throw e;
    }).finally(function () {
      if (btn) { btn.disabled = false; btn.textContent = 'Refresh'; }
    });
  }

  /* -------------------------------------------------- advanced chart panel */
  function buildAdvancedChart() {
    var root = $('professional-charting-v8');
    if (!root || $('npv10-adv')) return;
    var panel = document.createElement('div');
    panel.id = 'npv10-adv';
    panel.innerHTML =
      '<div class="npv10-adv-head"><div><b>Pulse Advanced Chart</b><span>Interactive candles, indicators and verified history</span></div>' +
      '<div class="npv10-pills"><input id="npv10AdvSymbol" class="npv10-input" style="height:34px;width:120px;padding:0 9px" value="NABIL" aria-label="Chart symbol"><button class="npv10-btn sm" id="npv10AdvLoad">Load</button></div></div>' +
      '<div class="npv10-pills" id="npv10AdvRanges" style="margin:8px 0"><span class="npv10-glabel">RANGE</span>' +
        ['1D', '5D', '1M', '3M', '6M', 'YTD', '1Y', '5Y', 'MAX'].map(function (r, i) {
          return '<button class="npv10-pill' + (i === 6 ? ' active' : '') + '" data-npvr="' + r + '">' + r + '</button>';
        }).join('') + '</div>' +
      '<div class="npv10-pills" id="npv10AdvIntervals" style="margin:0 0 8px"><span class="npv10-glabel">INTERVAL</span>' +
        [['1', '1m'], ['5', '5m'], ['15', '15m'], ['30', '30m'], ['60', '1h'], ['D', 'Daily'], ['W', 'Weekly'], ['M', 'Monthly']].map(function (r, i) {
          return '<button class="npv10-pill' + (i === 5 ? ' active' : '') + '" data-npvi="' + r[0] + '">' + r[1] + '</button>';
        }).join('') + '</div>' +
      '<div class="npv10-pills" id="npv10AdvInds" style="margin:0 0 8px"><span class="npv10-glabel">INDICATORS</span>' +
        [['volume', 'Volume', true], ['sma20', 'SMA 20'], ['ema20', 'EMA 20'], ['bb', 'Bollinger'], ['vwap', 'VWAP'], ['sr', 'S/R'], ['rsi', 'RSI'], ['macd', 'MACD']].map(function (x) {
          return '<button class="npv10-pill' + (x[2] ? ' active' : '') + '" data-npvi2="' + x[0] + '">' + x[1] + '</button>';
        }).join('') + '</div>' +
      '<div class="npv10-adv-meta" id="npv10AdvMeta">Loading candles...</div>' +
      '<div class="npv10-adv-host" id="npv10AdvHost"></div>' +
      '<div class="npv10-adv-note" id="npv10AdvNote">Wheel to zoom, drag to scroll, double-click to fit.</div>';
    var shell = root.querySelector('.txp-chart-shell');
    if (shell && shell.parentNode) shell.parentNode.insertBefore(panel, shell);
    else root.appendChild(panel);

    var state = { symbol: 'NABIL', interval: 'D', range: '1Y', candles: [], chart: null };
    var chart = new window.NpChart($('npv10AdvHost'), { visible: 90, intraday: false });
    state.chart = chart;
    $('npv10AdvHost').style.height = '470px';

    function setMeta(text) { var m = $('npv10AdvMeta'); if (m) m.textContent = text; }

    async function load() {
      var sym = String($('npv10AdvSymbol').value || '').trim().toUpperCase();
      if (!/^[A-Z0-9._-]{1,24}$/.test(sym)) { setMeta('Enter a valid NEPSE symbol.'); return; }
      state.symbol = sym;
      var interval = state.interval;
      var intraday = ['1', '5', '15', '30', '60'].indexOf(interval) !== -1;
      chart.intraday = intraday;
      setMeta('Loading ' + sym + ' ' + (intraday ? interval + '-minute' : interval) + ' candles...');
      try {
        var d = await fetchJSON('/api/tv/history?symbol=' + encodeURIComponent(sym) + '&resolution=' + interval + '&countback=' + (intraday ? 500 : 2000));
        if (d.s !== 'ok' || !Array.isArray(d.t) || !d.t.length) {
          setMeta(intraday
            ? 'Intraday ' + interval + '-minute history is not available from the verified NEPSE feed yet. No data was invented; use Daily, Weekly or Monthly.'
            : 'No verified ' + interval + ' candles were returned for ' + sym + '.');
          if (!state.candles.length) chart.draw();
          return;
        }
        var rows = [];
        for (var i = 0; i < d.t.length; i++) {
          var c = { time: Number(d.t[i]) * 1000, open: Number(d.o[i]), high: Number(d.h[i]), low: Number(d.l[i]), close: Number(d.c[i]), volume: Number(d.v ? d.v[i] : 0) || 0 };
          if ([c.open, c.high, c.low, c.close].every(Number.isFinite)) rows.push(c);
        }
        if (!rows.length) { setMeta('Feed returned no usable candles for ' + sym + '.'); return; }
        state.candles = rows;
        applyRange(true);
        setMeta(sym + ' - ' + rows.length + ' ' + (intraday ? interval + 'm' : interval) + ' candles - verified NEPSE feed');
        var note = $('npv10AdvNote');
        if (note) note.textContent = 'Wheel/pinch to zoom, drag to scroll, double-click to fit. ' + (intraday ? 'Intraday series: ' + interval + 'm.' : '');
        // keep the legacy TradeX terminal on the same symbol
        var legacy = document.getElementById('txpSymbol');
        if (legacy) legacy.value = sym;
      } catch (e) {
        setMeta('Chart data unavailable: ' + e.message);
      }
    }

    function applyRange(resetVisible) {
      var rows = state.candles;
      if (!rows.length) return;
      var days = { '1D': 1, '5D': 5, '1M': 31, '3M': 92, '6M': 183, 'YTD': 0, '1Y': 366, '5Y': 1830, 'MAX': 100000 }[state.range];
      var cutoff;
      if (state.range === 'YTD') cutoff = new Date(new Date().getFullYear(), 0, 1).getTime();
      else cutoff = Date.now() - days * 86400000;
      var start = 0;
      for (var i = 0; i < rows.length; i++) { if (rows[i].time >= cutoff) { start = i; break; } }
      var slice = rows.slice(start);
      if (slice.length < 2) slice = rows.slice(-Math.min(rows.length, 2));
      chart.visible = Math.max(12, Math.min(slice.length, state.range === '1D' ? 30 : 90));
      chart.setData(slice, { symbol: state.symbol });
      if (resetVisible) chart.fit();
    }

    $('npv10AdvLoad').addEventListener('click', load);
    $('npv10AdvSymbol').addEventListener('keydown', function (e) { if (e.key === 'Enter') load(); });
    $$('#npv10AdvRanges [data-npvr]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        state.range = btn.getAttribute('data-npvr');
        $$('#npv10AdvRanges [data-npvr]').forEach(function (b) { b.classList.toggle('active', b === btn); });
        applyRange(true);
      });
    });
    $$('#npv10AdvIntervals [data-npvi]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        state.interval = btn.getAttribute('data-npvi');
        $$('#npv10AdvIntervals [data-npvi]').forEach(function (b) { b.classList.toggle('active', b === btn); });
        load();
      });
    });
    $$('#npv10AdvInds [data-npvi2]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var key = btn.getAttribute('data-npvi2');
        var on = !btn.classList.contains('active');
        btn.classList.toggle('active', on);
        var patch = {}; patch[key] = on;
        chart.setIndicators(patch);
      });
    });
    window.addEventListener('npv10-chart-symbol', function (e) {
      var sym = e.detail && e.detail.symbol;
      if (!sym) return;
      $('npv10AdvSymbol').value = sym;
      load();
    });
    load();
  }

  /* ---------------------------------------------------------------- routing */
  function buildToolsHub() {
    if ($('pulse-tools-v10')) return;
    var section = document.createElement('section');
    section.id = 'pulse-tools-v10';
    section.className = 'pulse-home-feature-module npv10-tool';
    section.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">TRADING TOOLS</div><h2>Pulse Tools</h2>' +
      '<p>Chart replay, position sizing, simulations, portfolio risk and side-by-side stock comparison. All calculations use verified market history or the values you enter.</p></div></div>' +
      '<div class="npv10-tools-grid">' +
        toolCard('replay', '&#9201;', 'Chart Replay', 'Replay verified historical candles bar by bar without future data.') +
        toolCard('risk', '&#128200;', 'Risk Calculator', 'Position size, maximum risk and risk-to-reward for long or short trades.') +
        toolCard('hypo', '&#128188;', 'Hypothetical Positions', 'Simulated long/short positions with unrealised and realised P&amp;L.') +
        toolCard('scenario', '&#127919;', 'Scenario Simulator', 'Model bullish and bearish price paths and the resulting portfolio value.') +
        toolCard('portfolio-risk', '&#128202;', 'Portfolio Risk', 'Drawdown, volatility, exposure and diversification for your holdings.') +
        toolCard('compare', '&#8644;', 'Stock Comparison', 'Compare normalised performance of up to five NEPSE symbols.') +
        toolCard('floorsheet', '&#127974;', 'Daily Floorsheet', 'Every stored contract with paging, filters and broker analysis.') +
      '</div>';
    var main = document.querySelector('main');
    (main || document.body).appendChild(section);
  }

  function toolCard(id, icon, title, text) {
    return '<button type="button" class="npv10-tool-card" data-npv-tool="' + id + '">' +
      '<span class="ico">' + icon + '</span><span><b>' + title + '</b><small>' + text + '</small></span></button>';
  }

  function registerRoutes() {
    var prevOpenModule = window.openModule;
    var map = {
      'Chart Replay': ['npv10-replay', 'Chart Replay'],
      'Risk Calculator': ['npv10-risk', 'Risk Calculator'],
      'Risk Management': ['npv10-risk', 'Risk Calculator'],
      'Hypothetical Positions': ['npv10-hypo', 'Hypothetical Positions'],
      'Scenario Simulator': ['npv10-scenario', 'Scenario Simulator'],
      'Portfolio Risk': ['npv10-portfolio-risk', 'Portfolio Risk'],
      'Stock Comparison': ['npv10-compare', 'Stock Comparison'],
      'Daily Floorsheet': ['npv10-floorsheet', 'Daily Floorsheet'],
      'Floorsheet': ['npv10-floorsheet', 'Daily Floorsheet'],
      'Tools': ['pulse-tools-v10', 'Pulse Tools']
    };
    window.openModule = function (name) {
      var key = String(name || '');
      if (map[key]) {
        if (typeof window.openFeatureWorkspace === 'function') window.openFeatureWorkspace(map[key][0], map[key][1]);
        return;
      }
      return typeof prevOpenModule === 'function' ? prevOpenModule.apply(this, arguments) : undefined;
    };
    document.addEventListener('click', function (e) {
      var card = e.target.closest('[data-npv-tool]');
      if (!card) return;
      var id = card.getAttribute('data-npv-tool');
      var titles = { replay: 'Chart Replay', risk: 'Risk Calculator', hypo: 'Hypothetical Positions', scenario: 'Scenario Simulator', 'portfolio-risk': 'Portfolio Risk', compare: 'Stock Comparison', floorsheet: 'Daily Floorsheet' };
      if (typeof window.openFeatureWorkspace === 'function') window.openFeatureWorkspace('npv10-' + id, titles[id] || 'Pulse Tool');
    });
  }

  function addDrawerEntries() {
    var list = document.querySelector('#featureDrawer .feature-list');
    if (!list || $('npv10DrawerTools')) return;
    var wrap = document.createElement('div');
    wrap.id = 'npv10DrawerTools';
    wrap.innerHTML =
      '<div class="feature-group">TRADING TOOLS</div>' +
      '<button class="feature-item" onclick="openModule(\'Tools\')"><b>&#129517; Pulse Tools</b><small>Replay, risk, simulation, portfolio and comparison</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Chart Replay\')"><b>&#9201; Chart Replay</b><small>Step through verified historical candles</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Risk Calculator\')"><b>&#128200; Risk Calculator</b><small>Position size and risk-to-reward</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Portfolio Risk\')"><b>&#128202; Portfolio Risk</b><small>Drawdown, volatility and diversification</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Scenario Simulator\')"><b>&#127919; Scenario Simulator</b><small>Bullish and bearish price paths</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Hypothetical Positions\')"><b>&#128188; Hypothetical Positions</b><small>Simulated long/short positions</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Stock Comparison\')"><b>&#8644; Stock Comparison</b><small>Normalised multi-stock performance</small></button>' +
      '<button class="feature-item" onclick="openModule(\'Daily Floorsheet\')"><b>&#127974; Daily Floorsheet</b><small>Complete daily contracts with paging and broker analysis</small></button>';
    var trading = null;
    Array.prototype.forEach.call(list.children, function (el) {
      if (el.classList && el.classList.contains('feature-group') && /TRADING TOOLS/i.test(el.textContent || '')) trading = el;
    });
    if (trading && trading.nextSibling) list.insertBefore(wrap, trading.nextSibling);
    else list.insertBefore(wrap, list.firstChild);
  }

  function addHomeEntry() {
    var grid = document.querySelector('main section.grid');
    if (!grid || $('npv10HomeTools')) return;
    var btn = document.createElement('button');
    btn.id = 'npv10HomeTools';
    btn.className = 'card';
    btn.setAttribute('onclick', "openModule('Tools')");
    btn.innerHTML = '<div class="icon">&#129517;</div><h3>Pulse Tools</h3><p>Chart replay, risk, simulations and comparison.</p><span class="tag">TOOLS</span>';
    grid.appendChild(btn);
  }

  /* -------------------------------------------------------------- lifecycle */
  function boot() {
    injectStyle();
    buildDashboard();
    buildToolsHub();
    buildAdvancedChart();
    addDrawerEntries();
    addHomeEntry();
    registerRoutes();
    refreshDashboard(false).catch(function () {});
    setInterval(function () {
      if (document.hidden) return;
      if (marketOpenNow()) refreshDashboard(false).catch(function () {});
    }, 90000);
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && Date.now() - MARKET.at > 120000) refreshDashboard(false).catch(function () {});
    });
    window.dispatchEvent(new CustomEvent('npv10-ready'));
  }

  window.NPV10 = {
    api: API, num: num, pick: pick, esc: esc, fmt: fmt, money: money, pct: pct, cls: cls,
    fetchJSON: fetchJSON, loadMarket: loadMarket, market: function () { return MARKET.data; },
    refreshMarket: function () { return loadMarket(true); }, openStock: openStock,
    toast: toast, store: store, save: save, dateKey: dateKey, marketOpenNow: marketOpenNow,
    buildAdvancedChart: buildAdvancedChart
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else setTimeout(boot, 0);
})();
