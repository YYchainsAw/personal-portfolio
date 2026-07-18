<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'

import { publishingApi } from '@/api/publishingApi'
import { projectApi } from '@/api/projectApi'
import { siteApi } from '@/api/siteApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import {
  PROJECT_CATALOG_ID,
  SITE_ID,
  type AggregateType,
  type RevisionSummaryDto,
} from '@/types/publishing'

type LoadHistory = (
  aggregateType: AggregateType,
  aggregateId: string,
) => Promise<RevisionSummaryDto[]>
type RestoreRevision = (
  revisionId: string,
  request: Readonly<{ expectedWorkspaceVersion: number }>,
) => Promise<void>
type LoadWorkspaceVersion = (
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>,
  aggregateId: string,
) => Promise<number>
type NavigateAfterRestore = (
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>,
  aggregateId: string,
) => Promise<void>
type RestorableTarget = Readonly<{
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>
  aggregateId: string
}>

const props = defineProps<{
  aggregateType: string
  aggregateId: string
  loadHistory?: LoadHistory
  restoreRevision?: RestoreRevision
  loadWorkspaceVersion?: LoadWorkspaceVersion
  navigateAfterRestore?: NavigateAfterRestore
}>()

const router = useRouter()
const revisions = ref<readonly RevisionSummaryDto[]>(Object.freeze([]))
const loading = ref(false)
const loadProblem = ref<ApiProblem | null>(null)
const selectedRevision = ref<RevisionSummaryDto | null>(null)
const restoreBusy = ref(false)
const restoreApplied = ref(false)
const restoreOutcomeUncertain = ref(false)
const restoreProblem = ref<ApiProblem | null>(null)
const announcement = ref('')
const cancelButton = ref<HTMLButtonElement | null>(null)
const refreshRetryButton = ref<HTMLButtonElement | null>(null)
let restoreTrigger: HTMLElement | null = null
let requestGeneration = 0
let restoreGeneration = 0
let disposed = false

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const allowedTypes = new Set<AggregateType>(['SITE', 'PROJECT', 'PROJECT_CATALOG'])

function clientProblem(title: string, code: string): ApiProblem {
  return new ApiProblem({
    type: 'client_validation',
    title,
    status: 0,
    code,
    traceId: 'client',
  })
}

function safeProblem(cause: unknown, title: string, code: string): ApiProblem {
  return cause instanceof ApiProblem ? cause : clientProblem(title, code)
}

function routeContract(): { aggregateType: AggregateType; aggregateId: string } | null {
  const aggregateType = props.aggregateType
  const aggregateId = props.aggregateId
  if (!allowedTypes.has(aggregateType as AggregateType) || !UUID.test(aggregateId)) return null
  if (aggregateType === 'SITE' && aggregateId.toLowerCase() !== SITE_ID) return null
  if (
    aggregateType === 'PROJECT' &&
    (aggregateId.toLowerCase() === SITE_ID || aggregateId.toLowerCase() === PROJECT_CATALOG_ID)
  ) {
    return null
  }
  if (
    aggregateType === 'PROJECT_CATALOG' &&
    aggregateId.toLowerCase() !== PROJECT_CATALOG_ID
  ) {
    return null
  }
  return { aggregateType: aggregateType as AggregateType, aggregateId }
}

const contract = computed(routeContract)
const canRestore = computed(
  () => contract.value !== null && contract.value.aggregateType !== 'PROJECT_CATALOG',
)
const restoreWriteLocked = computed(
  () => restoreApplied.value || restoreOutcomeUncertain.value,
)
const heading = computed(() => {
  switch (contract.value?.aggregateType) {
    case 'SITE':
      return '站点发布历史'
    case 'PROJECT':
      return '项目发布历史'
    case 'PROJECT_CATALOG':
      return '项目目录发布历史'
    default:
      return '发布历史'
  }
})

const backRoute = computed<RouteLocationRaw>(() => {
  const value = contract.value
  if (value?.aggregateType === 'SITE') return { name: 'site' }
  if (value?.aggregateType === 'PROJECT') {
    return { name: 'project-edit', params: { projectId: value.aggregateId } }
  }
  return { name: 'projects' }
})

