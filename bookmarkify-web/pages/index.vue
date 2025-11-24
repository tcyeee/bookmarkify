<template>
  <div class="overflow-hidden select-none">
    <!-- 内容信息 -->
    <div class="absolute w-screen h-screen z-99">

      <!-- 时间信息 -->
      <HomeTimeStr :class="classFadeDate" @click="sceneToggle()" class="text-[5rem] text-white opacity-80 text-center font-bold" />

      <!-- 内容页面 -->
      <HomeMain :status="data.fade" class="absolute left-[10%] right-[10%] top-[30%]" />

      <!-- 去设置 -->
      <div v-if="!data.fade" class="fixed bottom-0">
        <NuxtLink to="/setting">
          <div class="cy-btn cy-btn-ghost  mb-5 ml-5">
            <span class="icon--setting" />
          </div>
        </NuxtLink>
      </div>
    </div>

    <!-- 背景信息 -->
    <div :class="classFadeBg" class="index-base" />
  </div>
</template>

<script lang="ts" setup>
const sysStore = useSysStore();
const storeBookmark = useBookmarkStore();

const data = reactive<{
  fade: boolean;
  duringAnimate: boolean;
}>({
  fade: true,
  duringAnimate: false,
});

// watchEffect(() => {
console.log("======");

// if (data.fade) storeBookmark.update();
// });

onMounted(() => {
  sysStore.registerKeyEvent("Space", "/", () => sceneToggle());
  sysStore.registerKeyEvent("Escape", "/", () => sceneToggle());
  // storeBookmark.update();
});

const classFadeBg = computed(() => {
  return {
    "animate-fade-bg-in": data.fade,
    "animate-fade-bg-out": !data.fade,
  };
});

const classFadeDate = computed(() => {
  return {
    "animate-fade-date-in": data.fade,
    "animate-fade-date-out": !data.fade,
  };
});

// 开关APP显示
function sceneToggle() {
  data.fade = !data.fade;
}
</script>

<style scoped>
.index-base {
  height: calc(100vh + 20px);
  margin: -10px;
  overflow: hidden;

  background-image: linear-gradient(135deg, #a69f9f, #c1baba, #8f9ea6);
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

