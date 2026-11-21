# NEPSE Pulse — Kotlin/JS migration audit

Audit source: `main` at `a1ad02f05c2f5e6f412822fa3f3d29b2cbdbda80` (2026-11-16).
Migration branch: `kotlin-migration`. The production branch and the original single-page app are untouched.

## 1. Existing architecture (preserved, not replaced)

- Legacy site: `index.html` (~1.66 MB single page) with inline HTML/CSS/JS plus `central-feed-engine.js`, `market-math.js`, `nepse-udf.js`.
- Render backend: `https://nepse-pulse-1.onrender.com` (`config.js`, `render.yaml`), FastAPI service (`nepse.py`, deployment entry `app:app`).
- TradingView UDF routes: `/api/tv/config`, `/api/tv/time`, `/api/tv/search`, `/api/tv/symbols`, `/api/tv/history`; index history at `/api/history?index=`.
- Supabase project `https://kymqmcgzjvbbgngfxnig.supabase.co` with the existing publishable key in `auth-config.js`. Auth methods used: email/password, Google OAuth, Facebook OAuth, phone OTP, password reset, session persistence, sign-out. Cloud data: `public.watchlists` (`user_id`,`symbol` PK, RLS by `auth.uid()`).
- Account lifecycle: `privacy-policy.html` and `delete-account.html` (email-verified deletion request) are retained and linked from the app.
- Android: Capacitor container `com.nepsepulse.app`, AdMob via `@capacitor-community/admob`, consent flow, adaptive banner, navigation interstitial with cooldown, app-open ad, deep link `com.nepsepulse.app://login-callback`.
- AdMob production IDs (unchanged): app `ca-app-pub-1565800134063497~9972330494`, banner `.../8028868444`, interstitial `.../1639379056`, app open `.../9483086731`. Debug builds substitute Google's official test units.
- CI: `.github/workflows/kotlin-migration.yml` compiles Kotlin/JS; `.github/workflows/kotlin-android-build.yml` builds the Capacitor Android project, generates icons/splash, restores production ad units, signs with repository secrets, and uploads APK/AAB.

## 2. New Kotlin/JS application

Path: `kotlin-web/` (Kotlin Multiplatform, JS browser target, Kotlin 2.4.21, webpack bundle).

| File | Responsibility |
| --- | --- |
| `core.kt` | API client with TTL cache, in-flight request de-duplication, abort timeouts; NEPSE data normalisation; formatting; OHLCV parsing; technical analysis (RSI, SMA, pivots, trendlines, 52-week range, volume ratio); local persistence for portfolio/alerts/watchlist/theme. |
| `ui.kt` | Design system: DOM helpers, icon set, buttons/chips/badges/cards, tables, skeleton and empty/error states, toasts, modals, theme manager, canvas candlestick and area charts with hover tooltips and volume. |
| `auth.kt` | Supabase auth: session restore and auth-state subscription, email sign-in/sign-up, Google/Facebook OAuth with PKCE + native deep link exchange, phone OTP, password reset, sign-out, cloud watchlist CRUD, account-deletion entry point. |
| `screens_market.kt` | Dashboard, market overview, Stock X-Ray, sector heatmap, market structure. |
| `screens_flow.kt` | Stock screener, Screener & Quant, broker analytics, Traders Zone, AI tools. |
| `screens_floorsheet.kt` | Floorsheet, broker aggregation, bulk/block and unusual-trade scans. |
| `screens_user.kt` | Portfolio, watchlist, price alerts, corporate actions, IPO center, profile/settings/auth UI. |
| `main.kt` | App shell, grouped navigation, mobile bottom navigation + sheet, global symbol search (local rows plus `/api/tv/search`), hash routing, theme, refresh timer. |

Design system: dark/light themes with CSS variables, Inter + JetBrains Mono, rounded cards, responsive grids (desktop sidebar, tablet collapse, mobile bottom nav), safe-area insets, reduced-motion support.

## 3. Feature preservation checklist

### Core market intelligence
- [x] NEPSE index value, point change, percentage change, day high/low, previous close (`/api/market`).
- [x] Market status and provider source, with last-updated timestamp.
- [x] Turnover, transactions, traded shares, scrips, market cap, float cap (provider summary).
- [x] Market breadth with advance/decline/unchanged and ratio.
- [x] Top gainers, top losers and turnover leaders.
- [x] Interactive index chart from `/api/index/intraday`.
- [x] Market news/updates panel from provider notice feeds with graceful failure state.
- [x] Live market table with search, sector filter, gainers/losers/active filters and paging.
- [x] Sector pulse strip.

### Research
- [x] Stock X-Ray: price summary, company profile (`/api/company`), fundamentals (`/api/stock-xray`), computed technicals, recent trades (`/api/company-floorsheet`), candle chart with D/W/M resolutions.
- [x] UDF datafeed integration through `/api/tv/history` (countback + resolution), plus TradingView chart deep link.
- [x] Advanced/fundamental screener with RSI, SMA, relative volume, trend filters and presets (`/api/technical-screener`, 250 symbols).
- [x] Screener & Quant workspace with market-wide statistics, quant signals, symbol rail, cloud/local watchlist.
- [x] Traders Zone with 12 price/volume/trend scans.
- [x] AI tools: trendline detection and evidence-backed stock detective computed from real OHLCV.
- [x] Sector heatmap with constituent drill-down (`/api/sectors`, `/api/sector-constituents`).
- [x] Market structure: breadth, liquidity share, sector dispersion.

