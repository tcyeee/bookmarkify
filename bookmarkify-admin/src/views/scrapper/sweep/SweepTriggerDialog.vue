<script lang="ts" setup>
/**
 * 手动触发一轮活性巡检的确认框。
 *
 * ## 为什么不是一个直接开跑的按钮
 *
 * 这不是刷新，是一次有副作用的写操作：一轮存活巡检最多打 200 个站点、会把连续失败到阈值的
 * 书签**改判为失联**、还会顺带向解析池投递几十条重新抓取。这几件事没有一件是"再点一次就好"的，
 * 所以点之前必须先看见范围——而范围不是常量，它取决于此刻有多少条到期、其中多少属于已判死的
 * 域名、解析队列还剩多少余量。这些数只有后端算得出来，于是确认框里的每一项都是现拉的
 * `sweep-preview`，用的是与真正开跑同一套候选查询。
 *
 * 预览与执行之间必然有时间差，所以文案一律写「预计」——尤其探测次数：站点层短路的页面若在
 * 开跑时发现根地址已恢复，会回到逐页探测，实际次数会更接近候选数。「最坏」那个数按那种情况算。
 */
import type { SweepPreviewVO } from '#/api/bookmark-sweep-log';

import { computed, ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
  ElDialog,
  ElMessage,
  ElOption,
  ElSelect,
  ElTag,
  ElTooltip,
} from '#/adapter/element';
import {
  getAdminSweepPreviewApi,
  SWEEP_TASK_LABELS,
  SWEEP_TRIGGERABLE_TASKS,
  triggerAdminSweepApi,
} from '#/api/bookmark-sweep-log';

import { formatDuration } from './duration';

const emit = defineEmits<{
  /** 已受理，调用方据此开始等待新轮次落库 */
  (e: 'triggered', taskLabel: string): void;
}>();

const visible = defineModel<boolean>({ default: false });

const taskLabel = ref<string>(SWEEP_TRIGGERABLE_TASKS[0]);
const preview = ref<null | SweepPreviewVO>(null);
const loading = ref(false);
const submitting = ref(false);
/** 预览拉取失败的原因。失败时**不允许**触发：看不见范围就点，正是这个弹窗要防的事 */
const loadError = ref('');

async function loadPreview() {
  loading.value = true;
  loadError.value = '';
  preview.value = null;
  try {
    preview.value = await getAdminSweepPreviewApi(taskLabel.value);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '预览加载失败';
  } finally {
    loading.value = false;
  }
}

// 每次打开都重新拉：候选是按 next_check_at 滚动的，弹窗实例复用时留着上次的数字
// 等于给出一个过期的范围，而这个弹窗存在的全部意义就是那几个数字准确
watch(
  () => [visible.value, taskLabel.value] as const,
  ([open]) => {
    if (open) loadPreview();
  },
);

const canSubmit = computed(
  () => !!preview.value && !preview.value.running && !loading.value,
);

/** 预估的出处。写出来是因为「预计 20s」和「预计 6 分钟」的可信度完全不同，取决于有没有历史样本 */
const estimateBasis = computed(() => {
  const p = preview.value;
  if (!p) return '';
  return p.sampleProbeMs === null
    ? '没有可参考的历史轮次，按每条探测 3s、并发 8 估算'
    : `依据最近 ${p.sampleRounds} 轮的真实记录：平均每条探测 ${p.sampleProbeMs} ms（已含并发效果）`;
});

