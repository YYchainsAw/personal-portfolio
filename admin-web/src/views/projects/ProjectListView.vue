<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { projectApi } from '@/api/projectApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import { ApiProblem } from '@/types/api'
import type { ProjectWorkspaceDto } from '@/types/content'

type LoadProjects = () => Promise<ProjectWorkspaceDto[]>

const props = withDefaults(defineProps<{ load?: LoadProjects }>(), {
  load: () => projectApi.list(),
})

interface DisplayProblem {
  readonly title: string
  readonly traceId: string
}

const projects = ref<ProjectWorkspaceDto[]>([])
const loading = ref(false)
const error = ref<DisplayProblem | null>(null)
const search = ref('')
const status = ref('')
let requestGeneration = 0
let disposed = false

function safeProblem(cause: unknown): DisplayProblem {
  if (cause instanceof ApiProblem) {
    return { title: cause.body.title, traceId: cause.body.traceId }
  }
  return { title: '无法加载项目列表', traceId: 'client' }
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

async function loadProjects(): Promise<void> {
  const operation = ++requestGeneration
  loading.value = true
  error.value = null
  try {
    const result = await props.load()
    if (disposed || operation !== requestGeneration) return
    projects.value = [...result]
  } catch (cause) {
    if (disposed || operation !== requestGeneration) return
    projects.value = []
    error.value = safeProblem(cause)
  } finally {
    if (!disposed && operation === requestGeneration) loading.value = false
  }
}

onMounted(() => void loadProjects())
onBeforeUnmount(() => {
  disposed = true
  requestGeneration += 1
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
      <RouterLink
        class="inline-flex items-center justify-center rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800"
        :to="{ name: 'project-new' }"
      >
        新建项目
      </RouterLink>
    </header>

    <AsyncPanel
      :loading="loading"
      :error-title="error?.title"
      :trace-id="error?.traceId"
      :empty="!loading && error === null && projects.length === 0"
      :on-retry="loadProjects"
    >
      <template #empty>
        <p class="font-semibold text-slate-800">还没有项目</p>
        <p class="mt-1">创建第一个项目后，它会出现在这里。</p>
      </template>

      <div v-if="projects.length > 0" class="space-y-5">
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
