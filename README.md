# NEPSE Pulse V26 — Real Feature Data Engine

V26 upgrades the V25.1 central backend so feature modules are independently sourced and normalized.

## Included
- Central market/index/history feed
- Floorsheet endpoint with normalized trade rows
- Broker analysis calculated from floorsheet trades
- Sector endpoint
- Company/fundamental endpoint with financials, dividends and corporate-action attempts
- Historical price endpoint
- Technical endpoint calculating SMA20, SMA50, EMA20 and RSI14 from returned history
- Stock X-Ray aggregation endpoint
- Command Center with independent datasets and diagnostics
- Verified-source policy: unavailable data stays unavailable; no demo values are generated

## Deploy

1. Push `app.py`, `index.html`, `requirements.txt`, `config.js`, `render.yaml`, `auth-config.js`, and `splash.png` to the repository.
2. Create/redeploy the Render web service from `render.yaml`. The service starts with `uvicorn app:app --host 0.0.0.0 --port $PORT` and uses `/health` as its health check.
3. Confirm the Render service URL. If Render assigns a different URL than `https://nepse-pulse.onrender.com`, update `config.js` with that exact URL.
4. After deployment, test these endpoints in the browser: `/health`, `/api/market`, `/api/command-center`, `/api/floorsheet`, `/api/brokers`, `/api/sectors`, `/api/stock-xray/NABIL`, and `/api/diagnostics`.
5. Hard-refresh the frontend so the browser uses the new central-feed code.

### Central feed architecture

The browser uses the configured Render backend as the single market-data source. The FastAPI process keeps one persistent `AsyncNepseClient` session so the temporary NEPSE token is reused instead of creating a new session for every metric request.

## Data sources
Primary source is the public NEPSE frontend through `nepsepy`. The backend can use public API/static fallbacks when a primary call is unavailable. `nepsepy` documents market, floorsheet, index, company, financial report, dividend and corporate-action access. Public open datasets provide OHLC/floorsheet/reference data for historical fallback.