<script lang="ts" setup>
import type { SiteAdminVO, SiteBasicInfoUpdateParams, SiteLockedField } from "#/api/site";

import { computed, reactive, ref, watch } from "vue";

import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElSwitch,
  ElTag,
} from "#/adapter/element";
import { updateSiteBasicInfoApi } from "#/api/site";

/**
 * 站点手工编辑。
 *
 * 补的是一条一直断着的运营动线：筛选栏的「站点全名 = 为空」「人工认证」筛得出需要人工过一遍
 * 的站点，而在这个弹窗之前，站点侧一个写入口都没有 —— 筛得出来、改不了。
 *
 * 锁语义是这里唯一需要想一下的东西，见 [LOCK_HINT]。
 */
const props = defineProps<{
  /** 要编辑的站点；为空时弹窗不渲染内容 */
  site?: null | SiteAdminVO;
}>();

const emit = defineEmits<{
  /** 保存成功，带回后端算好的完整快照，调用方就地替换自己那一行 */
  (e: "saved", site: SiteAdminVO): void;
}>();

const visible = defineModel<boolean>({ default: false });

const LOCK_HINT =
  "手工改过的字段会被锁定，下一轮自动抓取不再覆盖它；清空则解锁，把这一列交回抓取托管。";

const LOCKED_FIELD_LABEL: Record<SiteLockedField, string> = {
  BRAND_NAME: "全名",
  SHORT_NAME: "短名",
};

const form = reactive({
  brandName: "",
  shortName: "",
  verifyFlag: false,
  nsfw: false,
  nsfwReason: "",
});

/** 本次要显式解锁的字段：只想接受抓取值、不想改现值时用 */
const unlockFields = ref<SiteLockedField[]>([]);
const saving = ref(false);

// 每次打开都从 props 重新灌一遍：弹窗实例是复用的，不重置会把上一个站点的值带进来
watch(
  () => [visible.value, props.site?.id] as const,
  ([open]) => {
    if (!open || !props.site) return;
    form.brandName = props.site.brandName ?? "";
    form.shortName = props.site.shortName ?? "";
    form.verifyFlag = props.site.verifyFlag;
    form.nsfw = props.site.nsfw;
    form.nsfwReason = props.site.nsfwReason ?? "";
    unlockFields.value = [];
  },
  { immediate: true },
);

function isLocked(field: SiteLockedField) {
  return props.site?.lockedFields?.includes(field) ?? false;
}

function toggleUnlock(field: SiteLockedField) {
  const i = unlockFields.value.indexOf(field);
  if (i === -1) unlockFields.value.push(field);
  else unlockFields.value.splice(i, 1);
}

/**
 * 只送真正动过的字段。
 *
 * 不能无脑全量提交：`brandName` 的空串在后端是「清空并解锁」这个明确动作，而不是「没填」。
 * 全量提交会让「打开弹窗、什么都没改、点保存」把一个本来就是空的品牌名"清空"一次，
 * 顺带把它解锁 —— 一次无意的点击改掉了锁状态。
 */
const payload = computed<SiteBasicInfoUpdateParams>(() => {
  const site = props.site;
  const body: SiteBasicInfoUpdateParams = {};
  if (!site) return body;

  if (form.brandName !== (site.brandName ?? "")) body.brandName = form.brandName;
  if (form.shortName !== (site.shortName ?? "")) body.shortName = form.shortName;
  if (form.verifyFlag !== site.verifyFlag) body.verifyFlag = form.verifyFlag;
  // NSFW 的第三态（还没判过）必须保住：没动过开关就一个字段都不送，
  // 送了 nsfw=false 会把「未判定」写成「判过且干净」，那是两回事
  if (form.nsfw !== site.nsfw) {
    body.nsfw = form.nsfw;
    if (form.nsfw) body.nsfwReason = form.nsfwReason || undefined;
  }
  if (unlockFields.value.length > 0) body.unlockFields = [...unlockFields.value];
  return body;
});

const hasChanges = computed(() => Object.keys(payload.value).length > 0);

async function handleSave() {
  if (!props.site || !hasChanges.value) return;
  saving.value = true;
  try {
    const updated = await updateSiteBasicInfoApi(props.site.id, payload.value);
    emit("saved", updated);
    visible.value = false;
    ElMessage.success("已保存");
  } catch {
    // 错误提示已由请求拦截器统一弹出，这里只需别留下未捕获的 rejection
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <ElDialog v-model="visible" title="编辑站点" width="520px" :close-on-click-modal="false">
    <template v-if="site">
      <div class="site-head">
        <span class="site-head__host">{{ site.host }}</span>
        <ElTag size="small" type="info" disable-transitions>{{ site.pageCount }} 个页面</ElTag>
      </div>

      <ElForm :model="form" label-width="88px" label-position="left" @submit.prevent="handleSave">
        <ElFormItem label="站点全名">
          <div class="field">
            <ElInput v-model="form.brandName" placeholder="留空则交回抓取托管" clearable />
            <ElButton
              v-if="isLocked('BRAND_NAME')"
              link
              size="small"
              :type="unlockFields.includes('BRAND_NAME') ? 'warning' : 'info'"
              @click="toggleUnlock('BRAND_NAME')"
            >
              {{ unlockFields.includes("BRAND_NAME") ? "将解锁" : "已锁定" }}
            </ElButton>
          </div>
        </ElFormItem>

        <ElFormItem label="站点短名">
          <div class="field">
            <ElInput v-model="form.shortName" placeholder="磁贴文案用，留空则交回抓取托管" clearable />
            <ElButton
              v-if="isLocked('SHORT_NAME')"
              link
              size="small"
              :type="unlockFields.includes('SHORT_NAME') ? 'warning' : 'info'"
              @click="toggleUnlock('SHORT_NAME')"
            >
              {{ unlockFields.includes("SHORT_NAME") ? "将解锁" : "已锁定" }}
            </ElButton>
          </div>
        </ElFormItem>

        <ElFormItem label="人工认证">
          <div class="field">
            <ElSwitch v-model="form.verifyFlag" />
            <span class="hint">开启后，品牌名与图标不再被任何抓取覆盖</span>
          </div>
        </ElFormItem>

        <ElFormItem label="NSFW">
          <div class="field">
            <ElSwitch v-model="form.nsfw" />
            <span v-if="!site.nsfwReason" class="hint">该站尚未做过 NSFW 判定</span>
            <span v-else-if="!form.nsfw" class="hint">当前判定：干净</span>
          </div>
        </ElFormItem>

        <ElFormItem v-if="form.nsfw" label="NSFW 理由">
          <ElInput v-model="form.nsfwReason" placeholder="选填，最多 50 字" maxlength="50" />
        </ElFormItem>
      </ElForm>

      <p class="lock-hint">{{ LOCK_HINT }}</p>
      <p v-if="unlockFields.length > 0" class="lock-hint lock-hint--warn">
        保存后将解锁：{{ unlockFields.map((f) => LOCKED_FIELD_LABEL[f]).join("、") }}，
        这些字段此后会被自动抓取覆盖。
      </p>
    </template>

    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" :disabled="!hasChanges" @click="handleSave">
        保存
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.site-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
}

.site-head__host {
  font-size: 15px;
  font-weight: 500;
}

.field {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.lock-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.lock-hint--warn {
  color: var(--el-color-warning);
}
</style>
