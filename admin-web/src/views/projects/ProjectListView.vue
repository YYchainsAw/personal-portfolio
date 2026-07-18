<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { projectApi } from '@/api/projectApi'
import { publishingApi } from '@/api/publishingApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type { ProjectWorkspaceDto } from '@/types/content'
import {
  PROJECT_CATALOG_ID,
  type PublicationResultDto,
  type PublicationStateDto,
  type ReorderCatalogCommand,
} from '@/types/publishing'

type LoadProjects = () => Promise<ProjectWorkspaceDto[]>
type LoadCatalogState = () => Promise<PublicationStateDto>
type ReorderCatalog = (command: ReorderCatalogCommand) => Promise<PublicationResultDto>

const props = withDefaults(
  defineProps<{
    load?: LoadProjects
    loadCatalogState?: LoadCatalogState
    reorderCatalog?: ReorderCatalog
  }>(),
  {
    load: () => projectApi.list(),
    loadCatalogState: () => publishingApi.state('PROJECT_CATALOG', PROJECT_CATALOG_ID),
    reorderCatalog: (command: ReorderCatalogCommand) =>
      publishingApi.reorderCatalog(command),
  },
)

interface DisplayProblem {
  readonly title: string
  readonly traceId: string
}

interface ReorderOutcome {
  readonly aggregateVersion: number
  readonly projectIdsInOrder: readonly string[]
}

const projects = ref<ProjectWorkspaceDto[]>([])
const catalogState = ref<PublicationStateDto | null>(null)
const projectIdsInOrder = ref<readonly string[]>(Object.freeze([]))
const loading = ref(false)
const error = ref<DisplayProblem | null>(null)
const reorderBusy = ref(false)
const reorderProblem = ref<DisplayProblem | null>(null)
const reorderApplied = ref(false)
const reorderAnnouncement = ref('')
const search = ref('')
const status = ref('')
let requestGeneration = 0
let reorderGeneration = 0
let reorderOutcome: ReorderOutcome | null = null
let disposed = false

function safeProblem(cause: unknown, fallback = '无法加载项目列表'): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: fallback, traceId: 'client' }
}

const orderedProjects = computed(() =>
  [...projects.value].sort(
    (left, right) => left.sortOrder - right.sortOrder || left.id.localeCompare(right.id),
  ),
)

const statusOptions = computed(() => {
  const values = new Set<string>()
  for (const project of orderedProjects.value) {
    for (const value of [
      project.translations['zh-CN'].status,
      project.translations.en.status,
    ]) {
      if (value.trim().length > 0) values.add(value)
    }
  }
  return [...values].sort((left, right) => left.localeCompare(right, 'zh-CN'))
})

function searchableValues(project: ProjectWorkspaceDto): string[] {
  return [
    project.id,
    project.externalKey,
    project.slug,
    project.number,
    project.translations['zh-CN'].title,
    project.translations.en.title,
    project.translations['zh-CN'].status,
    project.translations.en.status,
  ]
}

const filteredProjects = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return orderedProjects.value.filter((project) => {
    const matchesStatus =
      status.value.length === 0 ||
      project.translations['zh-CN'].status === status.value ||
      project.translations.en.status === status.value
    const matchesSearch =
      query.length === 0 ||
      searchableValues(project).some((value) =>
        value.toLocaleLowerCase().includes(query),
      )
    return matchesStatus && matchesSearch
  })
})

