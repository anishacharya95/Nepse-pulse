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
- [ ] Google/Facebook OAuth round-trip verified on a device with provider configuration. The PKCE authorize URL, verifier persistence and callback exchange were verified in a browser; a live provider callback still needs the Supabase redirect allow-list and a Google test account.
- [ ] Phone OTP verified with an SMS-enabled number.
- [x] OAuth callback now extracts the authorization code, exchanges it (not the URL), handles provider errors, cold-start launch URLs, duplicate callbacks and browser cancellation.
- [x] Session restoration wiring, auth state subscription, sign-out binding, Profile & Settings identity refresh.
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
  - Dashboard intraday chart canvas renders (629×156 in the overview card; 881×450 in Stock X-Ray).
  - Portfolio add/valuation, watchlist add, alert creation, theme toggle and mobile layout (390 px, document scroll width 375 < 390, no horizontal page overflow) verified.
  - Supabase client initialises and a sign-in attempt returns the provider's error ("Invalid login credentials"), proving the auth endpoint is reachable.
- GitHub Actions on commit `ee3d514` (both workflows succeeded):
  - `Kotlin migration validation` — success.
  - `NEPSE Pulse Kotlin Android Build` — success in 6m 24s, artifact `NEPSE-Pulse-Kotlin-Android` (24 MB).
- Artifact contents downloaded and inspected directly:
  - `apk/debug/app-debug.apk` 12.4 MB — AdMob unit IDs are Google test IDs (`ca-app-pub-3940256099942544/6300978111`, `/1033173712`, `/5575463023`).
  - `apk/release/app-release.apk` 5.4 MB — production unit IDs (`.../8028868444`, `.../1639379056`, `.../9483086731`) and production app ID `ca-app-pub-1565800134063497~9972330494`; APK Signature Scheme v2 block present, `META-INF` has no v1 entries (modern v2/v3 signing).
  - `bundle/release/app-release.aab` 7.8 MB — `jarsigner -verify` passes, signer certificate valid until 2054-03-10.
  - Manifest in both APKs: package `com.nepsepulse.app`, versionName `2.1.0`, permissions INTERNET, ACCESS_NETWORK_STATE, AD_ID, AdServices, foreground service. Adaptive icons (`mipmap-anydpi-v26/ic_launcher.xml`, foreground/background, round) and splash images at ldpi→xxxhdpi, portrait, landscape and night variants are packaged from `resources/`.
- Kotlin/JS production build passes (240 KiB minified).
- Android Gradle build reproduced locally with the same CI steps and SDK 36: debug APK, release APK and AAB all build. Debug APK verified signed with the Android debug certificate; release artifacts are unsigned because no signing secrets exist in this environment. Manifest verified: package `com.nepsepulse.app`, minSdk 24, targetSdk 36, singleTask OAuth deep link, `@capacitor/app`, `@capacitor/browser` and AdMob registered, branded launcher and splash resources packaged.
- Stock X-Ray fundamentals verified against live `/api/fundamentals/{symbol}` data for NABIL and NTC: reported EPS/P/E/book value/net profit, calculated P/B, dividend yield, market cap, TTM EPS/profit and annual ROE, with N/A for revenue, operating profit, assets and liabilities that the provider does not publish.
- Not verified here: full authenticated flows on a physical device and native AdMob rendering. This environment has no Android device/emulator and no Supabase test account.

## 5. Known provider limitations

The Render service currently returns 404/500 (and missing CORS headers) for `/api/production/announcements`, `/api/production/notices`, `/api/production/events` and `/api/production/disclosures`. The original site calls the same routes. Corporate actions and IPO screens therefore show an explicit "no provider records / some sources failed" state with a retry action instead of fabricated data. `/api/brokers` does not respond within 70 s; broker analytics are computed from the working floorsheet endpoint. Technical screening is slow because the provider recomputes OHLCV history per request; results are cached for 5 minutes and warmed at startup.

## 6. Android build pipeline

- Kotlin/JS production webpack bundle is copied into the Capacitor `android-web` directory (minified output preferred).
- Capacitor 7 project generated in CI with app name `NEPSE Pulse`, package `com.nepsepulse.app`, SDK 24/36, versionCode 11, versionName 2.1.0.
- Launcher icon, adaptive icon and Android 12 / pre-12 splash screens generated from the exact `nepse-pulse-exact-icon.png` by `scripts/generate_android_branding.py`; the committed `android-overlay/` launch theme uses dark navy `#061020`.
- Deep-link intent filter for `com.nepsepulse.app://login-callback`, `singleTask` launch mode, verified in the built manifest.
- CI pins AGP 8.9.2 for compileSdk 36 and verifies plugin registration, SDK levels, the deep link and every branded resource before building.
- AdMob app ID and the existing production unit IDs restored for release; Google test units used for the debug APK.
- Release signing from repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); keystores are never committed.
- Artifacts uploaded: debug APK, release APK, signed AAB (signing requires the secrets to be present).

## 7. Remaining work before production cutover

- Install `app-release.apk` (or the debug APK) on a physical device and smoke-test launch, navigation and the WebView AdMob bridge.
- Validate AdMob consent, adaptive banner, interstitial frequency and app-open behaviour in a debug build with test units. Debug builds keep the production app ID with test unit IDs; switch the manifest app ID to Google's test app ID if the account must never see debug requests.
- Complete OAuth/OTP/reset verification with real provider accounts. The Supabase redirect allow-list must include the production web origin and `com.nepsepulse.app://login-callback`.
- Fix or replace the provider notice/event endpoints on Render so corporate actions and IPO data can populate.
- Decide whether to publish the Kotlin/JS build as the main web experience; `main` still serves the legacy single-page app.