function immutableDescending(rows: readonly RevisionSummaryDto[]): readonly RevisionSummaryDto[] {
  return Object.freeze(
    [...rows]
      .map((row) => Object.freeze({ ...row }))
      .sort((left, right) => right.version - left.version || right.id.localeCompare(left.id)),
  )
}

async function load(): Promise<void> {
  const value = contract.value
  const operation = ++requestGeneration
  loadProblem.value = null
  revisions.value = Object.freeze([])
  if (value === null) {
    loading.value = false
    loadProblem.value = clientProblem('发布历史地址无效', 'PUBLISHING_HISTORY_ROUTE_INVALID')
    return
  }

  loading.value = true
  try {
    const loader = props.loadHistory ?? publishingApi.history
    const rows = await loader(value.aggregateType, value.aggregateId)
    if (disposed || operation !== requestGeneration) return
    revisions.value = immutableDescending(rows)
  } catch (cause) {
    if (disposed || operation !== requestGeneration) return
    loadProblem.value = safeProblem(
      cause,
      '无法读取发布历史',
      'PUBLISHING_HISTORY_LOAD_FAILED',
    )
  } finally {
    if (!disposed && operation === requestGeneration) loading.value = false
  }
}

function openConfirmation(revision: RevisionSummaryDto, event: MouseEvent): void {
  if (!canRestore.value || restoreBusy.value || restoreWriteLocked.value) return
  selectedRevision.value = revision
  restoreProblem.value = null
  restoreTrigger = event.currentTarget as HTMLElement | null
  void nextTick(() => cancelButton.value?.focus())
}

async function closeConfirmation(): Promise<void> {
  if (restoreBusy.value) return
  selectedRevision.value = null
  await nextTick()
  if (restoreTrigger?.isConnected) restoreTrigger.focus()
  restoreTrigger = null
}

async function defaultWorkspaceVersion(
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>,
  aggregateId: string,
): Promise<number> {
  if (aggregateType === 'SITE') return (await siteApi.get()).version
  return (await projectApi.get(aggregateId)).version
}

async function defaultNavigate(
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>,
  aggregateId: string,
): Promise<void> {
  const destination: RouteLocationRaw =
    aggregateType === 'SITE'
      ? { name: 'site' }
      : { name: 'project-edit', params: { projectId: aggregateId } }
  const failure = await router.replace(destination)
  if (failure !== undefined) throw clientProblem('恢复已完成，但页面跳转被中止', 'RESTORE_NAVIGATION_ABORTED')
}

async function loadCurrentWorkspaceVersion(
  aggregateType: Exclude<AggregateType, 'PROJECT_CATALOG'>,
  aggregateId: string,
): Promise<number> {
  const loader = props.loadWorkspaceVersion ?? defaultWorkspaceVersion
  const currentVersion = await loader(aggregateType, aggregateId)
  if (!Number.isSafeInteger(currentVersion) || currentVersion < 0) {
    throw clientProblem('工作区版本响应无效', 'RESTORE_WORKSPACE_VERSION_INVALID')
  }
  return currentVersion
}

function contractMatches(expected: { aggregateType: AggregateType; aggregateId: string }): boolean {
  const current = contract.value
  return (
    current !== null &&
    current.aggregateType === expected.aggregateType &&
    current.aggregateId.toLowerCase() === expected.aggregateId.toLowerCase()
  )
}

async function verifyAndNavigate(
  operation: number,
  value: RestorableTarget,
): Promise<void> {
  if (!contractMatches(value)) return
  await loadCurrentWorkspaceVersion(value.aggregateType, value.aggregateId)
  if (disposed || operation !== restoreGeneration || !contractMatches(value)) return
  const navigate = props.navigateAfterRestore ?? defaultNavigate
  await navigate(value.aggregateType, value.aggregateId)
}

async function continueAfterAppliedRestore(): Promise<void> {
  if (!restoreWriteLocked.value || restoreBusy.value) return
  const value = contract.value
  if (value === null || value.aggregateType === 'PROJECT_CATALOG') return
  const target: RestorableTarget = {
    aggregateType: value.aggregateType,
    aggregateId: value.aggregateId,
  }
  const operation = ++restoreGeneration
  restoreBusy.value = true
  restoreProblem.value = null
  try {
    await verifyAndNavigate(operation, target)
  } catch (cause) {
    if (disposed || operation !== restoreGeneration) return
    restoreProblem.value = safeProblem(
      cause,
      '历史版本已经恢复，但重新载入编辑器失败；请仅重试刷新',
      'RESTORE_REFRESH_FAILED',
    )
  } finally {
    if (!disposed && operation === restoreGeneration) restoreBusy.value = false
  }
}

