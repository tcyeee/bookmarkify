/**
 * 书签巡检配置的共享状态。
 *
 * 后端把这一整组参数存成一条记录、整体读写，而设置页按语义把它们拆到左侧两个分类下
 * （「巡检与判定」「重试与归档」）。两个分组编辑的必须是同一份对象：否则在 A 分组改了值、
 * 切到 B 分组点保存，提交的就是 A 的旧值，改动被静默覆盖回去。
 * 所以状态放在模块级，分组组件只负责渲染自己那几行。
 */
import type { BookmarkLivenessConfigVO } from '#/api/bookmark-liveness-config';

import { reactive, ref } from 'vue';

import { ElMessage } from 'element-plus';

import {
  getBookmarkLivenessConfigApi,
  saveBookmarkLivenessConfigApi,
} from '#/api/bookmark-liveness-config';

// 字段与 BookmarkLivenessConfigVO 一一对应，所以取数/保存都用 Object.assign 整体同步，
// 不再逐字段抄一遍 —— 加一项配置时漏抄一行不会报错，只会让那一项永远保存不上
const config = reactive<BookmarkLivenessConfigVO>({
  activeCheckIntervalHours: 168,
  abnormalBackoffMultiplier: 2,
  abnormalCheckIntervalHours: 24,
  abnormalMaxIntervalHours: 384,
  contentRefreshIntervalDays: 30,
  deadConfirmFailures: 3,
  maxRetryFailures: 10,
});

const loading = ref(false);
const saving = ref(false);

async function load() {
  loading.value = true;
  try {
    Object.assign(config, await getBookmarkLivenessConfigApi());
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    // 后端会把越界值夹回合法区间再返回，所以回写响应而不是保留本地输入
    Object.assign(config, await saveBookmarkLivenessConfigApi({ ...config }));
    ElMessage.success('已保存');
  } finally {
    saving.value = false;
  }
}

export function useLivenessConfig() {
  return { config, load, loading, save, saving };
}
