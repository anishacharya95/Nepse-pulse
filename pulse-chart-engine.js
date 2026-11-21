/* NEPSE Pulse - advanced chart engine (v10)
   Canvas candlestick / line charts used by Professional Charting, Chart Replay,
   Scenario Simulator, Portfolio Drawdown and Stock Comparison.
   No synthetic market data: every candle comes from the verified NEPSE feed. */
(function () {
  'use strict';
  if (window.NpChart) return;

  var COLORS = {
    up: '#16a34a', down: '#dc2626', upFill: 'rgba(22,163,74,.85)', downFill: 'rgba(220,38,38,.85)',
    text: '#475569', grid: '#e8edf4', axis: '#cbd5e1', cross: '#64748b', bg: '#ffffff',
    ema: '#f59e0b', sma: '#2563eb', bb: '#7c3aed', vwap: '#0891b2', sr: '#d97706'
  };

  function num(v) { var n = Number(v); return Number.isFinite(n) ? n : null; }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtPrice(v) {
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return '--';
    var n = Number(v);
    if (Math.abs(n) >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
    return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function fmtVol(v) {
    var n = num(v); if (n === null) return '--';
    if (Math.abs(n) >= 1e7) return (n / 1e7).toFixed(2) + ' Cr';
    if (Math.abs(n) >= 1e5) return (n / 1e5).toFixed(2) + ' L';
    if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(Math.round(n));
  }
  function fmtTime(ms, intraday) {
    var d = new Date(ms);
    if (intraday) return pad(d.getHours()) + ':' + pad(d.getMinutes());
    return d.getDate() + ' ' + ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()] + ' ' + String(d.getFullYear()).slice(2);
  }

  /* ---------- indicator maths ---------- */
  function sma(values, period) {
    var out = new Array(values.length).fill(null), sum = 0;
    for (var i = 0; i < values.length; i++) {
      sum += values[i];
      if (i >= period) sum -= values[i - period];
      if (i >= period - 1) out[i] = sum / period;
    }
    return out;
  }
  function ema(values, period) {
    var out = new Array(values.length).fill(null), k = 2 / (period + 1), prev = null;
    for (var i = 0; i < values.length; i++) {
      var v = values[i];
      if (v === null || !Number.isFinite(v)) continue;
      prev = prev === null ? v : v * k + prev * (1 - k);
      out[i] = prev;
    }
    return out;
  }
  function rsi(values, period) {
    period = period || 14;
    var out = new Array(values.length).fill(null), gain = 0, loss = 0;
    for (var i = 1; i < values.length; i++) {
      var diff = values[i] - values[i - 1];
      var g = Math.max(diff, 0), l = Math.max(-diff, 0);
      if (i <= period) { gain += g; loss += l; if (i === period) { gain /= period; loss /= period; out[i] = 100 - 100 / (1 + (loss === 0 ? 100 : gain / loss)); } }
      else {
        gain = (gain * (period - 1) + g) / period;
        loss = (loss * (period - 1) + l) / period;
        out[i] = 100 - 100 / (1 + (loss === 0 ? 100 : gain / loss));
      }
    }
    return out;
  }
  function macd(values, fast, slow, signalPeriod) {
    fast = fast || 12; slow = slow || 26; signalPeriod = signalPeriod || 9;
    var f = ema(values, fast), s = ema(values, slow);
    var line = values.map(function (_, i) { return f[i] !== null && s[i] !== null ? f[i] - s[i] : null; });
    var clean = line.map(function (v) { return v === null ? 0 : v; });
    var sig = ema(clean, signalPeriod);
    var hist = line.map(function (v, i) { return v !== null && sig[i] !== null ? v - sig[i] : null; });
    return { line: line, signal: sig, hist: hist };
  }
  function bollinger(values, period, mult) {
    period = period || 20; mult = mult || 2;
    var mid = sma(values, period), upper = new Array(values.length).fill(null), lower = new Array(values.length).fill(null);
    for (var i = period - 1; i < values.length; i++) {
      var slice = values.slice(i - period + 1, i + 1);
      var m = mid[i];
      var variance = slice.reduce(function (a, v) { return a + Math.pow(v - m, 2); }, 0) / period;
      var sd = Math.sqrt(variance);
      upper[i] = m + mult * sd; lower[i] = m - mult * sd;
    }
    return { mid: mid, upper: upper, lower: lower };
  }
  function vwap(candles) {
    var out = new Array(candles.length).fill(null), pv = 0, vol = 0;
    for (var i = 0; i < candles.length; i++) {
      var c = candles[i];
      var typical = (c.high + c.low + c.close) / 3;
      var v = Number(c.volume) || 0;
      pv += typical * v; vol += v;
      out[i] = vol > 0 ? pv / vol : null;
    }
    return out;
  }
  function swings(candles, lookback) {
    lookback = lookback || 5;
    var highs = [], lows = [];
    for (var i = lookback; i < candles.length - lookback; i++) {
      var hi = true, lo = true;
      for (var j = i - lookback; j <= i + lookback; j++) {
        if (j === i) continue;
        if (candles[j].high >= candles[i].high) hi = false;
        if (candles[j].low <= candles[i].low) lo = false;
      }
      if (hi) highs.push({ i: i, price: candles[i].high });
      if (lo) lows.push({ i: i, price: candles[i].low });
    }
    highs.sort(function (a, b) { return b.i - a.i; });
    lows.sort(function (a, b) { return b.i - a.i; });
    return { resistance: highs.slice(0, 3), support: lows.slice(0, 3) };
  }
  function coreIndicators() {}

  window.NpIndicators = { sma: sma, ema: ema, rsi: rsi, macd: macd, bollinger: bollinger, vwap: vwap, swings: swings };

  /* ---------- candlestick chart ---------- */
  function NpChart(host, options) {
    if (!host) throw new Error('NpChart requires a container');
    options = options || {};
    var self = this;
    this.host = host;
    this.candles = [];
    this.intraday = !!options.intraday;
    this.indicators = { volume: true, sma20: false, ema20: false, bb: false, vwap: false, sr: false, rsi: false, macd: false };
    this.visible = options.visible || 80;
    this.offset = 0;          /* bars hidden on the right of the viewport */
    this.reveal = null;       /* replay: last visible bar index (inclusive) */
    this.cursor = null;
    this.options = options || {};
    this.onLegend = options.onLegend || null;
    this.onCrosshair = options.onCrosshair || null;
    this.padding = { left: 8, right: 64, top: 8, bottom: 22 };
    this._drag = null;
    this._pointers = {};

    host.classList.add('npchart-host');
    host.style.position = host.style.position || 'relative';
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'npchart-canvas';
    this.canvas.style.width = '100%';
    this.canvas.style.height = '100%';
    this.canvas.style.display = 'block';
    this.canvas.style.touchAction = 'none';
    host.appendChild(this.canvas);
    this.legend = document.createElement('div');
    this.legend.className = 'npchart-legend';
    host.appendChild(this.legend);
    this.ctx = this.canvas.getContext('2d');

    this._resize = function () { self.resize(); };
    window.addEventListener('resize', this._resize);
    if (window.ResizeObserver) { this._ro = new ResizeObserver(function () { self.resize(); }); this._ro.observe(host); }
    this._bind();
    this.resize();
  }

  NpChart.prototype._bind = function () {
    var self = this, c = this.canvas;
    function local(e) { var r = c.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
    c.addEventListener('pointerdown', function (e) {
      var p = local(e);
      self._pointers[e.pointerId] = p;
      if (Object.keys(self._pointers).length === 1) {
        self._drag = { x: p.x, offset: self.offset };
        try { c.setPointerCapture(e.pointerId); } catch (_) {}
      }
    });
    c.addEventListener('pointermove', function (e) {
      var p = local(e);
      var keys = Object.keys(self._pointers);
      if (keys.length === 2 && self._pinch) {
        self._pointers[e.pointerId] = p;
        var a = self._pointers[keys[0]], b = self._pointers[keys[1]];
        var dist = Math.hypot(a.x - b.x, a.y - b.y);
        if (self._pinchStart && dist > 0) {
          var ratio = self._pinchStart / dist;
          self.setVisible(Math.round(self._pinchVisible * ratio));
        }
        return;
      }
      self._pointers[e.pointerId] = p;
      if (self._drag) {
        var barW = self._barWidth();
        if (barW > 0) {
          var shift = Math.round((p.x - self._drag.x) / barW);
          self.offset = Math.max(0, Math.min(self._maxOffset(), self._drag.offset + shift));
          self.draw();
        }
      }
      self.cursor = p;
      self._drawCrosshairAt(p);
    });
    function end(e) {
      delete self._pointers[e.pointerId];
      if (Object.keys(self._pointers).length < 2) { self._pinch = false; self._pinchStart = null; }
      if (Object.keys(self._pointers).length === 0) self._drag = null;
      try { c.releasePointerCapture(e.pointerId); } catch (_) {}
    }
    c.addEventListener('pointerup', end);
    c.addEventListener('pointercancel', end);
    c.addEventListener('pointerleave', function () { if (!self._drag) { self.cursor = null; self._drawCrosshairAt(null); } });
    c.addEventListener('wheel', function (e) {
      if (!self.candles.length) return;
      e.preventDefault();
      var step = Math.max(2, Math.round(self.visible * 0.12));
      self.setVisible(self.visible + (e.deltaY > 0 ? step : -step));
    }, { passive: false });
    c.addEventListener('dblclick', function () { self.fit(); });
    c.addEventListener('touchstart', function (e) {
      if (e.touches.length === 2) {
        self._pinch = true;
        self._pinchVisible = self.visible;
        self._pinchStart = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
      }
    }, { passive: true });
  };

  NpChart.prototype.resize = function () {
    var r = this.host.getBoundingClientRect();
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var w = Math.max(240, Math.floor(r.width)), h = Math.max(180, Math.floor(r.height));
    this.w = w; this.h = h;
    this.canvas.width = Math.floor(w * dpr);
    this.canvas.height = Math.floor(h * dpr);
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (this.candles.length && this.offset === 0 && !this._userZoom) this.fit();
    else this.draw();
  };

  NpChart.prototype.setData = function (candles, meta) {
    this.candles = (Array.isArray(candles) ? candles : []).filter(function (c) {
      return c && Number.isFinite(c.time) && Number.isFinite(c.open) && Number.isFinite(c.close);
    }).sort(function (a, b) { return a.time - b.time; });
    this.meta = meta || {};
    this.offset = 0; this.reveal = null; this._userZoom = false;
    this.fit();
    return this;
  };

  NpChart.prototype.setReveal = function (n) {
    this.reveal = n === null || n === undefined ? null : Math.max(0, Math.min(this.candles.length - 1, n));
    this.offset = 0;
    this.draw();
  };

  NpChart.prototype.setIndicators = function (obj) {
    Object.assign(this.indicators, obj || {});
    this.draw();
  };

  NpChart.prototype.setVisible = function (n) {
    var total = this._total();
    this.visible = Math.max(12, Math.min(Math.max(12, total), Math.round(n)));
    this._userZoom = true;
    this.offset = Math.max(0, Math.min(this._maxOffset(), this.offset));
    this.draw();
  };

  NpChart.prototype._total = function () {
    return this.reveal === null ? this.candles.length : Math.min(this.candles.length, this.reveal + 1);
  };
  NpChart.prototype._maxOffset = function () { return Math.max(0, this._total() - this.visible); };

  NpChart.prototype.fit = function () {
    this.visible = Math.max(12, Math.min(this._total(), (this.options && this.options.visible) || 80));
    this.offset = 0;
    this.draw();
  };

  NpChart.prototype.panTo = function (endIndex) {
    var total = this._total();
    var visible = Math.min(this.visible, total);
    this.offset = Math.max(0, total - 1 - endIndex);
    this.draw();
  };

  NpChart.prototype._view = function () {
    var total = this._total();
    var visible = Math.max(5, Math.min(this.visible, total));
    var end = total - this.offset;
    var start = Math.max(0, end - visible);
    return { start: start, end: Math.max(start + 1, end), visible: visible, total: total };
  };

  NpChart.prototype._barWidth = function () {
    var v = this._view();
    var innerW = this.w - this.padding.left - this.padding.right;
    return innerW / Math.max(1, v.visible);
  };

  NpChart.prototype._panes = function () {
    var H = this.h - this.padding.top - this.padding.bottom;
    var volH = this.indicators.volume ? Math.round(H * 0.16) : 0;
    var rsiH = this.indicators.rsi ? Math.round(H * 0.16) : 0;
    var macdH = this.indicators.macd ? Math.round(H * 0.18) : 0;
    var mainH = Math.max(80, H - volH - rsiH - macdH - 6);
    var top = this.padding.top;
    return {
      main: { top: top, height: mainH },
      volume: { top: top + mainH + 3, height: volH },
      rsi: { top: top + mainH + volH + 6, height: rsiH },
      macd: { top: top + mainH + volH + rsiH + 9, height: macdH }
    };
  };

  NpChart.prototype.draw = function () {
    var ctx = this.ctx, w = this.w, h = this.h, self = this;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = COLORS.bg;
    ctx.fillRect(0, 0, w, h);
    if (!this.candles.length) {
      ctx.fillStyle = '#94a3b8';
      ctx.font = '600 12px system-ui,sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('No verified candles available', w / 2, h / 2);
      return;
    }
    var v = this._view();
    var rows = this.candles.slice(v.start, v.end);
    var panes = this._panes();
    var plotLeft = this.padding.left;
    var plotRight = w - this.padding.right;
    var plotW = plotRight - plotLeft;
    var barW = plotW / Math.max(1, rows.length);

    var closes = this.candles.map(function (c) { return c.close; });
    var highs = rows.map(function (c) { return c.high; });
    var lows = rows.map(function (c) { return c.low; });
    var min = Math.min.apply(null, lows), max = Math.max.apply(null, highs);
    var compute = {
      sma20: sma(closes, 20), ema20: ema(closes, 20), bb: bollinger(closes, 20, 2),
      /* VWAP is cumulative: slice to the visible history so a replay never leaks future bars */
      vwap: vwap(this.candles.slice(0, v.end)), rsi: rsi(closes, 14), macd: macd(closes), sr: swings(this.candles.slice(0, v.end), 4)
    };
    if (this.indicators.bb) {
      var bbSlice = compute.bb;
      for (var k = v.start; k < v.end; k++) {
        if (bbSlice.upper[k] !== null) max = Math.max(max, bbSlice.upper[k]);
        if (bbSlice.lower[k] !== null) min = Math.min(min, bbSlice.lower[k]);
      }
    }
    if (this.indicators.sr) {
      compute.sr.support.concat(compute.sr.resistance).forEach(function (l) { max = Math.max(max, l.price); min = Math.min(min, l.price); });
    }
    var span = max - min || Math.max(1, max * 0.02);
    var yOf = function (price) { return panes.main.top + panes.main.height - ((price - min) / span) * panes.main.height; };
    var xOf = function (i) { return plotLeft + (i + 0.5) * barW; };

    /* grid + price axis */
    ctx.font = '10px system-ui,sans-serif';
    ctx.textAlign = 'left';
    for (var g = 0; g <= 4; g++) {
      var py = panes.main.top + (panes.main.height * g) / 4;
      var price = max - (span * g) / 4;
      ctx.strokeStyle = COLORS.grid; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(plotLeft, Math.round(py) + 0.5); ctx.lineTo(w, Math.round(py) + 0.5); ctx.stroke();
      ctx.fillStyle = COLORS.text;
      ctx.fillText(fmtPrice(price), plotRight + 6, py + 3);
    }

    /* time axis */
    ctx.textAlign = 'center';
    var step = Math.max(1, Math.floor(rows.length / Math.max(2, Math.floor(plotW / 78))));
    for (var ti = 0; ti < rows.length; ti += step) {
      var tx = xOf(ti);
      ctx.strokeStyle = COLORS.grid;
      ctx.beginPath(); ctx.moveTo(tx, panes.main.top); ctx.lineTo(tx, h - this.padding.bottom); ctx.stroke();
      ctx.fillStyle = COLORS.text;
      ctx.fillText(fmtTime(rows[ti].time, this.intraday), tx, h - 7);
    }

    /* candles + volume */
    var bodyW = Math.max(1, Math.min(14, barW * 0.68));
    var volRows = rows.map(function (r) { return Number(r.volume) || 0; });
    var volMax = Math.max.apply(null, volRows.concat([1]));
    rows.forEach(function (c, i) {
      var x = xOf(i);
      var up = c.close >= c.open;
      ctx.strokeStyle = up ? COLORS.up : COLORS.down;
      ctx.fillStyle = up ? COLORS.upFill : COLORS.downFill;
      ctx.lineWidth = Math.max(1, Math.min(2, barW * 0.12));
      ctx.beginPath(); ctx.moveTo(x, yOf(c.high)); ctx.lineTo(x, yOf(c.low)); ctx.stroke();
      var yO = yOf(c.open), yC = yOf(c.close);
      var top = Math.min(yO, yC), hgt = Math.max(1, Math.abs(yC - yO));
      ctx.fillRect(x - bodyW / 2, top, bodyW, hgt);
      if (panes.volume.height) {
        var vh = (volRows[i] / volMax) * (panes.volume.height - 2);
        ctx.fillStyle = up ? 'rgba(22,163,74,.45)' : 'rgba(220,38,38,.45)';
        ctx.fillRect(x - bodyW / 2, panes.volume.top + panes.volume.height - vh, bodyW, vh);
      }
    });
    if (panes.volume.height) {
      ctx.fillStyle = '#94a3b8'; ctx.textAlign = 'left'; ctx.font = '9px system-ui,sans-serif';
      ctx.fillText('VOL ' + fmtVol(volMax), plotLeft + 2, panes.volume.top + 9);
    }

    /* overlays */
    this._line = function (values, color, width, dashed) {
      ctx.strokeStyle = color; ctx.lineWidth = width || 1.4;
      ctx.setLineDash(dashed || []);
      ctx.beginPath();
      var started = false;
      for (var i = 0; i < rows.length; i++) {
        var idx = v.start + i, val = values[idx];
        if (val === null || val === undefined || !Number.isFinite(val)) { started = false; continue; }
        var yy = yOf(val), xx = xOf(i);
        if (!started) { ctx.moveTo(xx, yy); started = true; } else ctx.lineTo(xx, yy);
      }
      ctx.stroke(); ctx.setLineDash([]);
    };
    if (this.indicators.sma20) this._line(compute.sma20, COLORS.sma, 1.4);
    if (this.indicators.ema20) this._line(compute.ema20, COLORS.ema, 1.4);
    if (this.indicators.bb) {
      this._line(compute.bb.upper, COLORS.bb, 1, [4, 3]);
      this._line(compute.bb.lower, COLORS.bb, 1, [4, 3]);
      this._line(compute.bb.mid, 'rgba(124,58,237,.55)', 1);
    }
    if (this.indicators.vwap) this._line(compute.vwap, COLORS.vwap, 1.5, [5, 3]);

    /* support / resistance */
    if (this.indicators.sr) {
      ctx.font = '9px system-ui,sans-serif'; ctx.textAlign = 'left';
      compute.sr.resistance.forEach(function (lvl) {
        var yy = yOf(lvl.price);
        if (yy < panes.main.top || yy > panes.main.top + panes.main.height) return;
        ctx.strokeStyle = 'rgba(217,119,6,.75)'; ctx.setLineDash([6, 4]); ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(plotLeft, yy); ctx.lineTo(plotRight, yy); ctx.stroke();
        ctx.fillStyle = '#b45309'; ctx.fillText('R ' + fmtPrice(lvl.price), plotLeft + 4, yy - 3);
      });
      compute.sr.support.forEach(function (lvl) {
        var yy = yOf(lvl.price);
        if (yy < panes.main.top || yy > panes.main.top + panes.main.height) return;
        ctx.strokeStyle = 'rgba(13,148,136,.75)'; ctx.setLineDash([6, 4]); ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(plotLeft, yy); ctx.lineTo(plotRight, yy); ctx.stroke();
        ctx.fillStyle = '#0f766e'; ctx.fillText('S ' + fmtPrice(lvl.price), plotLeft + 4, yy + 11);
      });
      ctx.setLineDash([]);
    }

    /* RSI pane */
    if (panes.rsi.height) {
      var rTop = panes.rsi.top, rH = panes.rsi.height;
      var yR = function (val) { return rTop + rH - (Math.max(0, Math.min(100, val)) / 100) * rH; };
      ctx.strokeStyle = COLORS.grid;
      [30, 50, 70].forEach(function (lvl) {
        ctx.setLineDash(lvl === 50 ? [] : [3, 3]);
        ctx.beginPath(); ctx.moveTo(plotLeft, yR(lvl)); ctx.lineTo(plotRight, yR(lvl)); ctx.stroke();
        ctx.fillStyle = '#94a3b8'; ctx.font = '9px system-ui,sans-serif'; ctx.textAlign = 'left';
        ctx.fillText(String(lvl), plotRight + 6, yR(lvl) + 3);
      });
      ctx.setLineDash([]);
      ctx.strokeStyle = '#9333ea'; ctx.lineWidth = 1.4; ctx.beginPath();
      var startedR = false;
      for (var ri = 0; ri < rows.length; ri++) {
        var rv = compute.rsi[v.start + ri];
        if (rv === null) { startedR = false; continue; }
        var rx = xOf(ri), ry = yR(rv);
        if (!startedR) { ctx.moveTo(rx, ry); startedR = true; } else ctx.lineTo(rx, ry);
      }
      ctx.stroke();
      ctx.fillStyle = '#9333ea'; ctx.textAlign = 'left'; ctx.font = '9px system-ui,sans-serif';
      ctx.fillText('RSI 14', plotLeft + 2, rTop + 9);
    }

    /* MACD pane */
    if (panes.macd.height) {
      var mTop = panes.macd.top, mH = panes.macd.height;
      var mvals = [];
      for (var mi = v.start; mi < v.end; mi++) {
        [compute.macd.line[mi], compute.macd.signal[mi], compute.macd.hist[mi]].forEach(function (x) { if (x !== null && Number.isFinite(x)) mvals.push(x); });
      }
      var mMax = Math.max.apply(null, mvals.concat([0.0001]));
      var mMin = Math.min.apply(null, mvals.concat([-0.0001]));
      var mSpan = (mMax - mMin) || 1;
      var yM = function (val) { return mTop + mH - ((val - mMin) / mSpan) * mH; };
      ctx.strokeStyle = COLORS.grid; ctx.beginPath(); ctx.moveTo(plotLeft, yM(0)); ctx.lineTo(plotRight, yM(0)); ctx.stroke();
      for (var hi = 0; hi < rows.length; hi++) {
        var hv = compute.macd.hist[v.start + hi];
        if (hv === null) continue;
        ctx.fillStyle = hv >= 0 ? 'rgba(22,163,74,.5)' : 'rgba(220,38,38,.5)';
        var hx = xOf(hi), hy = yM(hv), hz = yM(0);
        ctx.fillRect(hx - Math.max(1, bodyW / 2), Math.min(hy, hz), Math.max(1, bodyW), Math.max(1, Math.abs(hy - hz)));
      }
      ctx.strokeStyle = '#2563eb'; ctx.lineWidth = 1.3; ctx.beginPath();
      var st = false;
      for (var li = 0; li < rows.length; li++) {
        var lv = compute.macd.line[v.start + li];
        if (lv === null) { st = false; continue; }
        var lx = xOf(li), ly = yM(lv);
        if (!st) { ctx.moveTo(lx, ly); st = true; } else ctx.lineTo(lx, ly);
      }
      ctx.stroke();
      ctx.strokeStyle = '#f59e0b'; ctx.beginPath();
      st = false;
      for (var si = 0; si < rows.length; si++) {
        var sv = compute.macd.signal[v.start + si];
        if (sv === null) { st = false; continue; }
        var sx = xOf(si), sy = yM(sv);
        if (!st) { ctx.moveTo(sx, sy); st = true; } else ctx.lineTo(sx, sy);
      }
      ctx.stroke();
      ctx.fillStyle = '#475569'; ctx.font = '9px system-ui,sans-serif'; ctx.textAlign = 'left';
      ctx.fillText('MACD 12 26 9', plotLeft + 2, mTop + 9);
    }
    if (panes.volume.height || panes.rsi.height || panes.macd.height) {
      ctx.strokeStyle = COLORS.axis;
      var divider = panes.main.top + panes.main.height + 1;
      ctx.beginPath(); ctx.moveTo(plotLeft, divider); ctx.lineTo(plotRight, divider); ctx.stroke();
    }

    this._lastFrame = { rows: rows, v: v, yOf: yOf, xOf: xOf, compute: compute, panes: panes, barW: barW, min: min, max: max };
    this._updateLegend(null);
    if (this.cursor && !this._inCrosshair) this._drawCrosshairAt(this.cursor);
  };

  NpChart.prototype._indexAt = function (x) {
    var f = this._lastFrame; if (!f) return null;
    var i = Math.floor((x - this.padding.left) / f.barW);
    if (i < 0 || i >= f.rows.length) return null;
    return i;
  };

  NpChart.prototype._drawCrosshairAt = function (p) {
    var f = this._lastFrame; if (!f) return;
    if (!p) {
      this._inCrosshair = true; this.draw(); this._inCrosshair = false;
      this._updateLegend(null); if (this.onCrosshair) this.onCrosshair(null); return;
    }
    var idx = this._indexAt(p.x);
    if (idx === null) {
      this._inCrosshair = true; this.draw(); this._inCrosshair = false;
      this._updateLegend(null); if (this.onCrosshair) this.onCrosshair(null); return;
    }
    var row = f.rows[idx], x = f.xOf(idx), y = p.y;
    var ctx = this.ctx;
    /* redraw the frame under the crosshair so old lines do not accumulate */
    this._inCrosshair = true; this.draw(); this._inCrosshair = false;
    ctx.save();
    ctx.strokeStyle = 'rgba(71,85,105,.65)'; ctx.setLineDash([4, 3]); ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(x, this.padding.top); ctx.lineTo(x, this.h - this.padding.bottom); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(this.padding.left, y); ctx.lineTo(this.w - this.padding.right, y); ctx.stroke();
    ctx.setLineDash([]);
    var price = this._priceAtY(y);
    if (price !== null) {
      ctx.fillStyle = '#334155';
      ctx.fillRect(this.w - this.padding.right, y - 8, this.padding.right - 2, 16);
      ctx.fillStyle = '#fff'; ctx.textAlign = 'left'; ctx.font = '9px system-ui,sans-serif';
      ctx.fillText(fmtPrice(price), this.w - this.padding.right + 4, y + 3);
    }
    ctx.fillStyle = '#0f172a';
    ctx.fillRect(x - 34, this.h - this.padding.bottom, 68, 16);
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.font = '9px system-ui,sans-serif';
    ctx.fillText(fmtTime(row.time, this.intraday), x, this.h - this.padding.bottom + 11);
    ctx.restore();
    this._updateLegend(row);
    if (this.onCrosshair) this.onCrosshair(row, idx);
  };

  NpChart.prototype._priceAtY = function (y) {
    var f = this._lastFrame; if (!f) return null;
    var main = f.panes.main;
    if (y < main.top || y > main.top + main.height) return null;
    var ratio = (main.top + main.height - y) / main.height;
    return f.min + ratio * (f.max - f.min);
  };

  NpChart.prototype._updateLegend = function (row) {
    var f = this._lastFrame; if (!f) { this.legend.innerHTML = ''; return; }
    var c = row || f.rows[f.rows.length - 1];
    if (!c) { this.legend.innerHTML = ''; return; }
    var up = c.close >= c.open;
    var change = c.open ? ((c.close - c.open) / c.open) * 100 : 0;
    var parts = [
      '<span class="npch-sym">' + (this.meta.symbol || 'NEPSE') + '</span>',
      '<span>O <b>' + fmtPrice(c.open) + '</b></span>',
      '<span>H <b>' + fmtPrice(c.high) + '</b></span>',
      '<span>L <b>' + fmtPrice(c.low) + '</b></span>',
      '<span>C <b class="' + (up ? 'npch-up' : 'npch-down') + '">' + fmtPrice(c.close) + '</b></span>',
      '<span>V <b>' + fmtVol(c.volume) + '</b></span>',
      '<span class="' + (change >= 0 ? 'npch-up' : 'npch-down') + '">' + (change >= 0 ? '+' : '') + change.toFixed(2) + '%</span>',
      '<span class="npch-date">' + fmtTime(c.time, this.intraday) + '</span>'
    ];
    this.legend.innerHTML = parts.join('');
    if (this.onLegend) this.onLegend(c);
  };

  NpChart.prototype.destroy = function () {
    window.removeEventListener('resize', this._resize);
    if (this._ro) this._ro.disconnect();
    if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
    if (this.legend.parentNode) this.legend.parentNode.removeChild(this.legend);
  };

  /* ---------- line chart (comparison / drawdown / scenario) ---------- */
  function NpLineChart(host, options) {
    if (!host) throw new Error('NpLineChart requires a container');
    options = options || {};
    var self = this;
    this.host = host; this.series = []; this.options = options; this.cursor = null;
    this.padding = { left: 8, right: 56, top: 10, bottom: 22 };
    host.classList.add('npchart-host');
    this.canvas = document.createElement('canvas');
    this.canvas.style.width = '100%'; this.canvas.style.height = '100%'; this.canvas.style.display = 'block';
    this.canvas.style.touchAction = 'none';
    host.appendChild(this.canvas);
    this.legend = document.createElement('div');
    this.legend.className = 'npchart-legend';
    host.appendChild(this.legend);
    this.ctx = this.canvas.getContext('2d');
    this._resize = function () { self.resize(); };
    window.addEventListener('resize', this._resize);
    if (window.ResizeObserver) { this._ro = new ResizeObserver(function () { self.resize(); }); this._ro.observe(host); }
    this.canvas.addEventListener('pointermove', function (e) {
      var r = self.canvas.getBoundingClientRect();
      self.cursor = { x: e.clientX - r.left, y: e.clientY - r.top };
      self.draw();
    });
    this.canvas.addEventListener('pointerleave', function () { self.cursor = null; self.draw(); });
    this.resize();
  }
  NpLineChart.prototype.resize = function () {
    var r = this.host.getBoundingClientRect();
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var w = Math.max(220, Math.floor(r.width)), h = Math.max(140, Math.floor(r.height));
    this.w = w; this.h = h;
    this.canvas.width = Math.floor(w * dpr); this.canvas.height = Math.floor(h * dpr);
    this.canvas.style.width = w + 'px'; this.canvas.style.height = h + 'px';
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.draw();
  };
  NpLineChart.prototype.setSeries = function (series) {
    this.series = (series || []).map(function (s) {
      return {
        name: s.name || '', color: s.color || '#2563eb', area: !!s.area,
        points: (s.points || []).filter(function (p) { return p && Number.isFinite(p.x) && Number.isFinite(p.y); })
          .sort(function (a, b) { return a.x - b.x; }),
        format: s.format || null
      };
    });
    this.draw();
  };
  NpLineChart.prototype.draw = function () {
    var ctx = this.ctx, w = this.w, h = this.h, self = this;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h);
    var series = this.series.filter(function (s) { return s.points.length; });
    if (!series.length) {
      ctx.fillStyle = '#94a3b8'; ctx.font = '600 12px system-ui,sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(this.options.emptyText || 'No data available', w / 2, h / 2);
      return;
    }
    var all = [];
    series.forEach(function (s) { s.points.forEach(function (p) { all.push(p); }); });
    var minX = Math.min.apply(null, all.map(function (p) { return p.x; }));
    var maxX = Math.max.apply(null, all.map(function (p) { return p.x; }));
    var minY = Math.min.apply(null, all.map(function (p) { return p.y; }));
    var maxY = Math.max.apply(null, all.map(function (p) { return p.y; }));
    if (minY === maxY) { minY -= 1; maxY += 1; }
    var padY = (maxY - minY) * 0.08; minY -= padY; maxY += padY;
    var left = this.padding.left, right = w - this.padding.right, top = this.padding.top, bottom = h - this.padding.bottom;
    var xOf = function (x) { return left + ((x - minX) / Math.max(1, maxX - minX)) * (right - left); };
    var yOf = function (y) { return top + (1 - (y - minY) / (maxY - minY)) * (bottom - top); };

    ctx.font = '9px system-ui,sans-serif';
    for (var g = 0; g <= 4; g++) {
      var py = top + ((bottom - top) * g) / 4;
      var val = maxY - ((maxY - minY) * g) / 4;
      ctx.strokeStyle = COLORS.grid; ctx.beginPath(); ctx.moveTo(left, py); ctx.lineTo(right, py); ctx.stroke();
      ctx.fillStyle = COLORS.text; ctx.textAlign = 'left';
      ctx.fillText((series[0].format ? series[0].format(val) : fmtPrice(val)), right + 5, py + 3);
    }
    var count = Math.max(2, Math.floor((right - left) / 80));
    for (var t = 0; t <= count; t++) {
      var xv = minX + ((maxX - minX) * t) / count;
      var xx = xOf(xv);
      ctx.strokeStyle = COLORS.grid; ctx.beginPath(); ctx.moveTo(xx, top); ctx.lineTo(xx, bottom); ctx.stroke();
      ctx.fillStyle = COLORS.text; ctx.textAlign = 'center';
      ctx.fillText(fmtTime(xv, false), xx, h - 7);
    }

    series.forEach(function (s) {
      ctx.strokeStyle = s.color; ctx.lineWidth = 1.8;
      if (s.area) {
        var grad = ctx.createLinearGradient(0, top, 0, bottom);
        grad.addColorStop(0, s.color + '55'); grad.addColorStop(1, s.color + '05');
        ctx.fillStyle = grad;
        ctx.beginPath(); ctx.moveTo(xOf(s.points[0].x), bottom);
        s.points.forEach(function (p) { ctx.lineTo(xOf(p.x), yOf(p.y)); });
        ctx.lineTo(xOf(s.points[s.points.length - 1].x), bottom);
        ctx.closePath(); ctx.fill();
      }
      ctx.beginPath();
      s.points.forEach(function (p, i) { var X = xOf(p.x), Y = yOf(p.y); if (i === 0) ctx.moveTo(X, Y); else ctx.lineTo(X, Y); });
      ctx.stroke();
    });

    this._frame = { xOf: xOf, yOf: yOf, minX: minX, maxX: maxX, minY: minY, maxY: maxY, series: series };
    if (this.cursor) {
      var best = null;
      series.forEach(function (s) {
        s.points.forEach(function (p) {
          var dx = Math.abs(xOf(p.x) - self.cursor.x);
          if (!best || dx < best.dx) best = { dx: dx, p: p, s: s };
        });
      });
      if (best) {
        var X = xOf(best.p.x), Y = yOf(best.p.y);
        ctx.strokeStyle = 'rgba(71,85,105,.5)'; ctx.setLineDash([4, 3]);
        ctx.beginPath(); ctx.moveTo(X, top); ctx.lineTo(X, bottom); ctx.stroke(); ctx.setLineDash([]);
        ctx.fillStyle = best.s.color; ctx.beginPath(); ctx.arc(X, Y, 3.5, 0, Math.PI * 2); ctx.fill();
        this._legendHtml = series.map(function (s) {
          var nearest = s.points.reduce(function (acc, p) { return Math.abs(p.x - best.p.x) < Math.abs(acc.x - best.p.x) ? p : acc; }, s.points[0]);
          return '<span><i style="background:' + s.color + '"></i>' + s.name + ' <b>' + (s.format ? s.format(nearest.y) : fmtPrice(nearest.y)) + '</b></span>';
        }).join('') + '<span class="npch-date">' + fmtTime(best.p.x, false) + '</span>';
      }
    } else {
      this._legendHtml = series.map(function (s) {
        var last = s.points[s.points.length - 1];
        return '<span><i style="background:' + s.color + '"></i>' + s.name + ' <b>' + (s.format ? s.format(last.y) : fmtPrice(last.y)) + '</b></span>';
      }).join('');
    }
    this.legend.innerHTML = this._legendHtml || '';
  };
  NpLineChart.prototype.destroy = function () {
    window.removeEventListener('resize', this._resize);
    if (this._ro) this._ro.disconnect();
    if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
    if (this.legend.parentNode) this.legend.parentNode.removeChild(this.legend);
  };

  window.NpChart = NpChart;
  window.NpLineChart = NpLineChart;
  window.NpChartColors = COLORS;
  window.NpChartUtils = { fmtPrice: fmtPrice, fmtVol: fmtVol, fmtTime: fmtTime };
})();
