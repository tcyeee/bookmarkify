<script lang="ts" setup>
import type { CategoryEntity } from "#/api/category";

import { defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage, ElMessageBox } from "element-plus";

import {
  deleteCategoryApi,
  getCategoryListApi,
  saveCategoryApi,
} from "#/api/category";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog),
);

const ElForm = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElForm),
);

const ElFormItem = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/form/index"),
    import("element-plus/es/components/form/style/css"),
  ]).then(([res]) => res.ElFormItem),
);

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput),
);

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber),
);

const dialogVisible = ref(false);
const saving = ref(false);

const form = reactive<Partial<CategoryEntity>>({
  id: undefined,
  slug: "",
  name: "",
  description: "",
  color: "",
  sort: 0,
});

function resetForm() {
  form.id = undefined;
  form.slug = "";
  form.name = "";
  form.description = "";
  form.color = "";
  form.sort = 0;
}

function handleAdd() {
  resetForm();
  dialogVisible.value = true;
}

function handleEdit(row: CategoryEntity) {
  form.id = row.id;
  form.slug = row.slug;
  form.name = row.name;
  form.description = row.description ?? "";
  form.color = row.color ?? "";
  form.sort = row.sort ?? 0;
  dialogVisible.value = true;
}

async function handleSave() {
  if (!form.slug?.trim() || !form.name?.trim()) {
    ElMessage.warning("slug 和名称不能为空");
    return;
  }
  saving.value = true;
  try {
    await saveCategoryApi({ ...form });
    ElMessage.success("已保存");
    dialogVisible.value = false;
    await gridApi.query();
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: CategoryEntity) {
  try {
    await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, "提示", {
      type: "warning",
    });
  } catch {
    return; // user cancelled or closed
  }
  await deleteCategoryApi(row.id);
  ElMessage.success("已删除");
  await gridApi.query();
}

const gridOptions: VxeGridProps<CategoryEntity> = {
  id: "admin-bookmark-category",
  columns: [
    { field: "name", title: "名称", minWidth: 140 },
    { field: "slug", title: "Slug", minWidth: 140 },
    { field: "description", title: "描述", minWidth: 220 },
    { field: "color", title: "颜色", width: 100, slots: { default: "color" } },
    { field: "sort", title: "排序", width: 80 },
    { title: "操作", width: 160, slots: { default: "actions" } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { enabled: false },
  proxyConfig: {
    ajax: {
      query: async () => {
        const items = await getCategoryListApi();
        return { items, total: items.length };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>分类管理</span>
          <ElButton type="primary" @click="handleAdd">新增分类</ElButton>
        </div>
      </template>
      <Grid>
        <template #color="{ row }">
          <span
            v-if="row.color"
            class="inline-block h-4 w-4 rounded"
            :style="{ backgroundColor: row.color }"
          />
          <span v-else class="text-gray-300">-</span>
        </template>
        <template #actions="{ row }">
          <ElButton link type="primary" @click="handleEdit(row)">编辑</ElButton>
          <ElButton link type="danger" @click="handleDelete(row)">删除</ElButton>
        </template>
      </Grid>

      <ElDialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="480px">
        <ElForm :model="form" label-width="80px">
          <ElFormItem label="名称">
            <ElInput v-model="form.name" placeholder="分类中文展示名" />
          </ElFormItem>
          <ElFormItem label="Slug">
            <ElInput v-model="form.slug" placeholder="稳定标识，喂给 DeepSeek" />
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="form.description" type="textarea" :rows="2" placeholder="给 DeepSeek 的判定说明" />
          </ElFormItem>
          <ElFormItem label="颜色">
            <ElInput v-model="form.color" placeholder="#RRGGBB" />
          </ElFormItem>
          <ElFormItem label="排序">
            <ElInputNumber v-model="form.sort" :min="0" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="saving" @click="handleSave">保存</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </Page>
</template>
