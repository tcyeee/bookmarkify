<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">{{ $t('shareManage.title') }}</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('shareManage.desc') }}</p>
    </div>

    <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
      <div
        class="flex items-center gap-3 px-4 py-2.5 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
        <span class="flex-1">{{ $t('shareManage.columns.note') }}</span>
        <span class="w-20 shrink-0 text-center hidden sm:block">{{ $t('shareManage.columns.bookmarks') }}</span>
        <span class="w-24 shrink-0 text-center">{{ $t('shareManage.columns.status') }}</span>
        <span class="w-32 shrink-0 text-center hidden sm:block">{{ $t('shareManage.columns.createdAt') }}</span>
        <span class="w-32 shrink-0" />
      </div>

      <div v-if="loading" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        <Icon icon="mdi:loading" class="size-6 animate-spin mx-auto mb-2" />
        {{ $t('shareManage.loading') }}
      </div>
      <div v-else-if="records.length === 0" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        {{ $t('shareManage.empty') }}
      </div>
      <div v-else class="max-h-[28rem] overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
        <div
          v-for="item in records"
          :key="item.id"
          class="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium truncate text-slate-700 dark:text-slate-200">{{ item.note || $t('shareManage.noNote') }}</p>
            <p
              v-if="item.status === ShareStatus.REVIEW_REJECTED && item.rejectReason"
              class="text-xs text-rose-500 truncate">
              {{ $t('shareManage.rejectReasonLabel', { reason: item.rejectReason }) }}
            </p>
            <p v-else class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ expireLabel(item) }}</p>
          </div>
          <span class="w-20 shrink-0 text-center text-xs text-slate-500 dark:text-slate-400 hidden sm:block">{{ item.bookmarkCount }}</span>
          <span class="w-24 shrink-0 text-center">
            <span class="cy-badge cy-badge-sm" :class="statusBadgeClass(item.status)">{{ statusLabel(item.status) }}</span>
          </span>
          <span class="w-32 shrink-0 text-center text-xs text-slate-400 dark:text-slate-500 hidden sm:block">{{ formatDate(item.createTime) }}</span>
          <span class="w-32 shrink-0 flex items-center justify-end gap-1">
            <template v-if="item.status === ShareStatus.NORMAL">
              <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle" :title="$t('shareManage.copyLink')" @click="copyLink(item)">
                <Icon icon="mdi:link-variant" class="size-4 text-slate-400 hover:text-sky-500" />
              </button>
              <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle" :title="$t('shareManage.edit')" @click="openEditDialog(item)">
                <Icon icon="mdi:pencil-outline" class="size-4 text-slate-400 hover:text-sky-500" />
              </button>
              <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle" :title="$t('shareManage.cancel')" @click="cancelShareItem(item)">
                <Icon icon="mdi:cancel" class="size-4 text-slate-400 hover:text-rose-500" />
              </button>
            </template>
            <template v-else-if="item.status === ShareStatus.REVIEW_REJECTED">
              <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle" :title="$t('shareManage.cancel')" @click="cancelShareItem(item)">
                <Icon icon="mdi:cancel" class="size-4 text-slate-400 hover:text-rose-500" />
              </button>
            </template>
          </span>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div class="flex items-center justify-between text-sm text-slate-500 dark:text-slate-400">
      <span>{{ $t('shareManage.pagination.total', { total: page?.total ?? 0 }) }}</span>
      <div v-if="totalPages > 1" class="flex items-center gap-2">
        <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" :disabled="currentPage <= 1" @click="goToPage(currentPage - 1)">
          <Icon icon="mdi:chevron-left" class="size-4" />
        </button>
        <span>{{ $t('shareManage.pagination.pageOf', { current: currentPage, total: totalPages }) }}</span>
        <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" :disabled="currentPage >= totalPages" @click="goToPage(currentPage + 1)">
          <Icon icon="mdi:chevron-right" class="size-4" />
        </button>
      </div>
    </div>

    <!-- 修改弹窗 -->
    <dialog ref="editDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ $t('shareManage.editDialog.title') }}</h3>
        <label class="block mt-4">
          <span class="text-sm text-slate-600 dark:text-slate-300">{{ $t('shareManage.editDialog.noteLabel') }}</span>
          <textarea
            v-model="editNote"
            rows="3"
            maxlength="500"
            class="cy-textarea w-full mt-1"
            :placeholder="$t('shareManage.editDialog.notePlaceholder')" />
        </label>
        <div class="mt-4">
          <label class="flex items-center gap-2 cursor-pointer">
            <input v-model="editHasExpiry" type="checkbox" class="cy-checkbox cy-checkbox-sm" />
            <span class="text-sm text-slate-600 dark:text-slate-300">{{ $t('shareManage.editDialog.expiryLabel') }}</span>
          </label>
          <input v-if="editHasExpiry" v-model="editExpireDate" type="date" :min="minDate" class="cy-input cy-input-sm mt-2" />
        </div>
        <div class="cy-modal-action mt-6">
          <button type="button" class="cy-btn cy-btn-ghost" @click="closeEditDialog">{{ $t('shareManage.editDialog.cancel') }}</button>
          <button type="button" class="cy-btn cy-btn-accent" :disabled="editSaving" @click="confirmEdit">
            {{ $t('shareManage.editDialog.confirm') }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { shareMine, shareCancel, shareUpdate } from '@api'
import { ShareStatus } from '@typing'
import type * as t from '@typing'

const { t: translate } = useI18n()
const toastStore = useToastStore()
const confirmStore = useConfirmStore()
const runtimeConfig = useRuntimeConfig()
const websocketStore = useWebSocketStore()

const PAGE_SIZE = 20

const currentPage = ref(1)
const loading = ref(false)
const page = ref<t.BookmarkPage<t.ShareVO> | null>(null)

const records = computed(() => page.value?.records ?? [])
const totalPages = computed(() => (page.value ? Math.max(1, Math.ceil(page.value.total / PAGE_SIZE)) : 1))

async function fetchPage() {
  loading.value = true
  try {
    page.value = await shareMine({ currentPage: currentPage.value, pageSize: PAGE_SIZE })
  } catch {
    // http 层已提示
  } finally {
    loading.value = false
  }
}

function goToPage(p: number) {
  if (p < 1 || p > totalPages.value) return
  currentPage.value = p
  fetchPage()
}

function statusLabel(status: t.ShareVO['status']): string {
  switch (status) {
    case ShareStatus.EXPIRED:
      return translate('shareManage.status.expired')
    case ShareStatus.CANCELLED:
      return translate('shareManage.status.cancelled')
    case ShareStatus.ADMIN_TAKEDOWN:
      return translate('shareManage.status.adminTakedown')
    case ShareStatus.REVIEW_REJECTED:
      return translate('shareManage.status.reviewRejected')
    default:
      return translate('shareManage.status.normal')
  }
}

function statusBadgeClass(status: t.ShareVO['status']): string {
  switch (status) {
    case ShareStatus.NORMAL:
      return 'cy-badge-success text-white'
    case ShareStatus.EXPIRED:
      return 'cy-badge-ghost'
    default:
      return 'cy-badge-error text-white'
  }
}

function expireLabel(item: t.ShareVO): string {
  return item.expireTime
    ? translate('shareManage.expireLabel', { date: item.expireTime.slice(0, 10) })
    : translate('shareManage.neverExpire')
}

function formatDate(value: number | string): string {
  return new Date(value).toLocaleDateString()
}

async function copyLink(item: t.ShareVO) {
  const url = `${runtimeConfig.public.siteUrl}/share/${item.id}`
  try {
    await navigator.clipboard.writeText(url)
    toastStore.success(translate('shareManage.copySuccess'))
  } catch (error) {
    console.error('[ShareManage] 复制链接失败', error)
  }
}

async function cancelShareItem(item: t.ShareVO) {
  try {
    await confirmStore.confirm(translate('shareManage.cancelConfirm'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await shareCancel(item.id)
    item.status = ShareStatus.CANCELLED
    toastStore.success(translate('shareManage.cancelSuccess'))
  } catch {
    // http 层已提示
  }
}

const editDialogRef = ref<HTMLDialogElement | null>(null)
const editingItem = ref<t.ShareVO | null>(null)
const editNote = ref('')
const editHasExpiry = ref(false)
const editExpireDate = ref('')
const editSaving = ref(false)
const minDate = new Date().toISOString().slice(0, 10)

function openEditDialog(item: t.ShareVO) {
  editingItem.value = item
  editNote.value = item.note ?? ''
  editHasExpiry.value = !!item.expireTime
  editExpireDate.value = item.expireTime ? item.expireTime.slice(0, 10) : ''
  editDialogRef.value?.showModal()
}

function closeEditDialog() {
  editDialogRef.value?.close()
}

async function confirmEdit() {
  const item = editingItem.value
  if (!item || editSaving.value) return
  editSaving.value = true
  try {
    const res = await shareUpdate({
      id: item.id,
      note: editNote.value.trim() || undefined,
      expireTime: editHasExpiry.value && editExpireDate.value ? `${editExpireDate.value}T23:59:59` : null,
    })
    item.note = res.note
    item.expireTime = res.expireTime
    closeEditDialog()
    toastStore.success(translate('shareManage.editSuccess'))
  } catch {
    // http 层已提示
  } finally {
    editSaving.value = false
  }
}

watch(
  () => websocketStore.lastShareStatusChange,
  (change) => {
    if (!change) return
    const item = records.value.find((r) => r.id === change.id)
    if (!item) return
    item.status = change.status
    item.rejectReason = change.rejectReason
  },
)

onMounted(fetchPage)
</script>
