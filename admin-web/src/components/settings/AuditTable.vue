<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { settingsApi } from '@/api/settingsApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type { AdminAuditItem, AdminAuditPage, AdminAuditQuery } from '@/types/settings'

type LoadAudit = (query?: Readonly<AdminAuditQuery>) => Promise<AdminAuditPage>

interface DisplayProblem {
  readonly title: string
  readonly traceId?: string
}

interface FailedRequest {
  readonly query: Readonly<AdminAuditQuery>
  readonly append: boolean
}

const props = defineProps<{
  load?: LoadAudit
}>()

const items = ref<readonly AdminAuditItem[]>(Object.freeze([]))
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const appending = ref(false)
const loadProblem = ref<DisplayProblem | null>(null)
const appendProblem = ref<DisplayProblem | null>(null)
const queryProblem = ref('')
const filterDirty = ref(false)

const action = ref('')
const outcome = ref<'' | 'SUCCESS' | 'FAILURE'>('')
const fromInput = ref('')
const toInput = ref('')
const limit = ref<number | string>(50)

let disposed = false
let mounted = false
let generation = 0
let appliedQuery: Readonly<AdminAuditQuery> = Object.freeze({ limit: 50 })
let failedRequest: FailedRequest | null = null

function displayProblem(cause: unknown): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: '无法加载审计记录 / Unable to load audit history.' }
}

function canonicalInstant(value: string): string | null {
  if (value === '') return null
  const instant = new Date(value)
  if (!Number.isFinite(instant.getTime())) return null
  return instant.toISOString().replace(/\.000Z$/, 'Z')
}

function safeQuery(): Readonly<AdminAuditQuery> | null {
  queryProblem.value = ''
  const normalizedLimit = typeof limit.value === 'number' ? limit.value : Number(limit.value)
  if (!Number.isSafeInteger(normalizedLimit) || normalizedLimit < 1 || normalizedLimit > 100) {
    queryProblem.value = '每页条数必须是 1–100 的整数 / Limit must be an integer from 1–100.'
    return null
  }

  const normalizedFrom = canonicalInstant(fromInput.value)
  const normalizedTo = canonicalInstant(toInput.value)
  if ((fromInput.value !== '' && normalizedFrom === null) || (toInput.value !== '' && normalizedTo === null)) {
    queryProblem.value = '请选择有效的时间范围 / Choose a valid time range.'
    return null
  }
  if (
    normalizedFrom !== null &&
    normalizedTo !== null &&
    Date.parse(normalizedFrom) >= Date.parse(normalizedTo)
  ) {
    queryProblem.value = '开始时间必须早于结束时间 / From must be before to.'
    return null
  }

  limit.value = normalizedLimit
  const normalizedAction = action.value.trim()
  if (normalizedAction !== '' && !/^[A-Z0-9_]{1,96}$/.test(normalizedAction)) {
    queryProblem.value =
      '动作筛选只能包含 1–96 位大写字母、数字或下划线 / Action must use 1–96 uppercase letters, digits, or underscores.'
    return null
  }
  return Object.freeze({
    ...(normalizedAction === '' ? {} : { action: normalizedAction }),
    ...(outcome.value === '' ? {} : { outcome: outcome.value }),
    ...(normalizedFrom === null ? {} : { from: normalizedFrom }),
    ...(normalizedTo === null ? {} : { to: normalizedTo }),
    limit: normalizedLimit,
  })
}

function appendUnique(existing: readonly AdminAuditItem[], incoming: readonly AdminAuditItem[]) {
  const next = [...existing]
  const seen = new Set(existing.map((item) => item.id.toLowerCase()))
  for (const item of incoming) {
    const key = item.id.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    next.push(Object.freeze({ ...item, metadata: Object.freeze({ ...item.metadata }) }))
  }
  return Object.freeze(next)
}

