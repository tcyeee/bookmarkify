<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
<script  lang="ts" setup>
// const userStore = useBaseStore();
// const sysStore = sysBaseStore();

onMounted(() => {
  // /* 如果用户没有登录,则通过指纹登陆 */
  // if (!userStore.hasLogin) userStore.fingerPrintInit();
  // // 注册基础按键功能
  // sysStore.registerKeyEvent("Escape", "/setting", () => navigateTo("/"));
  // // 打开键盘监听
  // window.addEventListener("keydown", handleKeyDown);
});

// 阻止右键菜单
function click(e: MouseEvent) {
  e.preventDefault();
}

// 监听按键
function handleKeyDown(event: KeyboardEvent) {
  console.log("================================");
  // sysStore.triggerKeyEvent(event.code, useRoute().path);
}
</script>