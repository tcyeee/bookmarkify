/**
 * 「这条书签会不会被抓取」——后台侧的判断。
 *
 * 与后端 `WebsiteParser.classifyLinkType` / `ScrapeTargetGuard` 是同一条规则的镜像：
 * **只有域名才抓**，`localhost` / `127.0.0.1` / 裸 IP 一律不抓（见后端 ErrorType.E309）。
 *
 * 前端这份不是为了"校验"——真正的权威永远在后端，它会直接拒绝这类抓取请求并报错。
 * 这份存在的理由是**别让管理员点一个注定失败的按钮**：重抓、活性检测、AI 归类这些操作
 * 对本机/IP 书签永远不会有结果，把它们摆在那里可点，就是在制造"点了没反应"的困惑，
 * 而每点一次都会真的往后端发一次请求。
 *
 * 两种输入形态各有用途：
 * - {@link isScrapableUrl} 用于只有一个 URL 字符串的地方（scrapper 调用日志表）；
 * - 有书签行对象时直接读后端下发的 `linkType`，那是权威值，不要在前端重算。
 */

// 页面与站点两层共用同一个枚举（后端也是同一个 BookmarkLinkType），不另起一份
import type { SiteLinkType } from '#/api/site';

export const LINK_TYPE_LABEL: Record<SiteLinkType, string> = {
  DOMAIN: '域名',
  IP: 'IP 地址',
  LOCAL: '本机地址',
  OTHER: '其他',
};

/** 不抓取的理由，直接展示给管理员 */
export const LINK_TYPE_REASON: Record<SiteLinkType, string> = {
  DOMAIN: '',
  IP: '该地址是 IP 而非域名，指向的多半是某台机器上的内部服务，抓回来只会是登录页或 404',
  LOCAL: '该地址指向本机（localhost / 127.0.0.1），对我方服务器而言就是抓取容器自己',
  OTHER: '该地址不是一个可抓取的域名',
};

/** 这个链接类型会参与抓取吗（只有域名会）。 */
export function isScrapableType(type?: SiteLinkType | string): boolean {
  return type === 'DOMAIN';
}

/**
 * 从一个 URL 字符串判断能否抓取。规则与后端保持一致：
 * IP 字面量（含公网 IP、IPv6）与 localhost 家族都不抓。
 *
 * 解析不出来时返回 `false`：「认不出来」和「不是域名」在这里处置一致，都不该发请求。
 */
export function isScrapableUrl(url?: null | string): boolean {
  return linkTypeOfUrl(url) === 'DOMAIN';
}

export function linkTypeOfUrl(url?: null | string): SiteLinkType {
  const raw = (url || '').trim();
  if (!raw) return 'OTHER';
  let hostname: string;
  try {
    hostname = new URL(/^[a-z][\d+.a-z-]*:\/\//i.test(raw) ? raw : `https://${raw}`)
      .hostname;
  } catch {
    return 'OTHER';
  }
  if (!hostname) return 'OTHER';
  // URL.hostname 对 IPv6 返回带方括号的字面量（`[::1]`）
  const bare = hostname.replace(/^\[|]$/g, '').replace(/\.$/, '').toLowerCase();
  if (bare === 'localhost' || bare.endsWith('.localhost')) return 'LOCAL';
  if (bare === '127.0.0.1' || bare === '::1') return 'LOCAL';
  if (isIpLiteral(bare)) return 'IP';
  return bare.includes('.') ? 'DOMAIN' : 'OTHER';
}

function isIpLiteral(host: string): boolean {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    return host.split('.').every((part) => Number(part) <= 255);
  }
  // 粗判 IPv6：含冒号且只由十六进制字符与冒号组成
  return host.includes(':') && /^[\da-f:]+$/.test(host);
}