async function loadSnapshot(query: Readonly<AdminAuditQuery>, append: boolean): Promise<void> {
  if (disposed || (append && (appending.value || query.cursor === undefined))) return
  const operation = ++generation
  failedRequest = null
  if (append) {
    appending.value = true
    appendProblem.value = null
  } else {
    loading.value = true
    loadProblem.value = null
    appendProblem.value = null
    nextCursor.value = null
  }
  try {
    const result = await (props.load ?? settingsApi.getAudit)(query)
    if (disposed || operation !== generation) return
    items.value = append ? appendUnique(items.value, result.items) : appendUnique([], result.items)
    nextCursor.value = result.nextCursor
    filterDirty.value = false
  } catch (cause) {
    if (disposed || operation !== generation) return
    const problem = displayProblem(cause)
    failedRequest = Object.freeze({ query, append })
    if (append) appendProblem.value = problem
    else loadProblem.value = problem
  } finally {
    if (!disposed && operation === generation) {
      if (append) appending.value = false
      else loading.value = false
    }
  }
}

function applyFilters(): void {
  const query = safeQuery()
  if (query === null) return
  appliedQuery = query
  void loadSnapshot(query, false)
}

async function resetFilters(): Promise<void> {
  action.value = ''
  outcome.value = ''
  fromInput.value = ''
  toInput.value = ''
  limit.value = 50
  await nextTick()
  if (disposed) return
  appliedQuery = Object.freeze({ limit: 50 })
  void loadSnapshot(appliedQuery, false)
}

function loadMore(): void {
  if (nextCursor.value === null || filterDirty.value) return
  void loadSnapshot(Object.freeze({ ...appliedQuery, cursor: nextCursor.value }), true)
}

function retryFailed(): void {
  if (failedRequest === null) return
  void loadSnapshot(failedRequest.query, failedRequest.append)
}

function metadataEntries(item: AdminAuditItem): readonly [string, string][] {
  return Object.freeze(
    Object.entries(item.metadata).sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0)),
  )
}

watch([action, outcome, fromInput, toInput, limit], () => {
  if (!mounted) return
  generation += 1
  loading.value = false
  appending.value = false
  nextCursor.value = null
  appendProblem.value = null
  failedRequest = null
  filterDirty.value = true
})

onMounted(() => {
  mounted = true
  void loadSnapshot(appliedQuery, false)
})

onBeforeUnmount(() => {
  disposed = true
  generation += 1
})
</script>

