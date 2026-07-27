<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div class="flex items-start justify-between gap-4">
      <div>
        <h3 class="text-xl font-semibold">{{ $t('accessTokenManage.title') }}</h3>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('accessTokenManage.desc') }}</p>
      </div>
      <button type="button" class="cy-btn cy-btn-accent shrink-0" @click="openCreateDialog">
        <Icon icon="mdi:plus" class="size-4" />
        {{ $t('accessTokenManage.generate') }}
      </button>
    </div>

    <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
      <div
        class="flex items-center gap-3 px-4 py-2.5 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
        <span class="flex-1">{{ $t('accessTokenManage.columns.name') }}</span>
        <span class="w-40 shrink-0 hidden sm:block">{{ $t('accessTokenManage.columns.prefix') }}</span>
        <span class="w-32 shrink-0 text-center hidden sm:block">{{ $t('accessTokenManage.columns.lastUsedAt') }}</span>
        <span class="w-32 shrink-0 text-center hidden sm:block">{{ $t('accessTokenManage.columns.createdAt') }}</span>
        <span class="w-16 shrink-0" />
      </div>

      <div v-if="loading" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        <Icon icon="mdi:loading" class="size-6 animate-spin mx-auto mb-2" />
        {{ $t('accessTokenManage.loading') }}
      </div>
      <div v-else-if="tokens.length === 0" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        {{ $t('accessTokenManage.empty') }}
      </div>
      <div v-else class="max-h-[28rem] overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
        <div
          v-for="item in tokens"
          :key="item.id"
          class="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium truncate text-slate-700 dark:text-slate-200">{{ item.name }}</p>
            <p class="text-xs text-slate-400 dark:text-slate-500 truncate sm:hidden">{{ item.tokenPrefix }}</p>
          </div>
          <span class="w-40 shrink-0 text-xs font-mono text-slate-500 dark:text-slate-400 truncate hidden sm:block">{{ item.tokenPrefix }}</span>
          <span class="w-32 shrink-0 text-center text-xs text-slate-400 dark:text-slate-500 hidden sm:block">
            {{ item.lastUsedAt ? formatDate(item.lastUsedAt) : $t('accessTokenManage.neverUsed') }}
          </span>
          <span class="w-32 shrink-0 text-center text-xs text-slate-400 dark:text-slate-500 hidden sm:block">{{ formatDate(item.createTime) }}</span>
          <span class="w-16 shrink-0 flex items-center justify-end">
            <button
              type="button"
              class="cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle"
              :title="$t('accessTokenManage.revoke')"
              @click="revokeToken(item)">
              <Icon icon="mdi:trash-can-outline" class="size-4 text-slate-400 hover:text-rose-500" />
            </button>
          </span>
        </div>
      </div>
    </div>

    <!-- 生成弹窗 -->
    <dialog ref="createDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ $t('accessTokenManage.createDialog.title') }}</h3>
        <label class="block mt-4">
          <span class="text-sm text-slate-600 dark:text-slate-300">{{ $t('accessTokenManage.createDialog.nameLabel') }}</span>
          <input
            v-model="createName"
            type="text"
            maxlength="100"
            class="cy-input w-full mt-1"
            :placeholder="$t('accessTokenManage.createDialog.namePlaceholder')"
            @keyup.enter="confirmCreate" />
        </label>
        <div class="cy-modal-action mt-6">
          <button type="button" class="cy-btn cy-btn-ghost" @click="closeCreateDialog">{{ $t('accessTokenManage.createDialog.cancel') }}</button>
          <button type="button" class="cy-btn cy-btn-accent" :disabled="creating" @click="confirmCreate">
            {{ $t('accessTokenManage.createDialog.confirm') }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>

    <!-- 一次性展示明文 token -->
    <dialog ref="revealDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-lg">
        <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ $t('accessTokenManage.revealDialog.title') }}</h3>
        <div class="cy-alert cy-alert-warning mt-4 text-sm">
          <Icon icon="mdi:alert-outline" class="size-5 shrink-0" />
          <span>{{ $t('accessTokenManage.revealDialog.warning') }}</span>
        </div>
        <div class="mt-4 flex items-center gap-2">
          <code class="flex-1 min-w-0 truncate rounded-lg bg-slate-100 dark:bg-slate-800 px-3 py-2 text-sm font-mono">{{ createdToken }}</code>
          <button type="button" class="cy-btn cy-btn-ghost cy-btn-circle" :title="$t('accessTokenManage.revealDialog.copy')" @click="copyToken">
            <Icon icon="mdi:content-copy" class="size-4" />
          </button>
        </div>
        <div class="cy-modal-action mt-6">
          <button type="button" class="cy-btn cy-btn-accent" @click="closeRevealDialog">{{ $t('accessTokenManage.revealDialog.done') }}</button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { accessTokenCreate, accessTokenList, accessTokenRevoke } from '@api'
import type * as t from '@typing'

const { t: translate } = useI18n()
const toastStore = useToastStore()
const confirmStore = useConfirmStore()

const loading = ref(false)
const tokens = ref<t.AccessTokenVO[]>([])

async function fetchList() {
  loading.value = true
  try {
    tokens.value = await accessTokenList()
  } catch {
    // http 层已提示
  } finally {
    loading.value = false
  }
}

function formatDate(value: number | string): string {
  return new Date(value).toLocaleString()
}

const createDialogRef = ref<HTMLDialogElement | null>(null)
const createName = ref('')
const creating = ref(false)

function openCreateDialog() {
  createName.value = ''
  createDialogRef.value?.showModal()
}

function closeCreateDialog() {
  createDialogRef.value?.close()
}

const revealDialogRef = ref<HTMLDialogElement | null>(null)
const createdToken = ref('')

async function confirmCreate() {
  if (creating.value) return
  creating.value = true
  try {
    const res = await accessTokenCreate({ name: createName.value.trim() || translate('accessTokenManage.defaultName') })
    tokens.value.unshift({ id: res.id, name: res.name, tokenPrefix: extractPrefix(res.token), lastUsedAt: null, createTime: res.createTime })
    createdToken.value = res.token
    closeCreateDialog()
    revealDialogRef.value?.showModal()
  } catch {
    // http 层已提示
  } finally {
    creating.value = false
  }
}

// 列表刷新前先用明文 token 本地估算展示前缀，避免生成成功后要额外拉一次列表才能显示
function extractPrefix(token: string): string {
  return `${token.slice(0, 16)}****`
}

function closeRevealDialog() {
  revealDialogRef.value?.close()
  createdToken.value = ''
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(createdToken.value)
    toastStore.success(translate('accessTokenManage.revealDialog.copySuccess'))
  } catch (error) {
    console.error('[AccessTokenManage] 复制失败', error)
  }
}

async function revokeToken(item: t.AccessTokenVO) {
  try {
    await confirmStore.confirm(translate('accessTokenManage.revokeConfirm'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await accessTokenRevoke(item.id)
    tokens.value = tokens.value.filter((token) => token.id !== item.id)
    toastStore.success(translate('accessTokenManage.revokeSuccess'))
  } catch {
    // http 层已提示
  }
}

onMounted(fetchList)
</script>
