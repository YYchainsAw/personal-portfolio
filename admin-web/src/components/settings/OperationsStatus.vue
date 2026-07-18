<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { settingsApi } from '@/api/settingsApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type {
  MaintenanceView,
  OperationsStatus as OperationsStatusDto,
} from '@/types/settings'

type LoadOperations = () => Promise<OperationsStatusDto>
type OperationKey = Exclude<keyof OperationsStatusDto, 'serverTime'>

interface DisplayProblem {
  readonly title: string
  readonly traceId?: string
}

interface OperationDefinition {
  readonly key: OperationKey
  readonly label: string
  readonly englishLabel: string
}

interface OperationCard extends OperationDefinition {
  readonly value: MaintenanceView | null
}

const definitions: readonly OperationDefinition[] = Object.freeze([
  { key: 'databaseBackup', label: '数据库备份', englishLabel: 'Database backup' },
  { key: 'mediaBackup', label: '媒体备份', englishLabel: 'Media backup' },
  { key: 'analyticsAggregation', label: '分析聚合', englishLabel: 'Analytics aggregation' },
  { key: 'contactRetention', label: '联系人保留清理', englishLabel: 'Contact retention' },
  { key: 'mediaCleanup', label: '媒体清理', englishLabel: 'Media cleanup' },
  { key: 'deployment', label: '部署', englishLabel: 'Deployment' },
  { key: 'restoreDrill', label: '恢复演练', englishLabel: 'Restore drill' },
])

const props = defineProps<{
  load?: LoadOperations
}>()

const status = ref<OperationsStatusDto | null>(null)
const loading = ref(false)
const problem = ref<DisplayProblem | null>(null)

let disposed = false
let generation = 0

const cards = computed<readonly OperationCard[]>(() =>
  Object.freeze(
    definitions.map((definition) =>
      Object.freeze({ ...definition, value: status.value?.[definition.key] ?? null }),
    ),
  ),
)

function displayProblem(cause: unknown): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: '无法加载运维状态 / Unable to load operations status.' }
}

async function refresh(): Promise<void> {
  if (disposed) return
  const operation = ++generation
  loading.value = true
  problem.value = null
  try {
    const result = await (props.load ?? settingsApi.getOperations)()
    if (disposed || operation !== generation) return
    status.value = Object.freeze({ ...result })
  } catch (cause) {
    if (disposed || operation !== generation) return
    problem.value = displayProblem(cause)
  } finally {
    if (!disposed && operation === generation) loading.value = false
  }
}

onMounted(() => {
  void refresh()
})

onBeforeUnmount(() => {
  disposed = true
  generation += 1
})
</script>

<template>
  <section aria-labelledby="settings-operations-title">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="settings-operations-title" class="text-xl font-semibold text-slate-950">
          运维状态
        </h2>
        <p class="mt-1 text-sm text-slate-500">Operations · 只读状态，不提供主机操作</p>
      </div>
      <button
        class="rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        type="button"
        data-action="refresh-operations"
        :disabled="loading"
        @click="refresh"
      >刷新</button>
    </div>

    <AsyncPanel
      class="mt-5"
      :loading="loading"
      :error-title="problem?.title"
      :trace-id="problem?.traceId"
      :on-retry="refresh"
    >
      <div v-if="status !== null" class="space-y-4">
        <p class="text-xs text-slate-500">
          服务器时间：<time :datetime="status.serverTime">{{ status.serverTime }}</time>
        </p>
        <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="card in cards"
            :key="card.key"
            :data-operation-key="card.key"
            class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
          >
            <h3 class="font-semibold text-slate-950">{{ card.label }}</h3>
            <p class="mt-1 text-xs text-slate-500">{{ card.englishLabel }}</p>
            <p v-if="card.value === null" class="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600" data-operation-empty>
              从未记录 / Never recorded
            </p>
            <dl v-else class="mt-5 space-y-3 text-sm">
              <div><dt class="text-xs font-semibold text-slate-500">类型 / Type</dt><dd class="mt-1 break-all text-slate-900">{{ card.value.type }}</dd></div>
              <div><dt class="text-xs font-semibold text-slate-500">状态 / Status</dt><dd class="mt-1 break-all text-slate-900">{{ card.value.status }}</dd></div>
              <div><dt class="text-xs font-semibold text-slate-500">开始 / Started</dt><dd class="mt-1 break-all text-slate-900">{{ card.value.startedAt }}</dd></div>
              <div><dt class="text-xs font-semibold text-slate-500">结束 / Finished</dt><dd class="mt-1 break-all text-slate-900">{{ card.value.finishedAt ?? '未结束 / Not finished' }}</dd></div>
              <div><dt class="text-xs font-semibold text-slate-500">校验和 / Checksum</dt><dd class="mt-1 break-all font-mono text-xs text-slate-900">{{ card.value.artifactChecksum ?? '不可用 / Unavailable' }}</dd></div>
              <div><dt class="text-xs font-semibold text-slate-500">安全错误类别 / Error category</dt><dd class="mt-1 break-all text-slate-900">{{ card.value.errorCategory ?? '无 / None' }}</dd></div>
            </dl>
          </article>
        </div>
      </div>
      <p v-else class="rounded-2xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500" role="status">
        尚无运维快照 / No operations snapshot loaded
      </p>
    </AsyncPanel>
  </section>
</template>
