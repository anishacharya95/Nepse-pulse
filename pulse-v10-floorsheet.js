/* NEPSE Pulse v10 - complete daily floorsheet with server-side pagination,
   filters, sorting and stock/broker analysis from real NEPSE transactions. */
(function () {
  'use strict';
  if (window.NPV10Floorsheet) return;
  var $ = function (id) { return document.getElementById(id); };
  var V = null;
  var num = function (v) { if (v === null || v === undefined) return null; var raw = String(v).trim(); if (!raw) return null; var n = Number(raw.replace(/,/g, '')); return Number.isFinite(n) ? n : null; };
  var esc = function (s) { return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); };
  var fmt = function (v, d) { var n = num(v); return n === null ? '--' : n.toLocaleString(undefined, { maximumFractionDigits: d === undefined ? 2 : d }); };
  var money = function (v) { var n = num(v); if (n === null) return '--'; if (Math.abs(n) >= 1e7) return (n / 1e7).toFixed(2) + ' Cr'; return n.toLocaleString(undefined, { maximumFractionDigits: 0 }); };
  var pageSizeOptions = [25, 50, 100];

  var st = {
    date: '', page: 1, size: 25, total: 0, rows: [], loading: false,
    latestDate: '', filters: {}, sort: '', scanRows: null, scanDate: '', scanInfo: null,
    seq: 0, error: ''
  };

  function build() {
    if ($('npv10-floorsheet')) return;
    V = window.NPV10;
    var s = document.createElement('section');
    s.id = 'npv10-floorsheet';
    s.className = 'pulse-home-feature-module npv10-tool';
    s.innerHTML =
      '<div class="npv10-tool-head"><div><div class="npv10-kicker">DAILY TRANSACTIONS</div><h2>NEPSE Floorsheet</h2>' +
      '<p>Every executed contract stored for the selected trading date, with server-side paging, filters, sorting and stock/broker analysis. No mock transactions.</p></div>' +
      '<div class="npv10-pills"><input id="npv10FsDate" class="npv10-input" type="date" style="width:150px;height:38px"><button class="npv10-btn sm alt" id="npv10FsLatest">Latest</button><button class="npv10-btn sm" id="npv10FsRefresh">Refresh</button></div></div>' +

      '<div class="npv10-card" style="margin-bottom:12px">' +
        '<div class="npv10-grid" style="grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px">' +
          '<div><label class="npv10-label">Stock symbol</label><input id="npv10FsSymbol" class="npv10-input" placeholder="e.g. NABIL"></div>' +
          '<div><label class="npv10-label">Contract ID</label><input id="npv10FsContract" class="npv10-input" inputmode="numeric" placeholder="contract"></div>' +
          '<div><label class="npv10-label">Buyer broker</label><input id="npv10FsBuyer" class="npv10-input" placeholder="broker no. or name"></div>' +
          '<div><label class="npv10-label">Seller broker</label><input id="npv10FsSeller" class="npv10-input" placeholder="broker no. or name"></div>' +
          '<div><label class="npv10-label">Min rate</label><input id="npv10FsRate" class="npv10-input" inputmode="decimal" placeholder="price"></div>' +
          '<div><label class="npv10-label">Min quantity</label><input id="npv10FsQty" class="npv10-input" inputmode="numeric" placeholder="qty"></div>' +
          '<div><label class="npv10-label">Min amount</label><input id="npv10FsAmount" class="npv10-input" inputmode="decimal" placeholder="amount"></div>' +
          '<div><label class="npv10-label">Sort by</label><select id="npv10FsSort" class="npv10-select">' +
            '<option value="">Default order</option><option value="amount-desc">Highest amount</option>' +
            '<option value="quantity-desc">Highest quantity</option><option value="rate-desc">Highest price</option>' +
            '<option value="rate-asc">Lowest price</option></select></div>' +
        '</div>' +
        '<div class="npv10-pills" style="margin-top:10px"><button class="npv10-btn" id="npv10FsApply">Apply filters</button>' +
          '<button class="npv10-btn alt" id="npv10FsClear">Clear</button>' +
          '<button class="npv10-btn dark" id="npv10FsAnalyse">Floorsheet analysis</button>' +
          '<span class="npv10-note" id="npv10FsMeta" style="flex:1;min-width:180px">Loading floorsheet...</span></div>' +
        '<div class="npv10-note" id="npv10FsScanNote" style="margin-top:8px;display:none"></div>' +
      '</div>' +

      '<div class="npv10-card" style="padding:0;overflow:hidden">' +
        '<div style="display:flex;justify-content:space-between;gap:10px;align-items:center;padding:11px 13px;border-bottom:1px solid #e6edf6;flex-wrap:wrap">' +
          '<div style="font-size:11px;color:#51637d;font-weight:800" id="npv10FsCount">-- records</div>' +
          '<div class="npv10-pills"><span style="font-size:10px;color:#64748b">Rows per page</span><select id="npv10FsSize" class="npv10-select" style="height:34px;width:86px">' +
            pageSizeOptions.map(function (n) { return '<option value="' + n + '"' + (n === 25 ? ' selected' : '') + '>' + n + '</option>'; }).join('') +
          '</select></div>' +
        '</div>' +
        '<div class="npv10-tbl-wrap" style="border:0;border-radius:0;max-height:62vh">' +
          '<table class="npv10-tbl"><thead><tr><th>Contract</th><th>Symbol</th><th>Buyer</th><th>Seller</th><th style="text-align:right">Qty</th><th style="text-align:right">Rate</th><th style="text-align:right">Amount</th><th>Time</th></tr></thead>' +
          '<tbody id="npv10FsRows"></tbody></table>' +
        '</div>' +
        '<div id="npv10FsPager" style="padding:11px 13px;display:flex;gap:7px;align-items:center;flex-wrap:wrap"></div>' +
      '</div>' +

      '<div id="npv10FsAnalysis" class="npv10-card" style="margin-top:12px;display:none"></div>';

    (document.querySelector('main') || document.body).appendChild(s);
    bind();
    load(true);
  }

  function busy(on) {
    st.loading = on;
    ['npv10FsApply', 'npv10FsClear', 'npv10FsAnalyse', 'npv10FsRefresh', 'npv10FsLatest'].forEach(function (id) {
      var b = $(id); if (b) b.disabled = on;
    });
  }

  function setMeta(text) { var el = $('npv10FsMeta'); if (el) el.textContent = text; }

  function readFilters() {
    return {
      symbol: String($('npv10FsSymbol').value || '').trim().toUpperCase(),
      contract: String($('npv10FsContract').value || '').trim(),
      buyer: String($('npv10FsBuyer').value || '').trim().toLowerCase(),
      seller: String($('npv10FsSeller').value || '').trim().toLowerCase(),
      minRate: num($('npv10FsRate').value),
      minQty: num($('npv10FsQty').value),
      minAmount: num($('npv10FsAmount').value)
    };
  }

  function hasAdvancedFilters(f) {
    return !!(f.contract || f.buyer || f.seller || f.minRate !== null || f.minQty !== null || f.minAmount !== null);
  }

  function compact(r) {
    var raw = r.raw || {};
    return {
      contract: String(r.trade || raw.contractId || raw.tradeBookId || ''),
      symbol: String(r.symbol || raw.stockSymbol || '').toUpperCase(),
      buyerId: String(r.buyerBrokerId || raw.buyerMemberId || ''),
      buyer: String(r.buyerBrokerName || r.buyerBroker || raw.buyerBrokerName || ''),
      sellerId: String(r.sellerBrokerId || raw.sellerMemberId || ''),
      seller: String(r.sellerBrokerName || r.sellerBroker || raw.sellerBrokerName || ''),
      qty: num(r.quantity || raw.contractQuantity),
      rate: num(r.rate || raw.contractRate),
      amount: num(r.amount || raw.contractAmount),
      date: String(r.businessDate || raw.businessDate || st.date),
      time: String(r.tradeTime || raw.tradeTime || '')
    };
  }

  async function fetchPage(offset, limit, symbol, date) {
    var qs = '?limit=' + limit + '&offset=' + offset;
    if (date) qs += '&business_date=' + encodeURIComponent(date);
    if (symbol) qs += '&symbol=' + encodeURIComponent(symbol);
    var d = await V.fetchJSON('/api/floorsheet-cache' + qs);
    var rows = Array.isArray(d.data) ? d.data : (Array.isArray(d.floorsheet) ? d.floorsheet : []);
    return {
      rows: rows.map(compact),
      total: num(d.total) || 0,
      businessDate: d.businessDate || date || '',
      hasMore: !!d.hasMore
    };
  }

  function filterRow(r, f) {
    if (f.symbol && r.symbol !== f.symbol) return false;
    if (f.contract && String(r.contract) !== f.contract) return false;
    if (f.buyer && !(String(r.buyerId) === f.buyer || r.buyer.toLowerCase().indexOf(f.buyer) !== -1)) return false;
    if (f.seller && !(String(r.sellerId) === f.seller || r.seller.toLowerCase().indexOf(f.seller) !== -1)) return false;
    if (f.minRate !== null && !(r.rate >= f.minRate)) return false;
    if (f.minQty !== null && !(r.qty >= f.minQty)) return false;
    if (f.minAmount !== null && !(r.amount >= f.minAmount)) return false;
    return true;
  }

  async function scanDay(date, onProgress) {
    if (st.scanRows && st.scanDate === date) return st.scanRows;
    var collected = [], offset = 0, total = 0, guard = 0;
    do {
      var page = await fetchPage(offset, 5000, '', date);
      total = page.total || total;
      collected = collected.concat(page.rows);
      offset += 5000;
      guard += 1;
      if (onProgress) onProgress(offset, total);
      if (!page.hasMore || !page.rows.length) break;
    } while (offset < total && guard < 60);
    /* Offset paging can repeat a contract while the backend ingests a new session; keep each contract once. */
    var seen = {};
    collected = collected.filter(function (r) {
      if (!r.contract) return true;
      if (seen[r.contract]) return false;
      seen[r.contract] = 1;
      return true;
    });
    st.scanRows = collected;
    st.scanDate = date;
    st.scanInfo = { total: total, fetched: collected.length };
    return collected;
  }

  async function load(resetPage) {
    if (resetPage) st.page = 1;
    if (!st._matchRows) st._matchRows = null;
    var f = readFilters();
    st.filters = f;
    st.sort = $('npv10FsSort').value || '';
    var date = $('npv10FsDate').value || st.date || '';
    st.date = date;
    var seq = ++st.seq;
    busy(true);
    st.error = '';
    if (!(hasAdvancedFilters(f) || st.sort)) st._matchRows = null;
    renderRows([], true);
    try {
      if (hasAdvancedFilters(f) || st.sort) {
        setMeta('Scanning the selected day for matching contracts...');
        var scanNote = $('npv10FsScanNote');
        if (scanNote) { scanNote.style.display = 'block'; scanNote.textContent = 'Scanning server pages...'; }
        var all = await scanDay(date, function (done, total) {
          if (seq !== st.seq) return;
          setMeta('Scanned ' + done.toLocaleString() + ' of ' + (total || '?').toLocaleString() + ' records...');
          if (scanNote) scanNote.textContent = 'Filtering ' + Math.min(done, total).toLocaleString() + ' of ' + total.toLocaleString() + ' daily contracts. Matching rows are kept in memory only.';
        });
        if (seq !== st.seq) return;
        var matches = all.filter(function (r) { return filterRow(r, f); });
        if (st.sort) {
          var sp = st.sort.split('-');
          var key = sp[0] === 'quantity' ? 'qty' : sp[0];
          var dir = sp[1] === 'asc' ? 1 : -1;
          matches.sort(function (a, b) { return ((a[key] || 0) - (b[key] || 0)) * dir; });
        }
        st.total = matches.length;
        st._matchRows = matches;
        st.latestDate = st.latestDate || date;
        if (scanNote) scanNote.textContent = matches.length.toLocaleString() + ' matching contracts in ' + all.length.toLocaleString() + ' scanned records for ' + (date || st.latestDate || 'the latest session') + '. Server-side date and symbol filters plus a bounded server scan are used - no mock rows.';
        setMeta((hasAdvancedFilters(f) ? 'Filtered' : 'Sorted') + ' floorsheet - ' + matches.length.toLocaleString() + ' records');
        renderPageFromMatches();
      } else {
        var page = await fetchPage((st.page - 1) * st.size, st.size, f.symbol, date);
        if (seq !== st.seq) return;
        st.rows = page.rows;
        st.total = page.total;
        if (page.businessDate && !st.date) {
          st.date = page.businessDate;
          $('npv10FsDate').value = page.businessDate;
        }
        if (!st.latestDate) { st.latestDate = page.businessDate; $('npv10FsDate').value = page.businessDate; }
        var sn = $('npv10FsScanNote');
        if (sn) sn.style.display = 'none';
        setMeta('Showing server page ' + st.page + ' - ' + st.total.toLocaleString() + ' records for ' + (page.businessDate || date || 'latest session'));
        renderRows(st.rows, false);
        renderPager();
        renderCount();
      }
    } catch (e) {
      if (seq !== st.seq) return;
      st.error = e.message;
      st.rows = [];
      renderRows([], false);
      setMeta('Floorsheet unavailable: ' + e.message);
      $('npv10FsCount').textContent = '0 records';
      $('npv10FsRows').innerHTML = '<tr><td colspan="8" style="padding:18px;color:#b91c1c">Could not load the floorsheet: ' + esc(e.message) + '. Check the backend connection and try Refresh.</td></tr>';
    } finally {
      if (seq === st.seq) busy(false);
    }
  }

  function renderRows(rows, loadingState) {
    var tbody = $('npv10FsRows');
    if (!tbody) return;
    if (loadingState) {
      tbody.innerHTML = Array.from({ length: 6 }).map(function () {
        return '<tr><td colspan="8" style="padding:10px"><div style="height:12px;border-radius:6px;background:linear-gradient(90deg,#eef2f8,#f8fafc,#eef2f8);background-size:200% 100%;animation:npv10sh 1.1s linear infinite"></div></td></tr>';
      }).join('');
      if (!$('npv10sh-style')) {
        var stl = document.createElement('style'); stl.id = 'npv10sh-style';
        stl.textContent = '@keyframes npv10sh{0%{background-position:0 0}100%{background-position:-200% 0}}';
        document.head.appendChild(stl);
      }
      return;
    }
    if (!rows.length) {
      tbody.innerHTML = '<tr><td colspan="8" style="padding:22px;text-align:center;color:#64748b">No transactions match these filters for the selected date.</td></tr>';
      return;
    }
    tbody.innerHTML = rows.map(function (r) {
      var t = r.time ? String(r.time).replace('T', ' ').slice(11, 19) : '';
      return '<tr>' +
        '<td style="font-family:ui-monospace,monospace;font-size:10.5px">' + esc(r.contract) + '</td>' +
        '<td><b data-fs-symbol="' + esc(r.symbol) + '" style="color:#1d4ed8;cursor:pointer">' + esc(r.symbol) + '</b></td>' +
        '<td data-fs-broker="' + esc(r.buyerId) + '|buyer" style="cursor:pointer">' + esc(r.buyerId ? r.buyerId + ' - ' : '') + esc(r.buyer) + '</td>' +
        '<td data-fs-broker="' + esc(r.sellerId) + '|seller" style="cursor:pointer">' + esc(r.sellerId ? r.sellerId + ' - ' : '') + esc(r.seller) + '</td>' +
        '<td style="text-align:right">' + fmt(r.qty, 0) + '</td>' +
        '<td style="text-align:right">' + fmt(r.rate) + '</td>' +
        '<td style="text-align:right">' + fmt(r.amount) + '</td>' +
        '<td style="font-size:10.5px;color:#64748b">' + esc(t) + '</td></tr>';
    }).join('');
  }

  function pageCount() {
    var total = st._matchRows ? st._matchRows.length : st.total;
    return Math.max(1, Math.ceil(total / st.size));
  }

  function renderCount() {
    var total = st._matchRows ? st._matchRows.length : st.total;
    $('npv10FsCount').textContent = total.toLocaleString() + ' records - page ' + st.page + ' of ' + pageCount() +
      ' - ' + (st._matchRows ? 'filtered' : 'server-side') + (st.date ? ' - ' + st.date : '');
  }

  function renderPageFromMatches() {
    var all = st._matchRows || [];
    var start = (st.page - 1) * st.size;
    st.rows = all.slice(start, start + st.size);
    renderRows(st.rows, false);
    renderPager();
    renderCount();
  }

  function renderPager() {
    var box = $('npv10FsPager');
    if (!box) return;
    var pages = pageCount();
    var html = '<button class="npv10-btn sm alt" id="npv10FsPrev"' + (st.page <= 1 ? ' disabled' : '') + '>&#9664; Previous</button>';
    var windowStart = Math.max(1, Math.min(st.page - 2, pages - 4));
    var windowEnd = Math.min(pages, windowStart + 4);
    if (windowStart > 1) html += '<button class="npv10-btn sm alt" data-fs-page="1">1</button>' + (windowStart > 2 ? '<span style="color:#94a3b8">...</span>' : '');
    for (var p = windowStart; p <= windowEnd; p++) {
      html += '<button class="npv10-btn sm ' + (p === st.page ? '' : 'alt') + '" data-fs-page="' + p + '">' + p + '</button>';
    }
    if (windowEnd < pages) html += (windowEnd < pages - 1 ? '<span style="color:#94a3b8">...</span>' : '') + '<button class="npv10-btn sm alt" data-fs-page="' + pages + '">' + pages + '</button>';
    html += '<button class="npv10-btn sm alt" id="npv10FsNext"' + (st.page >= pages ? ' disabled' : '') + '>Next &#9654;</button>';
    html += '<span style="font-size:10px;color:#64748b;margin-left:auto">Page ' + st.page + ' / ' + pages + ' &middot; ' + st.size + ' per page</span>';
    box.innerHTML = html;
  }

  function goTo(page) {
    var pages = pageCount();
    st.page = Math.max(1, Math.min(pages, page));
    if (st._matchRows) renderPageFromMatches();
    else loadLocalPage();
  }

  async function loadLocalPage() {
    busy(true);
    try {
      var page = await fetchPage((st.page - 1) * st.size, st.size, st.filters.symbol || '', st.date);
      st.rows = page.rows;
      st.total = page.total;
      renderRows(st.rows, false); renderPager(); renderCount();
      setMeta('Showing server page ' + st.page + ' - ' + st.total.toLocaleString() + ' records');
    } catch (e) {
      setMeta('Floorsheet page failed: ' + e.message);
    } finally { busy(false); }
  }

  /* ------------------------------------------------------------- analysis */
  async function analyse() {
    var box = $('npv10FsAnalysis');
    box.style.display = 'block';
    box.innerHTML = '<div class="npv10-note">Aggregating the selected day from verified floorsheet transactions... this can take a few seconds.</div>';
    busy(true);
    try {
      var all = await scanDay(st.date || st.latestDate, function (done, total) {
        box.innerHTML = '<div class="npv10-note">Aggregating... ' + Math.min(done, total).toLocaleString() + ' of ' + total.toLocaleString() + ' contracts.</div>';
      });
      var bySymbol = {}, byBroker = {};
      function ensure(map, key) {
        if (!map[key]) map[key] = { qty: 0, amount: 0, count: 0, buyQty: 0, buyAmount: 0, buyCount: 0, sellQty: 0, sellAmount: 0, sellCount: 0, buyers: {}, sellers: {}, symbols: {} };
        return map[key];
      }
      all.forEach(function (r) {
        var s = ensure(bySymbol, r.symbol);
        s.qty += r.qty || 0; s.amount += r.amount || 0; s.count += 1;
        if (r.buyerId) {
          s.buyers[r.buyerId] = s.buyers[r.buyerId] || { id: r.buyerId, name: r.buyer, qty: 0, amount: 0, count: 0 };
          s.buyers[r.buyerId].qty += r.qty || 0; s.buyers[r.buyerId].amount += r.amount || 0; s.buyers[r.buyerId].count += 1;
        }
        if (r.sellerId) {
          s.sellers[r.sellerId] = s.sellers[r.sellerId] || { id: r.sellerId, name: r.seller, qty: 0, amount: 0, count: 0 };
          s.sellers[r.sellerId].qty += r.qty || 0; s.sellers[r.sellerId].amount += r.amount || 0; s.sellers[r.sellerId].count += 1;
        }
        if (r.buyerId) {
          var b = ensure(byBroker, r.buyerId);
          b.buyQty += r.qty || 0; b.buyAmount += r.amount || 0; b.buyCount += 1;
          b.symbols[r.symbol] = (b.symbols[r.symbol] || 0) + (r.amount || 0);
          b.name = r.buyer;
        }
        if (r.sellerId) {
          var sl = ensure(byBroker, r.sellerId);
          sl.sellQty += r.qty || 0; sl.sellAmount += r.amount || 0; sl.sellCount += 1;
          sl.symbols[r.symbol] = (sl.symbols[r.symbol] || 0) + (r.amount || 0);
          sl.name = sl.name || r.seller;
        }
      });
      var symbolList = Object.keys(bySymbol).map(function (k) { return Object.assign({ symbol: k }, bySymbol[k]); })
        .sort(function (a, b) { return b.amount - a.amount; });
      var brokerList = Object.keys(byBroker).map(function (k) { return Object.assign({ id: k }, byBroker[k]); })
        .sort(function (a, b) { return (b.buyAmount + b.sellAmount) - (a.buyAmount + a.sellAmount); });
      var totals = all.reduce(function (a, r) { a.qty += r.qty || 0; a.amount += r.amount || 0; a.count += 1; return a; }, { qty: 0, amount: 0, count: 0 });
      var topSym = symbolList.slice(0, 8);
      box.innerHTML =
        '<div class="npv10-tool-head" style="margin-bottom:8px"><div><div class="npv10-kicker">FLOORSHEET INTELLIGENCE</div><h2 style="font-size:18px">Stock &amp; Broker Analysis - ' + esc(st.date || st.latestDate) + '</h2></div></div>' +
        '<div class="npv10-result" style="margin-bottom:12px">' +
          '<div class="npv10-stat"><small>Contracts</small><b>' + totals.count.toLocaleString() + '</b></div>' +
          '<div class="npv10-stat"><small>Total quantity</small><b>' + fmt(totals.qty, 0) + '</b></div>' +
          '<div class="npv10-stat"><small>Total amount</small><b>Rs ' + money(totals.amount) + '</b></div>' +
          '<div class="npv10-stat"><small>Traded symbols</small><b>' + symbolList.length.toLocaleString() + '</b></div>' +
          '<div class="npv10-stat"><small>Active brokers</small><b>' + brokerList.length.toLocaleString() + '</b></div>' +
          '<div class="npv10-stat"><small>Average contract</small><b>Rs ' + money(totals.count ? totals.amount / totals.count : null) + '</b></div>' +
        '</div>' +
        '<div class="npv10-grid">' +
          '<div><label class="npv10-label">Top stocks by amount</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Symbol</th><th>Qty</th><th>Amount</th><th>Tx</th><th>Avg price</th></tr></thead><tbody>' +
            topSym.map(function (s) {
              return '<tr data-fs-symbol="' + esc(s.symbol) + '"><td><b>' + esc(s.symbol) + '</b></td><td>' + fmt(s.qty, 0) + '</td><td>' + money(s.amount) + '</td><td>' + s.count.toLocaleString() + '</td><td>' + fmt(s.qty ? s.amount / s.qty : null) + '</td></tr>';
            }).join('') + '</tbody></table></div></div>' +
          '<div><label class="npv10-label">Top brokers by activity</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Broker</th><th>Buy amount</th><th>Sell amount</th><th>Tx</th></tr></thead><tbody>' +
            brokerList.slice(0, 8).map(function (b) {
              return '<tr data-fs-broker="' + esc(b.id) + '|any"><td><b>' + esc(b.id) + '</b> ' + esc(String(b.name || '').slice(0, 26)) + '</td><td>' + money(b.buyAmount) + '</td><td>' + money(b.sellAmount) + '</td><td>' + (b.buyCount + b.sellCount).toLocaleString() + '</td></tr>';
            }).join('') + '</tbody></table></div></div>' +
        '</div>' +
        '<div class="npv10-note" style="margin-top:10px">All figures aggregate the ' + all.length.toLocaleString() + ' verified contracts stored for ' + esc(st.date || st.latestDate) + '. Select any stock symbol or broker in the table above for a detailed breakdown.</div>';

      // detail panel
      var detail = document.createElement('div');
      detail.id = 'npv10FsDetail';
      detail.style.marginTop = '12px';
      box.appendChild(detail);
      box._bySymbol = bySymbol;
      box._byBroker = byBroker;

      if (!box._wired) {
        box._wired = true;
        box.addEventListener('click', function (e) {
          var symEl = e.target.closest('[data-fs-symbol]');
          if (symEl) { showSymbolDetail(symEl.getAttribute('data-fs-symbol')); return; }
          var brEl = e.target.closest('[data-fs-broker]');
          if (brEl) { showBrokerDetail(brEl.getAttribute('data-fs-broker').split('|')[0]); }
        });
      }
    } catch (e) {
      box.innerHTML = '<div class="npv10-note" style="background:#fff1f2;border-color:#fecdd3;color:#9f1239">Analysis failed: ' + esc(e.message) + '</div>';
    } finally { busy(false); }
  }

  function showSymbolDetail(symbol) {
    var box = $('npv10FsAnalysis');
    if (!box || !box._bySymbol) return;
    var s = box._bySymbol[symbol];
    var detail = $('npv10FsDetail');
    if (!s || !detail) return;
    var buyers = Object.keys(s.buyers).map(function (k) { return s.buyers[k]; }).sort(function (a, b) { return b.qty - a.qty; }).slice(0, 5);
    var sellers = Object.keys(s.sellers).map(function (k) { return s.sellers[k]; }).sort(function (a, b) { return b.qty - a.qty; }).slice(0, 5);
    detail.innerHTML =
      '<div class="npv10-card" style="background:#f8fbff"><div class="npv10-tool-head" style="margin-bottom:8px"><div><div class="npv10-kicker">STOCK ANALYSIS</div><h2 style="font-size:17px">' + esc(symbol) + '</h2></div><button class="npv10-btn sm alt" onclick="document.getElementById(\'npv10FsDetail\').innerHTML=\'\'">Close</button></div>' +
      '<div class="npv10-result">' +
        '<div class="npv10-stat"><small>Total traded quantity</small><b>' + fmt(s.qty, 0) + '</b></div>' +
        '<div class="npv10-stat"><small>Total amount</small><b>Rs ' + money(s.amount) + '</b></div>' +
        '<div class="npv10-stat"><small>Transactions</small><b>' + s.count.toLocaleString() + '</b></div>' +
        '<div class="npv10-stat"><small>Average price</small><b>' + fmt(s.qty ? s.amount / s.qty : null) + '</b></div>' +
      '</div>' +
      '<div class="npv10-grid" style="margin-top:10px">' +
        '<div><label class="npv10-label">Top buyers</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Broker</th><th>Qty</th><th>Amount</th></tr></thead><tbody>' +
          buyers.map(function (b) { return '<tr data-fs-broker="' + esc(b.id) + '|buyer"><td><b>' + esc(b.id) + '</b> ' + esc(String(b.name || '').slice(0, 24)) + '</td><td>' + fmt(b.qty, 0) + '</td><td>' + money(b.amount) + '</td></tr>'; }).join('') +
        '</tbody></table></div></div>' +
        '<div><label class="npv10-label">Top sellers</label><div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Broker</th><th>Qty</th><th>Amount</th></tr></thead><tbody>' +
          sellers.map(function (b) { return '<tr data-fs-broker="' + esc(b.id) + '|seller"><td><b>' + esc(b.id) + '</b> ' + esc(String(b.name || '').slice(0, 24)) + '</td><td>' + fmt(b.qty, 0) + '</td><td>' + money(b.amount) + '</td></tr>'; }).join('') +
        '</tbody></table></div></div>' +
      '</div></div>';
    detail.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function showBrokerDetail(id) {
    var box = $('npv10FsAnalysis');
    if (!box || !box._byBroker) return;
    var b = box._byBroker[id];
    var detail = $('npv10FsDetail');
    if (!b || !detail) return;
    var symbols = Object.keys(b.symbols).map(function (k) { return { symbol: k, amount: b.symbols[k] }; }).sort(function (a, c) { return c.amount - a.amount; }).slice(0, 8);
    var net = b.buyAmount - b.sellAmount;
    detail.innerHTML =
      '<div class="npv10-card" style="background:#f8fbff"><div class="npv10-tool-head" style="margin-bottom:8px"><div><div class="npv10-kicker">BROKER ANALYSIS</div><h2 style="font-size:17px">Broker ' + esc(id) + ' - ' + esc(b.name || '') + '</h2></div><button class="npv10-btn sm alt" onclick="document.getElementById(\'npv10FsDetail\').innerHTML=\'\'">Close</button></div>' +
      '<div class="npv10-result">' +
        '<div class="npv10-stat"><small>Buy quantity</small><b>' + fmt(b.buyQty, 0) + '</b></div>' +
        '<div class="npv10-stat"><small>Buy amount</small><b>Rs ' + money(b.buyAmount) + '</b></div>' +
        '<div class="npv10-stat"><small>Sell quantity</small><b>' + fmt(b.sellQty, 0) + '</b></div>' +
        '<div class="npv10-stat"><small>Sell amount</small><b>Rs ' + money(b.sellAmount) + '</b></div>' +
        '<div class="npv10-stat"><small>Net flow</small><b class="' + (net >= 0 ? 'npv10-up' : 'npv10-down') + '">' + (net >= 0 ? '+' : '') + money(net) + '</b></div>' +
        '<div class="npv10-stat"><small>Transactions</small><b>' + (b.buyCount + b.sellCount).toLocaleString() + '</b></div>' +
      '</div>' +
      '<label class="npv10-label" style="margin-top:10px">Broker-wise activity by stock</label>' +
      '<div class="npv10-tbl-wrap"><table class="npv10-tbl"><thead><tr><th>Symbol</th><th>Turnover</th></tr></thead><tbody>' +
        symbols.map(function (s) { return '<tr data-fs-symbol="' + esc(s.symbol) + '"><td><b>' + esc(s.symbol) + '</b></td><td>' + money(s.amount) + '</td></tr>'; }).join('') +
      '</tbody></table></div></div>';
    detail.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function bind() {
    $('npv10FsApply').addEventListener('click', function () { load(true); });
    $('npv10FsClear').addEventListener('click', function () {
      ['npv10FsSymbol', 'npv10FsContract', 'npv10FsBuyer', 'npv10FsSeller', 'npv10FsRate', 'npv10FsQty', 'npv10FsAmount'].forEach(function (id) { $(id).value = ''; });
      $('npv10FsSort').value = '';
      st._matchRows = null;
      load(true);
    });
    $('npv10FsRefresh').addEventListener('click', function () { load(false); });
    $('npv10FsLatest').addEventListener('click', function () {
      $('npv10FsDate').value = st.latestDate || '';
      $('npv10FsSort').value = '';
      ['npv10FsSymbol', 'npv10FsContract', 'npv10FsBuyer', 'npv10FsSeller'].forEach(function (id) { $(id).value = ''; });
      st._matchRows = null;
      load(true);
    });
    $('npv10FsDate').addEventListener('change', function () {
      st.date = $('npv10FsDate').value;
      st._matchRows = null;
      load(true);
    });
    $('npv10FsSize').addEventListener('change', function () {
      st.size = Number($('npv10FsSize').value) || 25;
      st.page = 1;
      if (st._matchRows) renderPageFromMatches(); else load(false);
    });
    $('npv10FsAnalyse').addEventListener('click', analyse);
    $('npv10FsPager').addEventListener('click', function (e) {
      if (e.target.id === 'npv10FsPrev') { goTo(st.page - 1); return; }
      if (e.target.id === 'npv10FsNext') { goTo(st.page + 1); return; }
      var pageBtn = e.target.closest('[data-fs-page]');
      if (pageBtn) goTo(Number(pageBtn.getAttribute('data-fs-page')));
    });
    $('npv10FsRows').addEventListener('click', function (e) {
      var sym = e.target.closest('[data-fs-symbol]');
      if (sym) {
        if (!st.scanRows) analyse().then(function () { showSymbolDetail(sym.getAttribute('data-fs-symbol')); });
        else { if ($('npv10FsAnalysis').style.display === 'none') analyse().then(function () { showSymbolDetail(sym.getAttribute('data-fs-symbol')); }); else showSymbolDetail(sym.getAttribute('data-fs-symbol')); }
        return;
      }
      var br = e.target.closest('[data-fs-broker]');
      if (br) {
        if ($('npv10FsAnalysis').style.display === 'none') analyse().then(function () { showBrokerDetail(br.getAttribute('data-fs-broker').split('|')[0]); });
        else showBrokerDetail(br.getAttribute('data-fs-broker').split('|')[0]);
      }
    });
    $('npv10FsSymbol').addEventListener('keydown', function (e) { if (e.key === 'Enter') load(true); });
    $('npv10FsContract').addEventListener('keydown', function (e) { if (e.key === 'Enter') load(true); });
  }

  function openWithSymbol(symbol) {
    if (typeof window.openFeatureWorkspace === 'function') window.openFeatureWorkspace('npv10-floorsheet', 'Daily Floorsheet');
    var el = $('npv10FsSymbol');
    if (symbol && el) { el.value = String(symbol).toUpperCase(); load(true); }
  }

  window.NPV10Floorsheet = {
    open: openWithSymbol,
    reload: function () { return load(true); },
    analyse: analyse,
    state: function () { return { date: st.date, page: st.page, size: st.size, total: st.total }; }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build, { once: true });
  else setTimeout(build, 0);
})();