const filtersActive = computed(
  () => search.value.trim().length > 0 || status.value.length > 0,
)
const publishedProjectIds = computed(
  () =>
    new Set(
      (catalogState.value?.projectIdsInOrder ?? []).map((id) => id.toLowerCase()),
    ),
)
const projectById = computed(
  () => new Map(projects.value.map((project) => [project.id.toLowerCase(), project])),
)
const orderedPublishedProjects = computed(() =>
  projectIdsInOrder.value
    .map((id) => projectById.value.get(id.toLowerCase()))
    .filter((project): project is ProjectWorkspaceDto => project !== undefined),
)
const reorderDirty = computed(() => {
  const authoritative = catalogState.value?.projectIdsInOrder ?? []
  return (
    projectIdsInOrder.value.length === authoritative.length &&
    projectIdsInOrder.value.some(
      (id, index) => id.toLowerCase() !== authoritative[index]?.toLowerCase(),
    )
  )
})
const reorderDisabled = computed(
  () =>
    loading.value ||
    error.value !== null ||
    reorderBusy.value ||
    reorderApplied.value ||
    catalogState.value === null ||
    filtersActive.value,
)

function sameProjectOrder(left: readonly string[], right: readonly string[]): boolean {
  return (
    left.length === right.length &&
    left.every((id, index) => id.toLowerCase() === right[index]?.toLowerCase())
  )
}

function reorderRefreshOutcome(): 'exact' | 'newer' | null {
  const outcome = reorderOutcome
  const state = catalogState.value
  if (outcome === null || state === null || state.version < outcome.aggregateVersion) return null
  if (state.version > outcome.aggregateVersion) return 'newer'
  return sameProjectOrder(state.projectIdsInOrder, outcome.projectIdsInOrder) ? 'exact' : null
}

function finishReorderRefresh(outcome: 'exact' | 'newer'): void {
  reorderApplied.value = false
  reorderOutcome = null
  reorderProblem.value = null
  reorderAnnouncement.value =
    outcome === 'newer'
      ? '排序发布成功；刷新期间目录又有更新，已载入更高版本。'
      : '目录排序发布成功。'
}

function markUnprovenReorderRefresh(): void {
  reorderApplied.value = true
  reorderProblem.value = {
    title: '排序已经发布，但载入的目录尚未证明最新版本；请仅重试刷新',
    traceId: error.value?.traceId ?? 'client',
  }
}

function validateCatalogProjection(
  nextProjects: readonly ProjectWorkspaceDto[],
  nextState: PublicationStateDto,
): void {
  const ids = new Set(nextProjects.map((project) => project.id.toLowerCase()))
  if (nextState.projectIdsInOrder.some((id) => !ids.has(id.toLowerCase()))) {
    throw new Error('catalog publication state references an unknown project')
  }
}

async function loadProjects(options: { readonly preserveReorderProblem?: boolean } = {}): Promise<boolean> {
  const operation = ++requestGeneration
  loading.value = true
  error.value = null
  try {
    const [result, nextState] = await Promise.all([
      props.load(),
      props.loadCatalogState(),
    ])
    if (disposed || operation !== requestGeneration) return false
    validateCatalogProjection(result, nextState)
    projects.value = [...result]
    catalogState.value = nextState
    projectIdsInOrder.value = Object.freeze([...nextState.projectIdsInOrder])
    if (!options.preserveReorderProblem) reorderProblem.value = null
    return true
  } catch (cause) {
    if (disposed || operation !== requestGeneration) return false
    error.value = safeProblem(cause)
    return false
  } finally {
    if (!disposed && operation === requestGeneration) loading.value = false
  }
}

function movePublishedProject(index: number, direction: -1 | 1): void {
  if (reorderDisabled.value) return
  const destination = index + direction
  const ids = [...projectIdsInOrder.value]
  if (index < 0 || destination < 0 || index >= ids.length || destination >= ids.length) {
    return
  }
  const [moved] = ids.splice(index, 1)
  if (moved === undefined) return
  ids.splice(destination, 0, moved)
  projectIdsInOrder.value = Object.freeze(ids)
  reorderProblem.value = null
  reorderAnnouncement.value = `已将发布项目移动到第 ${destination + 1} 位，尚未发布排序。`
}

function resetPublishedOrder(): void {
  if (reorderDisabled.value || catalogState.value === null) return
  projectIdsInOrder.value = Object.freeze([...catalogState.value.projectIdsInOrder])
  reorderProblem.value = null
  reorderAnnouncement.value = '已撤销尚未发布的排序调整。'
}