<template>
  <section aria-labelledby="settings-audit-title">
    <div>
      <h2 id="settings-audit-title" class="text-xl font-semibold text-slate-950">安全审计</h2>
      <p class="mt-1 text-sm text-slate-500">Audit history · 只读、不可修改</p>
    </div>

    <form
      class="mt-5 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
      data-audit-filters
      novalidate
      @submit.prevent="applyFilters"
    >
      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
        <label class="text-sm font-medium text-slate-700">
          动作 / Action
          <input v-model="action" data-filter="action" class="mt-2 block w-full rounded-xl border border-slate-300 px-3 py-2" autocomplete="off" />
        </label>
        <label class="text-sm font-medium text-slate-700">
          结果 / Outcome
          <select v-model="outcome" data-filter="outcome" class="mt-2 block w-full rounded-xl border border-slate-300 px-3 py-2">
            <option value="">全部</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="FAILURE">FAILURE</option>
          </select>
        </label>
        <label class="text-sm font-medium text-slate-700">
          开始 / From
          <input v-model="fromInput" data-filter="from" class="mt-2 block w-full rounded-xl border border-slate-300 px-3 py-2" type="datetime-local" step="1" />
        </label>
        <label class="text-sm font-medium text-slate-700">
          结束 / To（不含）
          <input v-model="toInput" data-filter="to" class="mt-2 block w-full rounded-xl border border-slate-300 px-3 py-2" type="datetime-local" step="1" />
        </label>
        <label class="text-sm font-medium text-slate-700">
          每页 / Limit
          <input v-model.number="limit" data-filter="limit" class="mt-2 block w-full rounded-xl border border-slate-300 px-3 py-2" type="number" min="1" max="100" step="1" />
        </label>
      </div>
      <p v-if="queryProblem" class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950" role="alert">{{ queryProblem }}</p>
      <p v-if="filterDirty" class="mt-4 text-xs text-slate-500" role="status">筛选已变化，请应用后继续翻页。</p>
      <div class="mt-4 flex flex-wrap gap-3">
        <button class="rounded-lg bg-blue-700 px-4 py-2 text-sm font-semibold text-white" type="submit" data-action="apply-audit-filters">应用筛选</button>
        <button class="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700" type="button" data-action="reset-audit-filters" @click="resetFilters">重置</button>
      </div>
    </form>

    <AsyncPanel
      class="mt-5"
      :loading="loading"
      :error-title="loadProblem?.title"
      :trace-id="loadProblem?.traceId"
      :empty="!loading && loadProblem === null && items.length === 0"
      :on-retry="retryFailed"
    >
      <template #empty>暂无匹配的审计记录 / No matching audit events</template>
      <ol class="list-none space-y-3 p-0" aria-label="管理员审计记录">
        <li
          v-for="item in items"
          :key="item.id"
          :data-audit-id="item.id"
          class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
        >
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p class="font-semibold text-slate-950">{{ item.action }}</p>
              <p class="mt-1 text-xs text-slate-500">{{ item.targetType }} · {{ item.targetId ?? '无目标 / No target' }}</p>
            </div>
            <span class="rounded-full px-2.5 py-1 text-xs font-semibold" :class="item.outcome === 'SUCCESS' ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-800'">{{ item.outcome }}</span>
          </div>
          <dl class="mt-4 grid gap-3 text-xs text-slate-600 sm:grid-cols-2 lg:grid-cols-4">
            <div><dt class="font-semibold text-slate-500">时间</dt><dd class="mt-1 break-all"><time :datetime="item.timestamp">{{ item.timestamp }}</time></dd></div>
            <div><dt class="font-semibold text-slate-500">操作者</dt><dd class="mt-1 break-all">{{ item.actorAdminId ?? 'SYSTEM' }}</dd></div>
            <div><dt class="font-semibold text-slate-500">请求编号</dt><dd class="mt-1 break-all">{{ item.traceId }}</dd></div>
            <div><dt class="font-semibold text-slate-500">事件 ID</dt><dd class="mt-1 break-all">{{ item.id }}</dd></div>
          </dl>
          <dl v-if="metadataEntries(item).length > 0" class="mt-4 flex flex-wrap gap-2" aria-label="安全元数据">
            <div v-for="entry in metadataEntries(item)" :key="entry[0]" class="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-700">
              <dt class="inline font-semibold">{{ entry[0] }}:</dt>
              <dd class="inline break-all"> {{ entry[1] }}</dd>
            </div>
          </dl>
        </li>
      </ol>
    </AsyncPanel>

    <div v-if="appendProblem" class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" role="alert">
      <p class="font-semibold">{{ appendProblem.title }}</p>
      <p v-if="appendProblem.traceId" class="mt-1 text-xs">请求编号：{{ appendProblem.traceId }}</p>
      <button class="mt-3 rounded-lg border border-amber-400 bg-white px-3 py-2 font-semibold" type="button" data-action="retry-audit-append" @click="retryFailed">重试本页</button>
    </div>
    <button
      v-if="nextCursor !== null"
      class="mt-4 rounded-xl border border-blue-300 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-800 disabled:opacity-50"
      type="button"
      data-action="load-more-audit"
      :disabled="appending || filterDirty"
      @click="loadMore"
    >{{ appending ? '加载中…' : '加载更多审计记录' }}</button>
  </section>
</template>
