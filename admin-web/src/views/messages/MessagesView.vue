<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import { operationsApi } from '@/api/operationsApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type {
  EmailDeliveryView,
  MessageDetailDto,
  MessageListOptions,
  MessagePageDto,
  MessageStatus,
  MessageSummaryDto,
  UpdateMessageStatusRequest,
} from '@/types/operations'

import MessageDetailPanel from './MessageDetailPanel.vue'

type ListMessages = (options: Readonly<MessageListOptions>) => Promise<MessagePageDto>
type GetMessage = (id: string) => Promise<MessageDetailDto>
type UpdateStatus = (
  id: string,
  request: Readonly<UpdateMessageStatusRequest>,
) => Promise<MessageDetailDto>
type RetryEmail = (id: string) => Promise<void>
type DeleteMessage = (id: string) => Promise<void>

const props = withDefaults(
  defineProps<{
    list?: ListMessages
    detail?: GetMessage
    updateStatus?: UpdateStatus
    retryEmail?: RetryEmail
    deleteMessage?: DeleteMessage
  }>(),
  {
    list: (options: Readonly<MessageListOptions>) => operationsApi.listMessages(options),
    detail: (id: string) => operationsApi.getMessage(id),
    updateStatus: (id: string, request: Readonly<UpdateMessageStatusRequest>) =>
      operationsApi.updateMessageStatus(id, request),
    retryEmail: (id: string) => operationsApi.retryMessageEmail(id),
    deleteMessage: (id: string) => operationsApi.deleteMessage(id),
  },
)

interface DisplayProblem {
  readonly title: string
  readonly traceId: string
  readonly code: string
  readonly status: number
}

interface PendingStatus {
  readonly id: string
  readonly target: MessageStatus
  readonly baseStatus: MessageStatus
  readonly baseVersion: number
}

interface PendingRetry {
  readonly id: string
  readonly baseEmail: string
}

interface PendingDelete {
  readonly id: string
  readonly baseVersion: number
}

const inboxTitle = ref<HTMLElement | null>(null)
const items = ref<MessageSummaryDto[]>([])
const knownMessages = new Map<string, MessageSummaryDto>()
const deletedMessageIds = new Set<string>()
const statusFilter = ref<MessageStatus | ''>('')
const nextCursor = ref<string | null>(null)
const listLoading = ref(false)
const appending = ref(false)
const listProblem = ref<DisplayProblem | null>(null)
const appendProblem = ref<DisplayProblem | null>(null)
const announcement = ref('')

const selectedId = ref<string | null>(null)
const selectedDetail = ref<MessageDetailDto | null>(null)
const detailLoading = ref(false)
const detailProblem = ref<DisplayProblem | null>(null)
const detailOpener = ref<HTMLElement | null>(null)

const mutationProblem = ref<DisplayProblem | null>(null)
const statusBusy = ref(false)
const statusUncertain = ref(false)
const statusConflict = ref(false)
const retryBusy = ref(false)
const retryUncertain = ref(false)
const deleteBusy = ref(false)
const deleteUncertain = ref(false)
const deleteText = ref('')

let pendingStatus: PendingStatus | null = null
let pendingRetry: PendingRetry | null = null
let pendingDelete: PendingDelete | null = null
let listGeneration = 0
let detailGeneration = 0
let statusGeneration = 0
let retryGeneration = 0
let deleteGeneration = 0
let mounted = false
let disposed = false

const statusOptions: readonly MessageStatus[] = Object.freeze([
  'UNREAD',
  'READ',
  'ARCHIVED',
  'SPAM',
])
const statusLabels: Readonly<Record<MessageStatus, string>> = Object.freeze({
  UNREAD: '未读',
  READ: '已读',
  ARCHIVED: '已归档',
  SPAM: '垃圾留言',
})

function sameId(left: string | null | undefined, right: string | null | undefined): boolean {
  return typeof left === 'string' && typeof right === 'string' && left.toLowerCase() === right.toLowerCase()
}

