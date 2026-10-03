# NEPSE Pulse Charting Library Package

This is a GitHub-ready integration package for **TradingView Advanced Charts** and the NEPSE Pulse UDF datafeed.

## Important licensing note

TradingView Advanced Charts is distributed through TradingView's private GitHub repository and is **not redistributable**. Do not commit the proprietary `public/charting_library/` contents to a public GitHub repository.

TradingView's official installation flow requires authorized GitHub access to the private repository.

## Install

```bash
npm install
npm run tv:install
```

To install a specific authorized release:

```bash
npm run tv:install -- 32.0.0
```

The installer places the library at:

```text
public/charting_library/
```

## NEPSE datafeed

The adapter in `src/nepse-udf.js` connects Advanced Charts to the existing NEPSE Pulse endpoints:

- `/api/tv/config`
- `/api/tv/time`
- `/api/tv/search`
- `/api/tv/symbols`
- `/api/tv/history`

Copy or load `src/nepse-udf.js` before creating the TradingView widget.

Example:

```html
<script src="charting_library/charting_library.standalone.js"></script>
<script src="../src/nepse-udf.js"></script>
<script>
  new TradingView.widget({
    container: "chartContainer",
    library_path: "charting_library/",
    datafeed: window.NEPSE_PULSE_UDF_DATAFEED,
    symbol: "NEPSE:NABIL",
    interval: "1D",
    timezone: "Asia/Kathmandu",
    locale: "en",
    autosize: true
  });
</script>
```

## Current NEPSE data limitation

The existing backend supports daily/weekly/monthly historical data. It does not invent intraday data. A verified NEPSE intraday/live stream is required for 1m/5m/15m/30m/1h charts and real-time `subscribeBars`.

## GitHub publishing

You may publish this **integration scaffold** publicly. Do not publish TradingView's proprietary `charting_library` files.

Suggested repository name:

`nepse-pulse-charting`

Suggested structure:

```text
nepse-pulse-charting/
├── public/
│   └── charting_library/       # installed privately; DO NOT COMMIT
├── scripts/
│   ├── install-tradingview.mjs
│   └── check-tradingview.mjs
├── src/
│   └── nepse-udf.js
├── .gitignore
├── package.json
└── README.md
```
