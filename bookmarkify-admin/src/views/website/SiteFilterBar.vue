<script lang="ts" setup>
import type { SiteFilters } from "./siteFilters";

import { computed, ref } from "vue";

import {
  ElButton,
  ElDatePicker,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElTag,
} from "#/adapter/element";

import { BOOL_OPTIONS, countAdvancedFilters, LINK_TYPE_META, resetSiteFilters } from "./siteFilters";

/**
 * 站点筛选栏，「站点管理」平表与「站点与页面」下钻视图共用。
 *
 * 两处以前各有一份逐字相同的九项筛选表单。除了改一处漏一处之外，九项一字排开在 <1600px
 * 会折成两三行 —— 而下钻视图是 `auto-content-height` 的，筛选栏每多一行，下面的站点列表和
 * 页面表就各少一行。所以常用三项常驻，其余收进折叠区，靠角标提示里面还有几项在生效。
 */
const filters = defineModel<SiteFilters>({ required: true });

const emit = defineEmits<{
  (e: "search"): void;
  (e: "reset"): void;
}>();

const expanded = ref(false);

const advancedCount = computed(() => countAdvancedFilters(filters.value));

function handleReset() {
  resetSiteFilters(filters.value);
  emit("reset");
}
</script>

<template>
  <div class="site-filter-bar">
    <ElForm :model="filters" label-position="left" @submit.prevent="emit('search')">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
        <ElFormItem label="搜索" class="!mb-0">
          <ElInput
            v-model="filters.keyword"
            placeholder="域名 / 站点全名 / 短名"
            clearable
            style="width: 200px"
            @keyup.enter="emit('search')"
          />
        </ElFormItem>

        <ElFormItem label="类型" class="!mb-0">
          <ElSelect v-model="filters.linkType" placeholder="全部" clearable style="width: 110px">
            <ElOption
              v-for="(meta, value) in LINK_TYPE_META"
              :key="value"
              :label="meta.label"
              :value="value"
            />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="域名活性" class="!mb-0">
          <ElSelect v-model="filters.alive" placeholder="全部" clearable style="width: 105px">
            <ElOption label="可达" :value="true" />
            <ElOption label="不可达" :value="false" />
          </ElSelect>
        </ElFormItem>

        <ElFormItem class="!mb-0 ml-auto">
          <ElButton type="primary" @click="emit('search')">搜索</ElButton>
          <ElButton class="ml-2" @click="handleReset">重置</ElButton>
          <ElButton class="ml-2" link type="primary" @click="expanded = !expanded">
            {{ expanded ? "收起" : "更多筛选" }}
            <ElTag
              v-if="!expanded && advancedCount > 0"
              size="small"
              type="primary"
              disable-transitions
              class="ml-1"
            >
              {{ advancedCount }}
            </ElTag>
          </ElButton>
        </ElFormItem>
      </div>

      <div v-show="expanded" class="mt-3 flex flex-wrap items-center gap-x-6 gap-y-3">
        <ElFormItem label="NSFW" class="!mb-0">
          <ElSelect v-model="filters.nsfw" placeholder="全部" clearable style="width: 95px">
            <ElOption
              v-for="o in BOOL_OPTIONS"
              :key="String(o.value)"
              :label="o.label"
              :value="o.value"
            />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="人工认证" class="!mb-0">
          <ElSelect v-model="filters.verifyFlag" placeholder="全部" clearable style="width: 95px">
            <ElOption
              v-for="o in BOOL_OPTIONS"
              :key="String(o.value)"
              :label="o.label"
              :value="o.value"
            />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="站点全名" class="!mb-0">
          <ElSelect
            v-model="filters.brandNameEmpty"
            placeholder="全部"
            clearable
            style="width: 105px"
          >
            <ElOption label="为空" :value="true" />
            <ElOption label="已抓到" :value="false" />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="连续失败 ≥" class="!mb-0">
          <ElInputNumber
            v-model="filters.minConsecutiveFail"
            :min="1"
            :step="1"
            controls-position="right"
            style="width: 105px"
          />
        </ElFormItem>

        <ElFormItem label="收录时间" class="!mb-0">
          <ElDatePicker
            v-model="filters.createRange"
            type="daterange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </ElFormItem>
      </div>
    </ElForm>
  </div>
</template>

<style scoped>
.site-filter-bar :deep(.el-form-item__label) {
  height: 32px;
  font-weight: 400 !important;
  line-height: 32px;
  color: var(--el-text-color-regular);
}
</style>
