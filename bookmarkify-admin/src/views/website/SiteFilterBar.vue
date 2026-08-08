<script lang="ts" setup>
import type { SiteFilters } from "./siteFilters";

import { computed } from "vue";

import { ElDatePicker, ElInput, ElInputNumber, ElOption, ElSelect } from "#/adapter/element";
import { FilterBar, FilterItem } from "#/components/filter-bar";

import { BOOL_OPTIONS, countAdvancedFilters } from "./siteFilters";

/**
 * 站点筛选栏，「站点管理」平表与「站点与页面」下钻视图共用。
 *
 * 两处以前各有一份逐字相同的筛选表单，改一处漏一处。「常用几项常驻、其余收进折叠区」的取舍
 * 与版式、重置按钮一并由 {@link FilterBar} 承担 —— 九项一字排开在 <1600px 会折成两三行，
 * 而这些视图是 `auto-content-height` 的，筛选栏每多一行下面的表就少一行。
 *
 * 纯展示组件：条件一变就重查是调用方用 `useAutoSearch` 盯着这个 model 做的，与筛选项长什么样无关。
 */
const filters = defineModel<SiteFilters>({ required: true });

const emit = defineEmits<{ (e: "reset"): void }>();

const advancedCount = computed(() => countAdvancedFilters(filters.value));
</script>

<template>
  <FilterBar :advanced-count="advancedCount" @reset="emit('reset')">
    <FilterItem label="关键字" width="240px">
      <ElInput v-model="filters.keyword" placeholder="域名 / 站点全名 / 短名" clearable />
    </FilterItem>

    <FilterItem label="域名活性" width="120px">
      <ElSelect v-model="filters.alive" placeholder="全部" clearable>
        <ElOption label="可达" :value="true" />
        <ElOption label="不可达" :value="false" />
      </ElSelect>
    </FilterItem>

    <template #advanced>
      <FilterItem label="NSFW" width="120px">
        <ElSelect v-model="filters.nsfw" placeholder="全部" clearable>
          <ElOption
            v-for="o in BOOL_OPTIONS"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </ElSelect>
      </FilterItem>

      <FilterItem label="人工认证" width="120px">
        <ElSelect v-model="filters.verifyFlag" placeholder="全部" clearable>
          <ElOption
            v-for="o in BOOL_OPTIONS"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </ElSelect>
      </FilterItem>

      <FilterItem label="站点全名" width="120px">
        <ElSelect v-model="filters.brandNameEmpty" placeholder="全部" clearable>
          <ElOption label="为空" :value="true" />
          <ElOption label="已抓到" :value="false" />
        </ElSelect>
      </FilterItem>

      <FilterItem label="连续失败 ≥" width="120px">
        <ElInputNumber
          v-model="filters.minConsecutiveFail"
          :min="1"
          :step="1"
          controls-position="right"
        />
      </FilterItem>

      <FilterItem label="非域名站点" width="140px">
        <ElSelect v-model="filters.includeNonDomain" placeholder="不显示" clearable>
          <ElOption label="一并显示" :value="true" />
        </ElSelect>
      </FilterItem>

      <FilterItem label="收录时间" width="240px">
        <ElDatePicker
          v-model="filters.createRange"
          type="daterange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
          start-placeholder="开始"
          end-placeholder="结束"
        />
      </FilterItem>
    </template>
  </FilterBar>
</template>
