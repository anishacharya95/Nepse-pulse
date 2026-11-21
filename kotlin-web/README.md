# NEPSE Pulse — Kotlin/JS web application

Production web client for NEPSE Pulse, written in Kotlin/JS and packaged for Android with Capacitor.
The legacy single-page site on `main` is untouched; this directory is the migration target.

## Requirements

- JDK 17 or 21
- Gradle 8.14+ (or a Gradle wrapper)
- Node.js is supplied by the Kotlin/JS Gradle plugin; no separate install is required

## Build

```bash
cd kotlin-web
gradle jsBrowserProductionWebpack
```

Outputs:

- `build/kotlin-webpack/js/productionExecutable/kotlin-web.js` — minified browser bundle
- `build/compileSync/js/main/productionExecutable/kotlin/kotlin-web.js` — unminified bundle (debugging)

Compile-only check:

```bash
gradle compileKotlinJs
```

## Run locally

Assemble the static shell and serve it:

```bash
mkdir -p build/web-preview
cp build/kotlin-webpack/js/productionExecutable/kotlin-web.js build/web-preview/
cp src/jsMain/resources/index.html src/jsMain/resources/styles.css \
   src/jsMain/resources/auth-config.js ../privacy-policy.html ../delete-account.html \
   ../nepse-pulse-exact-icon.png build/web-preview/
python3 -m http.server 3000 --bind 0.0.0.0 -d build/web-preview
```

Open `http://localhost:3000`. Supabase and the Render API are loaded from their production URLs.

## Architecture

- `core.kt` — API layer (TTL cache, request de-duplication, abort timeouts), market normalisation, formatting, OHLCV parsing, technical analysis, local storage.
- `ui.kt` — design system: icons, cards, tables, states, toasts, modals, themes, canvas candlestick/area charts.
- `auth.kt` — Supabase auth (email, Google, Facebook, phone OTP, reset, session, sign-out), cloud watchlist, OAuth deep-link exchange.
- `fundamentals.kt` — NEPSE company fundamentals for Stock X-Ray: valuation, profitability, financial position, TTM calculations, reporting periods and honest N/A handling.
- `screens_market.kt` — dashboard, market overview, Stock X-Ray, sectors, market structure.
- `screens_flow.kt` — screener, quant, broker analytics, traders zone, AI tools.
- `screens_floorsheet.kt` — floorsheet, broker aggregation, bulk and unusual scans.
- `screens_user.kt` — portfolio, watchlist, alerts, corporate actions, IPO center, profile/settings.
- `main.kt` — shell, navigation, global search, hash routing, refresh timer, theme bootstrap.

## Preservation contract

- Supabase project remains `https://kymqmcgzjvbbgngfxnig.supabase.co`; only the existing publishable key is used. No service-role key exists in client code.
- Cloud watchlists use the existing `public.watchlists` table and its RLS policies.
- Render backend remains `https://nepse-pulse-1.onrender.com`.
- TradingView/UDF routes remain `/api/tv/config`, `/api/tv/time`, `/api/tv/search`, `/api/tv/symbols`, `/api/tv/history`, plus `/api/history?index=`.
- AdMob production IDs are preserved and applied by the Android workflow:
  - App: `ca-app-pub-1565800134063497~9972330494`
  - Banner: `ca-app-pub-1565800134063497/8028868444`
  - Interstitial: `ca-app-pub-1565800134063497/1639379056`
  - App Open: `ca-app-pub-1565800134063497/9483086731`
- Debug Android builds use Google's official test ad units.
- Account deletion remains an operator-verified email request via `delete-account.html`.

## Android packaging
`.github/workflows/kotlin-android-build.yml` compiles this module, copies the bundle into a Capacitor
Android project (`com.nepsepulse.app`), pins SDK 24/36 and AGP 8.9.2, configures AdMob and the OAuth
deep link, builds a debug APK and a release APK/AAB (release signing requires repository secrets),
then uploads the artifacts.

### Native branding
`scripts/generate_android_branding.py` uses the repository's existing `nepse-pulse-exact-icon.png` to
generate every launcher density, the adaptive icon foreground and the Android 12 / pre-12 splash
bitmaps. The committed XML in `android-overlay/` defines the dark-navy launch theme. Run
`scripts/android-sync.sh` instead of a bare `npx cap sync android` so Capacitor's generated project is
re-branded after every sync.

### Provider sign-in
OAuth uses PKCE and the app deep link `com.nepsepulse.app://login-callback`. The Supabase dashboard
must list that exact URL (and `com.nepsepulse.app://login-callback/**`) under Authentication → URL
Configuration → Redirect URLs, because Supabase validates `redirectTo` server-side. The app handles
authorization codes, provider errors, cold-start launch URLs, duplicate callbacks and session
restoration; the code is exchanged in the same WebView that stored the PKCE verifier.

## Data notes

Portfolio holdings and price alerts are stored on the device (`localStorage`), because the existing
Supabase schema only defines `public.watchlists`. Watchlists sync to Supabase when signed in.