function toProblem(cause: unknown, fallback: string): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return {
      title: cause.body.title,
      traceId: cause.body.traceId,
      code: cause.body.code,
      status: cause.body.status,
    }
  }
  return { title: fallback, traceId: 'client', code: 'CLIENT_ERROR', status: 0 }
}

function clientProblem(title: string, code: string): DisplayProblem {
  return { title, traceId: 'client', code, status: 0 }
}

function isUncertainMutation(cause: unknown): boolean {
  if (!(cause instanceof ApiProblem)) return true
  return cause.body.status === 0 || cause.body.status >= 500
}

function isMissingMessage(cause: unknown): cause is ApiProblem {
  return (
    cause instanceof ApiProblem &&
    cause.body.status === 404 &&
    cause.body.code === 'MESSAGE_NOT_FOUND'
  )
}

function summaryFromDetail(message: MessageDetailDto): MessageSummaryDto {
  return {
    id: message.id,
    visitorName: message.visitorName,
    visitorEmail: message.visitorEmail,
    subject: message.subject,
    status: message.status,
    emailStatus: message.email.status,
    createdAt: message.createdAt,
    version: message.version,
  }
}

function statusMatches(message: MessageSummaryDto): boolean {
  return statusFilter.value === '' || message.status === statusFilter.value
}

function rememberSummary(
  candidate: MessageSummaryDto,
  preferEqual = false,
): MessageSummaryDto {
  const key = candidate.id.toLowerCase()
  const known = knownMessages.get(key)
  if (
    known === undefined ||
    candidate.version > known.version ||
    (preferEqual && candidate.version === known.version)
  ) {
    knownMessages.set(key, candidate)
    return candidate
  }
  return known
}

function mergeResetPage(candidates: readonly MessageSummaryDto[]): MessageSummaryDto[] {
  const result: MessageSummaryDto[] = []
  const seen = new Set<string>()
  for (const candidate of candidates) {
    if (deletedMessageIds.has(candidate.id.toLowerCase())) continue
    const freshest = rememberSummary(candidate)
    const key = freshest.id.toLowerCase()
    if (seen.has(key) || !statusMatches(freshest)) continue
    seen.add(key)
    result.push(freshest)
  }
  return result
}

function mergeAppendPage(candidates: readonly MessageSummaryDto[]): void {
  const next = [...items.value]
  const positions = new Map(next.map((message, index) => [message.id.toLowerCase(), index]))
  for (const candidate of candidates) {
    if (deletedMessageIds.has(candidate.id.toLowerCase())) continue
    const freshest = rememberSummary(candidate)
    const key = freshest.id.toLowerCase()
    const position = positions.get(key)
    if (!statusMatches(freshest)) {
      if (position !== undefined) {
        next.splice(position, 1)
        positions.clear()
        next.forEach((message, index) => positions.set(message.id.toLowerCase(), index))
      }
      continue
    }
    if (position === undefined) {
      positions.set(key, next.length)
      next.push(freshest)
    } else {
      next[position] = freshest
    }
  }
  items.value = next
}

function upsertListFromDetail(message: MessageDetailDto): void {
  const summary = rememberSummary(summaryFromDetail(message), true)
  const index = items.value.findIndex((item) => sameId(item.id, summary.id))
  if (!statusMatches(summary)) {
    if (index >= 0) items.value = items.value.filter((_item, itemIndex) => itemIndex !== index)
    return
  }
  if (index < 0) return
  const next = [...items.value]
  next[index] = summary
  items.value = next
}

function applyDetail(message: MessageDetailDto): boolean {
  if (!sameId(message.id, selectedId.value)) return false
  if (
    selectedDetail.value !== null &&
    sameId(selectedDetail.value.id, message.id) &&
    selectedDetail.value.version > message.version
  ) {
    return false
  }
  selectedDetail.value = message
  upsertListFromDetail(message)
  return true
}

const selectedSummary = computed<MessageSummaryDto | null>(() => {
  if (selectedId.value === null) return null
  if (selectedDetail.value !== null && sameId(selectedDetail.value.id, selectedId.value)) {
    return summaryFromDetail(selectedDetail.value)
  }
  return (
    items.value.find((item) => sameId(item.id, selectedId.value)) ??
    knownMessages.get(selectedId.value.toLowerCase()) ??
    null
  )
})