### Order flow
- [x] Floorsheet with symbol/broker filter (`/api/floorsheet?limit=500`).
- [x] Broker aggregation: buy/sell/net flow, contracts, concentration.
- [x] Bulk/block threshold scan and average-rate deviation scan.
- [x] Broker analytics: net flow ranking, turnover ranking, counterparty pairs.

### Personal and primary market
- [x] Portfolio: local holdings, live valuation, P/L, day change, allocation.
- [x] Watchlist: Supabase-backed when signed in, device storage otherwise, device→cloud sync, remove/clear.
- [x] Price alerts: above/below price and percentage triggers evaluated on live data, stored on device.
- [x] Corporate actions screen with provider records, filter, reload and dividend-history lookup (`/api/dividend-history/{symbol}`).
- [x] IPO center using the same multi-source collector filtered to primary-market instruments.
- [x] Profile/settings: identity, provider, cloud watchlist, theme, cache clearing, privacy and account-deletion links, service health ping.

### Accounts
- [ ] Email/password sign-in verified end-to-end with a real account (Supabase connectivity and error handling verified; no credentials available in this environment).
- [ ] Google/Facebook OAuth round-trip verified on a device with provider configuration.
- [ ] Phone OTP verified with an SMS-enabled number.
- [ ] Password-reset email delivered and deep link followed.
- [x] Session restoration wiring, auth state subscription, sign-out binding.
- [x] Cloud watchlist read/write/delete code paths use the existing schema and RLS.

## 4. Verification performed

- `gradle compileKotlinJs` — passes.
- `gradle jsBrowserProductionWebpack` — passes; 216 KiB minified bundle.
- Served the production bundle locally and exercised it in a browser:
  - Dashboard renders live NEPSE index, breadth, summary metrics, movers, sector chips and intraday chart.
  - Market overview lists 352 live securities with search/filters.
  - Stock X-Ray (NICA) renders company profile, fundamentals, computed technicals and a canvas candle chart; D/W/M switching works.
  - Stock screener returns 250 securities from the provider technical endpoint (loading takes ~35–60 s on the free backend).
  - Floorsheet returns 500 contracts and broker aggregation; bulk and unusual scans derive from them.
  - Sector heatmap renders 22 tiles and loads constituents on selection.
  - Portfolio add/valuation, watchlist add, alert creation, theme toggle and mobile layout (390 px, no horizontal page overflow) verified.
  - Supabase client initialises and a sign-in attempt returns the provider's error ("Invalid login credentials"), proving the auth endpoint is reachable.
- Not verified here: full authenticated flows, native AdMob rendering, and Android APK/AAB output. This environment has no Android SDK and no Supabase test account; those checks require the CI run with signing secrets and a device/emulator.

## 5. Known provider limitations

The Render service currently returns 404/500 (and missing CORS headers) for `/api/production/announcements`, `/api/production/notices`, `/api/production/events` and `/api/production/disclosures`. The original site calls the same routes. Corporate actions and IPO screens therefore show an explicit "no provider records / some sources failed" state with a retry action instead of fabricated data. `/api/brokers` does not respond within 70 s; broker analytics are computed from the working floorsheet endpoint. Technical screening is slow because the provider recomputes OHLCV history per request; results are cached for 5 minutes and warmed at startup.

## 6. Android build pipeline

- Kotlin/JS production webpack bundle is copied into the Capacitor `android-web` directory (minified output preferred).
- Capacitor 7 project generated in CI with app name `NEPSE Pulse`, package `com.nepsepulse.app`, SDK 24/36, versionCode 11, versionName 2.1.0.
- Launcher icon and splash screen generated from `resources/icon.png` and `resources/splash.png` (derived from the existing artwork).
- Deep-link intent filter for `com.nepsepulse.app://login-callback`, `singleTask` launch mode.
- AdMob app ID and the existing production unit IDs restored for release; Google test units used for the debug APK.
- Release signing from repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); keystores are never committed.
- Artifacts uploaded: debug APK, release APK, signed AAB (signing requires the secrets to be present).

## 7. Remaining work before production cutover

- Run the Android workflow and confirm the three artifacts, then install the APK on a physical device.
- Validate AdMob consent, adaptive banner, interstitial frequency and app-open behaviour in a debug build with test units.
- Complete OAuth/OTP/reset verification with real provider accounts and confirm the Supabase redirect allow-list includes the production web origin and the Android deep link.
- Fix or replace the provider notice/event endpoints on Render so corporate actions and IPO data can populate.
- Decide whether to publish the Kotlin/JS build as the main web experience; `main` still serves the legacy single-page app.
