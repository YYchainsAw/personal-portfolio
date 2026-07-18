<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import { settingsApi } from '@/api/settingsApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type { SessionView } from '@/types/settings'

type LoadSessions = () => Promise<readonly SessionView[]>
type RevokeSession = (metadataId: string) => Promise<void>
type HandleCurrentRevoked = () => void

interface DisplayProblem {
  readonly title: string
  readonly traceId?: string
}

const props = defineProps<{
  load?: LoadSessions
  revoke?: RevokeSession
  handleCurrentRevoked?: HandleCurrentRevoked
}>()

const emit = defineEmits<{
  'current-revoked': []
}>()

const sessions = ref<readonly SessionView[]>(Object.freeze([]))
const loading = ref(false)
const loadProblem = ref<DisplayProblem | null>(null)
const mutationProblem = ref<DisplayProblem | null>(null)
const busyId = ref<string | null>(null)
const uncertainId = ref<string | null>(null)
const currentCandidate = ref<SessionView | null>(null)
const currentConfirmation = ref('')
const pendingLeaveNotice = ref('')
const currentConfirmationInput = ref<HTMLInputElement | null>(null)

let disposed = false
let loadGeneration = 0
let mutationGeneration = 0

function displayProblem(cause: unknown, fallback: string): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: fallback }
}

function isUncertainMutation(cause: unknown): boolean {
  return !(cause instanceof ApiProblem) || cause.body.status === 0 || cause.body.status >= 500
}

function currentRevokePending(): boolean {
  return currentCandidate.value?.current === true && busyId.value === currentCandidate.value.id
}

function formatInstant(value: string | null): string {
  if (value === null) return '不适用 / Not applicable'
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone: 'Asia/Hong_Kong',
  }).format(new Date(value))
}

function formatAccess(value: number): string {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone: 'Asia/Hong_Kong',
  }).format(new Date(value))
}

async function refresh(): Promise<void> {
  if (disposed) return
  const operation = ++loadGeneration
  loading.value = true
  loadProblem.value = null
  try {
    const result = await (props.load ?? settingsApi.listSessions)()
    if (disposed || operation !== loadGeneration) return
    sessions.value = Object.freeze(result.map((session) => Object.freeze({ ...session })))
    if (
      uncertainId.value !== null &&
      !sessions.value.some(
        (session) => session.id === uncertainId.value && session.status === 'ACTIVE',
      )
    ) {
      uncertainId.value = null
      mutationProblem.value = null
    }
  } catch (cause) {
    if (disposed || operation !== loadGeneration) return
    loadProblem.value = displayProblem(cause, '无法加载会话记录 / Unable to load sessions.')
  } finally {
    if (!disposed && operation === loadGeneration) loading.value = false
  }
}

async function revokeConfirmed(session: SessionView): Promise<void> {
  if (disposed || busyId.value !== null || session.status !== 'ACTIVE') return
  const operation = ++mutationGeneration
  busyId.value = session.id
  mutationProblem.value = null
  uncertainId.value = null
  try {
    await (props.revoke ?? settingsApi.revokeSession)(session.id)
    if (session.current) {
      if (props.handleCurrentRevoked !== undefined) props.handleCurrentRevoked()
      else if (!disposed && operation === mutationGeneration) emit('current-revoked')
      return
    }
    if (disposed || operation !== mutationGeneration) return
    currentCandidate.value = null
    currentConfirmation.value = ''
    await refresh()
  } catch (cause) {
    if (disposed || operation !== mutationGeneration) return
    mutationProblem.value = displayProblem(cause, '无法撤销会话 / Unable to revoke session.')
    if (isUncertainMutation(cause)) uncertainId.value = session.id
  } finally {
    if (!disposed && operation === mutationGeneration) busyId.value = null
  }
}

async function requestRevoke(session: SessionView): Promise<void> {
  if (session.status !== 'ACTIVE' || busyId.value !== null || uncertainId.value !== null) return
  if (session.current) {
    currentCandidate.value = session
    currentConfirmation.value = ''
    await nextTick()
    currentConfirmationInput.value?.focus()
    return
  }
  if (!window.confirm('确认撤销这个其他设备上的活动会话？')) return
  await revokeConfirmed(session)
}

function cancelCurrentRevoke(): void {
  if (busyId.value !== null) return
  currentCandidate.value = null
  currentConfirmation.value = ''
}

async function confirmCurrentRevoke(): Promise<void> {
  const candidate = currentCandidate.value
  if (candidate === null || currentConfirmation.value !== 'REVOKE CURRENT SESSION') return
  await revokeConfirmed(candidate)
}

async function reconcileUncertain(): Promise<void> {
  if (uncertainId.value === null || busyId.value !== null) return
  await refresh()
  if (
    uncertainId.value !== null &&
    sessions.value.some(
      (session) => session.id === uncertainId.value && session.status === 'ACTIVE',
    )
  ) {
    mutationProblem.value = {
      title: '该会话仍显示为活动状态；不会自动再次提交撤销。',
    }
  }
}

function warnPendingBeforeUnload(event: BeforeUnloadEvent): void {
  if (!currentRevokePending()) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (!currentRevokePending()) return true
  pendingLeaveNotice.value =
    '当前会话正在撤销。请等待退出完成，避免停留在失效的管理页面。 / Current-session revocation is pending; wait for logout to finish.'
  return false
})

defineExpose({ refresh })

onMounted(() => {
  window.addEventListener('beforeunload', warnPendingBeforeUnload)
  void refresh()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', warnPendingBeforeUnload)
  disposed = true
  loadGeneration += 1
  mutationGeneration += 1
  currentConfirmation.value = ''
  pendingLeaveNotice.value = ''
})
</script>

