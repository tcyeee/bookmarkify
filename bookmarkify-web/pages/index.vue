<template>
  <div class="overflow-hidden select-none w-screen h-screen">
    <!-- 内容信息 -->
    <div class="absolute inset-0 z-1 flex flex-col items-center min-h-0">
      <!-- 时间信息 -->
      <HomeTimeStr
        :class="classFadeDate"
        @click="sceneToggle()"
        class="my-6 sm:mt-10 text-[5rem] text-white opacity-80 text-center font-bold z-0 px-4" />

      <!-- 内容页面 -->
      <div class="flex-1 min-h-0 w-full flex justify-center items-start overflow-hidden">
        <Transition name="fade-main">
          <div v-if="data.launchpadView"
            class="launchpad-scroll w-full max-w-6xl px-4 sm:px-6 lg:px-12">
            <LaunchpadList />
          </div>
        </Transition>
      </div>

      <!-- 去设置 -->
      <div class="fixed left-0 bottom-0 px-4 pb-4">
        <SettingButton @home-click="sceneToggle()" />
      </div>
    </div>

    <!-- 背景信息 -->
    <div :class="classFadeBg" class="index-base" :style="backgroundStyle" />
  </div>
</template>

<script lang="ts" setup>
import { getImageUrl, getImageUrlByUserFile } from '@config'
import { BackgroundType, type BacSettingVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const sysStore = useSysStore()
const preferenceStore = usePreferenceStore()

const data = reactive<{
  launchpadView: boolean // 是否显示APP
  duringAnimate: boolean // 是否正在动画
}>({
  launchpadView: false,
  duringAnimate: false,
})

const minimalMode = computed(() => preferenceStore.preference?.minimalMode ?? false)

watch(
  minimalMode,
  (val) => {
    if (val) data.launchpadView = true
  },
  { immediate: true },
)

onMounted(async () => {
  sysStore.registerKeyEvent('Space', '/', () => sceneToggle('Space'))
  sysStore.registerKeyEvent('Escape', '/', () => sceneToggle('Escape'))
})

const classFadeBg = computed(() => {
  return {
    'animate-fade-bg-in': data.launchpadView,
    'animate-fade-bg-out': !data.launchpadView,
  }
})

const classFadeDate = computed(() => {
  return {
    'animate-fade-date-in': data.launchpadView,
    'animate-fade-date-out': !data.launchpadView,
  }
})

const bacSetting = computed<BacSettingVO | undefined>(() => preferenceStore.preference?.imgBacShow ?? undefined)
const cachedBackgroundImage = computed(() => preferenceStore.backgroundImageDataUrl)

// 背景样式
const backgroundStyle = computed(() => {
  const config = bacSetting.value

  if (config && config.type === BackgroundType.GRADIENT) {
    const colors = config.bacColorGradient!.join(',')
    const direction = config.bacColorDirection || 135
    return { backgroundImage: `linear-gradient(${direction}deg, ${colors})` }
  }

  if (config && config.type === BackgroundType.IMAGE && config.bacImgFile) {
    const cached = cachedBackgroundImage.value
    if (cached) return { backgroundImage: `url(${cached})` }
    const file = config.bacImgFile as any
    const imageUrl = file.fullName ?? (file.environment && file.currentName ? getImageUrlByUserFile(file) : getImageUrl(file.currentName))
    return imageUrl ? { backgroundImage: `url(${imageUrl})` } : {}
  }

  return { backgroundImage: 'linear-gradient(135deg, #a69f9f, #c1baba, #8f9ea6)' }
})

// [ESC] 开关APP显示
function sceneToggle(key?: string) {
  // 如果正好处于添加书签窗口，则仅关闭添加窗口
  if (sysStore.addBookmarkDialogVisible) {
    // 如果是按下空格，则不管
    if (key === 'Space') return
    sysStore.addBookmarkDialogVisible = false
    return
  }
  if (minimalMode.value) {
    data.launchpadView = true
    return
  }
  data.launchpadView = !data.launchpadView
}
</script>

<style scoped>
.index-base {
  height: calc(100vh + 20px);
  margin: -10px;
  overflow: hidden;
  background-position: center;
  background-repeat: no-repeat;
  background-size: cover;
  z-index: -1;
}

.launchpad-scroll {
  position: relative;
  height: calc(100vh - 9rem);
  overflow-y: auto;
  scrollbar-width: none; /* Firefox 隐藏滚动条 */
  -ms-overflow-style: none; /* IE/Edge 隐藏滚动条 */
  padding: 90px 2rem 80px 2rem;
  box-sizing: border-box;
  overscroll-behavior: contain; /* 防止滚动链到页面本身 */
  -webkit-mask-image: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0),
    rgba(0, 0, 0, 0) 48px,
    rgba(0, 0, 0, 1) 88px,
    rgba(0, 0, 0, 1) calc(100% - 88px),
    rgba(0, 0, 0, 0) calc(100% - 48px),
    rgba(0, 0, 0, 0)
  );
  mask-image: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0),
    rgba(0, 0, 0, 0) 48px,
    rgba(0, 0, 0, 1) 88px,
    rgba(0, 0, 0, 1) calc(100% - 88px),
    rgba(0, 0, 0, 0) calc(100% - 48px),
    rgba(0, 0, 0, 0)
  );
}

.launchpad-scroll::-webkit-scrollbar {
  display: none; /* WebKit 隐藏滚动条 */
}

@media (min-width: 640px) {
  .launchpad-scroll {
    height: calc(100vh - 10rem);
  }
}

@media (min-width: 1024px) {
  .launchpad-scroll {
    height: calc(100vh - 11rem);
  }
}

.transition-filter {
  transition: filter 350ms;
}

.fade_bg {
  filter: brightness(0.5) blur(4px);
}
.fade_date {
  font-size: 10rem !important;
  margin-top: 3rem !important;
}

/* 定义缩放和移动的动画 */
@keyframes index-bg-in {
  0% {
    transform: scale(1);
    filter: brightness(0.8) blur(0px);
  }
  100% {
    transform: scale(1.2);
    filter: brightness(0.4) blur(5px);
  }
}

@keyframes index-bg-out {
  0% {
    transform: scale(1.2);
    filter: brightness(0.4) blur(5px);
  }
  100% {
    transform: scale(1);
    filter: brightness(0.8) blur(0px);
  }
}

@keyframes index-date-in {
  0% {
    transform: scale(2.2) translateY(10vh);
  }
  100% {
    transform: scale(1) translateY(8vh);
  }
}
@keyframes index-date-out {
  0% {
    transform: scale(1) translateY(8vh);
  }
  100% {
    transform: scale(2.2) translateY(10vh);
  }
}

/* 播放APP动画-IN */
.animate-fade-bg-in {
  animation: index-bg-in 0.5s forwards;
}

/* 播放APP动画-OUT */
.animate-fade-bg-out {
  animation: index-bg-out 0.5s forwards;
}

/* 播放APP动画-IN */
.animate-fade-date-in {
  animation: index-date-in 0.3s forwards;
}

/* 播放APP动画-OUT */
.animate-fade-date-out {
  animation: index-date-out 0.3s forwards;
}

.fade-main-enter-active,
.fade-main-leave-active {
  transition: all 100ms ease-in-out;
}

.fade-main-enter-from,
.fade-main-leave-to {
  transform: scale(1.2);
  opacity: 0;
}
</style>