async function confirmRestore(): Promise<void> {
  const value = contract.value
  const revision = selectedRevision.value
  if (
    value === null ||
    value.aggregateType === 'PROJECT_CATALOG' ||
    revision === null ||
    restoreBusy.value ||
    restoreWriteLocked.value
  ) {
    return
  }
  const target: RestorableTarget = {
    aggregateType: value.aggregateType,
    aggregateId: value.aggregateId,
  }

  const operation = ++restoreGeneration
  restoreBusy.value = true
  restoreProblem.value = null
  let restoreRequestDispatched = false
  try {
    const expectedWorkspaceVersion = await loadCurrentWorkspaceVersion(
      target.aggregateType,
      target.aggregateId,
    )
    if (disposed || operation !== restoreGeneration || !contractMatches(target)) return
    const restore = props.restoreRevision ?? publishingApi.restore
    restoreRequestDispatched = true
    await restore(revision.id, { expectedWorkspaceVersion })
    if (disposed || operation !== restoreGeneration || !contractMatches(target)) return
    restoreApplied.value = true
    announcement.value = `版本 ${revision.version} 已恢复，正在重新载入编辑器`
    await verifyAndNavigate(operation, target)
  } catch (cause) {
    if (disposed || operation !== restoreGeneration) return
    const definitiveRejection =
      cause instanceof ApiProblem && cause.body.status >= 400 && cause.body.status < 500
    if (restoreRequestDispatched && !restoreApplied.value && !definitiveRejection) {
      restoreOutcomeUncertain.value = true
      announcement.value = '恢复请求结果未确认；为避免重复写入，后续只会重新载入工作区'
    }
    restoreProblem.value = restoreOutcomeUncertain.value
      ? clientProblem(
          '恢复请求已发出，但没有收到确定结果；不会再次提交，请仅重新载入编辑器核对工作区',
          'RESTORE_RESULT_UNCERTAIN',
        )
      : safeProblem(
          cause,
          restoreApplied.value
            ? '历史版本已经恢复，但重新载入编辑器失败；请仅重试刷新'
            : '无法恢复所选历史版本',
          restoreApplied.value ? 'RESTORE_REFRESH_FAILED' : 'REVISION_RESTORE_FAILED',
        )
  } finally {
    if (!disposed && operation === restoreGeneration) {
      restoreBusy.value = false
      if (restoreWriteLocked.value && restoreProblem.value !== null) {
        await nextTick()
        if (!disposed && operation === restoreGeneration) refreshRetryButton.value?.focus()
      }
    }
  }
}

function displayTimestamp(value: string): string {
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp)
    ? new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        timeStyle: 'short',
        hour12: false,
      }).format(timestamp)
    : value
}

watch(
  () => [props.aggregateType, props.aggregateId] as const,
  () => {
    restoreGeneration += 1
    selectedRevision.value = null
    restoreBusy.value = false
    restoreApplied.value = false
    restoreOutcomeUncertain.value = false
    restoreProblem.value = null
    announcement.value = ''
    restoreTrigger = null
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
  restoreGeneration += 1
})
</script>

