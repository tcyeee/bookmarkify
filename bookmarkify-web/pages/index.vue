<template>
  <div class="overflow-hidden select-none">
    <!-- 内容信息 -->
    <div class="absolute w-screen h-screen z-1">
      <!-- 时间信息 -->
      <HomeTimeStr
        :class="classFadeDate"
        @click="sceneToggle()"
        class="text-[5rem] text-white opacity-80 text-center font-bold z-0" />

      <!-- 内容页面 -->
      <Home :status="data.appView" class="absolute left-[10%] right-[10%] top-[30%]" />

      <!-- 去设置 -->
      <div class="fixed bottom-0">
        <SettingButton @home-click="sceneToggle()" />
      </div>
    </div>

    <!-- 背景信息 -->
    <div :class="classFadeBg" class="index-base" :style="backgroundStyle" />
  </div>
</template>

<script lang="ts" setup>
import { getImageUrlByUserFile } from '@config'
import { BackgroundType, type BacSettingVO } from '@typing'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const sysStore = useSysStore()
const userStore = useUserStore()

const data = reactive<{
  appView: boolean // 是否显示APP
  duringAnimate: boolean // 是否正在动画
}>({
  appView: false,
  duringAnimate: false,
})

onMounted(async () => {
  sysStore.registerKeyEvent('Space', '/', () => sceneToggle('Space'))
  sysStore.registerKeyEvent('Escape', '/', () => sceneToggle('Escape'))
})

const classFadeBg = computed(() => {
  return {
    'animate-fade-bg-in': data.appView,
    'animate-fade-bg-out': !data.appView,
  }
})

const classFadeDate = computed(() => {
  return {
    'animate-fade-date-in': data.appView,
    'animate-fade-date-out': !data.appView,
  }
})

// 背景样式
const backgroundStyle = computed(() => {
  const config: BacSettingVO | undefined = userStore.account?.userSetting?.bacSetting

  if (config && config.type === BackgroundType.GRADIENT) {
    const colors = config.bacColorGradient!.join(',')
    const direction = config.bacColorDirection || 135
    return { backgroundImage: `linear-gradient(${direction}deg, ${colors})` }
  }

  if (config && config.type === BackgroundType.IMAGE && config.bacImgFile) {
    const imageUrl = getImageUrlByUserFile(config.bacImgFile)
    return { backgroundImage: `url(${imageUrl})` }
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
  data.appView = !data.appView
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
</style>
