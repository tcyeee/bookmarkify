/**
 * 根据环境获取文件服务基础 URL
 * 开发环境：返回项目根目录下的 upload 目录路径
 * 生产环境：返回生产环境的文件服务 URL
 */
function getFileServiceBaseUrl(): string {
    // 判断是否为开发环境
    const isDev = process.env.NODE_ENV === 'development' || process.env.NUXT_ENV === 'development'

    if (isDev) {
        // 开发环境：返回项目根目录下的 upload 目录
        return '/upload'
    }

    // 生产环境：返回生产环境的文件服务 URL
    return 'https://file.bookmarkify.cc'
}

export const imageConfig = {
    /**
     * 文件服务基础 URL
     * 用于拼接相对路径的图片 URL
     * 根据环境自动切换：
     * - 开发环境：/upload（项目根目录下的 upload 目录）
     * - 生产环境：https://file.bookmarkify.cc
     */
    get fileServiceBaseUrl() {
        return getFileServiceBaseUrl()
    },

    /**
     * 默认头像路径
     * 当用户未设置头像时使用
     */
    defaultAvatarPath: '/avatar/default.png',

    /**
     * 图片大小限制（字节）
     * 默认限制为 5MB
     */
    maxImageSize: 5 * 1024 * 1024, // 5MB
}

/**
 * 获取完整的图片 URL
 * @param path 图片路径（相对路径或完整 URL）
 * @returns 完整的图片 URL
 */
export function getImageUrl(path: string | null | undefined): string {
    if (!path) {
        return imageConfig.defaultAvatarPath
    }

    // 如果是 data URL（base64 编码的图片），直接返回
    if (path.startsWith('data:')) {
        return path
    }

    // 如果已经是完整 URL，直接返回
    if (path.startsWith('http://') || path.startsWith('https://')) {
        return path
    }

    // 拼接文件服务基础 URL
    // 移除 path 的前导斜杠，避免双斜杠
    const cleanPath = path.startsWith('/') ? path.slice(1) : path
    return `${imageConfig.fileServiceBaseUrl}/${cleanPath}`
}

/**
 * 获取头像 URL
 * @param avatarPath 头像路径（相对路径或完整 URL）
 * @returns 头像的完整 URL，如果为空则返回默认头像
 */
export function getAvatarUrl(avatarPath: string | null | undefined): string {
    if (!avatarPath) {
        return imageConfig.defaultAvatarPath
    }

    return getImageUrl(avatarPath)
}

