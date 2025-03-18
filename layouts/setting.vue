<template>
  <div class="bg-gray-200 h-screen pt-[4rem]">
    <div class="flex gap-10 sm:px-[1vw]  md:px-[5vw]  lg:px-[10vw] xl:px-[20vw] pb-[10vh] bg-gray-200">
      <div>
        <NuxtLink to="/">
          <div class="cy-btn cy-btn-wide cy-btn-lg rounded-xl mb-[2rem]">返回</div>
        </NuxtLink>

        <!-- 侧边栏 -->
        <ul class="cy-menu bg-white rounded-xl w-[10rem]">
          <li v-for="(item,index) in pages">
            <a class="text-gray-400 text-lg font-medium" @click="selectOne(index)" :class="sysStore.settingTabIndex==index?'cy-menu-active':''">
              <span>{{item}}</span>
            </a>
          </li>
        </ul>
      </div>
      <NuxtPage class="flex-1 rounded-xl min-h-screen " />
    </div>
    <LayoutFooter class="fixed bottom-0" />
  </div>
</template>

<script lang="ts" setup>
const sysStore = useSysStore();
const pages = ["个人资料", "书签管理", "偏好设置"];

function selectOne(index: number) {
  sysStore.settingTabIndex = index;
}
</script>