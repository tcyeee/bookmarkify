import type { UserFile } from '@typing'

/**
 * 根据环境获取文件服务基础 URL
 * 开发环境：返回项目根目录下的 upload 目录路径
 * 生产环境：返回生产环境的文件服务 URL
 */
function getFileServiceBaseUrl(env?: String): string {
  const localUrl = '/upload'
  const onlineUrl = 'https://file.bookmarkify.cc'
  if (env) return env === 'LOCAL' ? localUrl : onlineUrl

  const isDev = process.env.NODE_ENV === 'development' || process.env.NUXT_ENV === 'development'
  return isDev ? localUrl : onlineUrl
}

export const imageConfig = {
  /* 默认头像路径 */
  defaultAvatarPath: '/avatar/default.png',
  /* 图片大小限制（字节）  */
  maxImageSize: 5 * 1024 * 1024,
}

/**
 *
 * @param userFile UserFile对象
 * 示例:
 * {
 *   "id": "7bfde455-1310-4e5b-a520-f93157f61f3f",
 *   "uid": "1bf153c2-08e2-4200-b605-8a40af8ab586",
 *   "environment": "LOCAL",
 *   "originName": "IMG_7210.jpg",
 *   "currentName": "avatar/c100782c-de9c-4c58-a72e-b47dba08bf36.jpg",
 *   "type": "AVATAR_IMAGE",
 *   "size": 27915,
 *   "createTime": "2025-12-07T17:11:23.666985",
 * }
 * @returns 图片的完整 URL
 */
export function getImageUrlByUserFile(userFile: UserFile): string {
  return `${getFileServiceBaseUrl(userFile.environment)}/${userFile.currentName}`
}

/**
 * 获取完整的图片 URL
 * @param path 图片路径（相对路径或完整 URL）
 * @returns 完整的图片 URL
 */
export function getImageUrl(path: string | null | undefined): string {
  if (!path) return imageConfig.defaultAvatarPath

  // 如果是 data URL（base64 编码的图片），直接返回
  if (path.startsWith('data:')) return path

  // 如果已经是完整 URL，直接返回
  if (path.startsWith('http://') || path.startsWith('https://')) return path

  // 移除 path 的前导斜杠，避免双斜杠
  const cleanPath = path.startsWith('/') ? path.slice(1) : path
  return `${getFileServiceBaseUrl()}/${cleanPath}`
}

/**
 * 获取头像 URL
 * @param avatarPath 头像路径（相对路径或完整 URL）
 * @returns 头像的完整 URL，如果为空则返回默认头像
 */
export function getAvatarUrl(avatarPath: string | null | undefined): string {
  if (!avatarPath) return imageConfig.defaultAvatarPath

  return getImageUrl(avatarPath)
}