const hasExitRisk = computed(
  () =>
    statusBusy.value ||
    statusUncertain.value ||
    retryBusy.value ||
    retryUncertain.value ||
    deleteBusy.value ||
    deleteUncertain.value,
)

async function loadMessages(append = false): Promise<void> {
  if (disposed || (append && (nextCursor.value === null || appending.value))) return
  const operation = ++listGeneration
  const capturedStatus = statusFilter.value
  const cursor = append ? nextCursor.value : null
  if (append) {
    appending.value = true
    appendProblem.value = null
  } else {
    // A filter/reset supersedes any pending append. Retire its spinner as well
    // as its generation so a late cursor page cannot lock the new result set.
    appending.value = false
    listLoading.value = true
    listProblem.value = null
    appendProblem.value = null
    items.value = []
    nextCursor.value = null
  }

  try {
    const result = await props.list({
      ...(capturedStatus === '' ? {} : { status: capturedStatus }),
      ...(cursor === null ? {} : { cursor }),
    })
    if (
      disposed ||
      operation !== listGeneration ||
      statusFilter.value !== capturedStatus
    ) return
    if (append) mergeAppendPage(result.items)
    else items.value = mergeResetPage(result.items)
    nextCursor.value = result.nextCursor
  } catch (cause) {
    if (disposed || operation !== listGeneration) return
    const problem = toProblem(cause, '无法加载留言收件箱')
    if (append) appendProblem.value = problem
    else listProblem.value = problem
  } finally {
    if (!disposed && operation === listGeneration) {
      if (append) appending.value = false
      else listLoading.value = false
    }
  }
}

function retryList(): void {
  void loadMessages(false)
}

function loadMore(): void {
  void loadMessages(true)
}

function clearMutationState(): void {
  mutationProblem.value = null
  statusUncertain.value = false
  statusConflict.value = false
  retryUncertain.value = false
  deleteUncertain.value = false
  pendingStatus = null
  pendingRetry = null
  pendingDelete = null
  deleteText.value = ''
}

async function loadSelectedDetail(): Promise<void> {
  const id = selectedId.value
  if (id === null || disposed || hasExitRisk.value) return
  const operation = ++detailGeneration
  detailLoading.value = true
  detailProblem.value = null
  try {
    const result = await props.detail(id)
    if (disposed || operation !== detailGeneration || !sameId(selectedId.value, id)) return
    if (!sameId(result.id, id) || !applyDetail(result)) {
      detailProblem.value = clientProblem('服务器返回的留言详情与当前选择不一致。', 'MESSAGE_DETAIL_MISMATCH')
    }
  } catch (cause) {
    if (!disposed && operation === detailGeneration && sameId(selectedId.value, id)) {
      detailProblem.value = toProblem(cause, '无法加载完整留言')
    }
  } finally {
    if (!disposed && operation === detailGeneration) detailLoading.value = false
  }
}

function openMessage(message: MessageSummaryDto, event: Event): void {
  if (hasExitRisk.value) return
  detailGeneration += 1
  selectedId.value = message.id
  selectedDetail.value = null
  detailOpener.value = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  rememberSummary(message)
  detailProblem.value = null
  clearMutationState()
  void loadSelectedDetail()
}

async function closeDetail(force = false): Promise<void> {
  if (!force && hasExitRisk.value) return
  detailGeneration += 1
  const restore = detailOpener.value
  selectedId.value = null
  selectedDetail.value = null
  detailLoading.value = false
  detailProblem.value = null
  detailOpener.value = null
  clearMutationState()
  await nextTick()
  if (restore?.isConnected) restore.focus()
  else inboxTitle.value?.focus()
}

function retryDetail(): void {
  void loadSelectedDetail()
}

