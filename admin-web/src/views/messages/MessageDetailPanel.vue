<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'

import type {
  MessageDetailDto,
  MessageStatus,
  MessageSummaryDto,
} from '@/types/operations'

const props = defineProps<{
  summary: MessageSummaryDto
  message: MessageDetailDto | null
  loading: boolean
  detailProblemTitle?: string | null
  detailProblemTraceId?: string | null
  mutationProblemTitle?: string | null
  mutationProblemTraceId?: string | null
  statusBusy: boolean
  statusUncertain: boolean
  statusConflict: boolean
  retryBusy: boolean
  retryUncertain: boolean
  deleteBusy: boolean
  deleteUncertain: boolean
  deleteText: string
}>()

const emit = defineEmits<{
  close: []
  'retry-detail': []
  'change-status': [status: MessageStatus]
  'reconcile-status': []
  'retry-email': []
  'reconcile-retry': []
  'update:deleteText': [value: string]
  'delete-message': []
  'reconcile-delete': []
}>()

const heading = ref<HTMLElement | null>(null)
const statusReconcileButton = ref<HTMLButtonElement | null>(null)
const retryReconcileButton = ref<HTMLButtonElement | null>(null)
const deleteReconcileButton = ref<HTMLButtonElement | null>(null)
const deleteInputId = `message-delete-${useId()}`
const statuses: readonly MessageStatus[] = Object.freeze(['UNREAD', 'READ', 'ARCHIVED', 'SPAM'])
const statusLabels: Readonly<Record<MessageStatus, string>> = Object.freeze({
  UNREAD: '未读',
  READ: '已读',
  ARCHIVED: '已归档',
  SPAM: '垃圾留言',
})

const anyMutationLocked = computed(
  () =>
    props.statusBusy ||
    props.statusUncertain ||
    props.statusConflict ||
    props.retryBusy ||
    props.retryUncertain ||
    props.deleteBusy ||
    props.deleteUncertain,
)
const canRetryEmail = computed(
  () => props.message?.email.status === 'FAILED' || props.message?.email.status === 'DEAD',
)

watch(
  () => props.summary.id,
  async () => {
    await nextTick()
    heading.value?.focus()
  },
  { immediate: true },
)

watch(
  () => [props.message?.status, props.message?.email.status] as const,
  async (current, previous) => {
    if (
      previous === undefined ||
      (current[0] === previous[0] && current[1] === previous[1])
    ) return
    await nextTick()
    heading.value?.focus()
  },
)

watch(
  () => [
    props.statusUncertain,
    props.statusConflict,
    props.retryUncertain,
    props.deleteUncertain,
  ] as const,
  async ([statusUnknown, statusChanged, retryUnknown, deleteUnknown]) => {
    await nextTick()
    if (deleteUnknown) deleteReconcileButton.value?.focus()
    else if (retryUnknown) retryReconcileButton.value?.focus()
    else if (statusUnknown || statusChanged) statusReconcileButton.value?.focus()
  },
)

