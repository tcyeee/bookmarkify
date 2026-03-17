<template>
  <div class="fixed bottom-6 left-6 z-40 flex flex-col items-start gap-2">
    <!-- 设置面板：从左下角展开 -->
    <Transition name="settings-expand">
      <div
        v-if="isOpen"
        class="settings-panel flex overflow-hidden rounded-2xl bg-base-100 shadow-2xl ring-1 ring-black/10"
        :style="panelStyle">
        <!-- 左侧导航 -->
        <aside class="flex w-48 shrink-0 flex-col gap-1 border-r border-base-200 bg-base-100 p-3">
          <p class="mb-1 px-2 text-[10px] font-semibold uppercase tracking-widest text-base-content/35">设置</p>
          <ul class="flex flex-col gap-0.5">
            <li v-for="tab in tabs" :key="tab.value">
              <button
                class="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors"
                :class="
                  activeTab === tab.value
                    ? 'bg-base-200 text-base-content'
                    : 'text-base-content/55 hover:bg-base-200/60 hover:text-base-content'
                "
                @click="activeTab = tab.value">
                <span :class="['shrink-0', tab.icon]" />
                {{ tab.label }}
              </button>
            </li>
          </ul>
        </aside>

        <!-- 右侧内容 -->
        <main class="flex-1 overflow-y-auto p-5">
          <Transition name="fade-panel" mode="out-in">
            <component :is="currentComponent" :key="activeTab" />
          </Transition>
        </main>
      </div>
    </Transition>

    <!-- 触发按钮 -->
    <button
      class="cy-btn cy-btn-circle cy-btn-ghost bg-black/20 text-white backdrop-blur-sm hover:bg-black/35 transition-all"
      :class="isOpen ? 'rotate-[30deg]' : ''"
      title="设置"
      @click="toggle">
      <span class="icon--memory-settings icon-size-22" />
    </button>
  </div>

  <!-- 点击外部关闭的透明遮罩 -->
  <Transition name="fade-overlay">
    <div v-if="isOpen" class="fixed inset-0 z-30" @click="close" />
  </Transition>
</template>

<script lang="ts" setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import AccountProfile from '~/components/setting/account/AccountProfile.vue'
import SettingBookmarkManage from '~/components/setting/BookmarkManage.vue'
import BackgroundSettings from '~/components/setting/BackgroundSettings.vue'
import PreferenceSettings from '~/components/setting/PreferenceSettings.vue'

const isOpen = ref(false)
const activeTab = ref(0)

const tabs = [
  { value: 0, label: '个人资料', icon: 'icon--memory-account-box icon-size-22' },
  { value: 1, label: '书签管理', icon: 'icon--memory-application-code icon-size-22' },
  { value: 2, label: '主页背景', icon: 'icon--memory-cloud icon-size-22' },
  { value: 3, label: '偏好设置', icon: 'icon--memory-toggle-switch-off icon-size-22' },
]

const settingComponents = [AccountProfile, SettingBookmarkManage, BackgroundSettings, PreferenceSettings] as const
const currentComponent = computed(() => settingComponents[activeTab.value] ?? settingComponents[0])

/** 面板尺寸随视口自适应 */
const panelStyle = computed(() => ({
  width: 'min(760px, calc(100vw - 3rem))',
  height: 'min(520px, calc(100vh - 6rem))',
}))

function toggle() {
  isOpen.value = !isOpen.value
}

function close() {
  isOpen.value = false
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
/* 从左下角展开：以 bottom-left 为原点缩放 */
.settings-expand-enter-active {
  transition: transform 280ms cubic-bezier(0.32, 0.72, 0, 1), opacity 200ms ease;
  transform-origin: bottom left;
}
.settings-expand-leave-active {
  transition: transform 200ms cubic-bezier(0.4, 0, 1, 1), opacity 160ms ease;
  transform-origin: bottom left;
}
.settings-expand-enter-from,
.settings-expand-leave-to {
  transform: scale(0.6);
  opacity: 0;
}

/* 面板内容切换 */
.fade-panel-enter-active,
.fade-panel-leave-active {
  transition: opacity 120ms ease, transform 120ms ease;
}
.fade-panel-enter-from,
.fade-panel-leave-to {
  opacity: 0;
  transform: translateY(5px);
}

/* 背景遮罩淡入 */
.fade-overlay-enter-active,
.fade-overlay-leave-active {
  transition: opacity 200ms ease;
}
.fade-overlay-enter-from,
.fade-overlay-leave-to {
  opacity: 0;
}
</style>
