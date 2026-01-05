import { defineStore } from 'pinia'
import {
    BackgroundType,
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
        preference: undefined as UserPreference | null | undefined,
        avatar: undefined as UserFile | undefined,
        backgroundImageDataUrl: (import.meta.client ? localStorage.getItem('backgroundImageDataUrl') : null) as string | null,
        defaultImageBackgroundsList: [] as UserFile[],
        defaultGradientBackgroundsList: [] as BacGradientVO[],
        userImageBackgroundsList: [] as UserFile[],
        userGradientBackgroundsList: [] as BacGradientVO[],
        imageBackgroundUploading: false as boolean,
    }),

    actions: {
        async fetchPreference(): Promise<UserPreference | null> {
            try {
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
            const url: string | null =
                file.fullName ??
                (file.environment && file.currentName ? getImageUrlByUserFile(file) : null) ??
                (file.currentName ? getImageUrl(file.currentName) : null)
            if (!url) return

            try {
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
                void this.cacheBackgroundImage(setting.bacImgFile)
            } else {
                this.clearBackgroundImageCache()
            }
        },
    },

    persist: true,
})

function createDefaultPreference(): UserPreference {
    return {
        bookmarkOpenMode: BookmarkOpenMode.CURRENT_TAB,
        minimalMode: false,
        bookmarkLayout: BookmarkLayoutMode.DEFAULT,
        showTitle: true,
        pageMode: PageTurnMode.VERTICAL_SCROLL,
        imgBacShow: undefined,
    }
}

