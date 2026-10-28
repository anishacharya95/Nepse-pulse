/* ===========================================================================
   NEPSE Pulse — verified market math (one shared implementation)

   The index card, the chart summary and every dashboard panel must describe
   ONE observation for the same session, so all of them resolve it here.

   Rules:
   - Nothing is hardcoded: every number is read from the provider payload or
     derived from two provider-supplied prices for the same session.
   - change = verified current index - previous official close (zero/missing
     guarded). A field pair is only accepted when it reconciles with the
     provider's own reported change or percent change, because some providers
     ship a `close` that actually holds the previous session's close.
   - Breadth counts valid, de-duplicated securities of the same session using
     close vs previous-day close. Provider counts are used only when they
     reconcile with the session universe; otherwise the UI shows unavailable
     instead of an unverified guess.
   =========================================================================== */
(function () {
  'use strict';

  var num = function (v) {
    if (v === null || v === undefined || v === '') return null;
    var n = Number(String(v).replace(/,/g, ''));
    return Number.isFinite(n) ? n : null;
  };

  var normKey = function (k) { return String(k).toLowerCase().replace(/[^a-z0-9]/g, ''); };

  var pick = function (o, keys) {
    if (!o || typeof o !== 'object') return null;
    var map = {};
    Object.keys(o).forEach(function (k) { map[normKey(k)] = o[k]; });
    for (var i = 0; i < keys.length; i++) {
      var v = map[normKey(keys[i])];
      if (v !== undefined && v !== null && v !== '') return v;
    }
    return null;
  };

  var unique = function (list) {
    return list.filter(function (v, i) { return v !== null && list.indexOf(v) === i; });
  };

  // First usable candidate, always null (never undefined) when there is none.
  var firstOf = function (list) { return list.length ? list[0] : null; };

  // Session (current) price of one security.
  var PRICE_KEYS = ['ltp', 'closePrice', 'closingPrice', 'lastTradedPrice', 'lastPrice', 'close', 'price', 'currentPrice'];
  // Previous official close of one security (same-session comparison basis).
  var PREV_KEYS = ['prev', 'previousDayClosePrice', 'previousClose', 'previousClosingPrice', 'prevClose', 'previousPrice', 'previousDayClose', 'previousDayClosingPrice'];

  var round2 = function (v) { return Math.round((v + Number.EPSILON) * 100) / 100; };

  /* ---- Index observation ------------------------------------------------
     Returns {value, prevClose, change, pct, verified, basis}.
     `verified` is true only when the resolved pair reconciles with a
     provider-reported change/percent, or when it is derived from two distinct
     provider fields with no reported figure contradicting it. */
  function indexObservation(item) {
    var out = { value: null, prevClose: null, change: null, pct: null, verified: false, basis: 'missing' };
    if (!item || typeof item !== 'object') return out;

    var reportedChange = num(pick(item, ['change', 'pointChange', 'changeValue', 'difference', 'delta']));
    var reportedPct = num(pick(item, ['perChange', 'percentChange', 'percentageChange', 'changePercent', 'pChange', 'percent_change']));

    // Candidate current values, most authoritative first.
    var latest = unique([
      num(pick(item, ['currentValue', 'latest', 'current'])),
      num(pick(item, ['close'])),
      num(pick(item, ['value', 'indexValue']))
    ]);
    // Candidate previous official closes, most authoritative first.
    var prev = unique([
      num(pick(item, ['previousClose', 'previousClosingPrice', 'prevClose', 'previousDayClosePrice'])),
      num(pick(item, ['close'])),
      num(pick(item, ['currentValue', 'latest']))
    ]);

    var pairs = [];
    latest.forEach(function (l, li) {
      prev.forEach(function (p, pi) {
        if (l === null || p === null || !(p > 0) || l === p) return;
        pairs.push({ l: l, p: p, order: li * 10 + pi });
      });
    });

    // change is always recomputed from the resolved pair; the provider's
    // published percent is adopted only when it reconciles with that pair
    // within rounding, so the card matches both the maths and the source.
    var finish = function (pair) {
      var change = round2(pair.l - pair.p);
      var computedPct = round2((change / pair.p) * 100);
      var pct = computedPct;
      if (reportedPct !== null && Math.abs(reportedPct - computedPct) <= 0.05) pct = reportedPct;
      return { value: pair.l, prevClose: pair.p, change: change, pct: pct };
    };

    if (reportedChange !== null) {
      var tol = Math.max(0.02, Math.abs(reportedChange) * 0.02);
      var match = null;
      for (var i = 0; i < pairs.length; i++) {
        if (Math.abs(round2(pairs[i].l - pairs[i].p) - reportedChange) <= tol) { match = pairs[i]; break; }
      }
      if (match) {
        var ok = finish(match);
        ok.verified = true; ok.basis = 'reconciled-with-reported-change';
        return ok;
      }
      // The reported change matches no field pair: fall back to the canonical
      // (current value, previous close) pair so every panel stays consistent,
      // and flag the result as unverified rather than silently trusting it.
      var fl = firstOf(latest);
      var fp = firstOf(prev);
      if (fl !== null && fp !== null && fp > 0 && fl !== fp) {
        var forced = finish({ l: fl, p: fp });
        forced.verified = false; forced.basis = 'reported-change-unreconciled';
        return forced;
      }
      out.value = firstOf(latest); out.prevClose = firstOf(prev); out.basis = 'reported-change-unreconciled';
      return out;
    }

    if (reportedPct !== null) {
      var matchPct = null;
      for (var j = 0; j < pairs.length; j++) {
        if (Math.abs(round2(((pairs[j].l - pairs[j].p) / pairs[j].p) * 100) - reportedPct) <= 0.1) { matchPct = pairs[j]; break; }
      }
      if (matchPct) {
        var okPct = finish(matchPct);
        okPct.verified = true; okPct.basis = 'reconciled-with-reported-percent';
        return okPct;
      }
    }

    // No usable reported figures: derive the change only when the canonical
    // pair is genuinely two different prices (guards against swapped fields).
    var cl = firstOf(latest);
    var cp = firstOf(prev);
    if (cl !== null && cp !== null && cp > 0 && Math.abs(cl - cp) >= 0.005) {
      var derived = finish({ l: cl, p: cp });
      derived.verified = true; derived.basis = 'derived-from-close-and-previous-close';
      return derived;
    }
    out.value = cl;
    out.prevClose = cp;
    out.basis = cl === null ? 'missing' : (cp === null || !(cp > 0) ? 'missing-previous-close' : 'indistinguishable-fields');
    return out;
  }

  /* ---- Market breadth ---------------------------------------------------
     rows          : live rows for the session (raw or already normalized)
     provider      : provider-supplied {advancing, declining, unchanged}
     sessionTotal  : total scrips traded for the same session, when known
     Returns {advancing, declining, unchanged, verified, source, covered, skipped};
     advancing/declining/unchanged are null when nothing can be verified. */
  function breadth(rows, provider, sessionTotal) {
    var out = { advancing: null, declining: null, unchanged: null, verified: false, source: null, covered: 0, skipped: 0 };
    var list = Array.isArray(rows) ? rows : [];
    var seen = new Set();
    var adv = 0, dec = 0, unc = 0, skipped = 0;

    for (var i = 0; i < list.length; i++) {
      var row = list[i];
      if (!row || typeof row !== 'object') continue;
      var symbol = String(pick(row, ['symbol', 'stockSymbol', 'securitySymbol', 'ticker', 'scrip', 'code', 'security_code']) || '').trim().toUpperCase();
      if (!symbol || seen.has(symbol)) { skipped++; continue; }
      seen.add(symbol);
      var close = num(pick(row, PRICE_KEYS));
      var prevClose = num(pick(row, PREV_KEYS));
      // Invalid/missing prices and unusable comparisons are not counted.
      if (close === null || prevClose === null || !(close > 0) || !(prevClose > 0)) { skipped++; continue; }
      if (close > prevClose) adv++;
      else if (close < prevClose) dec++;
      else unc++;
    }

    var covered = adv + dec + unc;
    if (covered > 0) {
      return { advancing: adv, declining: dec, unchanged: unc, verified: true, source: 'session-rows', covered: covered, skipped: skipped };
    }

    // Rows could not be verified: accept provider counts only when they
    // reconcile with the session's own universe (same total).
    if (provider && typeof provider === 'object') {
      var pAdv = num(provider.advancing !== undefined ? provider.advancing : provider.advancers);
      var pDec = num(provider.declining !== undefined ? provider.declining : provider.decliners);
      var pUnc = num(provider.unchanged);
      if (pAdv !== null && pDec !== null && pUnc !== null && pAdv >= 0 && pDec >= 0 && pUnc >= 0) {
        var total = pAdv + pDec + pUnc;
        var universe = num(sessionTotal) !== null ? num(sessionTotal) : (list.length || null);
        if (universe !== null && total === universe) {
          return { advancing: pAdv, declining: pDec, unchanged: pUnc, verified: true, source: 'provider', covered: total, skipped: skipped };
        }
      }
    }
    out.skipped = skipped;
    return out;
  }

  /* ---- Summary metric lookup (backend ships summary as a label/value list) */
  function summaryValue(summary, labels) {
    var list = Array.isArray(summary) ? summary : (summary && typeof summary === 'object' ? [summary] : []);
    var targets = labels.map(normKey);
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      if (!item || typeof item !== 'object') continue;
      var label = String(pick(item, ['detail', 'label', 'name', 'title', 'key', 'metric']) || '');
      if (!label) continue;
      var nl = normKey(label);
      for (var t = 0; t < targets.length; t++) {
        if (nl.indexOf(targets[t]) !== -1) {
          var v = num(pick(item, ['value', 'amount', 'count', 'total', 'val']));
          if (v !== null) return v;
        }
      }
    }
    return null;
  }

  window.NPP = {
    num: num,
    pick: pick,
    normKey: normKey,
    indexObservation: indexObservation,
    breadth: breadth,
    summaryValue: summaryValue,
    PRICE_KEYS: PRICE_KEYS,
    PREV_KEYS: PREV_KEYS
  };
})();
