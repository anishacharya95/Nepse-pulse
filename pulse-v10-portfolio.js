/* NEPSE Pulse v10 - portfolio risk & drawdown analysis and stock comparison. */
(function () {
  'use strict';
  if (window.NPV10Portfolio) return;
  var $ = function (id) { return document.getElementById(id); };
  var V = null;
  var num = function (v) { if (v === null || v === undefined) return null; var raw = String(v).trim(); if (!raw) return null; var n = Number(raw.replace(/,/g, '')); return Number.isFinite(n) ? n : null; };
  var esc = function (s) { return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };
  var money = function (v) { var n = num(v); return n === null ? '--' : 'Rs ' + n.toLocaleString(undefined, { maximumFractionDigits: 2 }); };
  var pctf = function (v, d) { var n = num(v); return n === null ? '--' : (n > 0 ? '+' : '') + n.toFixed(d === undefined ? 2 : d) + '%'; };
  var POS_KEY = 'npv10_portfolio_positions_v1';

  var COLORS = ['#2563eb', '#16a34a', '#dc2626', '#9333ea', '#f59e0b'];

  function positionsState() {
    var st = V.store(POS_KEY, null);
    if (!st || typeof st !== 'object') st = { cash: 25000, positions: [] };
    if (!Array.isArray(st.positions)) st.positions = [];
    if (num(st.cash) === null) st.cash = 0;
    return st;
  }
  function savePositions(st) { V.save(POS_KEY, st); }

  function importFromLocalPortfolio() {
    // Read the existing app's locally saved portfolio shapes without changing them.
    var imported = [];
    try {
      Object.keys(localStorage).forEach(function (key) {
        if (!/portfolio/i.test(key)) return;
        var raw = localStorage.getItem(key);
        if (!raw) return;
        var data;
        try { data = JSON.parse(raw); } catch (e) { return; }
        var list = Array.isArray(data) ? data : (data && Array.isArray(data.holdings) ? data.holdings : []);
        list.forEach(function (h) {
          if (!h || typeof h !== 'object') return;
          var symbol = String(h.symbol || h.ticker || h.security || '').trim().toUpperCase();
          var qty = num(h.quantity || h.qty || h.shares || h.units);
          var avg = num(h.avgPrice || h.averagePrice || h.avg || h.cost || h.rate);
          if (symbol && qty > 0 && avg > 0) imported.push({ symbol: symbol, qty: qty, avg: avg });
        });
      });
    } catch (e) {}
    var seen = {};
    return imported.filter(function (p) { if (seen[p.symbol]) return false; seen[p.symbol] = 1; return true; });
  }

  async function fetchDaily(symbol, countback) {
    var d = await V.fetchJSON('/api/tv/history?symbol=' + encodeURIComponent(symbol) + '&resolution=D&countback=' + (countback || 260));
    if (d.s !== 'ok' || !Array.isArray(d.t)) return [];
    var rows = [];
    for (var i = 0; i < d.t.length; i++) {
      var c = Number(d.c[i]), h = Number(d.h[i]), l = Number(d.l[i]);
      if (Number.isFinite(c) && c > 0) rows.push({ t: Number(d.t[i]) * 1000, c: c, h: h, l: l });
    }
    return rows;
  }

  function stdev(arr) {
    if (arr.length < 2) return null;
    var mean = arr.reduce(function (a, b) { return a + b; }, 0) / arr.length;
    var variance = arr.reduce(function (a, b) { return a + Math.pow(b - mean, 2); }, 0) / (arr.length - 1);
    return Math.sqrt(variance);
  }

  /* ------------------------------------------------------- portfolio risk */
  function buildPortfolioRisk() {
    if ($('npv10-portfolio-risk')) return;
    V = window.NPV10;
    var st = positionsState();
    var s = document.createElement('section');
    s.id = 'npv10-portfolio-risk';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">PORTFOLIO ANALYTICS</div><h2>Portfolio Risk &amp; Drawdown</h2>' +
      '<p>Add your holdings (or import them from the portfolio saved on this device) to measure drawdown, volatility, exposure and diversification from verified daily history.</p></div></div>' +
      '<div class="npv10-grid two">' +
        '<div class="npv10-card">' +
          '<label class="npv10-label">Holdings</label>' +
          '<div id="npv10PrRows" style="margin-bottom:8px"></div>' +
          '<div class="npv10-grid" style="grid-template-columns:1.1fr .7fr .8fr;gap:6px">' +
            '<div><label class="npv10-label">Symbol</label><input id="npv10PrSymbol" class="npv10-input" value="NABIL"></div>' +
            '<div><label class="npv10-label">Qty</label><input id="npv10PrQty" class="npv10-input" inputmode="numeric" value="100"></div>' +
            '<div><label class="npv10-label">Avg price</label><input id="npv10PrAvg" class="npv10-input" inputmode="decimal" value="550"></div>' +
          '</div>' +
          '<label class="npv10-label" style="margin-top:9px">Cash balance (Rs)</label>' +
          '<input id="npv10PrCash" class="npv10-input" inputmode="decimal" value="' + esc(st.cash) + '">' +
          '<div class="npv10-pills" style="margin-top:10px">' +
            '<button class="npv10-btn" id="npv10PrAdd">Add holding</button>' +
            '<button class="npv10-btn alt" id="npv10PrImport">Import saved portfolio</button>' +
            '<button class="npv10-btn dark" id="npv10PrRun">Analyse</button>' +
          '</div>' +
          '<div class="npv10-note" style="margin-top:10px" id="npv10PrNote">Add at least one holding, then analyse.</div>' +
        '</div>' +
        '<div>' +
          '<div class="npv10-card"><div class="npv10-result" id="npv10PrStats"></div></div>' +
          '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Portfolio value and drawdown</label>' +
            '<div class="npv10-chartbox" style="height:260px"><div id="npv10PrChart" style="width:100%;height:100%"></div></div>' +
            '<div class="npv10-chartbox" style="height:180px;margin-top:8px"><div id="npv10PrDD" style="width:100%;height:100%"></div></div>' +
            '<div class="npv10-note" style="margin-top:8px">Top: projected portfolio value from verified daily closes. Bottom: drawdown from the running peak. The curve applies your current quantities and is a risk projection, not a record of past trades.</div>' +
          '</div>' +
          '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Risk detail</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Symbol</th><th>Qty</th><th>Avg</th><th>LTP</th><th>Value</th><th>Weight</th><th>P/L</th></tr></thead><tbody id="npv10PrDetail"></tbody></table></div></div>' +
        '</div>' +
      '</div>';
    (document.querySelector('main') || document.body).appendChild(s);

    $('npv10PrAdd').addEventListener('click', function () {
      var st2 = positionsState();
      var symbol = String($('npv10PrSymbol').value || '').trim().toUpperCase();
      var qty = num($('npv10PrQty').value), avg = num($('npv10PrAvg').value);
      if (!/^[A-Z0-9._-]{1,24}$/.test(symbol)) { V.toast('Enter a valid symbol'); return; }
      if (qty === null || qty <= 0) { V.toast('Quantity must be greater than zero'); return; }
      if (avg === null || avg <= 0) { V.toast('Average price must be greater than zero'); return; }
      var existing = st2.positions.filter(function (p) { return p.symbol === symbol; })[0];
      if (existing) {
        var totalQty = existing.qty + qty;
        existing.avg = ((existing.avg * existing.qty) + (avg * qty)) / totalQty;
        existing.qty = totalQty;
      } else st2.positions.push({ symbol: symbol, qty: qty, avg: avg });
      st2.cash = num($('npv10PrCash').value) || 0;
      savePositions(st2);
      renderPositionRows();
      analysePortfolio();
    });
    $('npv10PrImport').addEventListener('click', function () {
      var imported = importFromLocalPortfolio();
      if (!imported.length) { V.toast('No saved holdings were found on this device'); return; }
      var st2 = positionsState();
      imported.forEach(function (p) {
        var existing = st2.positions.filter(function (x) { return x.symbol === p.symbol; })[0];
        if (existing) { existing.qty += p.qty; existing.avg = p.avg; } else st2.positions.push(p);
      });
      savePositions(st2);
      renderPositionRows();
      analysePortfolio();
      V.toast('Imported ' + imported.length + ' holding(s)');
    });
    $('npv10PrRun').addEventListener('click', analysePortfolio);
    $('npv10PrRows').addEventListener('click', function (e) {
      var btn = e.target.closest('[data-rm-symbol]');
      if (!btn) return;
      var st2 = positionsState();
      st2.positions = st2.positions.filter(function (p) { return p.symbol !== btn.getAttribute('data-rm-symbol'); });
      savePositions(st2); renderPositionRows(); analysePortfolio();
    });
    $('npv10PrCash').addEventListener('change', function () {
      var st2 = positionsState(); st2.cash = num($('npv10PrCash').value) || 0; savePositions(st2); analysePortfolio();
    });

    window.NPV10_prChart = new window.NpLineChart($('npv10PrChart'), { emptyText: 'Add holdings to build the risk curve' });
    window.NPV10_prDDChart = new window.NpLineChart($('npv10PrDD'), { emptyText: 'Drawdown history appears after analysis' });
    renderPositionRows();
    analysePortfolio();
  }

  function renderPositionRows() {
    var st = positionsState();
    var box = $('npv10PrRows');
    if (!box) return;
    box.innerHTML = st.positions.length
      ? st.positions.map(function (p) {
          return '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 8px;border:1px solid #e4ebf5;border-radius:8px;margin-bottom:5px;font-size:11.5px">' +
            '<span><b>' + esc(p.symbol) + '</b> &middot; ' + p.qty.toLocaleString() + ' @ ' + p.avg.toFixed(2) + '</span>' +
            '<button class="npv10-btn sm alt" data-rm-symbol="' + esc(p.symbol) + '">Remove</button></div>';
        }).join('')
      : '<div class="npv10-note">No holdings added yet.</div>';
  }

  async function analysePortfolio() {
    var st = positionsState();
    var note = $('npv10PrNote');
    if (!st.positions.length) {
      note.textContent = 'Add at least one holding, then analyse.';
      $('npv10PrStats').innerHTML = '';
      $('npv10PrDetail').innerHTML = '<tr><td colspan="7" style="color:#64748b">No holdings.</td></tr>';
      if (window.NPV10_prChart) window.NPV10_prChart.setSeries([]);
      if (window.NPV10_prDDChart) window.NPV10_prDDChart.setSeries([]);
      return;
    }
    note.textContent = 'Loading verified market prices and 260 sessions of history...';
    $('npv10PrRun').disabled = true;
    try {
      var snap = await V.loadMarket(false);
      var marks = {};
      (snap.rows || []).forEach(function (r) { if (r.ltp !== null) marks[r.symbol] = r.ltp; });
      var histories = await Promise.all(st.positions.map(function (p) {
        return fetchDaily(p.symbol, 260).catch(function () { return []; });
      }));
      var currentValue = 0, invested = 0, missing = [];
      var details = st.positions.map(function (p) {
        var ltp = marks[p.symbol];
        if (ltp === undefined) { missing.push(p.symbol); ltp = p.avg; }
        var value = ltp * p.qty;
        currentValue += value; invested += p.avg * p.qty;
        return { symbol: p.symbol, qty: p.qty, avg: p.avg, ltp: ltp, value: value, pl: (ltp - p.avg) * p.qty };
      });
      var cash = num(st.cash) || 0;
      var totalValue = currentValue + cash;

      // Build an aligned portfolio curve from verified closes.
      var dateMap = {};
      histories.forEach(function (rows, idx) {
        var qty = st.positions[idx].qty;
        rows.forEach(function (r) {
          var key = V.dateKey(r.t);
          if (!dateMap[key]) dateMap[key] = { t: r.t, total: 0, count: 0, priced: 0 };
          dateMap[key].total += 0;
          dateMap[key].count += 1;
        });
        rows.forEach(function (r) {
          var key = V.dateKey(r.t);
          if (!dateMap[key].prices) dateMap[key].prices = {};
          dateMap[key].prices[idx] = r.c * qty;
        });
      });
      var keys = Object.keys(dateMap).sort();
      var last = {};
      histories.forEach(function (rows, idx) {
        if (rows.length) last[idx] = rows[0].c * st.positions[idx].qty;
      });
      var curve = [];
      var prevValue = null, peak = -Infinity, maxDD = 0, peakDate = null, troughDate = null, ddPeakVal = 0;
      var returns = [];
      keys.forEach(function (key) {
        var e = dateMap[key];
        if (e.prices) Object.keys(e.prices).forEach(function (i) { last[i] = e.prices[i]; });
        var val = cash;
        Object.keys(last).forEach(function (i) { val += last[i]; });
        if (prevValue !== null && prevValue > 0) returns.push((val - prevValue) / prevValue);
        prevValue = val;
        curve.push({ x: e.t, y: val });
        if (val > peak) { peak = val; peakDate = e.t; }
        var dd = peak > 0 ? (val - peak) / peak : 0;
        if (dd < maxDD) { maxDD = dd; troughDate = e.t; ddPeakVal = peak; }
      });

      var ddSeries = curve.map(function (p) {
        var runPeak = -Infinity;
        return p;
      });
      // running drawdown uses the peak computed above; recompute in one pass for accuracy
      var run = -Infinity, ddPoints = [];
      curve.forEach(function (p) {
        if (p.y > run) run = p.y;
        ddPoints.push({ x: p.x, y: run > 0 ? ((p.y - run) / run) * 100 : 0 });
      });

      var vol = stdev(returns);
      var annualVol = vol === null ? null : vol * Math.sqrt(252) * 100;
      var totalReturn = invested > 0 ? ((currentValue - invested) / invested) * 100 : null;
      var weights = details.map(function (d) { return totalValue > 0 ? d.value / totalValue : 0; });
      var hhi = weights.reduce(function (a, w) { return a + w * w; }, 0);
      var effective = hhi > 0 ? 1 / hhi : 0;
      var largest = weights.length ? Math.max.apply(null, weights) : 0;
      var exposure = totalValue > 0 ? (currentValue / totalValue) * 100 : 0;
      var maxDDPct = maxDD * 100;
      var riskReward = maxDDPct < 0 && totalReturn !== null ? totalReturn / Math.abs(maxDDPct) : null;

      var stats = [
        ['Portfolio value', money(totalValue)],
        ['Invested cost', money(invested)],
        ['Unrealised P/L', (currentValue - invested >= 0 ? '+' : '') + money(currentValue - invested)],
        ['Total return', pctf(totalReturn)],
        ['Max drawdown', maxDDPct.toFixed(2) + '%'],
        ['Annualised volatility', annualVol === null ? '--' : annualVol.toFixed(2) + '%'],
        ['Risk exposure', exposure.toFixed(1) + '%'],
        ['Cash weight', (totalValue > 0 ? (cash / totalValue) * 100 : 0).toFixed(1) + '%'],
        ['Largest position', (largest * 100).toFixed(1) + '%'],
        ['Diversification (eff. N)', effective.toFixed(2)],
        ['Return / max DD', riskReward === null ? '--' : riskReward.toFixed(2)],
        ['Sessions analysed', curve.length]
      ];
      $('npv10PrStats').innerHTML = stats.map(function (x) {
        var tone = /P\/L|return|drawdown/i.test(x[0]) ? (String(x[1]).indexOf('-') === 0 ? ' style="color:#dc2626"' : ' style="color:#16a34a"') : '';
        return '<div class="npv10-stat"><small>' + x[0] + '</small><b' + tone + '>' + x[1] + '</b></div>';
      }).join('');

      $('npv10PrDetail').innerHTML = details.map(function (d) {
        var weight = totalValue > 0 ? (d.value / totalValue) * 100 : 0;
        return '<tr><td><b>' + esc(d.symbol) + '</b></td><td>' + d.qty.toLocaleString() + '</td><td>' + d.avg.toFixed(2) + '</td><td>' + d.ltp.toFixed(2) + '</td>' +
          '<td>' + money(d.value) + '</td><td>' + weight.toFixed(1) + '%</td><td class="' + (d.pl > 0 ? 'npv10-up' : d.pl < 0 ? 'npv10-down' : '') + '">' + (d.pl >= 0 ? '+' : '') + money(d.pl) + '</td></tr>';
      }).join('') || '<tr><td colspan="7" style="color:#64748b">No holdings.</td></tr>';

      if (window.NPV10_prChart) {
        window.NPV10_prChart.setSeries([
          { name: 'Portfolio value', color: '#2563eb', area: true, points: curve, format: function (v) { return money(v); } }
        ]);
      }
      if (window.NPV10_prDDChart) {
        window.NPV10_prDDChart.setSeries([
          { name: 'Drawdown', color: '#dc2626', area: true, points: ddPoints, format: function (v) { return v.toFixed(2) + '%'; } }
        ]);
      }

      var extra = [];
      if (missing.length) extra.push('No market mark for: ' + missing.join(', ') + ' (cost price used).');
      if (maxDDPct < 0) extra.push('Max drawdown ' + maxDDPct.toFixed(2) + '% from peak to trough across ' + curve.length + ' sessions.');
      note.textContent = extra.join(' ') || 'Analysis complete.';
    } catch (e) {
      note.textContent = 'Portfolio analysis failed: ' + e.message;
    } finally {
      $('npv10PrRun').disabled = false;
    }
  }

  /* ---------------------------------------------------------- comparison */
  function buildCompare() {
    if ($('npv10-compare')) return;
    V = window.NPV10;
    var s = document.createElement('section');
    s.id = 'npv10-compare';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">RELATIVE PERFORMANCE</div><h2>Stock Comparison</h2>' +
      '<p>Compare up to five NEPSE symbols on one normalised scale. Each series starts at 100 on the first session of the selected period.</p></div></div>' +
      '<div class="npv10-card" style="margin-bottom:12px">' +
        '<div class="npv10-grid" style="grid-template-columns:minmax(0,2fr) minmax(0,1fr) auto;align-items:end">' +
          '<div><label class="npv10-label">Symbols (comma separated, max 5)</label><input id="npv10CmpSymbols" class="npv10-input" value="NABIL, NTC, GBIME"></div>' +
          '<div><label class="npv10-label">Period</label><select id="npv10CmpPeriod" class="npv10-select">' +
            '<option value="31">1 month</option><option value="92">3 months</option><option value="183" selected>6 months</option>' +
            '<option value="366">1 year</option><option value="1095">3 years</option><option value="0">All available</option></select></div>' +
          '<div><button class="npv10-btn" id="npv10CmpRun">Compare</button></div>' +
        '</div>' +
        '<div class="npv10-note" style="margin-top:9px" id="npv10CmpNote">Enter symbols and run the comparison.</div>' +
      '</div>' +
      '<div class="npv10-card"><div class="npv10-chartbox" style="height:380px"><div id="npv10CmpChart" style="width:100%;height:100%"></div></div></div>' +
      '<div class="npv10-card" style="margin-top:12px"><label class="npv10-label">Period statistics</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Symbol</th><th>Start</th><th>Last</th><th>Return</th><th>Volatility</th><th>Max drawdown</th><th>Period high</th><th>Period low</th></tr></thead><tbody id="npv10CmpRows"></tbody></table></div></div>';
    (document.querySelector('main') || document.body).appendChild(s);
    window.NPV10_cmpChart = new window.NpLineChart($('npv10CmpChart'), { emptyText: 'Run a comparison to plot series' });
    $('npv10CmpRun').addEventListener('click', runComparison);
    runComparison();
  }

  async function runComparison() {
    if (!$('npv10CmpRows')) return;
    var symbols = String($('npv10CmpSymbols').value || '').toUpperCase().split(/[,\s]+/).map(function (x) { return x.trim(); }).filter(Boolean).slice(0, 5);
    if (!symbols.length) { $('npv10CmpNote').textContent = 'Enter at least one symbol.'; return; }
    var days = Number($('npv10CmpPeriod').value);
    var note = $('npv10CmpNote');
    note.textContent = 'Loading ' + symbols.join(', ') + '...';
    $('npv10CmpRun').disabled = true;
    try {
      var results = await Promise.all(symbols.map(function (sym) {
        return fetchDaily(sym, 1500).then(function (rows) { return { symbol: sym, rows: rows }; }).catch(function () { return { symbol: sym, rows: [] }; });
      }));
      var cutoff = days > 0 ? Date.now() - days * 86400000 : 0;
      var series = [], table = '', missing = [];
      results.forEach(function (res, idx) {
        var rows = res.rows.filter(function (r) { return r.t >= cutoff; });
        if (rows.length < 2) { missing.push(res.symbol); return; }
        var base = rows[0].c;
        var points = rows.map(function (r) { return { x: r.t, y: (r.c / base) * 100 }; });
        series.push({ name: res.symbol, color: COLORS[idx % COLORS.length], points: points, format: function (v) { return v.toFixed(1); } });
        var rets = [];
        for (var i = 1; i < rows.length; i++) rets.push((rows[i].c - rows[i - 1].c) / rows[i - 1].c);
        var sd = stdev(rets);
        var peak = -Infinity, maxDD = 0;
        rows.forEach(function (r) { if (r.c > peak) peak = r.c; var dd = (r.c - peak) / peak; if (dd < maxDD) maxDD = dd; });
        var last = rows[rows.length - 1].c;
        var ret = ((last - base) / base) * 100;
        var high = Math.max.apply(null, rows.map(function (r) { return r.c; }));
        var low = Math.min.apply(null, rows.map(function (r) { return r.c; }));
        table += '<tr><td><b style="color:' + COLORS[idx % COLORS.length] + '">' + esc(res.symbol) + '</b></td><td>' + base.toFixed(2) + '</td><td>' + last.toFixed(2) + '</td>' +
          '<td class="' + (ret > 0 ? 'npv10-up' : ret < 0 ? 'npv10-down' : '') + '">' + pctf(ret) + '</td>' +
          '<td>' + (sd === null ? '--' : (sd * Math.sqrt(252) * 100).toFixed(2) + '%') + '</td>' +
          '<td class="npv10-down">' + (maxDD * 100).toFixed(2) + '%</td><td>' + high.toFixed(2) + '</td><td>' + low.toFixed(2) + '</td></tr>';
      });
      $('npv10CmpRows').innerHTML = table || '<tr><td colspan="8" style="color:#64748b">No usable history for the selected period.</td></tr>';
      if (window.NPV10_cmpChart) window.NPV10_cmpChart.setSeries(series);
      note.textContent = 'Normalised to 100 at the first session. ' + (missing.length ? 'No usable history: ' + missing.join(', ') + '. ' : '') + 'Compared ' + series.length + ' series over ' + (days > 0 ? days + ' days' : 'all available history') + '.';
    } catch (e) {
      note.textContent = 'Comparison failed: ' + e.message;
    } finally {
      $('npv10CmpRun').disabled = false;
    }
  }

  function boot() {
    buildPortfolioRisk();
    buildCompare();
  }

  window.NPV10Portfolio = {
    analyse: analysePortfolio, compare: runComparison,
    importFromLocalPortfolio: importFromLocalPortfolio
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
  else setTimeout(boot, 0);
})();
