<script lang="ts" setup>
import { computed } from "vue";

import { AuthPageLayout } from "@vben/layouts";
import { preferences } from "@vben/preferences";

const appName = computed(() => preferences.app.name);
const logo = computed(() => preferences.logo.source);
const logoDark = computed(() => preferences.logo.sourceDark);
</script>

<template>
  <AuthPageLayout :app-name="appName" :logo="logo" :logo-dark="logoDark" :page-description="'高效管理书签、书签集与用户数据'" :page-title="'书签鸭 · 后台管理系统'" :toolbar="false">
    <!-- 自定义工具栏 -->
    <!-- <template #toolbar></template> -->
  </AuthPageLayout>
</template>
