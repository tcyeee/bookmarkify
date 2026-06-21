# Admin Analytics — Real GoatCounter Data

**Date:** 2026-06-21
**Goal:** Replace the demo/hardcoded data in the admin dashboard analytics page
(`bookmarkify-admin/apps/web-ele/src/views/dashboard/analytics/`) with real
data from the self-hosted GoatCounter instance.

## Background

- GoatCounter runs in the `goatcounter` container (Postgres-backed) on `app_network`.
- bookmarkify is **site 3** (`bookmarkify.stats.viii.me`, parent = site 1).
- Existing read-only API token (`public-stats-read`, perm 64, `sites: [-1]`) already
  returns data for site 3 — no new token needed.
- REST API verified working: `/api/v0/stats/{total,hits,toprefs,browsers,systems,locations,sizes}`.
- `bookmarkify-api` resolves `goatcounter` on `app_network` (172.19.0.9) → prod calls
  `http://goatcounter:8080` with `Host: bookmarkify.stats.viii.me`. Dev calls the public
  `https://bookmarkify.stats.viii.me` directly.

## Architecture

Admin frontend → `bookmarkify-api` `/admin/analytics/**` (ADMIN realm, holds token) → GoatCounter.
No token in the browser. Mirrors the existing `ScrapperConfig` + Hutool `HttpUtil` outbound pattern.

### Backend (bookmarkify-api)

- `config/entity/GoatCounterConfig.kt` — `@ConfigurationProperties("bookmarkify.goatcounter")`
  with `baseUrl`, `siteHost`, `token`. Auto-registered via `@ConfigurationPropertiesScan`.
- `entity/dto/AnalyticsDto.kt` — response DTOs (`AnalyticsOverview`, `StatCard`, `TrendSeries`,
  `MonthlySeries`, `NamedCount`).
- `server/IAnalyticsService.kt` + `server/impl/AnalyticsServiceImpl.kt` — call GoatCounter,
  aggregate. Failures wrap in `CommonException` (reuse a generic error type).
- `controller/admin/AdminAnalyticsController.kt` — `GET /admin/analytics/overview?days=30`.

### Config (application*.yml)

```yaml
bookmarkify:
  goatcounter:
    base-url: ...        # dev: https://bookmarkify.stats.viii.me ; prod: http://goatcounter:8080
    site-host: bookmarkify.stats.viii.me
    token: ${BOOKMARKIFY_GOATCOUNTER_TOKEN:...}
```

## Data Mapping (all real, GoatCounter-native)

| Widget | Metric | Source |
|---|---|---|
| 4 stat cards | 访问量(PV) · 事件数 · 来源数 · 收录页面数 — all-time total + last-7-day value | `stats/total`, `toprefs`, `hits` |
| 流量趋势 (line) | daily pageviews + events, last `days` | `stats/total` daily array |
| 月访问量 (bar) | monthly pageviews, last 12 months | `stats/total` aggregated by month |
| 访问数量 (radar) | OS/系统分布 | `stats/systems` |
| 访问来源 (pie) | 引荐来源 | `stats/toprefs` |
| 商业占比 → 浏览器分布 (rose pie) | browsers | `stats/browsers` |

GoatCounter v0 API exposes no unique-visitor or bounce-rate count, so cards use the four
directly-countable metrics above. "下载量" is dropped (nothing tracks it).

## Frontend (web-ele)

- `api/analytics.ts` — `getAnalyticsOverviewApi()` typed to the backend DTO.
- `index.vue` — fetch once on mount, pass data to children as props, handle loading/error.
- Refactor the 6 child components from hardcoded `onMounted` echarts to render reactively
  (`watch` on a `data` prop), keeping their existing chart styling/layout.

## Out of scope

- No new GoatCounter token / no DB-direct queries.
- No registered-user count from bookmarkify's own DB (analytics-only).
- No date-range picker UI (fixed `days=30`, monthly = 12 months).
