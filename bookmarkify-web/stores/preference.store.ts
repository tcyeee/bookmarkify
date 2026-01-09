import { defineStore } from 'pinia'
import {
    BackgroundType,
    BookmarkImageSize,
    BookmarkLayoutMode,
    BookmarkOpenMode,
    PageTurnMode,
    type BacGradientVO,
    type BacSettingVO,
    type UserFile,
    type UserPreference,
} from '@typing'
import {
    defaultBackgrounds,
    myBackgrounds,
    queryUserPreference,
    updateUserPreference,
} from '@api'
import { getImageUrl, getImageUrlByUserFile } from '@config/image.config'

export const usePreferenceStore = defineStore('preference', {
    state: () => ({
        // 用户偏好数据（若未拉取则为 undefined，拉取失败为 null）
        preference: undefined as UserPreference | null | undefined,
        // 用户头像文件
        avatar: undefined as UserFile | undefined,
        // 背景图片的 DataURL 缓存，优先从 localStorage 读取
        backgroundImageDataUrl: (import.meta.client ? localStorage.getItem('backgroundImageDataUrl') : null) as string | null,
        // 平台提供的默认背景资源
        defaultImageBackgroundsList: [] as UserFile[],
        defaultGradientBackgroundsList: [] as BacGradientVO[],
        // 用户自定义背景资源
        userImageBackgroundsList: [] as UserFile[],
        userGradientBackgroundsList: [] as BacGradientVO[],
        // 是否正在上传自定义背景图
        imageBackgroundUploading: false as boolean,
    }),

    actions: {
        async fetchPreference(): Promise<UserPreference | null> {
            try {
                // 拉取并缓存用户偏好，顺带更新背景缓存
                const result = await queryUserPreference()
                this.preference = result ?? null
                this.handleBackgroundCache(this.preference?.imgBacShow)
                return this.preference
            } catch (err: any) {
                ElMessage.error(err?.message || '获取偏好设置失败')
                throw err
            }
        },

        upsertPreferenceBackground(setting: BacSettingVO | null | undefined) {
            const base = this.preference ?? createDefaultPreference()
            this.preference = { ...base, imgBacShow: setting ?? undefined }
            // 同步本地缓存的背景图
            this.handleBackgroundCache(setting ?? undefined)
        },

        async savePreference(preference: UserPreference): Promise<boolean> {
            try {
                const ok = await updateUserPreference(preference)
                if (ok) this.preference = { ...preference }
                return ok
            } catch (err: any) {
                ElMessage.error(err?.message || '保存偏好设置失败')
                throw err
            }
        },

        async refreshBackgroundConfig() {
            try {
                // 并行获取默认背景与个人背景列表
                const [data, mine] = await Promise.all([defaultBackgrounds(), myBackgrounds()])
                this.defaultGradientBackgroundsList = data.gradients ?? []
                this.defaultImageBackgroundsList = data.images ?? []
                this.userGradientBackgroundsList = mine.gradients ?? []
                this.userImageBackgroundsList = mine.images ?? []
            } catch (error) {
                console.error('[PREFERENCE] refreshBackgroundConfig failed', error)
            }
        },

        setImageBackgroundUploading(value: boolean) {
            this.imageBackgroundUploading = value
        },

        async cacheBackgroundImage(file?: any) {
            if (!import.meta.client || !file) return
            // 根据传入文件信息解析真实资源 URL
            const url: string | null =
                file.fullName ??
                (file.environment && file.currentName ? getImageUrlByUserFile(file) : null) ??
                (file.currentName ? getImageUrl(file.currentName) : null)
            if (!url) return

            try {
                // 将背景图片转为 DataURL 缓存，避免频繁请求
                const resp = await fetch(url, { cache: 'no-cache' })
                const blob = await resp.blob()
                await new Promise<void>((resolve, reject) => {
                    const reader = new FileReader()
                    reader.onloadend = () => {
                        this.backgroundImageDataUrl = reader.result as string
                        localStorage.setItem('backgroundImageDataUrl', this.backgroundImageDataUrl)
                        resolve()
                    }
                    reader.onerror = () => reject(reader.error)
                    reader.readAsDataURL(blob)
                })
            } catch (error) {
                console.error('[PREFERENCE] cacheBackgroundImage failed', error)
            }
        },

        clearBackgroundImageCache() {
            this.backgroundImageDataUrl = null
            if (import.meta.client) localStorage.removeItem('backgroundImageDataUrl')
        },

        handleBackgroundCache(setting?: BacSettingVO | null) {
            if (setting?.type === BackgroundType.IMAGE && setting.bacImgFile) {
                // 仅图片背景需要落盘缓存
                void this.cacheBackgroundImage(setting.bacImgFile)
            } else {
                this.clearBackgroundImageCache()
            }
        },
    },

    persist: { storage: piniaPluginPersistedstate.localStorage() },
})

function createDefaultPreference(): UserPreference {
    return {
        bookmarkOpenMode: BookmarkOpenMode.CURRENT_TAB,
        minimalMode: false,
        bookmarkLayout: BookmarkLayoutMode.DEFAULT,
    bookmarkImageSize: BookmarkImageSize.MEDIUM,
        showTitle: true,
        pageMode: PageTurnMode.VERTICAL_SCROLL,
        imgBacShow: undefined,
    }
}

