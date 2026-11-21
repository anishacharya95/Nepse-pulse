# NEPSE Pulse — Kotlin/JS migration workspace

This directory is an isolated migration target. It does not replace the production website.

## Build

Requires JDK 17+ and a Gradle 8.14-compatible installation (or a Gradle wrapper).

```bash
gradle jsBrowserProductionWebpack
```

The production website remains the feature reference until each screen and integration has been migrated and verified.

## Preservation contract

- Supabase project remains the existing project; no schema reset or replacement.
- Render backend remains `https://nepse-pulse-1.onrender.com`.
- TradingView UDF routes remain `/api/tv/config`, `/api/tv/time`, `/api/tv/search`, `/api/tv/symbols`, and `/api/tv/history`.
- Android AdMob IDs currently present in the existing workflow are preserved for native Android migration:
  - Banner: `ca-app-pub-1565800134063497/8028868444`
  - Interstitial: `ca-app-pub-1565800134063497/1639379056`
  - App Open: `ca-app-pub-1565800134063497/9483086731`

The current Kotlin page is only a migration shell, not a feature-complete replacement.