async function publishCatalogOrder(): Promise<void> {
  const state = catalogState.value
  if (reorderDisabled.value || !reorderDirty.value || state === null) return
  if (!window.confirm('确认发布当前项目展示顺序？')) return

  const operation = ++reorderGeneration
  reorderBusy.value = true
  reorderProblem.value = null
  reorderAnnouncement.value = ''
  try {
    const submittedOrder = Object.freeze([...projectIdsInOrder.value])
    const result = await props.reorderCatalog({
      expectedCatalogVersion: state.version,
      projectIdsInOrder: [...submittedOrder],
    })
    if (disposed || operation !== reorderGeneration) return
    reorderOutcome = Object.freeze({
      aggregateVersion: result.aggregateVersion,
      projectIdsInOrder: submittedOrder,
    })
    reorderApplied.value = true
    reorderAnnouncement.value = '目录排序已发布，正在重新载入最新状态。'
    const refreshed = await loadProjects()
    if (disposed || operation !== reorderGeneration) return
    const proof = refreshed ? reorderRefreshOutcome() : null
    if (proof === null) {
      if (refreshed) {
        markUnprovenReorderRefresh()
      } else {
        reorderApplied.value = true
        reorderProblem.value = {
          title: '排序已经发布，但最新目录载入失败；请仅重试刷新',
          traceId: error.value?.traceId ?? 'client',
        }
      }
    } else {
      finishReorderRefresh(proof)
    }
  } catch (cause) {
    if (disposed || operation !== reorderGeneration) return
    const problem = safeProblem(cause, '无法发布项目排序')
    reorderProblem.value = problem
    if (cause instanceof ApiProblem && cause.body.status === 409) {
      reorderAnnouncement.value = '目录版本已变化，正在重新载入；不会自动重试发布。'
      await loadProjects({ preserveReorderProblem: true })
    }
  } finally {
    if (!disposed && operation === reorderGeneration) reorderBusy.value = false
  }
}

async function retryRefreshAfterReorder(): Promise<void> {
  if (!reorderApplied.value || reorderBusy.value) return
  const operation = ++reorderGeneration
  reorderBusy.value = true
  reorderProblem.value = null
  try {
    const refreshed = await loadProjects()
    if (disposed || operation !== reorderGeneration) return
    const proof = refreshed ? reorderRefreshOutcome() : null
    if (proof !== null) {
      finishReorderRefresh(proof)
    } else if (refreshed) {
      markUnprovenReorderRefresh()
    } else {
      reorderApplied.value = true
      reorderProblem.value = {
        title: '排序已经发布，但最新目录仍无法载入；请仅重试刷新',
        traceId: error.value?.traceId ?? 'client',
      }
    }
  } finally {
    if (!disposed && operation === reorderGeneration) reorderBusy.value = false
  }
}

async function retryLoadProjects(): Promise<void> {
  await loadProjects()
}

onMounted(() => void loadProjects())
onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
  reorderGeneration += 1
  reorderOutcome = null
})
</script>

