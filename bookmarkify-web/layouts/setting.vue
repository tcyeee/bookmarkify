<template>
  <CommonHeader class="fixed top-0 z-1" />
  <div class="bg-gray-200 h-screen pt-28">
    <div class="flex gap-10 sm:px-[1vw] md:px-[2vw] lg:px-[4vw] xl:px-[6vw] pb-[10vh] bg-gray-200">
      <div>
        <NuxtLink to="/">
          <div class="cy-btn cy-btn-wide cy-btn-lg rounded-xl mb-8">返回</div>
        </NuxtLink>

        <!-- 侧边栏 -->
        <ul class="cy-menu bg-white rounded-xl w-40">
          <li v-for="(item, index) in pages">
            <a
              class="text-gray-400 text-lg font-medium"
              @click="selectOne(index)"
              :class="sysStore.settingTabIndex == index ? 'cy-menu-active' : ''">
              <span>{{ item }}</span>
            </a>
          </li>
        </ul>
      </div>
      <NuxtPage class="flex-1 rounded-xl min-h-screen" />
    </div>
    <CommonFooter />
  </div>
</template>

<script lang="ts" setup>
const sysStore = useSysStore()
const pages = ['个人资料', '书签管理', '偏好设置']

function selectOne(index: number) {
  sysStore.settingTabIndex = index
}
</script>
