<script lang="ts" setup>
/**
 * 巡检熔断的常驻告警条。
 *
 * 熔断的语义是「我方链路坏了，那一轮全表结论不可信」——它此前唯一的出口是一行会滚掉的
 * `log.error`，等于没人知道。放在 Scrapper 调用日志页顶部而不是单开一页，是因为告警要
 * 出现在人本来就会看的地方；轮次明细在「Scrapper 巡检健康」页展开。
 *
 * 一切正常时**整条不渲染**，不占版面也不制造"绿色仪表盘"式的噪音。
 */
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue';

import { useRouter } from 'vue-router';

import { formatDateTime } from '@vben/utils';

import {
  getAdminSweepHealthApi,
  SWEEP_TASK_LABELS,
  type SweepHealthVO,
} from '#/api/bookmark-sweep-log';

const ElAlert = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/alert/index'),
    import('element-plus/es/components/alert/style/css'),
  ]).then(([res]) => res.ElAlert),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/button/index'),
    import('element-plus/es/components/button/style/css'),
  ]).then(([res]) => res.ElButton),
);

const router = useRouter();
const health = ref<SweepHealthVO | null>(null);
let timer: null | ReturnType<typeof setInterval> = null;

/**
 * 「巡检停摆」的判定阈值。
 *
 * 小时级巡检每小时两轮，正常情况下 `lastRoundAt` 距今不会超过 1 小时。给到 3 小时是留够
 * 余量：一轮本身可能跑几分钟，服务重启也会错过一两轮。超过它基本只剩两种可能——调度线程
 * 卡死，或者巡检锁因进程被强杀而没释放。这两种情况熔断次数都恒为 0，只看熔断数发现不了。
 */
const STALE_ROUND_HOURS = 3;

/**
 * 「连续熔断」的升级阈值（轮次数）。
 *
 * 一次熔断可能只是我方链路抖了一下，下一轮就恢复。但熔断轮次不推进有效游标，下一轮会选出
 * **同一批**候选、得到同一个结论 —— 达到这个连续次数基本可以断定它进入了自我维持的停摆，
 * 需要人工介入（2026-08-10 那次连续 25 轮字节级相同）。两个小时级巡检，3 轮约等于 3 小时。
 */
const STUCK_BREAKER_ROUNDS = 3;

const hoursSinceLastRound = computed(() => {
  const at = health.value?.lastRoundAt;
  if (!at) return null;
  return (Date.now() - new Date(at).getTime()) / 3_600_000;
});

/** 一轮都没跑过时不报「停摆」：那是刚上线还没到第一个整点，不是故障 */
const sweepStalled = computed(() => {
  const hours = hoursSinceLastRound.value;
  return hours !== null && hours > STALE_ROUND_HOURS;
});

const hasBreaker = computed(() => (health.value?.breakerCount ?? 0) > 0);
const visible = computed(() => hasBreaker.value || sweepStalled.value);

/** 连续熔断到阈值：已经不是偶发，而是自我维持的停摆，措辞要升级 */
const breakerStuck = computed(
  () => (health.value?.maxConsecutiveBreaker ?? 0) >= STUCK_BREAKER_ROUNDS,
);

const stuckTaskName = computed(() => {
  const label = health.value?.maxConsecutiveBreakerTask;
  if (!label) return '';
  return SWEEP_TASK_LABELS[label] ?? label;
});

const title = computed(() => {
  if (!health.value) return '';
  if (sweepStalled.value) {
    const h = Math.floor(hoursSinceLastRound.value ?? 0);
    return `活性巡检已 ${h} 小时没有跑过`;
  }
  if (breakerStuck.value) {
    return `「${stuckTaskName.value}」已连续 ${health.value.maxConsecutiveBreaker} 轮被熔断中止`;
  }
  return `近 ${health.value.windowHours} 小时内活性巡检熔断 ${health.value.breakerCount} 次`;
});

const description = computed(() => {
  if (!health.value) return '';
  if (sweepStalled.value) {
    // sweepStalled 为真已经蕴含 lastRoundAt 非空（它由 hoursSinceLastRound 推出），
    // 但类型上仍是可空的，这里显式兜一下而不是断言
    const at = health.value.lastRoundAt;
    return `最近一轮：${at ? formatDateTime(at) : '未知'}。调度线程卡死或巡检锁未释放时，熔断次数恒为 0，只看熔断数发现不了。`;
  }
  const b = health.value.latestBreaker;
  if (!b) return '';
  const task = SWEEP_TASK_LABELS[b.taskLabel] ?? b.taskLabel;
  const base = `最近一次 ${formatDateTime(b.createTime)}「${task}」：${b.breakerReason}。熔断轮次不会改动任何书签，结论已被丢弃。`;
  if (breakerStuck.value) {
    // 熔断轮次不推进有效游标，下一轮选出同一批候选、得到同一个结论 —— 连续熔断没有自愈路径
    return `${base}连续熔断说明这批候选每轮都触发同一条判据，不会自行恢复，需人工排查抓取链路或熔断阈值。`;
  }
  return base;
});

async function load() {
  try {
    health.value = await getAdminSweepHealthApi(24);
  } catch {
    // 告警条自己拉取失败不该在页面上再弹一个错误——它是附属信息，主表才是这个页面的正文
    health.value = null;
  }
}

function goDetail() {
  router.push({ path: '/scrapper/sweep', query: hasBreaker.value ? { onlyBreaker: '1' } : {} });
}

onMounted(() => {
  load();
  // 巡检本身是小时级的，一分钟一拉已经远比它快，再密只是白打接口
  timer = setInterval(load, 60_000);
});

onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <ElAlert
    v-if="visible"
    class="mb-4"
    type="error"
    :closable="false"
    show-icon
    :title="title"
  >
    <template #default>
      <div class="flex items-start justify-between gap-4">
        <span class="text-xs leading-relaxed">{{ description }}</span>
        <ElButton link type="primary" size="small" class="shrink-0" @click="goDetail">
          查看轮次
        </ElButton>
      </div>
    </template>
  </ElAlert>
</template>
