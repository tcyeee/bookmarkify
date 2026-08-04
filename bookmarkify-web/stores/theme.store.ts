import { defineStore } from 'pinia'

export type ThemeMode = 'system' | 'light' | 'dark'

/**
 * 主题偏好。
 *
 * 单独开一个 store 而不是塞进 sys.store：后者存着 `keyEvents: Map` 和几个定时器句柄，
 * 一旦开持久化，这些非序列化值会以 `{}` 的形态被 JSON 读回来 —— 而 `{}` 是真值，
 * 所有 `if (this.emailCountdownTimer)` 之类的判断会永久卡死（见 CLAUDE.md 里 `pick` 那一条）。
 *
 * 也不放进 preference.store：那份偏好是随账号走的服务端数据，而"用浅色还是深色"是
 * 设备级的选择 —— 同一个账号在台式机和手机上想要的答案经常不一样，且它不该等一个网络请求。
 */
export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: 'system' as ThemeMode,
  }),

  actions: {
    setMode(mode: ThemeMode) {
      this.mode = mode
    },

    /** 命令面板的「切换主题」用：在浅/深之间来回切，跟随系统时以当前实际呈现的效果为准取反 */
    toggle(prefersDark: boolean) {
      const isDark = this.mode === 'system' ? prefersDark : this.mode === 'dark'
      this.mode = isDark ? 'light' : 'dark'
    },
  },

  // 显式指定 localStorage，理由同 auth.store：本项目未配置模块级 storage 时，
  // 裸 `persist: true` 会回退到 cookies。
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
