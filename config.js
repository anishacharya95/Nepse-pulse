window.NEPSE_PULSE_BACKEND_URL = 'https://nepse-pulse.onrender.com';

(function () {
  'use strict';

  function n(v) {
    var x = Number(v);
    return Number.isFinite(x) ? x : null;
  }

  function pick(o, keys) {
    for (var i = 0; i < keys.length; i++) {
      if (o && o[keys[i]] !== undefined && o[keys[i]] !== null && o[keys[i]] !== '') {
        return o[keys[i]];
      }
    }
    return null;
  }

  function rows(d) {
    return Array.isArray(d.live) ? d.live :
      Array.isArray(d.liveMarket) ? d.liveMarket :
      Array.isArray(d.securities) ? d.securities : [];
  }

  function pct(r) {
    return n(pick(r, [
      'pct','percentageChange','percentChange',
      'changePercent','changePct','pChange'
    ]));
  }

  function price(r) {
    return n(pick(r, [
      'ltp','lastPrice','LTP','price','closePrice'
    ]));
  }

  function change(r) {
    return n(pick(r, [
      'change','priceChange','pointChange'
    ]));
  }

  function symbol(r) {
    return pick(r, [
      'symbol','Symbol','ticker','stockSymbol','securitySymbol'
    ]) || '—';
  }

  function turnover(r) {
    return n(pick(r, [
      'turnover','totalTradedValue','value',
      'amount','totalValue','tradeValue'
    ])) || 0;
  }

  function volume(r) {
    return n(pick(r, [
      'volume','totalTradedQuantity','quantity','shares'
    ])) || 0;
  }

  function set(selector, value) {
    var el = document.querySelector(selector);
    if (el) el.textContent = value;
  }

  function money(v) {
    var x = n(v);
    if (x === null) return '—';
    if (Math.abs(x) >= 1000000000) return (x / 1000000000).toFixed(2) + ' Arab';
    if (Math.abs(x) >= 10000000) return (x / 10000000).toFixed(2) + ' Crore';
    return x.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  function summary(d, names) {
    var a = Array.isArray(d.summary) ? d.summary : [];

    for (var i = 0; i < a.length; i++) {
      var label = String(
        pick(a[i], ['detail','label','name','title','key']) || ''
      ).toLowerCase().replace(/[^a-z0-9]/g, '');

      for (var j = 0; j < names.length; j++) {
        if (label.indexOf(names[j]) >= 0) {
          return pick(a[i], ['value','amount','count','total']);
        }
      }
    }

    return null;
  }

  function indexData(d) {
    var list = Array.isArray(d.index) ? d.index : [];

    var r = list.find(function (x) {
      return String(
        pick(x, ['index','name','indexName','indexname']) || ''
      ).toLowerCase().replace(/[^a-z]/g, '').indexOf('nepse') >= 0;
    }) || list[0] || {};

    return {
      value: n(pick(r, [
        'close','currentValue','currentvalue',
        'value','indexValue','indexvalue','latest'
      ])),
      change: n(pick(r, [
        'change','pointChange','pointchange',
        'changeValue','changevalue'
      ])),
      pct: n(pick(r, [
        'perChange','perchange',
        'percentChange','percentchange',
        'percentageChange','percentagechange',
        'changePercent'
      ]))
    };
  }

  function render(d) {
    var rs = rows(d);
    var ix = indexData(d);

    /* Main market card */
    if (ix.value !== null) {
      set('[data-market="index"]',
        ix.value.toLocaleString(undefined, { maximumFractionDigits: 2 })
      );
      set('#nepseIndexValue',
        ix.value.toLocaleString(undefined, { maximumFractionDigits: 2 })
      );
    }

    if (ix.change !== null) {
      var c = (ix.change >= 0 ? '+' : '') + ix.change.toFixed(2);
      set('[data-market="points"]', c);
      set('#nepseIndexChange', c);
    }

    if (ix.pct !== null) {
      set('[data-market="percent"]',
        (ix.pct >= 0 ? '+' : '') + ix.pct.toFixed(2) + '%'
      );
    }

    var open =
      d.marketOpen === true ||
      String(d.marketOpen || '').toLowerCase() === 'open';

    set('[data-market="status"]', open ? 'OPEN' : 'CLOSED');

    document.querySelectorAll('[data-market="status"]').forEach(function (el) {
      el.style.background = open ? '#68bc72' : '#ff5b60';
      el.style.color = '#fff';
    });

    /* Turnover */
    var totalTurnover =
      summary(d, ['totalturnover','turnover']);

    if (totalTurnover === null) {
      totalTurnover = rs.reduce(function (a, r) {
        return a + turnover(r);
      }, 0);
    }

    set('[data-market="turnover"]',
      'Turnover: ' + money(totalTurnover)
    );

    set('[data-summary="turnover"]', money(totalTurnover));

    /* Volume */
    var totalVolume =
      summary(d, ['totaltradedshares','tradedshares','totalvolume']);

    if (totalVolume === null) {
      totalVolume = rs.reduce(function (a, r) {
        return a + volume(r);
      }, 0);
    }

    set('[data-summary="volume"]', money(totalVolume));

    /* Transactions */
    var transactions =
      summary(d, ['totaltransactions','transactions']);

    set('[data-summary="transactions"]',
      transactions === null ? '—' : money(transactions)
    );

    /* Scrips */
    var scrips =
      summary(d, ['totalscripstraded','scripstraded','tradedscrips']);

    if (scrips === null) scrips = rs.length;

    set('[data-summary="scrips"]', money(scrips));

    /* Market cap */
    set('[data-summary="marketcap"]',
      money(summary(d, [
        'totalmarketcapitalization',
        'marketcapitalization'
      ]))
    );

    /* Float market cap */
    set('[data-summary="floatcap"]',
      money(summary(d, [
        'totalfloatmarketcapitalization',
        'floatmarketcapitalization'
      ]))
    );

    /* Breadth */
    var advancing = rs.filter(function (r) {
      return (pct(r) || 0) > 0;
    }).length;

    var declining = rs.filter(function (r) {
      return (pct(r) || 0) < 0;
    }).length;

    var unchanged = rs.length - advancing - declining;

    set('[data-breadth="advance"] b', advancing);
    set('[data-breadth="decline"] b', declining);
    set('[data-breadth="unchanged"] b', unchanged);

    set('[data-breadth="positive"] b',
      rs.filter(function (r) {
        return (pct(r) || 0) >= 9.9;
      }).length
    );

    set('[data-breadth="negative"] b',
      rs.filter(function (r) {
        return (pct(r) || 0) <= -9.9;
      }).length
    );

    /* Save data for tabs */
    window.NEPSE_PULSE_LIVE_DATA = d;

    renderMovers(d);
    renderActivity(d);
  }

  function renderMovers(d) {
    var rs = rows(d);

    var gainers =
      Array.isArray(d.gainers) && d.gainers.length
      ? d.gainers
      : rs.slice().sort(function (a,b) {
          return (pct(b) || -999) - (pct(a) || -999);
        });

    var losers =
      Array.isArray(d.losers) && d.losers.length
      ? d.losers
      : rs.slice().sort(function (a,b) {
          return (pct(a) || 999) - (pct(b) || 999);
        });

    var mode = window.NEPSE_PULSE_MOVER_MODE || 'gainers';
    var list = (mode === 'losers' ? losers : gainers).slice(0, 10);

    var box = document.getElementById('pulseMoversRows');
    if (!box) return;

    box.innerHTML = list.map(function (r) {
      var p = pct(r);
      var c = change(r);
      var l = price(r);

      return '<div class="pulse-table-row">' +
        '<span>' + symbol(r) + '</span>' +
        '<span>' + (c === null ? '—' : (c >= 0 ? '+' : '') + c.toFixed(2)) + '</span>' +
        '<span>' + (p === null ? '—' : (p >= 0 ? '+' : '') + p.toFixed(2) + '%') + '</span>' +
        '<span>' + (l === null ? '—' : l.toFixed(2)) + '</span>' +
      '</div>';
    }).join('');
  }

  function renderActivity(d) {
    var rs = rows(d);
    var mode = window.NEPSE_PULSE_LIQ_MODE || 'turnover';

    var list = Array.isArray(d[mode]) && d[mode].length
      ? d[mode]
      : rs.slice().sort(function (a,b) {
          return (turnover(b) - turnover(a));
        });

    var box = document.getElementById('pulseLiquidityRows');
    if (!box) return;

    box.innerHTML = list.slice(0, 10).map(function (r) {
      var v = mode === 'volume'
        ? volume(r)
        : mode === 'transactions'
          ? pick(r, ['transactions','totalTransactions','transactionCount'])
          : turnover(r);

      return '<div class="pulse-table-row">' +
        '<span>' + symbol(r) + '</span>' +
        '<span>' + money(v) + '</span>' +
        '<span>' + (price(r) === null ? '—' : price(r).toFixed(2)) + '</span>' +
      '</div>';
    }).join('');
  }

  async function load() {
    try {
      var response = await fetch(
        window.NEPSE_PULSE_BACKEND_URL + '/api/market?force=true',
        { cache: 'no-store' }
      );

      var data = await response.json();

      if (data && data.ok) {
        render(data);
      }
    } catch (e) {
      console.log('NEPSE Pulse feed bridge:', e);
    }
  }

  document.addEventListener('click', function (e) {
    var mover = e.target.closest('[data-mover-tab]');
    if (mover) {
      window.NEPSE_PULSE_MOVER_MODE =
        mover.getAttribute('data-mover-tab') || 'gainers';

      if (window.NEPSE_PULSE_LIVE_DATA) {
        renderMovers(window.NEPSE_PULSE_LIVE_DATA);
      }
    }

    var liquidity = e.target.closest('[data-liq-tab]');
    if (liquidity) {
      window.NEPSE_PULSE_LIQ_MODE =
        liquidity.getAttribute('data-liq-tab') || 'turnover';

      if (window.NEPSE_PULSE_LIVE_DATA) {
        renderActivity(window.NEPSE_PULSE_LIVE_DATA);
      }
    }
  });

  window.addEventListener('load', function () {
    load();
    setInterval(load, 30000);
  });

})();
