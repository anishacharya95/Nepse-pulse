# NEPSE Pulse Kotlin Migration Audit

Audit source: `main` at `a1ad02f05c2f5e6f412822fa3f3d29b2cbdbda80` (2026-11-16).
Migration branch: `kotlin-migration`.
Production branch is intentionally unchanged.

## Existing architecture

- Website: a large single-page `index.html` (~1.66 MB) with inline HTML, CSS and JavaScript modules.
- NEPSE feed engine: `central-feed-engine.js`, with REST/WebSocket feed state, cache, polling, market summary, live rows, gainers/losers and liquidity.
- Market calculations: `market-math.js`, with verified index observations and breadth logic.
- Backend: Render service `https://nepse-pulse-1.onrender.com`, defined in `config.js` and `render.yaml`.
- Python backend: `app.py` and `nepse.py`; NEPSE data SDK and TradingView UDF endpoints.
- TradingView adapter: `nepse-udf.js`; UDF routes are `/api/tv/config`, `/api/tv/time`, `/api/tv/search`, `/api/tv/symbols`, `/api/tv/history`.
- Supabase: project URL `https://kymqmcgzjvbbgngfxnig.supabase.co`; public publishable key remains in the existing `auth-config.js` and is not duplicated in this migration shell.
- Existing Android app: `com.nepsepulse.app`, currently a WebView wrapper (not a native Compose feature implementation).
- Existing Android build: `.github/workflows/android-build.yml`, dynamically creates a Capacitor Android project and builds APK/AAB.

## Feature inventory extracted from the existing website

### Core dashboard and market intelligence
- Command Center: market overview, breadth, movers, liquidity and feed health.
- Market index summary, market status, advances/declines/unchanged, live label and feed diagnostics.
- Live market table and search.
- Gainers/losers and liquidity panels.
- Market-wide directory/listing views and stock/company lookup.

### Stock research and charts
- Stock X-Ray: fundamentals, valuation, technicals and activity.
- Professional TradingView charting.
- Price Volume Actions: sentiment, unusual volume, bullish/bearish signals and active symbols.
- Trendline Detection: support/resistance and volume analysis.
- AI Stock Detective: evidence-backed breakdown from price and volume history.
- Company profile and stock detail tabs.

### Screening, sectors and broker activity
- Advanced Screener: multi-factor, technical and liquidity scans.
- Screener & Quant: market filters, RSI, trend and quantitative scans.
- Quant & Watchlist central-feed screening.
- Floorsheet & Brokers: transactions, broker flow, bulk and unusual activity.
- Broker Analytics: broker activity, flow and transaction concentration.
- Sector Heatmap: sector performance, breadth and constituent drill-down.
- Market Structure: sector breadth and market participation.
- Traders Zone: 15 advanced scans, including breakout, volume, patterns and support/resistance.

### Personal finance and alerts
- Portfolio: holdings, average cost, P/L and dividends.
- Cloud watchlists using Supabase `public.watchlists`.
- Alerts & Watchlist: price, volume and technical alerts.
- Corporate Actions: dividends, bonus, rights and corporate events.
- IPO Center: IPO, FPO, rights, debentures and funds.

### Accounts and policies
- Supabase email/password sign-in and sign-up, Google OAuth, Facebook OAuth, phone OTP, password reset, persistent sessions and sign-out are present in the web source (provider enablement remains controlled in Supabase).
- Profile, account deletion flow, privacy policy and delete-account page.
- OAuth callback handling for Capacitor Android.

## Supabase preservation details

- Existing project URL and publishable key remain the source of configuration.
- Existing `watchlists` table uses `(user_id, symbol)` as its primary key and RLS policies restrict read/insert/delete to `auth.uid() = user_id`.
- Portfolio code contains Supabase cloud-save logic; its exact table/schema must be validated before porting. Do not reset or migrate the live database without an explicit schema diff and backup.
- Keep Supabase Auth session persistence, OAuth PKCE/callback handling, OTP and account deletion behavior.

## AdMob IDs found in the existing Android workflow

- Banner: `ca-app-pub-1565800134063497/8028868444`
- Interstitial: `ca-app-pub-1565800134063497/1639379056`
- App Open: `ca-app-pub-1565800134063497/9483086731`

The current workflow also includes consent handling, adaptive banner positioning, navigation-transition interstitial frequency/cooldown logic, and app-open lifecycle handling. These policies must be preserved in native Android; do not show web ads through AdMob.

## Migration gates (not yet complete)

- [ ] Port and test every dashboard component and feed state.
- [ ] Port all market tables, search, stock detail and company tabs.
- [ ] Integrate TradingView chart library/datafeed with its licensing restrictions respected.
- [ ] Port all screener, quant, floorsheet, broker and sector modules.
- [ ] Port portfolio, dividend, cloud watchlist and alert persistence.
- [ ] Port Supabase Auth flows and OAuth return/deep links.
- [ ] Port account deletion and policy screens.
- [ ] Package the Kotlin/JS web app in the Capacitor Android container and verify HTTPS-origin routing/OAuth return behavior.
- [ ] Verify native AdMob consent/banner/interstitial/app-open placements and policy behavior in debug/release builds.
- [ ] Add signed release APK/AAB using the existing upload/signing secrets; never commit keystore files.
- [ ] Run API, UI, Android and regression tests before production cutover.

## Current branch status

The Kotlin/JS production bundle has passed CI compilation. The migration dashboard currently includes the Render market API connection, index summary, provider-reported turnover, price-pair-based breadth, market movers, gainers/losers filters, searchable live rows and a clickable Stock X-Ray snapshot. A separate Android workflow now compiles the Kotlin/JS bundle, packages it into a Capacitor Android app, and carries forward the existing AdMob consent and ad-placement script/IDs.

The Android APK/AAB pipeline is still being validated. No successful APK/AAB artifact is claimed until the current GitHub Actions run completes and the artifacts are checked. The current Kotlin app is an incremental migration, not feature parity: the full stock research, TradingView chart UI, screeners, floorsheet/broker tools, portfolio, Supabase auth/watchlists/alerts, corporate actions and IPO flows remain to be ported and tested. Existing main-branch website, backend, Supabase and Android build are untouched.
