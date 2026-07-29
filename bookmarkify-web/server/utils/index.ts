import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { nanoid } from 'nanoid'
import { createAvatar } from '@dicebear/core'
import { adventurerNeutral } from '@dicebear/collection'

export { md5 } from 'js-md5'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(...inputs))
}

export function randomId() {
  return nanoid().slice(0, 8)
}

/**
 * 用 DiceBear adventurer-neutral 按 seed 生成头像，返回可上传的 SVG File 与可预览的 dataUri。
 * 仅客户端调用（依赖 File / Blob）。相同 seed 结果可复现（uid 用于默认头像，nanoid 用于随机头像）。
 */
export function createDicebearAvatar(seed: string): { file: File; dataUri: string } {
  const svg = createAvatar(adventurerNeutral, { seed }).toString()
  const file = new File([svg], 'avatar.svg', { type: 'image/svg+xml' })
  // 自行拼接 data URI：DiceBear v9 的 toDataUri() 产出 `;utf8,` 这种非法 media-type 参数，
  // 会被浏览器当作 text/plain，导致 <img> 无法渲染。改用标准的 charset=utf-8 形式。
  const dataUri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  return { file, dataUri }
}

/**
 * 用 DiceBear adventurer-neutral 按 seed 生成确定性头像 SVG File。
 * seed 一般传用户 uid，保证可复现。
 */
export function generateDefaultAvatarFile(seed: string): File {
  return createDicebearAvatar(seed).file
}

const NICK_ADJ = ['悠闲', '快乐', '迷糊', '勇敢', '安静', '热情', '慵懒', '机智', '温柔', '倔强', '神秘', '调皮', '优雅', '呆萌', '潇洒', '元气']
const NICK_NOUN = ['小熊猫', '柯基', '水豚', '旅人', '喵星人', '北极熊', '刺猬', '海獭', '狐狸', '考拉', '企鹅', '章鱼', '仓鼠', '麋鹿', '鲸鱼', '兔子']

/** 生成一个「形容词 + 的 + 动物」风格的随机昵称，长度可控（≤ 20）。 */
export function randomNickName(): string {
  const pick = <T>(arr: T[]): T => arr[Math.floor(Math.random() * arr.length)]
  return `${pick(NICK_ADJ)}的${pick(NICK_NOUN)}`
}