async function changeStatus(target: MessageStatus): Promise<void> {
  const current = selectedDetail.value
  if (
    current === null ||
    target === current.status ||
    hasExitRisk.value ||
    statusConflict.value
  ) return
  const operation = ++statusGeneration
  const pending: PendingStatus = Object.freeze({
    id: current.id,
    target,
    baseStatus: current.status,
    baseVersion: current.version,
  })
  pendingStatus = pending
  statusBusy.value = true
  mutationProblem.value = null
  try {
    const updated = await props.updateStatus(current.id, {
      status: target,
      version: current.version,
    })
    if (disposed || operation !== statusGeneration || !sameId(selectedId.value, current.id)) return
    if (
      !sameId(updated.id, current.id) ||
      updated.status !== target ||
      updated.version !== current.version + 1 ||
      !applyDetail(updated)
    ) {
      statusUncertain.value = true
      mutationProblem.value = clientProblem(
        '服务器响应尚未证明本次状态更新已完成；不会再次提交。',
        'MESSAGE_STATUS_UNPROVEN',
      )
      return
    }
    pendingStatus = null
    statusUncertain.value = false
    statusConflict.value = false
    announcement.value = '留言状态已更新。'
  } catch (cause) {
    if (disposed || operation !== statusGeneration || !sameId(selectedId.value, current.id)) return
    const problem = toProblem(cause, '无法更新留言状态')
    mutationProblem.value = problem
    if (problem.status === 409 && problem.code === 'MESSAGE_VERSION_CONFLICT') {
      statusConflict.value = true
      statusUncertain.value = false
      return
    }
    statusUncertain.value = isUncertainMutation(cause)
    if (!statusUncertain.value) pendingStatus = null
  } finally {
    if (!disposed && operation === statusGeneration) statusBusy.value = false
  }
}

async function reconcileStatus(): Promise<void> {
  const pending = pendingStatus
  if (
    pending === null ||
    (!statusUncertain.value && !statusConflict.value) ||
    statusBusy.value
  ) return
  const operation = ++statusGeneration
  statusBusy.value = true
  mutationProblem.value = null
  try {
    const current = await props.detail(pending.id)
    if (disposed || operation !== statusGeneration || !sameId(selectedId.value, pending.id)) return
    if (!sameId(current.id, pending.id) || !applyDetail(current)) {
      mutationProblem.value = clientProblem(
        '服务器返回的留言详情与当前选择不一致。',
        'MESSAGE_DETAIL_MISMATCH',
      )
      return
    }
    if (statusConflict.value || current.version > pending.baseVersion) {
      pendingStatus = null
      statusUncertain.value = false
      statusConflict.value = false
      mutationProblem.value = null
      announcement.value =
        current.status === pending.target
          ? '已确认服务器上的留言状态。'
          : '已加载服务器最新状态；如需修改，请重新明确选择。'
      return
    }
    mutationProblem.value = clientProblem(
      '服务器仍未证明上次状态更新已完成；请仅再次检查。',
      'MESSAGE_STATUS_UNPROVEN',
    )
  } catch (cause) {
    if (disposed || operation !== statusGeneration || !sameId(selectedId.value, pending.id)) return
    if (isMissingMessage(cause)) {
      await completeDelete(pending.id, '留言已不存在，已从收件箱移除。')
      return
    }
    mutationProblem.value = toProblem(cause, '仍无法确认留言状态')
  } finally {
    if (!disposed && operation === statusGeneration) statusBusy.value = false
  }
}

function emailFingerprint(email: EmailDeliveryView): string {
  return JSON.stringify([
    email.status,
    email.attempts,
    email.nextAttemptAt,
    email.sentAt,
    email.updatedAt,
    email.errorCategory,
  ])
}

