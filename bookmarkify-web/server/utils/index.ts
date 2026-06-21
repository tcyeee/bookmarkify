import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { nanoid } from 'nanoid'
import { md5 } from 'js-md5'
import { createAvatar } from '@dicebear/core'
import { adventurerNeutral } from '@dicebear/collection'

export { md5 }

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(...inputs))
}

export function randomId() {
  return nanoid().slice(0, 8)
}

/**
 * 用 DiceBear adventurer-neutral 按 seed 生成确定性头像 SVG File。
 * 仅客户端调用（依赖 File / Blob）。seed 一般传用户 uid，保证可复现。
 */
export function generateDefaultAvatarFile(seed: string): File {
  const svg = createAvatar(adventurerNeutral, { seed }).toString()
  return new File([svg], 'avatar.svg', { type: 'image/svg+xml' })
}

