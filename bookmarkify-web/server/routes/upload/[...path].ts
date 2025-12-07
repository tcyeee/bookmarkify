import { readFile } from 'fs/promises'
import { join, resolve } from 'path'
import { existsSync } from 'fs'

/**
 * 服务器路由：代理项目根目录下的 upload 文件
 * 仅在开发环境生效
 * 访问路径：/upload/avatar/xxx/xxx.jpg
 * 实际文件：项目根目录/upload/avatar/xxx/xxx.jpg
 * 
 * 生产环境使用外部文件服务：https://file.bookmarkify.cc
 */
export default defineEventHandler(async (event) => {
    // 仅在开发环境生效
    const isDev = process.env.NODE_ENV === 'development' || process.env.NUXT_ENV === 'development'
    if (!isDev) {
        throw createError({
            statusCode: 404,
            statusMessage: 'Not found'
        })
    }
    const path = getRouterParam(event, 'path')

    if (!path) {
        throw createError({
            statusCode: 400,
            statusMessage: 'Path is required'
        })
    }

    // 解析路径，防止路径遍历攻击
    const safePath = path.split('/').filter(Boolean).join('/')

    // 构建文件系统路径（项目根目录的 upload 目录）
    const projectRoot = resolve(process.cwd(), '..')
    const filePath = join(projectRoot, 'upload', safePath)

    // 确保文件路径在 upload 目录内（安全检查）
    const uploadDir = join(projectRoot, 'upload')
    if (!filePath.startsWith(uploadDir)) {
        throw createError({
            statusCode: 403,
            statusMessage: 'Access denied'
        })
    }

    // 检查文件是否存在
    if (!existsSync(filePath)) {
        throw createError({
            statusCode: 404,
            statusMessage: 'File not found'
        })
    }

    try {
        // 读取文件
        const fileBuffer = await readFile(filePath)

        // 根据文件扩展名设置 Content-Type
        const ext = filePath.split('.').pop()?.toLowerCase()
        const contentTypeMap: Record<string, string> = {
            'jpg': 'image/jpeg',
            'jpeg': 'image/jpeg',
            'png': 'image/png',
            'gif': 'image/gif',
            'webp': 'image/webp',
            'svg': 'image/svg+xml'
        }

        const contentType = contentTypeMap[ext || ''] || 'application/octet-stream'

        // 返回文件
        setHeader(event, 'Content-Type', contentType)
        setHeader(event, 'Cache-Control', 'public, max-age=31536000')

        return fileBuffer
    } catch (error: any) {
        throw createError({
            statusCode: 500,
            statusMessage: error.message || 'Failed to read file'
        })
    }
})

