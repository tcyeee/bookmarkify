import type {
  AssetRole,
  BookmarkEntity,
  BookmarkLockedField,
  DisplayMode,
  SiteAsset,
  SiteDisplayPref,
} from "#/api/bookmark";

/**
 * 新资产模型（site_asset 一行一图 + site_display_pref 按模式分行）的读取helper。
 *
 * 后端下发的是**全部**资产而非选好的那一张 —— 后台需要看到该站到底声明了哪些图、
 * 各自出处是什么、有没有互相撞 hash。这里提供按用途取图的便捷入口。
 */

/** 取某用途下的代表图：优先 isPrimary，其次可信度，最后按像素兜底 */
export function assetByRole(
  assets: SiteAsset[] | undefined,
  role: AssetRole,
): SiteAsset | undefined {
  const list = (assets ?? []).filter((a) => a.role === role && !a.errorMsg);
  if (list.length === 0) return undefined;
  return (
    list.find((a) => a.isPrimary) ??
    list.sort((a, b) => {
      const q = (b.quality === "TRUSTED" ? 1 : 0) - (a.quality === "TRUSTED" ? 1 : 0);
      if (q !== 0) return q;
      return Math.min(b.width ?? 0, b.height ?? 0) - Math.min(a.width ?? 0, a.height ?? 0);
    })[0]
  );
}

export function faviconOf(row: Pick<BookmarkEntity, "assets">): string | undefined {
  return assetByRole(row.assets, "FAVICON")?.url;
}

export function logoOf(row: Pick<BookmarkEntity, "assets">): string | undefined {
  return assetByRole(row.assets, "LOGO")?.url;
}

/** 社交分享图（旧代码里叫"og 图"；OG 是协议名不是类型名） */
export function socialOf(row: Pick<BookmarkEntity, "assets">): string | undefined {
  return assetByRole(row.assets, "SOCIAL")?.url;
}

/** 取某展示模式的设置；没有则给出与后端一致的默认值 */
export function prefOf(
  prefs: SiteDisplayPref[] | undefined,
  mode: DisplayMode,
): SiteDisplayPref {
  return (
    (prefs ?? []).find((p) => p.displayMode === mode) ?? {
      displayMode: mode,
      iconPadding: 0,
      iconBgColor: undefined,
      pinnedAssetId: undefined,
      previewUrl: undefined,
      monogram: true,
    }
  );
}

/** 该书签是否根本没有独立 LOGO（所有 LOGO 都与 favicon 同字节或缺席） */
export function lacksRealLogo(assets: SiteAsset[] | undefined): boolean {
  const logo = assetByRole(assets, "LOGO");
  return !logo || logo.quality === "DEGRADED";
}

/** 展示模式的中文名，用于后台标签 */
export const DISPLAY_MODE_LABEL: Record<DisplayMode, string> = {
  LIST: "列表（小图 + 全名）",
  TILE: "大图（大图 + 短名）",
};

/** 人工锁定字段的中文名：锁住的字段不会被下一轮自动抓取覆盖 */
export const BOOKMARK_LOCKED_FIELD_LABEL: Record<BookmarkLockedField, string> = {
  APP_NAME: "简称已锁定",
  DESCRIPTION: "描述已锁定",
  TITLE: "标题已锁定",
};

/** 出处枚举的中文释义，鼠标悬浮时解释每个取值 */
export const EXTRACTOR_LEGEND: Record<string, string> = {
  APPLE_TOUCH_ICON: "link[rel=apple-touch-icon]，iOS 主屏图标，尺寸通常较大",
  FAVICON_ICO_FALLBACK: "页面一个图标都没声明，对 /favicon.ico 的约定式兜底探测",
  HEADLESS_CAPTURE: "无头浏览器渲染出的页面截图",
  JSON_LD_IMAGE: "JSON-LD 的 image 字段，语义较泛",
  JSON_LD_ORG_LOGO: "JSON-LD 的 Organization.logo，唯一语义明确的品牌 LOGO",
  LINK_ICON: "link[rel=icon]，标准 favicon 声明",
  LINK_MASK_ICON: "link[rel=mask-icon]，Safari 固定标签页用的矢量图",
  MANIFEST_ICON: "Web App Manifest 的 icons[]，站点自己声明的多尺寸图标",
  MS_TILE_IMAGE: "meta[name=msapplication-TileImage]，Windows 磁贴图",
  OG_IMAGE: "meta[property=og:image]，社交分享图",
  TWITTER_IMAGE: "meta[name=twitter:image]，Twitter 卡片图",
};
