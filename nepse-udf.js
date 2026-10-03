/**
 * NEPSE Pulse UDF adapter.
 *
 * Advanced Charts expects a UDF-compatible datafeed. The NEPSE Pulse
 * backend already exposes:
 *   /api/tv/config
 *   /api/tv/time
 *   /api/tv/search
 *   /api/tv/symbols
 *   /api/tv/history
 *
 * Set window.NEPSE_PULSE_BACKEND_URL when the frontend and backend are
 * hosted on different origins.
 */
(function () {
  const root = window.NEPSE_PULSE_BACKEND_URL || "";
  const base = root.replace(/\/+$/, "");

  async function get(path, params = {}) {
    const url = new URL(base + path, window.location.origin);
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
    });
    const r = await fetch(url.toString(), { credentials: "omit" });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
  }

  function createUdfDatafeed() {
    return {
      onReady: async (cb) => cb(await get("/api/tv/config")),
      searchSymbols: async (userInput, exchange, symbolType, onResultReadyCallback) => {
        const rows = await get("/api/tv/search", { q: userInput, limit: 50 });
        onResultReadyCallback(Array.isArray(rows) ? rows : []);
      },
      resolveSymbol: async (symbolName, onSymbolResolvedCallback, onResolveErrorCallback) => {
        try {
          const symbol = symbolName.includes(":")
            ? symbolName.split(":").slice(1).join(":")
            : symbolName;
          const info = await get("/api/tv/symbols", { symbol });
          onSymbolResolvedCallback(info);
        } catch (e) {
          onResolveErrorCallback(String(e.message || e));
        }
      },
      getBars: async (symbolInfo, resolution, periodParams, onHistoryCallback, onErrorCallback) => {
        try {
          const rows = await get("/api/tv/history", {
            symbol: symbolInfo.ticker || symbolInfo.name,
            resolution,
            from: periodParams.from,
            to: periodParams.to,
            countback: periodParams.countBack || 500
          });
          if (!rows || rows.s !== "ok" || !rows.t?.length) {
            onHistoryCallback([], { noData: true });
            return;
          }
          const bars = rows.t.map((t, i) => ({
            time: Number(t) * 1000,
            open: Number(rows.o[i]),
            high: Number(rows.h[i]),
            low: Number(rows.l[i]),
            close: Number(rows.c[i]),
            volume: Number(rows.v?.[i] ?? 0)
          }));
          onHistoryCallback(bars, { noData: false });
        } catch (e) {
          onErrorCallback(String(e.message || e));
        }
      },
      subscribeBars: (_symbolInfo, _resolution, _onRealtimeCallback, _subscriberUID, _onResetCacheNeededCallback) => {
        // Daily/weekly/monthly history is currently supported by the backend.
        // Real-time streaming should be added when a verified NEPSE live feed
        // is available.
      },
      unsubscribeBars: (_subscriberUID) => {}
    };
  }

  window.NEPSE_PULSE_UDF_DATAFEED = createUdfDatafeed();
})();