<template>
  <section class="space-y-6" aria-labelledby="project-catalog-title">
    <header class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">PROJECT CATALOG</p>
        <h1 id="project-catalog-title" class="mt-2 text-3xl font-semibold text-slate-950">
          项目与作品
        </h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
          管理双语项目资料、展示媒体与内容模块。目录排序会在发布阶段统一处理。
        </p>
      </div>
      <div class="flex flex-wrap gap-3">
        <RouterLink
          class="inline-flex items-center justify-center rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50"
          :to="{
            name: 'publishing-history',
            params: { aggregateType: 'PROJECT_CATALOG', aggregateId: PROJECT_CATALOG_ID },
          }"
        >
          目录发布历史
        </RouterLink>
        <RouterLink
          class="inline-flex items-center justify-center rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800"
          :to="{ name: 'project-new' }"
        >
          新建项目
        </RouterLink>
      </div>
    </header>

    <AsyncPanel
      :loading="loading"
      :error-title="projects.length === 0 ? error?.title : undefined"
      :trace-id="projects.length === 0 ? error?.traceId : undefined"
      :empty="!loading && error === null && projects.length === 0"
      :on-retry="retryLoadProjects"
    >
      <template #empty>
        <p class="font-semibold text-slate-800">还没有项目</p>
        <p class="mt-1">创建第一个项目后，它会出现在这里。</p>
      </template>

      <div v-if="projects.length > 0" class="space-y-5">
        <div
          v-if="error && !reorderApplied"
          class="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-900"
          role="alert"
        >
          <p class="font-semibold">{{ error.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ error.traceId }}</p>
          <button
            class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold"
            type="button"
            data-action="retry-project-catalog-load"
            :disabled="loading"
            @click="retryLoadProjects"
          >重试载入最新目录</button>
        </div>

        <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
          {{ reorderAnnouncement }}
        </p>

        <section
          class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
          aria-labelledby="catalog-order-title"
          data-catalog-order
        >
          <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">PUBLISHED ORDER</p>
              <h2 id="catalog-order-title" class="mt-2 text-xl font-semibold text-slate-950">
                公开项目展示顺序
              </h2>
              <p class="mt-2 text-sm leading-6 text-slate-600">
                这里只排列当前已经发布的项目。筛选项目时排序会锁定，避免误改完整目录。
              </p>
            </div>
            <span class="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">
              目录版本 {{ catalogState?.version ?? '—' }}
            </span>
          </div>

          <div
            v-if="reorderProblem"
            class="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
            role="alert"
            data-reorder-problem
          >
            <p class="font-semibold">{{ reorderProblem.title }}</p>
            <p class="mt-1 text-xs">请求编号：{{ reorderProblem.traceId }}</p>
            <button
              v-if="reorderApplied"
              class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold"
              type="button"
              data-action="retry-reorder-refresh"
              :disabled="reorderBusy"
              @click="retryRefreshAfterReorder"
            >
              {{ reorderBusy ? '正在刷新…' : '仅重试刷新' }}
            </button>
          </div>

          <p
            v-if="filtersActive"
            class="mt-4 rounded-xl border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900"
            role="status"
          >
            当前正在筛选项目；清空筛选后才能调整完整发布顺序。
          </p>

          <ol v-if="orderedPublishedProjects.length > 0" class="mt-5 grid gap-3">
            <li
              v-for="(project, index) in orderedPublishedProjects"
              :key="project.id"
              class="flex flex-col gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 sm:flex-row sm:items-center sm:justify-between"
              :data-published-order-id="project.id"
            >
              <div class="min-w-0">
                <p class="text-xs font-semibold text-slate-500">第 {{ index + 1 }} 位</p>
                <p class="mt-1 truncate font-semibold text-slate-950">
                  {{ project.translations['zh-CN'].title || project.translations.en.title || project.slug }}
                </p>
              </div>
              <div class="flex gap-2">
                <button
                  class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
                  type="button"
                  data-action="move-published-up"
                  :disabled="reorderDisabled || index === 0"
                  :aria-label="`将 ${project.translations['zh-CN'].title || project.slug} 上移`"
                  @click="movePublishedProject(index, -1)"
                >上移</button>
                <button
                  class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
                  type="button"
                  data-action="move-published-down"
                  :disabled="reorderDisabled || index === orderedPublishedProjects.length - 1"
                  :aria-label="`将 ${project.translations['zh-CN'].title || project.slug} 下移`"
                  @click="movePublishedProject(index, 1)"
                >下移</button>
              </div>
            </li>
          </ol>
          <p v-else class="mt-5 text-sm text-slate-500">还没有已发布项目，目录顺序为空。</p>

          <div class="mt-5 flex flex-wrap gap-3 border-t border-slate-100 pt-4">
            <button
              class="rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="publish-catalog-order"
              :disabled="reorderDisabled || !reorderDirty"
              @click="publishCatalogOrder"
            >{{ reorderBusy ? '正在发布排序…' : '发布展示顺序' }}</button>
            <button
              class="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="reset-catalog-order"
              :disabled="reorderDisabled || !reorderDirty"
              @click="resetPublishedOrder"
            >撤销排序调整</button>
          </div>
        </section>

        <div class="grid gap-4 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm md:grid-cols-[minmax(0,1fr)_16rem]">
          <label class="text-sm font-semibold text-slate-800">
            搜索项目
            <input
              v-model="search"
              class="mt-2 w-full rounded-xl border border-slate-300 px-4 py-2.5 font-normal text-slate-950"
              type="search"
              autocomplete="off"
              data-filter="search"
              placeholder="标题、Slug、编号或状态"
            />
          </label>
          <label class="text-sm font-semibold text-slate-800">
            项目状态
            <select
              v-model="status"
              class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-4 py-2.5 font-normal text-slate-950"
              data-filter="status"
            >
              <option value="">全部状态</option>
              <option v-for="option in statusOptions" :key="option" :value="option">
                {{ option }}
              </option>
            </select>
          </label>
        </div>

        <p class="text-sm text-slate-600" data-result-count role="status" aria-live="polite">
          显示 {{ filteredProjects.length }} / {{ projects.length }} 个项目
        </p>

        <div
          v-if="filteredProjects.length === 0"
          class="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center"
          data-project-empty
          role="status"
        >
          <p class="font-semibold text-slate-800">没有匹配的项目</p>
          <p class="mt-1 text-sm text-slate-500">请调整搜索词或状态筛选。</p>
        </div>

        <div v-else class="grid gap-4 xl:grid-cols-2" aria-label="项目列表" role="list">
          <article
            v-for="project in filteredProjects"
            :key="project.id"
            class="flex flex-col justify-between gap-5 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
            :data-project-id="project.id"
            role="listitem"
          >
            <div>
              <div class="flex flex-wrap items-center gap-2 text-xs font-semibold">
                <span class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700">
                  {{ project.number }}
                </span>
                <span v-if="project.visible" class="rounded-full bg-emerald-100 px-2.5 py-1 text-emerald-800">
                  公开
                </span>
                <span v-else class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-600">
                  隐藏
                </span>
                <span v-if="project.featured" class="rounded-full bg-blue-100 px-2.5 py-1 text-blue-800">
                  精选
                </span>
                <span v-if="project.publicationDirty" class="rounded-full bg-amber-100 px-2.5 py-1 text-amber-900">
                  待发布
                </span>
                <span
                  v-if="publishedProjectIds.has(project.id.toLowerCase())"
                  class="rounded-full bg-blue-100 px-2.5 py-1 text-blue-800"
                >已发布</span>
                <span v-else class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-600">
                  未公开发布
                </span>
              </div>
              <h2 class="mt-4 text-xl font-semibold text-slate-950">
                {{ project.translations['zh-CN'].title || '未填写中文标题' }}
              </h2>
              <p class="mt-1 text-sm text-slate-600" lang="en">
                {{ project.translations.en.title || 'Untitled project' }}
              </p>
              <p class="mt-3 text-sm text-slate-500">
                {{ project.slug }} · {{ project.translations['zh-CN'].status || '未填写状态' }}
              </p>
            </div>

            <div class="flex items-center justify-between gap-4 border-t border-slate-100 pt-4">
              <span class="text-xs text-slate-500">工作区版本 {{ project.version }}</span>
              <RouterLink
                class="rounded-lg border border-blue-300 bg-blue-50 px-3 py-2 text-sm font-semibold text-blue-800 hover:bg-blue-100"
                :to="{ name: 'project-edit', params: { projectId: project.id } }"
                :aria-label="`编辑项目：${project.translations['zh-CN'].title || project.translations.en.title || project.slug}`"
              >
                编辑项目
              </RouterLink>
            </div>
          </article>
        </div>
      </div>
    </AsyncPanel>
  </section>
</template>
