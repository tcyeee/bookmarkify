<template>
  <div>
    <NuxtPage />
    <el-dialog v-model="dialogLogin" width="500" center>
      <div>1111</div>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { DialogStatus } from "~/stores/types";

const sysStore = useSysStore();
const dialogLogin = ref(false);

watch(dialogLogin, (newValue: any) => {
  sysStore.updateDialogStatus(DialogStatus.LOGIN, newValue);
});
</script>