/* NEPSE Pulse v10 - risk calculator, hypothetical positions, scenario simulator. */
(function () {
  'use strict';
  if (window.NPV10Tools) return;
  var $ = function (id) { return document.getElementById(id); };
  var V = null;
  var num = function (v) { if (v === null || v === undefined) return null; var raw = String(v).trim(); if (!raw) return null; var n = Number(raw.replace(/,/g, '')); return Number.isFinite(n) ? n : null; };
  var esc = function (s) { return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };
  var money = function (v) { var n = num(v); return n === null ? '--' : 'Rs ' + n.toLocaleString(undefined, { maximumFractionDigits: 2 }); };
  var price = function (v) { var n = num(v); return n === null ? '--' : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };

  /* ---------------------------------------------------------- risk section */
  function buildRisk() {
    if ($('npv10-risk')) return;
    V = window.NPV10;
    var s = document.createElement('section');
    s.id = 'npv10-risk';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">POSITION SIZING</div><h2>Risk Management Calculator</h2>' +
      '<p>Work out maximum monetary risk, position size and risk-to-reward before you take a trade. Long and short setups are both supported.</p></div></div>' +
      '<div class="npv10-grid two">' +
        '<div class="npv10-card">' +
          '<label class="npv10-label">Position direction</label>' +
          '<div class="npv10-field"><select id="npv10RkSide" class="npv10-select"><option value="long">Long (buy)</option><option value="short">Short (sell)</option></select></div>' +
          '<label class="npv10-label">Stock symbol</label>' +
          '<div class="npv10-field"><input id="npv10RkSymbol" class="npv10-input" value="NABIL" placeholder="e.g. NABIL"></div>' +
          '<label class="npv10-label">Account balance (Rs)</label>' +
          '<div class="npv10-field"><input id="npv10RkBalance" class="npv10-input" inputmode="decimal" value="100000"></div>' +
          '<label class="npv10-label">Risk per trade (%)</label>' +
          '<div class="npv10-field"><input id="npv10RkRiskPct" class="npv10-input" inputmode="decimal" value="1.5"></div>' +
          '<div class="npv10-grid" style="grid-template-columns:1fr 1fr;gap:8px">' +
            '<div><label class="npv10-label">Entry price</label><div class="npv10-field"><input id="npv10RkEntry" class="npv10-input" inputmode="decimal" value="550"></div></div>' +
            '<div><label class="npv10-label">Stop loss</label><div class="npv10-field"><input id="npv10RkStop" class="npv10-input" inputmode="decimal" value="530"></div></div>' +
            '<div><label class="npv10-label">Target price</label><div class="npv10-field"><input id="npv10RkTarget" class="npv10-input" inputmode="decimal" value="600"></div></div>' +
            '<div><label class="npv10-label">LTP (optional)</label><div class="npv10-field"><input id="npv10RkLtp" class="npv10-input" inputmode="decimal" placeholder="market price"></div></div>' +
          '</div>' +
          '<div class="npv10-pills"><button class="npv10-btn" id="npv10RkCalc">Calculate</button>' +
          '<button class="npv10-btn alt" id="npv10RkUseLtp">Use market price</button></div>' +
          '<div class="npv10-note" id="npv10RkError" style="margin-top:10px;display:none;background:#fff1f2;border-color:#fecdd3;color:#9f1239"></div>' +
        '</div>' +
        '<div>' +
          '<div class="npv10-card"><div class="npv10-result" id="npv10RkResults"></div></div>' +
          '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Risk profile</label>' +
            '<div id="npv10RkProfile" style="padding:6px 0"></div>' +
            '<div class="npv10-note" style="margin-top:8px">Never risk more than your plan allows. Position size is rounded down to whole shares, so actual risk can be slightly below your limit.</div>' +
          '</div>' +
        '</div>' +
      '</div>';
    (document.querySelector('main') || document.body).appendChild(s);
    ['npv10RkSide', 'npv10RkSymbol', 'npv10RkBalance', 'npv10RkRiskPct', 'npv10RkEntry', 'npv10RkStop', 'npv10RkTarget', 'npv10RkLtp'].forEach(function (id) {
      $(id).addEventListener('input', function () { calcRisk(false); });
      $(id).addEventListener('change', function () { calcRisk(false); });
    });
    $('npv10RkCalc').addEventListener('click', function () { calcRisk(true); });
    $('npv10RkUseLtp').addEventListener('click', useMarketPrice);
    calcRisk(false);
  }

  async function useMarketPrice() {
    var sym = String($('npv10RkSymbol').value || '').trim().toUpperCase();
    try {
      var snap = await (window.NPV10.loadMarket(false));
      var row = (snap.rows || []).filter(function (r) { return r.symbol === sym; })[0];
      if (!row || row.ltp === null) { showRiskError('No market price was returned for ' + sym + '.'); return; }
      $('npv10RkLtp').value = row.ltp;
      if (!$('npv10RkEntry').value) $('npv10RkEntry').value = row.ltp;
      calcRisk(false);
    } catch (e) { showRiskError('Market price unavailable: ' + e.message); }
  }

  function showRiskError(msg) {
    var el = $('npv10RkError');
    el.style.display = msg ? 'block' : 'none';
    el.textContent = msg || '';
  }

  function calcRisk(explicit) {
    if (!$('npv10RkEntry')) return;
    var side = $('npv10RkSide').value;
    var balance = num($('npv10RkBalance').value);
    var riskPct = num($('npv10RkRiskPct').value);
    var entry = num($('npv10RkEntry').value);
    var stop = num($('npv10RkStop').value);
    var target = num($('npv10RkTarget').value);
    var ltp = num($('npv10RkLtp').value);
    var errors = [];
    if (balance === null || balance <= 0) errors.push('Account balance must be greater than zero.');
    if (riskPct === null || riskPct <= 0 || riskPct > 100) errors.push('Risk percentage must be between 0 and 100.');
    if (entry === null || entry <= 0) errors.push('Entry price must be greater than zero.');
    if (stop === null || stop <= 0) errors.push('Stop-loss price must be greater than zero.');
    if (target === null || target <= 0) errors.push('Target price must be greater than zero.');
    if (entry !== null && stop !== null) {
      if (side === 'long' && stop >= entry) errors.push('For a long position the stop-loss must be below the entry price.');
      if (side === 'short' && stop <= entry) errors.push('For a short position the stop-loss must be above the entry price.');
    }
    if (entry !== null && target !== null) {
      if (side === 'long' && target <= entry) errors.push('For a long position the target must be above the entry price.');
      if (side === 'short' && target >= entry) errors.push('For a short position the target must be below the entry price.');
    }
    if (errors.length) {
      showRiskError(errors[0]);
      if (explicit) V.toast(errors[0]);
      $('npv10RkResults').innerHTML = '<div class="npv10-note">Enter valid prices to see the risk plan.</div>';
      $('npv10RkProfile').innerHTML = '';
      return;
    }
    showRiskError('');
    var riskAmount = balance * (riskPct / 100);
    var perShare = Math.abs(entry - stop);
    var shares = Math.floor(riskAmount / perShare);
    var position = shares * entry;
    var maxLoss = shares * perShare;
    var profit = shares * Math.abs(target - entry);
    var rr = maxLoss > 0 ? profit / maxLoss : null;
    var stopPct = (Math.abs(entry - stop) / entry) * 100;
    var targetPct = (Math.abs(target - entry) / entry) * 100;
    var results = [
      ['Max monetary risk', money(riskAmount)],
      ['Risk per share', money(perShare)],
      ['Number of shares', shares.toLocaleString()],
      ['Position size', money(position)],
      ['Potential profit', money(profit)],
      ['Potential loss', money(maxLoss)],
      ['Risk / reward', rr === null ? '--' : '1 : ' + rr.toFixed(2)],
      ['Stop-loss distance', stopPct.toFixed(2) + '%'],
      ['Target distance', targetPct.toFixed(2) + '%'],
      ['Position vs balance', ((position / balance) * 100).toFixed(1) + '%'],
      ['Unrealised (at LTP)', ltp === null ? '--' : money((side === 'long' ? ltp - entry : entry - ltp) * shares)],
      ['Breakeven move', stopPct.toFixed(2) + '% against']
    ];
    $('npv10RkResults').innerHTML = results.map(function (r) {
      return '<div class="npv10-stat"><small>' + r[0] + '</small><b>' + r[1] + '</b></div>';
    }).join('');
    renderRiskProfile(side, entry, stop, target, shares, position, balance);
  }

  function renderRiskProfile(side, entry, stop, target, shares, position, balance) {
    var lo = Math.min(stop, target, entry), hi = Math.max(stop, target, entry);
    var span = hi - lo || 1;
    var pos = function (p) { return ((p - lo) / span) * 100; };
    var box = $('npv10RkProfile');
    box.innerHTML =
      '<div style="position:relative;height:54px;background:linear-gradient(90deg,#fecdd3 0%,#fef3c7 45%,#dcfce7 100%);border-radius:10px;border:1px solid #e5eaf2">' +
        '<div style="position:absolute;left:' + pos(stop) + '%;top:0;bottom:0;width:2px;background:#be123c"></div>' +
        '<div style="position:absolute;left:' + pos(entry) + '%;top:0;bottom:0;width:2px;background:#0f172a"></div>' +
        '<div style="position:absolute;left:' + pos(target) + '%;top:0;bottom:0;width:2px;background:#15803d"></div>' +
        '<div style="position:absolute;left:' + Math.min(96, Math.max(0, pos(stop))) + '%;top:-2px;font-size:9px;font-weight:800;color:#be123c;transform:translateX(-50%)">SL ' + price(stop) + '</div>' +
        '<div style="position:absolute;left:' + Math.min(96, Math.max(1, pos(entry))) + '%;bottom:-2px;font-size:9px;font-weight:800;color:#0f172a;transform:translateX(-50%)">Entry ' + price(entry) + '</div>' +
        '<div style="position:absolute;left:' + Math.min(96, Math.max(0, pos(target))) + '%;top:-2px;font-size:9px;font-weight:800;color:#15803d;transform:translateX(-50%)">TP ' + price(target) + '</div>' +
      '</div>' +
      '<div style="display:flex;justify-content:space-between;font-size:10px;color:#64748b;margin-top:22px">' +
        '<span>' + (side === 'long' ? 'Long' : 'Short') + ' ' + shares.toLocaleString() + ' shares</span>' +
        '<span>Exposure ' + ((position / balance) * 100).toFixed(1) + '% of balance</span></div>';
  }

  /* ------------------------------------------------ hypothetical positions */
  var HYPO_KEY = 'npv10_hypo_v1';
  function hypoState() {
    var st = V.store(HYPO_KEY, null);
    if (!st || typeof st !== 'object') st = { balance: 100000, realized: 0, positions: [] };
    if (!Array.isArray(st.positions)) st.positions = [];
    if (num(st.balance) === null) st.balance = 100000;
    if (num(st.realized) === null) st.realized = 0;
    return st;
  }
  function saveHypo(st) { V.save(HYPO_KEY, st); }

  function buildHypo() {
    if ($('npv10-hypo')) return;
    V = window.NPV10;
    var s = document.createElement('section');
    s.id = 'npv10-hypo';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">SIMULATION ONLY</div><h2>Hypothetical Positions</h2>' +
      '<p>Practise long and short trade management. These are simulated positions stored on this device - they are never sent to NEPSE or any broker.</p></div></div>' +
      '<div class="npv10-grid two">' +
        '<div class="npv10-card">' +
          '<label class="npv10-label">Symbol</label><div class="npv10-field"><input id="npv10HySymbol" class="npv10-input" value="NABIL"></div>' +
          '<div class="npv10-grid" style="grid-template-columns:1fr 1fr;gap:8px">' +
            '<div><label class="npv10-label">Side</label><div class="npv10-field"><select id="npv10HySide" class="npv10-select"><option value="long">Long</option><option value="short">Short</option></select></div></div>' +
            '<div><label class="npv10-label">Quantity</label><div class="npv10-field"><input id="npv10HyQty" class="npv10-input" inputmode="numeric" value="100"></div></div>' +
            '<div><label class="npv10-label">Entry price</label><div class="npv10-field"><input id="npv10HyEntry" class="npv10-input" inputmode="decimal" value="550"></div></div>' +
            '<div><label class="npv10-label">Account balance</label><div class="npv10-field"><input id="npv10HyBalance" class="npv10-input" inputmode="decimal" value="100000"></div></div>' +
            '<div><label class="npv10-label">Stop-loss</label><div class="npv10-field"><input id="npv10HyStop" class="npv10-input" inputmode="decimal" placeholder="optional"></div></div>' +
            '<div><label class="npv10-label">Take-profit</label><div class="npv10-field"><input id="npv10HyTarget" class="npv10-input" inputmode="decimal" placeholder="optional"></div></div>' +
          '</div>' +
          '<div class="npv10-pills"><button class="npv10-btn" id="npv10HyAdd">Open simulated position</button>' +
          '<button class="npv10-btn alt" id="npv10HyRefresh">Refresh prices</button>' +
          '<button class="npv10-btn danger" id="npv10HyClear">Clear all</button></div>' +
          '<div class="npv10-note" style="margin-top:10px">Simulation only. No order is placed and no money moves.</div>' +
        '</div>' +
        '<div>' +
          '<div class="npv10-card"><div class="npv10-result" id="npv10HyStats"></div></div>' +
          '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Open simulated positions</label>' +
          '<div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Symbol</th><th>Side</th><th>Qty</th><th>Entry</th><th>Mark</th><th>Unrealised</th><th>SL / TP</th><th></th></tr></thead><tbody id="npv10HyRows"></tbody></table></div></div>' +
        '</div>' +
      '</div>';
    (document.querySelector('main') || document.body).appendChild(s);

    var st = hypoState();
    $('npv10HyBalance').value = st.balance;
    $('npv10HyAdd').addEventListener('click', addHypo);
    $('npv10HyRefresh').addEventListener('click', function () { renderHypo(); V.toast('Simulated marks refreshed'); });
    $('npv10HyClear').addEventListener('click', function () {
      if (!confirm('Clear all simulated positions and realised P&L on this device?')) return;
      saveHypo({ balance: num($('npv10HyBalance').value) || 100000, realized: 0, positions: [] });
      renderHypo();
    });
    $('npv10HyRows').addEventListener('click', function (e) {
      var btn = e.target.closest('[data-close-id]');
      if (!btn) return;
      closeHypo(btn.getAttribute('data-close-id'));
    });
    renderHypo();
  }

  function addHypo() {
    var st = hypoState();
    var symbol = String($('npv10HySymbol').value || '').trim().toUpperCase();
    var side = $('npv10HySide').value;
    var qty = num($('npv10HyQty').value), entry = num($('npv10HyEntry').value);
    var balance = num($('npv10HyBalance').value);
    var sl = num($('npv10HyStop').value), tp = num($('npv10HyTarget').value);
    if (!/^[A-Z0-9._-]{1,24}$/.test(symbol)) { V.toast('Enter a valid symbol'); return; }
    if (qty === null || qty <= 0) { V.toast('Quantity must be greater than zero'); return; }
    if (entry === null || entry <= 0) { V.toast('Entry price must be greater than zero'); return; }
    if (balance === null || balance <= 0) { V.toast('Account balance must be greater than zero'); return; }
    if (sl !== null && side === 'long' && sl >= entry) { V.toast('Long stop-loss must be below the entry'); return; }
    if (sl !== null && side === 'short' && sl <= entry) { V.toast('Short stop-loss must be above the entry'); return; }
    if (tp !== null && side === 'long' && tp <= entry) { V.toast('Long take-profit must be above the entry'); return; }
    if (tp !== null && side === 'short' && tp >= entry) { V.toast('Short take-profit must be below the entry'); return; }
    st.balance = balance;
    st.positions.push({ id: 'p' + Date.now() + Math.random().toString(36).slice(2, 6), symbol: symbol, side: side, qty: qty, entry: entry, sl: sl, tp: tp, openedAt: new Date().toISOString() });
    saveHypo(st);
    renderHypo();
    V.toast('Simulated ' + side + ' position opened for ' + symbol);
  }

  function closeHypo(id) {
    var st = hypoState();
    var mark = hypoMarks();
    var idx = st.positions.findIndex(function (p) { return p.id === id; });
    if (idx < 0) return;
    var p = st.positions[idx];
    var m = mark[p.symbol];
    if (m === undefined || m === null) { V.toast('No market mark for ' + p.symbol + ' - position kept open'); return; }
    var pnl = (p.side === 'long' ? m - p.entry : p.entry - m) * p.qty;
    st.realized = (num(st.realized) || 0) + pnl;
    st.balance = (num(st.balance) || 0) + pnl;
    st.positions.splice(idx, 1);
    saveHypo(st);
    renderHypo();
    V.toast('Closed ' + p.symbol + ': ' + (pnl >= 0 ? '+' : '') + pnl.toLocaleString(undefined, { maximumFractionDigits: 2 }));
  }

  var marksCache = { at: 0, map: {} };
  function hypoMarks() {
    var snap = V.market();
    if (!snap) return marksCache.map;
    var map = {};
    (snap.rows || []).forEach(function (r) { if (r.ltp !== null) map[r.symbol] = r.ltp; });
    marksCache = { at: Date.now(), map: map };
    return map;
  }

  async function renderHypo() {
    if (!$('npv10HyRows')) return;
    var st = hypoState();
    var marks = hypoMarks();
    if (!Object.keys(marks).length) {
      try { await V.loadMarket(false); marks = hypoMarks(); } catch (e) {}
    }
    var openPnl = 0, exposure = 0, rows = '';
    st.positions.forEach(function (p) {
      var m = marks[p.symbol] !== undefined ? marks[p.symbol] : null;
      var pnl = m === null ? null : (p.side === 'long' ? m - p.entry : p.entry - m) * p.qty;
      if (pnl !== null) openPnl += pnl;
      exposure += p.entry * p.qty;
      var badge = '';
      if (m !== null && p.sl !== null) badge += (p.side === 'long' ? m <= p.sl : m >= p.sl) ? '<span class="npv10-down">SL hit</span> ' : '';
      if (m !== null && p.tp !== null) badge += (p.side === 'long' ? m >= p.tp : m <= p.tp) ? '<span class="npv10-up">TP hit</span>' : '';
      rows += '<tr><td><b>' + esc(p.symbol) + '</b></td><td>' + (p.side === 'long' ? 'Long' : 'Short') + '</td><td>' + p.qty.toLocaleString() + '</td><td>' + price(p.entry) + '</td><td>' + (m === null ? '--' : price(m)) + '</td>' +
        '<td class="' + (pnl === null ? '' : pnl > 0 ? 'npv10-up' : pnl < 0 ? 'npv10-down' : '') + '">' + (pnl === null ? '--' : (pnl >= 0 ? '+' : '') + pnl.toLocaleString(undefined, { maximumFractionDigits: 2 })) + '</td>' +
        '<td>' + (p.sl === null ? '-' : price(p.sl)) + ' / ' + (p.tp === null ? '-' : price(p.tp)) + ' ' + badge + '</td>' +
        '<td><button class="npv10-btn sm alt" data-close-id="' + p.id + '">Close</button></td></tr>';
    });
    $('npv10HyRows').innerHTML = rows || '<tr><td colspan="8" style="color:#64748b">No simulated positions yet. Open one to start.</td></tr>';
    var stats = [
      ['Account balance', money(st.balance)],
      ['Open positions', st.positions.length],
      ['Unrealised P&L', (openPnl >= 0 ? '+' : '') + money(openPnl)],
      ['Realised P&L', ((num(st.realized) || 0) >= 0 ? '+' : '') + money(st.realized)],
      ['Open exposure', money(exposure)],
      ['Equity (balance + open)', money((num(st.balance) || 0) + openPnl)]
    ];
    $('npv10HyStats').innerHTML = stats.map(function (x) {
      var tone = /P&L/.test(x[0]) ? (String(x[1]).indexOf('-') === 0 ? ' style="color:#dc2626"' : ' style="color:#16a34a"') : '';
      return '<div class="npv10-stat"><small>' + x[0] + '</small><b' + tone + '>' + x[1] + '</b></div>';
    }).join('');
  }

  /* ------------------------------------------------------ scenario simulator */
  function buildScenario() {
    if ($('npv10-scenario')) return;
    V = window.NPV10;
    var s = document.createElement('section');
    s.id = 'npv10-scenario';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">WHAT IF</div><h2>Scenario Simulator</h2>' +
      '<p>Model a hypothetical path from the current price to a target price and see the effect on portfolio value. Simulations are illustrative, not forecasts.</p></div></div>' +
      '<div class="npv10-grid two">' +
        '<div class="npv10-card">' +
          '<label class="npv10-label">Direction</label><div class="npv10-field"><select id="npv10ScDir" class="npv10-select"><option value="bull">Bullish scenario</option><option value="bear">Bearish scenario</option></select></div>' +
          '<label class="npv10-label">Initial stock price</label><div class="npv10-field"><input id="npv10ScStart" class="npv10-input" inputmode="decimal" value="550"></div>' +
          '<label class="npv10-label">Target price</label><div class="npv10-field"><input id="npv10ScTarget" class="npv10-input" inputmode="decimal" value="660"></div>' +
          '<div class="npv10-grid" style="grid-template-columns:1fr 1fr;gap:8px">' +
            '<div><label class="npv10-label">Number of shares</label><div class="npv10-field"><input id="npv10ScShares" class="npv10-input" inputmode="numeric" value="100"></div></div>' +
            '<div><label class="npv10-label">Investment amount</label><div class="npv10-field"><input id="npv10ScInvest" class="npv10-input" inputmode="decimal" placeholder="optional"></div></div>' +
          '</div>' +
          '<label class="npv10-label">Simulation steps</label><div class="npv10-field"><input id="npv10ScSteps" class="npv10-input" inputmode="numeric" value="12"></div>' +
          '<div class="npv10-pills"><button class="npv10-btn" id="npv10ScRun">Run simulation</button><button class="npv10-btn alt" id="npv10ScUseLtp">Use market price</button></div>' +
          '<div class="npv10-note" style="margin-top:10px" id="npv10ScNote">The price path is a straight-line simulation between the initial and target price, divided into equal steps.</div>' +
        '</div>' +
        '<div>' +
          '<div class="npv10-card"><div class="npv10-result" id="npv10ScStats"></div></div>' +
          '<div class="npv10-card" style="margin-top:12px"><div class="npv10-chartbox" style="height:300px"><div id="npv10ScChart" style="width:100%;height:100%"></div></div></div>' +
          '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Alternative price outcomes</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Scenario price</th><th>Move</th><th>Portfolio value</th><th>Profit / loss</th><th>Return</th></tr></thead><tbody id="npv10ScRows"></tbody></table></div></div>' +
        '</div>' +
      '</div>';
    (document.querySelector('main') || document.body).appendChild(s);
    scenChart = new window.NpLineChart($('npv10ScChart'), { emptyText: 'Run a simulation to see the path' });
    $('npv10ScRun').addEventListener('click', runScenario);
    $('npv10ScUseLtp').addEventListener('click', async function () {
      try {
        var snap = await V.loadMarket(false);
        var top = (snap.rows || []).slice().sort(function (a, b) { return (b.turnover || 0) - (a.turnover || 0); })[0];
        if (top && top.ltp !== null) { $('npv10ScStart').value = top.ltp; runScenario(); V.toast('Using ' + top.symbol + ' market price'); }
      } catch (e) { V.toast('Market price unavailable'); }
    });
    ['npv10ScDir', 'npv10ScStart', 'npv10ScTarget', 'npv10ScShares', 'npv10ScInvest', 'npv10ScSteps'].forEach(function (id) {
      $(id).addEventListener('change', runScenario);
      $(id).addEventListener('input', function () { if (id === 'npv10ScDir') runScenario(); });
    });
    $('npv10ScDir').addEventListener('change', runScenario);
    runScenario();
  }
  var scenChart = null;

  function runScenario() {
    if (!$('npv10ScStats')) return;
    var dir = $('npv10ScDir').value;
    var start = num($('npv10ScStart').value);
    var target = num($('npv10ScTarget').value);
    var shares = num($('npv10ScShares').value);
    var invest = num($('npv10ScInvest').value);
    var steps = Math.round(num($('npv10ScSteps').value) || 12);
    var note = $('npv10ScNote');
    if (start === null || start <= 0) { note.textContent = 'Initial price must be greater than zero.'; return; }
    if (target === null || target <= 0) { note.textContent = 'Target price must be greater than zero.'; return; }
    if (dir === 'bull' && target <= start) { note.textContent = 'A bullish scenario needs a target above the initial price.'; return; }
    if (dir === 'bear' && target >= start) { note.textContent = 'A bearish scenario needs a target below the initial price.'; return; }
    steps = Math.max(2, Math.min(60, steps));
    if (shares === null || shares <= 0) {
      if (invest !== null && invest > 0) shares = Math.floor(invest / start);
      else shares = 100;
    }
    if (shares <= 0) { note.textContent = 'The investment amount is too small for one share at this price.'; return; }
    note.textContent = 'Illustrative path with ' + steps + ' equal steps. It is not a price forecast.';

    var invested = shares * start;
    var endpoint = shares * target;
    var pnl = endpoint - invested;
    var ret = (pnl / invested) * 100;
    var stats = [
      ['Shares simulated', shares.toLocaleString()],
      ['Invested amount', money(invested)],
      ['Target portfolio value', money(endpoint)],
      ['Expected profit / loss', (pnl >= 0 ? '+' : '') + money(pnl)],
      ['Percentage return', (ret >= 0 ? '+' : '') + ret.toFixed(2) + '%'],
      ['Direction', dir === 'bull' ? 'Bullish' : 'Bearish']
    ];
    $('npv10ScStats').innerHTML = stats.map(function (x) {
      var tone = /profit|return/i.test(x[0]) ? (String(x[1]).indexOf('-') === 0 ? ' style="color:#dc2626"' : ' style="color:#16a34a"') : '';
      return '<div class="npv10-stat"><small>' + x[0] + '</small><b' + tone + '>' + x[1] + '</b></div>';
    }).join('');

    var base = Date.now() - steps * 86400000;
    var points = [];
    for (var i = 0; i <= steps; i++) {
      var p = start + ((target - start) * i) / steps;
      points.push({ x: base + i * 86400000, y: p * shares });
    }
    if (scenChart) {
      scenChart.setSeries([
        { name: 'Portfolio value', color: dir === 'bull' ? '#16a34a' : '#dc2626', area: true, points: points, format: function (v) { return money(v); } },
        { name: 'Invested', color: '#94a3b8', points: [{ x: base, y: invested }, { x: base + steps * 86400000, y: invested }], format: function (v) { return money(v); } }
      ]);
    }

    var moves = [-30, -20, -10, -5, 0, 5, 10, 20, 30];
    $('npv10ScRows').innerHTML = moves.map(function (m) {
      var p = start * (1 + m / 100);
      var value = p * shares;
      var pl = value - invested;
      return '<tr><td>' + price(p) + '</td><td class="' + (m > 0 ? 'npv10-up' : m < 0 ? 'npv10-down' : '') + '">' + (m > 0 ? '+' : '') + m + '%</td>' +
        '<td>' + money(value) + '</td><td class="' + (pl > 0 ? 'npv10-up' : pl < 0 ? 'npv10-down' : '') + '">' + (pl >= 0 ? '+' : '') + money(pl) + '</td>' +
        '<td class="' + (m > 0 ? 'npv10-up' : m < 0 ? 'npv10-down' : '') + '">' + (m > 0 ? '+' : '') + m.toFixed(2) + '%</td></tr>';
    }).join('');
  }

  function boot() {
    buildRisk();
    buildHypo();
    buildScenario();
    // keep simulated marks fresh whenever the verified market snapshot changes
    document.addEventListener('npv10-market', renderHypo);
    setInterval(function () { if (!document.hidden && $('npv10-hypo')) renderHypo(); }, 120000);
  }

  window.NPV10Tools = {
    calcRisk: calcRisk, renderHypo: renderHypo, runScenario: runScenario,
    hypo: function () { return hypoState(); }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else setTimeout(boot, 0);
})();
