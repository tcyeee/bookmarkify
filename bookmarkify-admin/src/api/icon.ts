import type {
  AssetQuality,
  AssetRole,
  IconVerdict,
} from '#/api/enums.generated';

import { requestClient } from '#/api/request';

/**
 * 图标判定总览。
 *
 * 后端把 `AssetRolePolicy` 在存量数据上跑一遍，按结论分档。这个接口存在的唯一理由是**可度量**：
 * 图标规则的每次改动（见仓库根 `docs/ICON-DISPLAY-TODO.md`）都要能立刻回答「27 变成多少了」。
 */
export interface IconVerdictOverviewVO {
  buckets: IconVerdictBucketVO[];
  candidateHistogram: IconCandidateBucketVO[];
  /** 参与判定的站点数（至少有一行站点级 FAVICON/LOGO） */
  siteTotal: number;
  /**
   * 一行图标资产都没有的站点，**不计入 buckets**。
   * 与 NO_ASSET 是两回事：那一档是「抓到了图但没有一张能渲染」，这里是「根本没抓到过图」。
   */
  siteWithoutAssets: number;
  /**
   * 判成色块、但库里躺着一张合格候选的站点数 —— **规则的改进空间**。
   * 基线 31。修完缺陷后它应当趋近 0；它不降说明改动没打中要害。
   */
  salvageable: number;
  /** 本次判定使用的 TILE_MIN_SIZE，由后端下发而不是前端写死 */
  tileMinSize: number;
}

export interface IconVerdictBucketVO {
  verdict: IconVerdict;
  count: number;
  /** 这一档里「库里有合格候选」的站点数 */
  salvageable: number;
}

/** 候选图数量的一档；`candidates` 为 6 表示 6 张及以上 */
export interface IconCandidateBucketVO {
  candidates: number;
  sites: number;
}

export interface IconVerdictSiteVO {
  siteId: string;
  host: string;
  brandName?: null | string;
  verdict: IconVerdict;
  /** 选中那张图的签名地址；未落 OSS 时为 null（后端不回退源站直连） */
  chosenUrl?: null | string;
  chosenRole?: AssetRole | null;
  chosenQuality?: AssetQuality | null;
  /** scrapper 报的 extractor，即「这张图是从哪个标签拿到的」 */
  chosenExtractor?: null | string;
  /** 选中那张图的有效边长；矢量图为 null */
  chosenSize?: null | number;
  chosenIsVector: boolean;
  /** 可选图标数，按 content_hash 去重 */
  candidateCount: number;
  /** 库里最大的一张可渲染图的边长。与 chosenSize 不同就是一条线索 */
  bestSize?: null | number;
  bestIsVector: boolean;
  salvageable: boolean;
}

export interface IconVerdictQueryParams {
  verdict?: IconVerdict | null;
  onlySalvageable?: boolean;
  limit?: number;
}

/**
 * 每一档的含义与处置方式。
 *
 * `MONOGRAM_QUALITY` 与 `MONOGRAM_SIZE` 分开是这张表的核心：前者是规则的问题（改代码能修），
 * 后者是数据的问题（改代码修不了）。合并成一个「显示色块」会让每次规则改动都量不出效果。
 */
export const ICON_VERDICT_META: Record<
  IconVerdict,
  { desc: string; label: string; tone: 'danger' | 'info' | 'success' | 'warning' }
> = {
  IMAGE: {
    label: '正常显示图片',
    tone: 'success',
    desc: '规则选出了一张够格的图，磁贴上显示的就是它',
  },
  MONOGRAM_QUALITY: {
    label: '够大却判色块',
    tone: 'warning',
    desc: '选中的图尺寸达标，但因 quality=DEGRADED 被判成首字母色块。DEGRADED 是出处判断（「这不是品牌 LOGO，只是 favicon 换了个 rel」），而拒绝显示图片的理由只应该是「放大会糊」——这一档基本都是规则的问题',
  },
  MONOGRAM_SIZE: {
    label: '尺寸确实不够',
    tone: 'info',
    desc: '选中的图小于 TILE_MIN_SIZE，放大会糊，走首字母色块。若同一行的「库里最大」明显更大，说明规则没选中更好的那张，仍然有救',
  },
  NO_ASSET: {
    label: '无可渲染图标',
    tone: 'danger',
    desc: '有资产行但没有一张能渲染（都下载失败、或都没落 OSS 也没有可直连地址）。这是抓取链路的问题，不是选图规则的问题',
  },
};

export async function getAdminIconVerdictOverviewApi() {
  return requestClient.post<IconVerdictOverviewVO>('/admin/icon/verdict-overview');
}

export async function getAdminIconVerdictSitesApi(params: IconVerdictQueryParams) {
  return requestClient.post<IconVerdictSiteVO[]>('/admin/icon/verdict-sites', params);
}
