<script lang="ts" setup>
import type { UserAdminVO } from "#/api/user-manage";

import { defineAsyncComponent, onBeforeUnmount, ref, watch } from "vue";

import { getAdminUserListApi } from "#/api/user-manage";

import UserIdentityCell from "#/views/user/UserIdentityCell.vue";

defineOptions({ name: "UserQuerySelect" });

withDefaults(
  defineProps<{
    placeholder?: string;
  }>(),
  { placeholder: "输入用户邮箱或昵称查询" },
);

const uid = defineModel<string>({ default: "" });
const options = ref<UserAdminVO[]>([]);
const loading = ref(false);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect),
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption),
);

let debounceTimer: ReturnType<typeof setTimeout> | undefined;
let requestVersion = 0;

async function queryUsers(keyword: string, selectedUid?: string) {
  const version = ++requestVersion;
  loading.value = true;
  try {
    const result = await getAdminUserListApi({
      name: keyword || undefined,
      uid: selectedUid,
      currentPage: 1,
      pageSize: 20,
    });
    if (version === requestVersion) options.value = result.records;
  } catch {
    if (version === requestVersion) options.value = [];
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

function remoteMethod(keyword: string) {
  if (debounceTimer) clearTimeout(debounceTimer);
  const normalized = keyword.trim();
  if (!normalized) {
    requestVersion++;
    options.value = [];
    loading.value = false;
    return;
  }
  debounceTimer = setTimeout(() => {
    void queryUsers(normalized);
  }, 250);
}

watch(
  uid,
  (value) => {
    if (!value || options.value.some((user) => user.id === value)) return;
    void queryUsers("", value);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer);
});
</script>

<template>
  <ElSelect
    v-model="uid"
    class="user-query-select"
    filterable
    remote
    clearable
    :loading="loading"
    :placeholder="placeholder"
    :remote-method="remoteMethod"
  >
    <ElOption
      v-for="user in options"
      :key="user.id"
      :label="user.nickName"
      :value="user.id"
    >
      <div class="user-option">
        <UserIdentityCell :user="user" :size="28" />
        <span v-if="user.email" class="user-option__email">{{ user.email }}</span>
      </div>
    </ElOption>
  </ElSelect>
</template>

<style scoped>
.user-query-select {
  width: 100%;
}

.user-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 260px;
}

.user-option__email {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