async function retryMessageEmail(): Promise<void> {
  const current = selectedDetail.value
  if (
    current === null ||
    (current.email.status !== 'FAILED' && current.email.status !== 'DEAD') ||
    hasExitRisk.value ||
    !window.confirm('确认将这条留言的邮件重新加入投递队列？')
  ) return
  const pending: PendingRetry = Object.freeze({
    id: current.id,
    baseEmail: emailFingerprint(current.email),
  })
  pendingRetry = pending
  const operation = ++retryGeneration
  retryBusy.value = true
  mutationProblem.value = null
  let postAcknowledged = false
  try {
    await props.retryEmail(current.id)
    postAcknowledged = true
    if (disposed || operation !== retryGeneration || !sameId(selectedId.value, current.id)) return
    const refreshed = await props.detail(current.id)
    if (disposed || operation !== retryGeneration || !sameId(selectedId.value, current.id)) return
    if (!sameId(refreshed.id, current.id) || !applyDetail(refreshed)) {
      retryUncertain.value = true
      mutationProblem.value = clientProblem(
        '服务器响应尚未证明邮件已重新排队；不会再次提交。',
        'MESSAGE_RETRY_UNPROVEN',
      )
      return
    }
    if (emailFingerprint(refreshed.email) === pending.baseEmail) {
      retryUncertain.value = true
      mutationProblem.value = clientProblem(
        '服务器投递状态尚未变化；不会再次提交重投。',
        'MESSAGE_RETRY_UNPROVEN',
      )
      return
    }
    pendingRetry = null
    retryUncertain.value = false
    announcement.value = '邮件投递状态已刷新。'
  } catch (cause) {
    if (disposed || operation !== retryGeneration || !sameId(selectedId.value, current.id)) return
    mutationProblem.value = toProblem(cause, '无法重新投递留言邮件')
    retryUncertain.value = postAcknowledged || isUncertainMutation(cause)
    if (!retryUncertain.value) pendingRetry = null
  } finally {
    if (!disposed && operation === retryGeneration) retryBusy.value = false
  }
}

async function reconcileRetry(): Promise<void> {
  const pending = pendingRetry
  if (pending === null || !retryUncertain.value || retryBusy.value) return
  const operation = ++retryGeneration
  retryBusy.value = true
  mutationProblem.value = null
  try {
    const current = await props.detail(pending.id)
    if (disposed || operation !== retryGeneration || !sameId(selectedId.value, pending.id)) return
    if (!sameId(current.id, pending.id) || !applyDetail(current)) {
      mutationProblem.value = clientProblem(
        '服务器返回的留言详情与当前选择不一致。',
        'MESSAGE_DETAIL_MISMATCH',
      )
      return
    }
    if (emailFingerprint(current.email) !== pending.baseEmail) {
      pendingRetry = null
      retryUncertain.value = false
      mutationProblem.value = null
      announcement.value = '已确认服务器上的邮件投递状态。'
      return
    }
    mutationProblem.value = clientProblem(
      '服务器投递状态仍未变化；请仅再次检查，避免重复重投。',
      'MESSAGE_RETRY_UNPROVEN',
    )
  } catch (cause) {
    if (disposed || operation !== retryGeneration || !sameId(selectedId.value, pending.id)) return
    if (isMissingMessage(cause)) {
      await completeDelete(pending.id, '留言已不存在，已从收件箱移除。')
      return
    }
    mutationProblem.value = toProblem(cause, '仍无法确认邮件投递状态')
  } finally {
    if (!disposed && operation === retryGeneration) retryBusy.value = false
  }
}

async function completeDelete(
  id: string,
  completionAnnouncement = '留言已永久删除。',
): Promise<void> {
  const key = id.toLowerCase()
  deletedMessageIds.add(key)
  // A cursor request may have captured this row before the DELETE committed.
  // Invalidate it and retain a local tombstone for the rest of this view.
  listGeneration += 1
  appending.value = false
  listLoading.value = false
  items.value = items.value.filter((item) => !sameId(item.id, id))
  knownMessages.delete(key)
  pendingDelete = null
  deleteUncertain.value = false
  mutationProblem.value = null
  announcement.value = completionAnnouncement
  await closeDetail(true)
}

async function deleteSelectedMessage(): Promise<void> {
  const current = selectedDetail.value
  if (
    current === null ||
    deleteText.value !== 'DELETE' ||
    hasExitRisk.value ||
    !window.confirm('永久删除这条留言及其投递记录？此操作不可撤销。')
  ) return
  const pending: PendingDelete = Object.freeze({ id: current.id, baseVersion: current.version })
  pendingDelete = pending
  const operation = ++deleteGeneration
  deleteBusy.value = true
  mutationProblem.value = null
  try {
    await props.deleteMessage(current.id)
    if (disposed || operation !== deleteGeneration || !sameId(selectedId.value, current.id)) return
    await completeDelete(current.id)
  } catch (cause) {
    if (disposed || operation !== deleteGeneration || !sameId(selectedId.value, current.id)) return
    mutationProblem.value = toProblem(cause, '无法永久删除留言')
    deleteUncertain.value = isUncertainMutation(cause)
    if (!deleteUncertain.value) pendingDelete = null
  } finally {
    if (!disposed && operation === deleteGeneration) deleteBusy.value = false
  }
}