<template>
  <section class="mx-auto max-w-6xl space-y-6" aria-labelledby="publishing-history-title">
    <header class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
      <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">IMMUTABLE HISTORY</p>
      <h1 id="publishing-history-title" class="mt-2 text-3xl font-semibold text-slate-950">
        {{ heading }}
      </h1>
      <p class="mt-2 text-sm leading-6 text-slate-600">
        每条记录都是只读快照。恢复只会创建新的工作区草稿，不会改写历史或自动发布。
      </p>
      <RouterLink
        class="mt-4 inline-flex rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50"
        :to="backRoute"
      >返回编辑器</RouterLink>
    </header>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <AsyncPanel
      :loading="loading"
      :error-title="loadProblem?.body.title"
      :trace-id="loadProblem?.body.traceId"
      :empty="!loading && loadProblem === null && revisions.length === 0"
      :on-retry="load"
    >
      <template #empty>
        <p class="font-semibold text-slate-800">还没有发布记录</p>
        <p class="mt-1">首次发布成功后，版本会出现在这里。</p>
      </template>

      <div v-if="revisions.length > 0" class="grid gap-4" data-history-list>
        <article
          v-for="revision in revisions"
          :key="revision.id"
          class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
          :data-revision-id="revision.id"
        >
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p class="text-sm font-semibold text-slate-950">版本 {{ revision.version }}</p>
              <p class="mt-1 text-xs text-slate-500">
                Schema v{{ revision.schemaVersion }} ·
                <time :datetime="revision.publishedAt">{{ displayTimestamp(revision.publishedAt) }}</time>
              </p>
            </div>
            <button
              v-if="canRestore"
              class="rounded-xl border border-blue-300 bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-800 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="choose-restore"
              :disabled="restoreBusy || restoreWriteLocked"
              :aria-label="`恢复发布版本 ${revision.version} 到工作区`"
              @click="openConfirmation(revision, $event)"
            >恢复到工作区</button>
          </div>
          <dl class="mt-4 grid gap-3 text-xs text-slate-600 sm:grid-cols-2">
            <div><dt class="font-semibold text-slate-700">Revision ID</dt><dd class="mt-1 break-all font-mono">{{ revision.id }}</dd></div>
            <div><dt class="font-semibold text-slate-700">发布者</dt><dd class="mt-1 break-all font-mono">{{ revision.publishedBy }}</dd></div>
            <div class="sm:col-span-2"><dt class="font-semibold text-slate-700">SHA-256</dt><dd class="mt-1 break-all font-mono">{{ revision.checksum }}</dd></div>
          </dl>
        </article>
      </div>
    </AsyncPanel>

    <section
      v-if="selectedRevision !== null && !restoreWriteLocked"
      class="rounded-2xl border border-amber-300 bg-amber-50 p-5"
      role="region"
      data-restore-confirmation
      aria-labelledby="restore-confirm-title"
      aria-describedby="restore-confirm-description"
    >
      <h2 id="restore-confirm-title" class="text-lg font-semibold text-amber-950">
        恢复版本 {{ selectedRevision.version }}？
      </h2>
      <p id="restore-confirm-description" class="mt-2 text-sm leading-6 text-amber-900">
        当前工作区会被该快照替换为一个新的未发布草稿；现有发布内容和历史记录保持不变。
      </p>
      <div class="mt-4 flex flex-wrap gap-3">
        <button
          ref="cancelButton"
          class="rounded-xl border border-amber-300 bg-white px-4 py-2 text-sm font-semibold text-amber-900"
          type="button"
          data-action="cancel-restore"
          :disabled="restoreBusy"
          @click="closeConfirmation"
        >取消</button>
        <button
          class="rounded-xl bg-amber-700 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-55"
          type="button"
          data-action="confirm-restore"
          :disabled="restoreBusy"
          @click="confirmRestore"
        >{{ restoreBusy ? '正在恢复…' : '确认恢复草稿' }}</button>
      </div>
    </section>

    <div
      v-if="restoreProblem !== null"
      class="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm text-red-800"
      role="alert"
      data-restore-problem
    >
      <p class="font-semibold">{{ restoreProblem.body.title }}</p>
      <p class="mt-1 text-xs">请求编号：{{ restoreProblem.body.traceId }}</p>
      <ul v-if="restoreProblem.body.fieldErrors" class="mt-3 list-disc space-y-1 pl-5">
        <li v-for="(message, path) in restoreProblem.body.fieldErrors" :key="path">
          <span class="font-mono">{{ path }}</span>：{{ message }}
        </li>
      </ul>
      <button
        v-if="restoreWriteLocked"
        ref="refreshRetryButton"
        class="mt-4 rounded-xl border border-red-300 bg-white px-4 py-2 font-semibold"
        type="button"
        data-action="retry-restore-refresh"
        :disabled="restoreBusy"
        @click="continueAfterAppliedRestore"
      >{{ restoreBusy ? '正在重新载入…' : '仅重试重新载入' }}</button>
    </div>
  </section>
</template>