<template>
  <section aria-labelledby="settings-sessions-title">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="settings-sessions-title" class="text-xl font-semibold text-slate-950">
          登录会话
        </h2>
        <p class="mt-1 text-sm text-slate-500">Sessions · 最近创建的会话排在前面</p>
      </div>
      <button
        class="rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        type="button"
        data-action="refresh-sessions"
        :disabled="loading || busyId !== null"
        @click="refresh"
      >
        刷新
      </button>
    </div>

    <AsyncPanel
      class="mt-5"
      :loading="loading"
      :error-title="loadProblem?.title"
      :trace-id="loadProblem?.traceId"
      :empty="!loading && loadProblem === null && sessions.length === 0"
      :on-retry="refresh"
    >
      <template #empty>暂无会话记录 / No session history</template>

      <div class="overflow-x-auto rounded-2xl border border-slate-200 bg-white shadow-sm">
        <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
          <caption class="sr-only">管理员登录会话历史</caption>
          <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th class="px-4 py-3" scope="col">客户端</th>
              <th class="px-4 py-3" scope="col">状态</th>
              <th class="px-4 py-3" scope="col">创建 / 最近访问</th>
              <th class="px-4 py-3" scope="col">结束 / 原因</th>
              <th class="px-4 py-3" scope="col">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr v-for="session in sessions" :key="session.id" :data-session-id="session.id">
              <td class="max-w-xs px-4 py-4 align-top">
                <p class="break-words font-medium text-slate-900">{{ session.clientSummary }}</p>
                <code class="mt-1 block break-all text-xs text-slate-400">{{ session.id }}</code>
              </td>
              <td class="px-4 py-4 align-top">
                <span
                  class="inline-flex rounded-full px-2.5 py-1 text-xs font-semibold"
                  :class="session.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-slate-700'"
                >{{ session.status }}</span>
                <span
                  v-if="session.current"
                  data-current-session
                  class="ml-2 inline-flex rounded-full bg-blue-50 px-2.5 py-1 text-xs font-semibold text-blue-800"
                >当前 / Current</span>
              </td>
              <td class="px-4 py-4 align-top text-xs leading-6 text-slate-600">
                <p>创建：<time :datetime="session.createdAt">{{ formatInstant(session.createdAt) }}</time></p>
                <p>访问：<time :datetime="new Date(session.lastAccessMillis).toISOString()">{{ formatAccess(session.lastAccessMillis) }}</time></p>
              </td>
              <td class="px-4 py-4 align-top text-xs leading-6 text-slate-600">
                <p>{{ formatInstant(session.endedAt) }}</p>
                <p>{{ session.reason ?? '不适用 / Not applicable' }}</p>
              </td>
              <td class="px-4 py-4 align-top">
                <button
                  v-if="session.status === 'ACTIVE'"
                  class="rounded-lg border border-red-300 bg-white px-3 py-2 text-xs font-semibold text-red-800 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                  type="button"
                  data-action="revoke-session"
                  :data-current="session.current"
                  :disabled="busyId !== null || uncertainId !== null"
                  @click="requestRevoke(session)"
                >{{ busyId === session.id ? '撤销中…' : '撤销会话' }}</button>
                <span v-else class="text-xs text-slate-400">只读 / Read only</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </AsyncPanel>

    <div
      v-if="currentCandidate !== null"
      class="mt-5 rounded-2xl border border-red-300 bg-red-50 p-5"
      role="alertdialog"
      aria-labelledby="current-revoke-title"
    >
      <h3 id="current-revoke-title" class="font-semibold text-red-950">撤销当前会话并退出</h3>
      <p class="mt-2 text-sm leading-6 text-red-900">
        请输入 <code>REVOKE CURRENT SESSION</code>。成功后将直接返回登录页。
      </p>
      <label class="mt-4 block text-sm font-semibold text-red-950">
        确认短语
        <input
          ref="currentConfirmationInput"
          v-model="currentConfirmation"
          data-field="current-revoke-confirmation"
          class="mt-2 block w-full rounded-xl border border-red-300 bg-white px-3 py-2"
          autocomplete="off"
        />
      </label>
      <div class="mt-4 flex flex-wrap gap-3">
        <button
          class="rounded-lg bg-red-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
          type="button"
          data-action="confirm-current-revoke"
          :disabled="currentConfirmation !== 'REVOKE CURRENT SESSION' || busyId !== null"
          @click="confirmCurrentRevoke"
        >确认撤销并退出</button>
        <button
          class="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700"
          type="button"
          :disabled="busyId !== null"
          @click="cancelCurrentRevoke"
        >取消</button>
      </div>
    </div>

    <div
      v-if="mutationProblem !== null"
      class="mt-5 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
      role="alert"
    >
      <p class="font-semibold">{{ mutationProblem.title }}</p>
      <p v-if="mutationProblem.traceId" class="mt-1 text-xs">请求编号：{{ mutationProblem.traceId }}</p>
      <button
        v-if="uncertainId !== null"
        class="mt-3 rounded-lg border border-amber-400 bg-white px-3 py-2 font-semibold"
        type="button"
        data-action="reconcile-session"
        @click="reconcileUncertain"
      >仅刷新会话，不重复撤销</button>
    </div>
    <p
      v-if="pendingLeaveNotice"
      class="mt-5 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm font-medium text-amber-950"
      role="alert"
      data-current-revoke-pending-warning
    >{{ pendingLeaveNotice }}</p>
  </section>
</template>