async function reconcileDelete(): Promise<void> {
  const pending = pendingDelete
  if (pending === null || !deleteUncertain.value || deleteBusy.value) return
  const operation = ++deleteGeneration
  deleteBusy.value = true
  mutationProblem.value = null
  try {
    const current = await props.detail(pending.id)
    if (disposed || operation !== deleteGeneration || !sameId(selectedId.value, pending.id)) return
    if (!sameId(current.id, pending.id) || !applyDetail(current)) {
      mutationProblem.value = clientProblem(
        '服务器返回的留言详情与当前选择不一致。',
        'MESSAGE_DETAIL_MISMATCH',
      )
      return
    }
    mutationProblem.value = clientProblem(
      '留言仍然存在；不会自动再次删除。请刷新页面后重新评估。',
      'MESSAGE_DELETE_UNPROVEN',
    )
  } catch (cause) {
    if (disposed || operation !== deleteGeneration) return
    if (isMissingMessage(cause)) {
      await completeDelete(pending.id)
      return
    }
    mutationProblem.value = toProblem(cause, '仍无法确认留言是否已删除')
  } finally {
    if (!disposed && operation === deleteGeneration) deleteBusy.value = false
  }
}

function confirmRouteLeave(): boolean {
  if (!hasExitRisk.value) return true
  return window.confirm('留言操作仍在进行或结果未知。确定离开吗？')
}