async function submit() {
  if (!preview.value) return;
  submitting.value = true;
  try {
    const res = await triggerAdminSweepApi(taskLabel.value);
    if (res.accepted) {
      ElMessage.success(res.message);
      visible.value = false;
      emit('triggered', taskLabel.value);
    } else {
      // 唯一的不受理原因是巡检锁被占着。重拉一次预览，让 running 这行就地更新
      ElMessage.warning(res.message);
      loadPreview();
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <ElDialog v-model="visible" title="手动触发巡检" width="600px">
    <div class="space-y-4 text-sm">
      <div class="flex items-center gap-3">
        <span class="shrink-0 text-gray-500">巡检任务</span>
        <ElSelect v-model="taskLabel" class="flex-1">
          <ElOption
            v-for="key in SWEEP_TRIGGERABLE_TASKS"
            :key="key"
            :label="SWEEP_TASK_LABELS[key] ?? key"
            :value="key"
          />
        </ElSelect>
      </div>

      <div v-if="loading" class="py-6 text-center text-gray-400">
        正在统计本轮范围…
      </div>

      <ElAlert
        v-else-if="loadError"
        type="error"
        :closable="false"
        show-icon
        title="预览加载失败"
        :description="`${loadError}。看不到本轮范围时不允许触发。`"
      />

      <template v-else-if="preview">
        <ElAlert
          v-if="preview.running"
          type="warning"
          :closable="false"
          show-icon
          title="上一轮仍在进行"
          description="巡检锁被占着（上一轮还没跑完，或另一个实例正在跑）。这一轮即使触发也会被锁挡下。"
        />

        <!-- 一行一件事，左侧是问题、右侧是答案：这几行合起来就是「点下去会发生什么」 -->
        <div class="divide-y rounded border">
          <div class="flex items-baseline gap-3 px-3 py-2">
            <span class="w-24 shrink-0 text-gray-500">覆盖范围</span>
            <span>{{ preview.scope }}</span>
          </div>

          <div class="flex items-baseline gap-3 px-3 py-2">
            <span class="w-24 shrink-0 text-gray-500">本轮处理</span>
            <span>
              <span class="font-medium">{{ preview.candidates }}</span> 条
              <span class="text-xs text-gray-400">
                （到期共 {{ preview.backlog }} 条，单轮上限
                {{ preview.batchSize }}）
              </span>
            </span>
          </div>

          <!-- 到期数和实际处理数对不上时，两个原因含义相反，必须分开说 -->
          <div
            v-if="preview.truncated > 0 || preview.skippedNonDomain > 0"
            class="flex items-baseline gap-3 px-3 py-2"
          >
            <span class="w-24 shrink-0 text-gray-500">本轮不处理</span>
            <span class="text-xs text-gray-500">
              <span v-if="preview.truncated > 0" class="text-orange-500">
                {{ preview.truncated }} 条超出单轮上限，留到下一轮
              </span>
              <span v-if="preview.truncated > 0 && preview.skippedNonDomain > 0">
                ；
              </span>
              <span v-if="preview.skippedNonDomain > 0">
                {{ preview.skippedNonDomain }} 条是本地地址/IP 等非域名书签，不探测
              </span>
            </span>
          </div>

          <div class="flex items-baseline gap-3 px-3 py-2">
            <span class="w-24 shrink-0 text-gray-500">实际探测</span>
            <span>
              约 <span class="font-medium">{{ preview.probes }}</span> 次
              <span
                v-if="preview.shortCircuited > 0"
                class="text-xs text-gray-400"
              >
                （{{ preview.shortCircuited }} 条所属域名已判死，只对
                {{ preview.rootProbes }} 个根地址各探一次，不逐页探测）
              </span>
            </span>
          </div>

          <div class="flex items-baseline gap-3 px-3 py-2">
            <span class="w-24 shrink-0 text-gray-500">预计耗时</span>
            <span>
              <ElTooltip :content="estimateBasis" placement="top">
                <span class="cursor-help font-medium underline decoration-dotted">
                  {{ formatDuration(preview.estimatedMs) }}
                </span>
              </ElTooltip>
              <span class="text-xs text-gray-400">
                （最坏 {{ formatDuration(preview.worstCaseMs) }}：全部探测都吃满
                15s 超时，并发 {{ preview.concurrency }}）
              </span>
            </span>
          </div>

          <div
            v-if="preview.mayTriggerParse > 0"
            class="flex items-baseline gap-3 px-3 py-2"
          >
            <span class="w-24 shrink-0 text-gray-500">顺带重抓</span>
            <span>
              最多 {{ preview.mayTriggerParse }} 条
              <span class="text-xs text-gray-400">
                （异步进解析队列，不计入上面的耗时；队列余量不足时会被推迟到下一轮）
              </span>
            </span>
          </div>

          <div class="flex items-baseline gap-3 px-3 py-2">
            <span class="w-24 shrink-0 text-gray-500">写入影响</span>
            <span>
              <template v-if="preview.mayConfirmDeath">
                <ElTag type="danger" size="small">会改判失联</ElTag>
                <span class="ml-2 text-xs text-gray-500">
                  连续失败满 {{ preview.deadConfirmFailures }}
                  次的书签会被判为「失联」，用户桌面上随即变灰；单次失败只做退避
                </span>
              </template>
              <template v-else>
                <ElTag type="info" size="small">不会改判失联</ElTag>
                <span class="ml-2 text-xs text-gray-500">
                  这个任务只负责把书签救回来，失联数只可能由存活巡检增长
                </span>
              </template>
            </span>
          </div>
        </div>

        <div class="text-xs leading-relaxed text-gray-400">
          触发不影响原有的定时轮次（{{ preview.intervalHours }}h
          检测间隔照常）。本轮跑完后会在轮次列表里多出一行；若整批结果呈系统性失败的形态，
          会被熔断中止，届时不会改动任何书签。
        </div>
      </template>
    </div>

    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton
        type="primary"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      >
        确认触发
      </ElButton>
    </template>
  </ElDialog>
</template>
