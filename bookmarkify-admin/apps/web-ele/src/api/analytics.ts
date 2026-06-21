import { requestClient } from '#/api/request';

/** 顶部统计卡片：total=区间累计，recent=近 7 日 */
export interface StatCard {
  key: string;
  title: string;
  total: number;
  recent: number;
}

/** 按天趋势：dates 与 pageviews/trend 等长 */
export interface TrendSeries {
  dates: string[];
  pageviews: number[];
  trend: number[];
}

/** 按月访问量：months 形如 "2026-06" */
export interface MonthlySeries {
  months: string[];
  pageviews: number[];
}

/** 通用「名称-计数」条目 */
export interface NamedCount {
  name: string;
  count: number;
}

export interface AnalyticsOverview {
  cards: StatCard[];
  trend: TrendSeries;
  monthly: MonthlySeries;
  referrers: NamedCount[];
  browsers: NamedCount[];
  systems: NamedCount[];
}

/** 获取后台分析看板聚合数据（GoatCounter 真实埋点） */
export async function getAnalyticsOverviewApi(days = 30) {
  return requestClient.get<AnalyticsOverview>('/admin/analytics/overview', {
    params: { days },
  });
}