function updateDeleteText(event: Event): void {
  emit('update:deleteText', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <aside
    class="rounded-2xl border border-blue-200 bg-white p-5 shadow-sm sm:p-6"
    data-message-detail
    :data-message-id="summary.id"
    :data-version="message?.version ?? summary.version"
    aria-labelledby="message-detail-heading"
    :aria-busy="loading || statusBusy || retryBusy || deleteBusy"
  >
    <div class="flex flex-col gap-4 border-b border-slate-200 pb-5 sm:flex-row sm:items-start sm:justify-between">
      <div class="min-w-0">
        <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">MESSAGE DETAIL</p>
        <h2
          id="message-detail-heading"
          ref="heading"
          class="mt-2 rounded break-words text-2xl font-semibold text-slate-950 focus:outline-none focus:ring-2 focus:ring-blue-500"
          data-detail-heading
          tabindex="-1"
        >{{ message?.subject ?? summary.subject }}</h2>
        <p class="mt-2 break-all font-mono text-xs text-slate-500">{{ summary.id }}</p>
      </div>
      <button
        class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
        type="button"
        data-action="close-detail"
        :disabled="anyMutationLocked"
        @click="emit('close')"
      >关闭详情</button>
    </div>

    <div v-if="loading" class="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600" role="status">
      正在加载完整留言…
    </div>
    <div
      v-else-if="detailProblemTitle"
      class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
      role="alert"
      data-detail-problem
    >
      <p class="font-semibold">{{ detailProblemTitle }}</p>
      <p class="mt-1 text-xs">请求编号：{{ detailProblemTraceId ?? 'client' }}</p>
      <button
        class="mt-3 rounded-lg border border-red-300 bg-white px-3 py-2 font-semibold"
        type="button"
        data-action="retry-detail"
        @click="emit('retry-detail')"
      >重试详情</button>
    </div>

    <template v-if="message">
      <div class="mt-6 grid gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(19rem,0.85fr)]">
        <div class="space-y-5">
          <section class="rounded-2xl border border-slate-200 bg-slate-50 p-5" aria-labelledby="message-content-title">
            <h3 id="message-content-title" class="text-lg font-semibold text-slate-950">留言正文</h3>
            <dl class="mt-4 grid gap-3 text-sm sm:grid-cols-2">
              <div>
                <dt class="text-slate-500">访客姓名</dt>
                <dd class="mt-1 break-words font-medium text-slate-900">{{ message.visitorName }}</dd>
              </div>
              <div>
                <dt class="text-slate-500">访客邮箱</dt>
                <dd class="mt-1 break-all font-medium text-slate-900">{{ message.visitorEmail }}</dd>
              </div>
            </dl>
            <p
              class="mt-5 whitespace-pre-wrap break-words rounded-xl border border-slate-200 bg-white p-4 text-sm leading-7 text-slate-800"
              data-message-body
            >{{ message.body }}</p>
            <dl class="mt-4 grid gap-3 text-xs text-slate-600 sm:grid-cols-2">
              <div><dt>提交时间</dt><dd class="mt-1 font-mono">{{ message.createdAt }}</dd></div>
              <div><dt>隐私同意时间</dt><dd class="mt-1 font-mono">{{ message.privacyAcceptedAt }}</dd></div>
              <div><dt>更新时间</dt><dd class="mt-1 font-mono">{{ message.updatedAt }}</dd></div>
              <div><dt>留言版本</dt><dd class="mt-1 font-mono">{{ message.version }}</dd></div>
            </dl>
          </section>

          <section class="rounded-2xl border border-slate-200 p-5" aria-labelledby="message-status-title">
            <h3 id="message-status-title" class="text-lg font-semibold text-slate-950">处理状态</h3>
            <p class="mt-1 text-sm text-slate-600">状态更新使用当前详情版本，冲突时不会覆盖服务器数据。</p>
            <div class="mt-4 flex flex-wrap gap-2">
              <button
                v-for="status in statuses"
                :key="status"
                class="rounded-xl border px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50"
                :class="status === message.status ? 'border-blue-700 bg-blue-700 text-white' : 'border-slate-300 bg-white text-slate-700'"
                type="button"
                :data-status="status"
                :aria-pressed="status === message.status"
                :disabled="status === message.status || anyMutationLocked"
                @click="emit('change-status', status)"
              >{{ statusLabels[status] }} · {{ status }}</button>
            </div>
          </section>
        </div>

        <div class="space-y-5">
          <section class="rounded-2xl border border-slate-200 p-5" aria-labelledby="email-delivery-title">
            <h3 id="email-delivery-title" class="text-lg font-semibold text-slate-950">邮件投递</h3>
            <dl class="mt-4 space-y-3 text-sm">
              <div data-email-field="status"><dt class="text-slate-500">状态</dt><dd class="mt-1 font-semibold">{{ message.email.status }}</dd></div>
              <div data-email-field="attempts"><dt class="text-slate-500">尝试次数</dt><dd class="mt-1 font-mono">{{ message.email.attempts }}</dd></div>
              <div data-email-field="nextAttemptAt"><dt class="text-slate-500">下次计划时间</dt><dd class="mt-1 break-all font-mono text-xs">{{ message.email.nextAttemptAt }}</dd></div>
              <div data-email-field="sentAt"><dt class="text-slate-500">发送时间</dt><dd class="mt-1 break-all font-mono text-xs">{{ message.email.sentAt ?? '暂无 / Unavailable' }}</dd></div>
              <div data-email-field="updatedAt"><dt class="text-slate-500">投递更新时间</dt><dd class="mt-1 break-all font-mono text-xs">{{ message.email.updatedAt }}</dd></div>
              <div data-email-field="errorCategory"><dt class="text-slate-500">安全错误类别</dt><dd class="mt-1 break-all font-mono text-xs">{{ message.email.errorCategory ?? '暂无 / Unavailable' }}</dd></div>
            </dl>
            <button
              v-if="canRetryEmail"
              class="mt-4 w-full rounded-xl border border-amber-300 bg-amber-50 px-3 py-2.5 text-sm font-semibold text-amber-950 disabled:cursor-not-allowed disabled:opacity-50"
              type="button"
              data-action="retry-email"
              :disabled="anyMutationLocked"
              @click="emit('retry-email')"
            >{{ retryBusy ? '正在重新排队…' : '确认后重新投递' }}</button>
          </section>

          <section class="rounded-2xl border border-red-200 bg-red-50/40 p-5" aria-labelledby="message-delete-title">
            <h3 id="message-delete-title" class="text-lg font-semibold text-red-950">永久删除</h3>
            <p class="mt-1 text-sm leading-6 text-red-900">此操作会硬删除留言。请准确输入 DELETE，再进行最终确认。</p>
            <label class="mt-4 block text-sm font-semibold text-red-950" :for="deleteInputId">
              输入 DELETE
              <input
                :id="deleteInputId"
                class="mt-2 w-full rounded-xl border border-red-300 bg-white px-3 py-2.5 font-mono text-sm"
                type="text"
                autocomplete="off"
                spellcheck="false"
                data-delete-confirmation
                :value="deleteText"
                :disabled="anyMutationLocked"
                @input="updateDeleteText"
              />
            </label>
            <button
              class="mt-3 w-full rounded-xl bg-red-800 px-3 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50"
              type="button"
              data-action="delete-message"
              :disabled="deleteText !== 'DELETE' || anyMutationLocked"
              @click="emit('delete-message')"
            >{{ deleteBusy ? '正在删除…' : '永久删除留言' }}</button>
          </section>
        </div>
      </div>

      <div
        v-if="mutationProblemTitle"
        class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
        role="alert"
        data-mutation-problem
      >
        <p class="font-semibold">{{ mutationProblemTitle }}</p>
        <p class="mt-1 text-xs">请求编号：{{ mutationProblemTraceId ?? 'client' }}</p>
      </div>

      <div
        v-if="statusUncertain || statusConflict"
        class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
        data-status-reconcile
      >
        <p class="font-semibold">{{ statusConflict ? '服务器状态已变化，本地没有覆盖它。' : '状态更新结果未知，不会再次提交 PATCH。' }}</p>
        <button
          ref="statusReconcileButton"
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-50"
          type="button"
          data-action="reconcile-status"
          :disabled="statusBusy"
          @click="emit('reconcile-status')"
        >仅加载服务器最新状态</button>
      </div>

      <div
        v-if="retryUncertain"
        class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
        data-retry-uncertain
      >
        <p class="font-semibold">重投结果未知，不会再次发送 POST。</p>
        <button
          ref="retryReconcileButton"
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-50"
          type="button"
          data-action="reconcile-retry"
          :disabled="retryBusy"
          @click="emit('reconcile-retry')"
        >仅检查服务器投递状态</button>
      </div>

      <div
        v-if="deleteUncertain"
        class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
        data-delete-uncertain
      >
        <p class="font-semibold">删除结果未知，不会再次发送 DELETE。</p>
        <button
          ref="deleteReconcileButton"
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:opacity-50"
          type="button"
          data-action="reconcile-delete"
          :disabled="deleteBusy"
          @click="emit('reconcile-delete')"
        >仅检查留言是否仍存在</button>
      </div>
    </template>
  </aside>
</template>