function beforeUnload(event: BeforeUnloadEvent): void {
  if (!hasExitRisk.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(statusFilter, () => {
  if (!mounted || hasExitRisk.value) return
  void closeDetail(true)
  void loadMessages(false)
})

onBeforeRouteLeave(confirmRouteLeave)

onMounted(() => {
  mounted = true
  window.addEventListener('beforeunload', beforeUnload)
  void loadMessages(false)
})

onBeforeUnmount(() => {
  disposed = true
  mounted = false
  listGeneration += 1
  detailGeneration += 1
  statusGeneration += 1
  retryGeneration += 1
  deleteGeneration += 1
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="space-y-6" aria-labelledby="messages-title">
    <header class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">MESSAGE INBOX</p>
        <h1
          id="messages-title"
          ref="inboxTitle"
          class="mt-2 rounded text-3xl font-semibold text-slate-950 focus:outline-none focus:ring-2 focus:ring-blue-500"
          tabindex="-1"
        >留言收件箱</h1>
        <p class="mt-2 max-w-3xl text-sm leading-6 text-slate-600">
          按状态处理访客留言，核对邮件投递，并以显式确认执行重投或永久删除。
        </p>
      </div>
      <span class="w-fit rounded-full bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-800">
        已加载 {{ items.length }} 条
      </span>
    </header>

    <p
      class="sr-only"
      role="status"
      aria-live="polite"
      aria-atomic="true"
      data-announcement
    >{{ announcement }}</p>

    <section class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm" aria-labelledby="message-filter-title">
      <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 id="message-filter-title" class="text-lg font-semibold text-slate-950">浏览留言</h2>
          <p class="mt-1 text-sm text-slate-500">每次读取最多 30 条；状态变化会从首个游标重新加载。</p>
        </div>
        <label class="text-sm font-semibold text-slate-800 sm:min-w-56">
          留言状态
          <select
            v-model="statusFilter"
            class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 font-normal"
            data-filter="status"
            :disabled="hasExitRisk"
          >
            <option value="">全部状态</option>
            <option v-for="status in statusOptions" :key="status" :value="status">
              {{ statusLabels[status] }} · {{ status }}
            </option>
          </select>
        </label>
      </div>
    </section>

    <div v-if="listLoading && items.length === 0" data-state="messages-loading">
      <AsyncPanel :loading="true" />
    </div>
    <div v-else-if="listProblem && items.length === 0" data-state="messages-error">
      <AsyncPanel
        :error-title="listProblem.title"
        :trace-id="listProblem.traceId"
        :on-retry="retryList"
      />
    </div>
    <AsyncPanel v-else :loading="false" :empty="!listLoading && listProblem === null && items.length === 0">
      <template #empty>
        <div data-state="messages-empty">
          <p class="font-semibold text-slate-800">当前状态下没有留言</p>
          <p class="mt-1">新的访客留言会按提交时间显示在这里。</p>
        </div>
      </template>

      <div class="space-y-4">
        <div
          v-if="listProblem"
          class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
          role="alert"
          data-state="messages-error"
        >
          <p class="font-semibold">{{ listProblem.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ listProblem.traceId }}</p>
          <button class="mt-3 rounded-lg border border-red-300 bg-white px-3 py-2 font-semibold" type="button" data-action="retry-list" @click="retryList">重试列表</button>
        </div>

        <ul class="grid list-none gap-3 p-0" aria-label="访客留言列表">
          <li v-for="message in items" :key="message.id">
            <button
              class="w-full rounded-2xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:border-blue-300 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              :data-message-id="message.id"
              :data-status="message.status"
              :data-version="message.version"
              :aria-label="`查看留言：${message.subject}`"
              :disabled="hasExitRisk"
              @click="openMessage(message, $event)"
            >
              <span class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <span class="min-w-0">
                  <span class="block break-words font-semibold text-slate-950">{{ message.subject }}</span>
                  <span class="mt-1 block break-words text-sm text-slate-600">{{ message.visitorName }}</span>
                  <span class="mt-1 block break-all text-xs text-slate-500">{{ message.visitorEmail }}</span>
                </span>
                <span class="flex shrink-0 flex-wrap gap-2 text-xs font-semibold">
                  <span class="rounded-full bg-blue-50 px-2.5 py-1 text-blue-800">{{ message.status }}</span>
                  <span class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700">Email · {{ message.emailStatus }}</span>
                </span>
              </span>
              <span class="mt-3 block font-mono text-xs text-slate-500">{{ message.createdAt }} · v{{ message.version }}</span>
            </button>
          </li>
        </ul>

        <div
          v-if="appendProblem"
          class="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
          role="alert"
        >
          <p class="font-semibold">{{ appendProblem.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ appendProblem.traceId }}</p>
        </div>

        <button
          v-if="nextCursor !== null || appendProblem"
          class="rounded-xl border border-blue-300 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
          type="button"
          data-action="load-more"
          :disabled="appending || hasExitRisk || nextCursor === null"
          :aria-busy="appending"
          @click="loadMore"
        >{{ appending ? '正在加载更多…' : '加载更多留言' }}</button>
      </div>
    </AsyncPanel>

    <MessageDetailPanel
      v-if="selectedSummary"
      :summary="selectedSummary"
      :message="selectedDetail"
      :loading="detailLoading"
      :detail-problem-title="detailProblem?.title"
      :detail-problem-trace-id="detailProblem?.traceId"
      :mutation-problem-title="mutationProblem?.title"
      :mutation-problem-trace-id="mutationProblem?.traceId"
      :status-busy="statusBusy"
      :status-uncertain="statusUncertain"
      :status-conflict="statusConflict"
      :retry-busy="retryBusy"
      :retry-uncertain="retryUncertain"
      :delete-busy="deleteBusy"
      :delete-uncertain="deleteUncertain"
      :delete-text="deleteText"
      @close="closeDetail()"
      @retry-detail="retryDetail"
      @change-status="changeStatus"
      @reconcile-status="reconcileStatus"
      @retry-email="retryMessageEmail"
      @reconcile-retry="reconcileRetry"
      @update:delete-text="deleteText = $event"
      @delete-message="deleteSelectedMessage"
      @reconcile-delete="reconcileDelete"
    />
  </section>
</template>
