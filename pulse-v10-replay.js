/* NEPSE Pulse v10 - historical chart replay.
   Replays only verified candles supplied by /api/tv/history. Future candles are
   never rendered: the engine receives a reveal index and draws bars 0..index. */
(function () {
  'use strict';
  if (window.NPV10Replay) return;
  var $ = function (id) { return document.getElementById(id); };
  var V = null;
  var state = {
    symbol: 'NABIL', resolution: 'D', candles: [], cursor: 0, start: 0,
    timer: null, speed: 500, playing: false, chart: null, loaded: false
  };

  function fmtDate(ms) {
    var d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }
  function fmtTime(ms) {
    var d = new Date(ms);
    return d.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
  }

  function build() {
    if ($('npv10-replay')) return;
    V = window.NPV10;
    var section = document.createElement('section');
    section.id = 'npv10-replay';
    section.className = 'pulse-home-feature-module npv10-tool';
    section.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">HISTORICAL REPLAY</div><h2>Chart Replay</h2>' +
      '<p>Load a verified NEPSE series, choose a historical date and step through the candles. Future candles stay hidden until you advance.</p></div>' +
      '<div class="npv10-pills"><span class="npv10-badge" id="npv10ReplayState" style="background:#eef4ff;border:1px solid #dbe7fb;color:#1d4ed8;padding:7px 11px;border-radius:999px;font-size:10px;font-weight:900">IDLE</span></div></div>' +
      '<div class="npv10-grid two">' +
        '<div>' +
          '<div class="npv10-card">' +
            '<label class="npv10-label">Symbol</label>' +
            '<div class="npv10-field"><input id="npv10RpSymbol" class="npv10-input" value="NABIL" placeholder="e.g. NABIL" autocomplete="off"></div>' +
            '<label class="npv10-label">Interval</label>' +
            '<div class="npv10-field"><select id="npv10RpRes" class="npv10-select"><option value="D">Daily</option><option value="W">Weekly</option><option value="M">Monthly</option></select></div>' +
            '<label class="npv10-label">Replay from date</label>' +
            '<div class="npv10-field"><input id="npv10RpDate" class="npv10-input" type="date"></div>' +
            '<div class="npv10-pills" style="margin-bottom:10px"><button class="npv10-btn" id="npv10RpLoad">Load candles</button>' +
            '<button class="npv10-btn alt" id="npv10RpStartDate">Jump to date</button></div>' +
            '<div class="npv10-note" id="npv10RpMeta">Enter a symbol and load its verified history.</div>' +
          '</div>' +
          '<div class="npv10-card" style="margin-top:12px">' +
            '<label class="npv10-label">Playback</label>' +
            '<div class="npv10-pills" style="margin-bottom:9px">' +
              '<button class="npv10-btn" id="npv10RpPlay">Play</button>' +
              '<button class="npv10-btn alt" id="npv10RpBack">&#9664; Back</button>' +
              '<button class="npv10-btn alt" id="npv10RpFwd">Forward &#9654;</button>' +
              '<button class="npv10-btn alt" id="npv10RpReset">Reset</button>' +
            '</div>' +
            '<label class="npv10-label">Speed</label>' +
            '<div class="npv10-field"><select id="npv10RpSpeed" class="npv10-select">' +
              '<option value="200">200 ms (fast)</option><option value="300">300 ms</option>' +
              '<option value="500" selected>500 ms</option><option value="1000">1000 ms (slow)</option></select></div>' +
            '<label class="npv10-label">Timeline</label>' +
            '<input id="npv10RpTimeline" type="range" min="0" max="0" value="0" step="1" style="width:100%;accent-color:#1d4ed8">' +
            '<div class="npv10-result" style="margin-top:10px">' +
              '<div class="npv10-stat"><small>Position</small><b id="npv10RpPos">--</b></div>' +
              '<div class="npv10-stat"><small>Close</small><b id="npv10RpClose">--</b></div>' +
              '<div class="npv10-stat"><small>Since start</small><b id="npv10RpChange">--</b></div>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="npv10-card" style="padding:8px">' +
          '<div class="npv10-chartbox" style="height:520px"><div id="npv10RpChart" style="width:100%;height:100%"></div></div>' +
          '<div class="npv10-note" style="margin-top:8px">Verified daily/weekly/monthly OHLCV from the NEPSE Pulse backend. Intraday history is shown only where the feed returns it. No synthetic candles are generated.</div>' +
        '</div>' +
      '</div>';

    var main = document.querySelector('main') || document.body;
    main.appendChild(section);

    state.chart = new window.NpChart($('npv10RpChart'), { visible: 70, symbol: 'NABIL' });
    $('npv10RpChart').parentElement.appendChild($('npv10RpChart'));
    bind();
  }

  function setBadge(text, tone) {
    var el = $('npv10ReplayState');
    if (!el) return;
    el.textContent = text;
    el.style.background = tone === 'play' ? '#dcfce7' : tone === 'error' ? '#fee2e2' : '#eef4ff';
    el.style.color = tone === 'play' ? '#15803d' : tone === 'error' ? '#b91c1c' : '#1d4ed8';
  }

  function setMeta(text) { var el = $('npv10RpMeta'); if (el) el.textContent = text; }

  async function load() {
    V = window.NPV10 || V;
    var sym = String(($('npv10RpSymbol') && $('npv10RpSymbol').value) || '').trim().toUpperCase();
    if (!/^[A-Z0-9._-]{1,24}$/.test(sym)) { setMeta('Enter a valid NEPSE symbol.'); return; }
    stop();
    var res = $('npv10RpRes').value || 'D';
    state.symbol = sym; state.resolution = res;
    setBadge('LOADING', 'idle');
    setMeta('Loading ' + sym + ' ' + res + ' candles...');
    try {
      var d = await V.fetchJSON('/api/tv/history?symbol=' + encodeURIComponent(sym) + '&resolution=' + res + '&countback=1500');
      if (d.s !== 'ok' || !Array.isArray(d.t) || !d.t.length) throw new Error('no OHLCV history returned for ' + sym + ' at ' + res + ' resolution');
      var rows = [];
      for (var i = 0; i < d.t.length; i++) {
        var c = { time: Number(d.t[i]) * 1000, open: Number(d.o[i]), high: Number(d.h[i]), low: Number(d.l[i]), close: Number(d.c[i]), volume: Number(d.v ? d.v[i] : 0) || 0 };
        if ([c.open, c.high, c.low, c.close].every(Number.isFinite)) rows.push(c);
      }
      if (rows.length < 5) throw new Error('only ' + rows.length + ' usable candles were returned');
      state.candles = rows;
      state.chart.intraday = false;
      state.chart.setData(rows, { symbol: sym });
      // Start replay 45 bars before the end, or at the first candle for short series.
      state.start = Math.max(0, rows.length - Math.min(rows.length - 1, 45));
      state.cursor = state.start;
      var dateInput = $('npv10RpDate');
      if (dateInput) {
        dateInput.min = fmtDate(rows[0].time);
        dateInput.max = fmtDate(rows[rows.length - 1].time);
        dateInput.value = fmtDate(rows[state.start].time);
      }
      $('npv10RpTimeline').max = String(rows.length - 1);
      state.loaded = true;
      applyCursor();
      setBadge('READY', 'idle');
      setMeta(sym + ' - ' + rows.length + ' ' + (res === 'D' ? 'daily' : res === 'W' ? 'weekly' : 'monthly') + ' candles from ' + fmtTime(rows[0].time) + ' to ' + fmtTime(rows[rows.length - 1].time) + '. Replay begins ' + fmtTime(rows[state.start].time) + '.');
    } catch (e) {
      setBadge('ERROR', 'error');
      setMeta('Replay data unavailable: ' + e.message);
    }
  }

  function applyCursor() {
    if (!state.loaded) return;
    var c = state.candles;
    state.cursor = Math.max(0, Math.min(c.length - 1, state.cursor));
    state.chart.setReveal(state.cursor);
    var tl = $('npv10RpTimeline');
    if (tl && Number(tl.value) !== state.cursor) tl.value = String(state.cursor);
    var cur = c[state.cursor], first = c[state.start];
    $('npv10RpPos').textContent = (state.cursor + 1) + ' / ' + c.length;
    $('npv10RpClose').textContent = cur.close.toFixed(2);
    var change = state.cursor > state.start ? ((cur.close - first.close) / first.close) * 100 : 0;
    var chgEl = $('npv10RpChange');
    chgEl.textContent = (change > 0 ? '+' : '') + change.toFixed(2) + '%';
    chgEl.className = change > 0 ? 'npv10-up' : change < 0 ? 'npv10-down' : '';
  }

  function stop() {
    if (state.timer) { clearTimeout(state.timer); state.timer = null; }
    state.playing = false;
    var btn = $('npv10RpPlay');
    if (btn) { btn.textContent = 'Play'; btn.classList.remove('danger'); }
    if (state.loaded) setBadge('PAUSED', 'idle');
  }

  function tick() {
    if (!state.playing) return;
    if (state.cursor >= state.candles.length - 1) { stop(); setBadge('END OF DATA', 'idle'); return; }
    state.cursor += 1;
    applyCursor();
    state.timer = setTimeout(tick, state.speed);
  }

  function play() {
    if (!state.loaded) { setMeta('Load candles before starting replay.'); return; }
    if (state.cursor >= state.candles.length - 1) state.cursor = state.start;
    state.playing = true;
    var btn = $('npv10RpPlay');
    if (btn) { btn.textContent = 'Pause'; btn.classList.add('danger'); }
    setBadge('PLAYING', 'play');
    clearTimeout(state.timer);
    state.timer = setTimeout(tick, state.speed);
  }

  function bind() {
    $('npv10RpLoad').addEventListener('click', load);
    $('npv10RpSymbol').addEventListener('keydown', function (e) { if (e.key === 'Enter') load(); });
    $('npv10RpPlay').addEventListener('click', function () { if (state.playing) stop(); else play(); });
    $('npv10RpFwd').addEventListener('click', function () { stop(); if (!state.loaded) return; state.cursor += 1; applyCursor(); });
    $('npv10RpBack').addEventListener('click', function () { stop(); if (!state.loaded) return; state.cursor -= 1; applyCursor(); });
    $('npv10RpReset').addEventListener('click', function () {
      stop(); if (!state.loaded) return;
      state.cursor = state.start; applyCursor(); setBadge('RESET', 'idle');
    });
    $('npv10RpSpeed').addEventListener('change', function (e) {
      state.speed = Number(e.target.value) || 500;
      if (state.playing) { clearTimeout(state.timer); state.timer = setTimeout(tick, state.speed); }
    });
    $('npv10RpTimeline').addEventListener('input', function (e) {
      stop(); state.cursor = Number(e.target.value); applyCursor();
    });
    $('npv10RpStartDate').addEventListener('click', function () {
      if (!state.loaded) return;
      var picked = $('npv10RpDate').value;
      if (!picked) return;
      var target = new Date(picked + 'T00:00:00').getTime();
      var idx = state.candles.findIndex(function (c) { return fmtDate(c.time) >= picked; });
      if (idx < 0) idx = state.candles.length - 1;
      if (state.candles[idx].time < target) idx = Math.min(state.candles.length - 1, idx + 1);
      stop();
      state.cursor = idx;
      applyCursor();
      setBadge('JUMPED', 'idle');
      setMeta('Replay position set to ' + fmtDate(state.candles[idx].time) + '. Later candles stay hidden until you advance.');
    });
    var legacy = document.getElementById('txpSymbol');
    if (legacy) legacy.addEventListener('change', function () { if (legacy.value) $('npv10RpSymbol').value = legacy.value; });
  }

  window.NPV10Replay = {
    open: function (symbol) {
      if (symbol) { var el = $('npv10RpSymbol'); if (el) el.value = String(symbol).toUpperCase(); load(); }
      if (typeof window.openFeatureWorkspace === 'function') window.openFeatureWorkspace('npv10-replay', 'Chart Replay');
    },
    load: load,
    state: function () { return { symbol: state.symbol, cursor: state.cursor, total: state.candles.length, playing: state.playing }; }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build, { once: true });
  else setTimeout(build, 0);
})();
