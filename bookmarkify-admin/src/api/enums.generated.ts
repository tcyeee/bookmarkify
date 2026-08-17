// ⚠️ 本文件由 bookmarkify-api 的 SharedEnumGenerator 生成，**不要手工编辑**。
//
// 来源：bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/enums/
// 重新生成：cd bookmarkify-api && ./gradlew generateSharedEnums
// 一致性由 SharedEnumContractTest 守着 —— 改了 Kotlin 枚举却没重新生成，API 的测试会红。
//
// 形态是 `const 对象 + 同名类型`：值侧支持 `AssetRole.LOGO` 这种引用（web 侧的既有写法），
// 类型侧等价于 `'FAVICON' | 'LOGO' | …` 的字符串联合（admin 侧的既有写法），两边都不用改调用点。

/** 对应 Kotlin `AssetOwnerType` */
export const AssetOwnerType = {
  SITE: 'SITE',
  PAGE: 'PAGE',
} as const
export type AssetOwnerType = (typeof AssetOwnerType)[keyof typeof AssetOwnerType]

/** 对应 Kotlin `AssetQuality` */
export const AssetQuality = {
  TRUSTED: 'TRUSTED',
  DEGRADED: 'DEGRADED',
} as const
export type AssetQuality = (typeof AssetQuality)[keyof typeof AssetQuality]

/** 对应 Kotlin `AssetRole` */
export const AssetRole = {
  FAVICON: 'FAVICON',
  LOGO: 'LOGO',
  SOCIAL: 'SOCIAL',
  SCREENSHOT: 'SCREENSHOT',
} as const
export type AssetRole = (typeof AssetRole)[keyof typeof AssetRole]

/** 对应 Kotlin `BookmarkLinkType` */
export const BookmarkLinkType = {
  DOMAIN: 'DOMAIN',
  LOCAL: 'LOCAL',
  IP: 'IP',
  OTHER: 'OTHER',
} as const
export type BookmarkLinkType = (typeof BookmarkLinkType)[keyof typeof BookmarkLinkType]

/** 对应 Kotlin `PageLockedField` */
export const BookmarkLockedField = {
  TITLE: 'TITLE',
  DESCRIPTION: 'DESCRIPTION',
  APP_NAME: 'APP_NAME',
} as const
export type BookmarkLockedField = (typeof BookmarkLockedField)[keyof typeof BookmarkLockedField]

/** 对应 Kotlin `ParseStatusEnum` */
export const BookmarkParseStatus = {
  PENDING: 'PENDING',
  SUCCESS: 'SUCCESS',
  UNREACHABLE: 'UNREACHABLE',
  ARCHIVED: 'ARCHIVED',
} as const
export type BookmarkParseStatus = (typeof BookmarkParseStatus)[keyof typeof BookmarkParseStatus]

/** 对应 Kotlin `DisplayMode` */
export const DisplayMode = {
  TILE: 'TILE',
  LIST: 'LIST',
} as const
export type DisplayMode = (typeof DisplayMode)[keyof typeof DisplayMode]

/** 对应 Kotlin `IconVerdict` */
export const IconVerdict = {
  IMAGE: 'IMAGE',
  MONOGRAM_QUALITY: 'MONOGRAM_QUALITY',
  MONOGRAM_SIZE: 'MONOGRAM_SIZE',
  NO_ASSET: 'NO_ASSET',
} as const
export type IconVerdict = (typeof IconVerdict)[keyof typeof IconVerdict]

/** 对应 Kotlin `LogoImgType` */
export const LogoImgType = {
  ICO: 'ICO',
  IMG: 'IMG',
} as const
export type LogoImgType = (typeof LogoImgType)[keyof typeof LogoImgType]

/** 对应 Kotlin `LogoSizeEnum` */
export const LogoSizeEnum = {
  SMALL: 'SMALL',
  MEDIUM: 'MEDIUM',
  LARGE: 'LARGE',
  XLARGE: 'XLARGE',
} as const
export type LogoSizeEnum = (typeof LogoSizeEnum)[keyof typeof LogoSizeEnum]

/** 对应 Kotlin `PingOutcome` */
export const PingOutcome = {
  ALIVE: 'ALIVE',
  DEAD: 'DEAD',
  UNKNOWN: 'UNKNOWN',
} as const
export type PingOutcome = (typeof PingOutcome)[keyof typeof PingOutcome]

/** 对应 Kotlin `ShareStatus` */
export const ShareStatus = {
  NORMAL: 'NORMAL',
  EXPIRED: 'EXPIRED',
  CANCELLED: 'CANCELLED',
  ADMIN_TAKEDOWN: 'ADMIN_TAKEDOWN',
  REVIEW_REJECTED: 'REVIEW_REJECTED',
} as const
export type ShareStatus = (typeof ShareStatus)[keyof typeof ShareStatus]

/** 对应 Kotlin `SiteLockedField` */
export const SiteLockedField = {
  BRAND_NAME: 'BRAND_NAME',
  SHORT_NAME: 'SHORT_NAME',
} as const
export type SiteLockedField = (typeof SiteLockedField)[keyof typeof SiteLockedField]
