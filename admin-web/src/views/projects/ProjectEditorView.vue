<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'

import { projectApi } from '@/api/projectApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import ConflictBanner from '@/components/common/ConflictBanner.vue'
import TranslationTabs from '@/components/common/TranslationTabs.vue'
import ProjectMetadataForm from '@/components/projects/ProjectMetadataForm.vue'
import { useVersionedDraft } from '@/composables/useVersionedDraft'
import { ApiProblem, type FieldErrors, type VersionedDraft } from '@/types/api'
import type {
  Locale,
  ProjectWorkspaceDto,
  SaveWorkspaceRequest,
  TaxonomyWorkspaceDto,
  TranslationStatus,
} from '@/types/content'

type EditorMode = 'create' | 'edit'
type LoadProject = (projectId: string) => Promise<VersionedDraft<ProjectWorkspaceDto>>
type SaveProject = (
  projectId: string,
  request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
) => Promise<VersionedDraft<ProjectWorkspaceDto>>
type CreateProject = (
  workspace: ProjectWorkspaceDto,
) => Promise<VersionedDraft<ProjectWorkspaceDto>>
type ListProjects = () => Promise<ProjectWorkspaceDto[]>
type LoadTaxonomy = () => Promise<TaxonomyWorkspaceDto[]>

const props = withDefaults(
  defineProps<{
    mode: EditorMode
    projectId?: string
    loadProject?: LoadProject
    saveProject?: SaveProject
    createProject?: CreateProject
    listProjects?: ListProjects
    loadTags?: LoadTaxonomy
    loadSkills?: LoadTaxonomy
  }>(),
  {
    projectId: '',
    loadProject: (projectId: string) => projectApi.get(projectId),
    saveProject: (
      projectId: string,
      request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
    ) => projectApi.save(projectId, request),
    createProject: (workspace: ProjectWorkspaceDto) => projectApi.create(workspace),
    listProjects: () => projectApi.list(),
    loadTags: () => projectApi.tags(),
    loadSkills: () => projectApi.skills(),
  },
)

const router = useRouter()
const activeLocale = ref<Locale>('zh-CN')
const tags = ref<TaxonomyWorkspaceDto[]>([])
const skills = ref<TaxonomyWorkspaceDto[]>([])
const catalogLoading = ref(false)
const catalogError = ref<ApiProblem | null>(null)
const localErrors = ref<FieldErrors>({})
const createSlug = ref('')
const createNumber = ref('')
const createPending = ref(false)
const createError = ref<ApiProblem | null>(null)
const creationComplete = ref(false)
const createdProjectId = ref<string | null>(null)
const navigationPending = ref(false)
const navigationError = ref<ApiProblem | null>(null)
let catalogGeneration = 0
let createGeneration = 0
let navigationGeneration = 0
let disposed = false

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const JAVA_INT_MAX = 2_147_483_647
const COPY_FIELDS = Object.freeze([
  'status',
  'eyebrow',
  'title',
  'summary',
  'seoTitle',
  'seoDescription',
] as const)
const EMPTY_TRANSLATION_STATUS: TranslationStatus = Object.freeze({
  'zh-CN': Object.freeze({ complete: 0, total: COPY_FIELDS.length }),
  en: Object.freeze({ complete: 0, total: COPY_FIELDS.length }),
})

function projectTranslationStatus(workspace: ProjectWorkspaceDto): TranslationStatus {
  const status = {} as Record<Locale, Readonly<{ complete: number; total: number }>>
  for (const locale of ['zh-CN', 'en'] as const) {
    const complete = COPY_FIELDS.reduce(
      (count, field) =>
        count + (workspace.translations[locale][field].trim().length > 0 ? 1 : 0),
      0,
    )
    status[locale] = Object.freeze({ complete, total: COPY_FIELDS.length })
  }
  return Object.freeze(status)
}

function clientProblem(title: string, code: string, fieldErrors?: FieldErrors): ApiProblem {
  return new ApiProblem({
    type: 'client_validation',
    title,
    status: 0,
    code,
    traceId: 'client',
    ...(fieldErrors === undefined ? {} : { fieldErrors }),
  })
}

function displayProblem(cause: unknown, fallback: string, code: string): ApiProblem {
  return cause instanceof ApiProblem ? cause : clientProblem(fallback, code)
}

function isPersistableHttpsUrl(value: string): boolean {
  if (!value.startsWith('https://')) return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname.length > 0
  } catch {
    return false
  }
}

function validateWorkspace(workspace: ProjectWorkspaceDto): FieldErrors {
  const errors: Record<string, string> = {}
  if (!SLUG.test(workspace.slug) || workspace.slug.length > 120) {
    errors.slug = 'Slug 只能包含小写英文字母、数字和连字符，且不能超过 120 个字符'
  }
  if (workspace.number.trim().length === 0 || workspace.number.length > 16) {
    errors.number = '项目编号不能为空，且不能超过 16 个字符'
  }
  if (workspace.externalKey.trim().length === 0) {
    errors.externalKey = '稳定标识不能为空'
  }
  if (
    !Number.isSafeInteger(workspace.sortOrder) ||
    workspace.sortOrder < 0 ||
    workspace.sortOrder > JAVA_INT_MAX
  ) {
    errors.sortOrder = '目录排序必须是非负整数'
  }
  const mediaKeys = new Set<string>()
  const mediaOrders = new Set<string>()
  workspace.media.forEach((item, index) => {
    const key = `${item.assetId}:${item.usage}`
    const order = `${item.usage}:${item.sortOrder}`
    if (mediaKeys.has(key)) errors[`media.${index}.usage`] = '同一媒体不能重复用于相同位置'
    if (mediaOrders.has(order)) errors[`media.${index}.sortOrder`] = '同一用途的媒体排序不能重复'
    mediaKeys.add(key)
    mediaOrders.add(order)
    if (!UUID.test(item.assetId)) errors[`media.${index}.assetId`] = '媒体资源编号无效'
    if (!isPersistableHttpsUrl(item.sourceUrl)) {
      errors[`media.${index}.sourceUrl`] = '来源必须使用有效的 HTTPS 链接'
    }
    if (item.objectPosition.length > 64) {
      errors[`media.${index}.objectPosition`] = '图片焦点位置不能超过 64 个字符'
    }
  })
  return errors
}

async function loadDraft(): Promise<VersionedDraft<ProjectWorkspaceDto>> {
  const projectId = props.projectId
  if (!UUID.test(projectId)) {
    throw clientProblem('项目编号无效', 'PROJECT_ID_INVALID')
  }
  return props.loadProject(projectId)
}

async function saveDraft(
  request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
): Promise<VersionedDraft<ProjectWorkspaceDto>> {
  const validation = validateWorkspace(request.workspace)
  if (Object.keys(validation).length > 0) {
    localErrors.value = validation
    throw clientProblem('请先修正项目表单', 'CLIENT_PROJECT_VALIDATION', validation)
  }
  localErrors.value = {}
  const workspace = {
    ...request.workspace,
    version: request.expectedVersion,
  }
  return props.saveProject(props.projectId, {
    expectedVersion: request.expectedVersion,
    workspace,
  })
}

const {
  draft,
  version,
  loading,
  saving,
  dirty,
  error,
  conflict,
  reload,
  saveNow,
  reset: resetDraft,
} = useVersionedDraft<ProjectWorkspaceDto>({
  load: loadDraft,
  save: saveDraft,
  retryValidationAfterEdit: (problem) => problem.body.code === 'CLIENT_PROJECT_VALIDATION',
  isConflict: (problem) =>
    problem.body.status === 409 && problem.body.code === 'CONTENT_VERSION_CONFLICT',
})

const completion = computed(() =>
  draft.value === null
    ? EMPTY_TRANSLATION_STATUS
    : projectTranslationStatus(draft.value),
)

const fieldErrors = computed<FieldErrors>(() => ({
  ...(error.value?.body.fieldErrors ?? {}),
  ...localErrors.value,
}))

const saveState = computed(() => {
  if (saving.value) return '正在保存…'
  if (conflict.value !== null) return '存在版本冲突'
  if (dirty.value) return '有未保存修改'
  return `已保存 · 版本 ${version.value}`
})

async function loadTaxonomies(): Promise<void> {
  const operation = ++catalogGeneration
  catalogLoading.value = true
  catalogError.value = null
  try {
    const [nextTags, nextSkills] = await Promise.all([
      props.loadTags(),
      props.loadSkills(),
    ])
    if (disposed || operation !== catalogGeneration) return
    tags.value = [...nextTags]
    skills.value = [...nextSkills]
  } catch (cause) {
    if (disposed || operation !== catalogGeneration) return
    catalogError.value = displayProblem(
      cause,
      '分类目录暂时无法加载',
      'TAXONOMY_LOAD_FAILED',
    )
  } finally {
    if (!disposed && operation === catalogGeneration) catalogLoading.value = false
  }
}

async function reloadEverything(): Promise<void> {
  if (props.mode !== 'edit') return
  resetDraft()
  localErrors.value = {}
  await Promise.all([reload(), loadTaxonomies()])
}

function replaceDraft(workspace: ProjectWorkspaceDto): void {
  localErrors.value = {}
  draft.value = workspace
}

async function manualSave(): Promise<void> {
  if (draft.value === null) return
  const validation = validateWorkspace(draft.value)
  localErrors.value = validation
  if (Object.keys(validation).length > 0) return
  await saveNow()
}

function freshWorkspace(slug: string, number: string, sortOrder: number): ProjectWorkspaceDto {
  return {
    id: globalThis.crypto.randomUUID(),
    externalKey: slug,
    slug,
    number,
    sortOrder,
    featured: false,
    visible: false,
    publicationDirty: true,
    version: 0,
    translations: {
      'zh-CN': {
        status: '筹备中',
        eyebrow: '游戏开发',
        title: slug,
        summary: '项目内容正在整理中。',
        seoTitle: `${slug}｜易嘉轩`,
        seoDescription: '易嘉轩的游戏开发项目。',
      },
      en: {
        status: 'Draft',
        eyebrow: 'Game development',
        title: slug,
        summary: 'Project details are being prepared.',
        seoTitle: `${slug} | Yijiaxuan Yi`,
        seoDescription: 'A game development project by Yijiaxuan Yi.',
      },
    },
    tags: [],
    skills: [],
    media: [],
    blocks: [],
  }
}

const createFieldErrors = computed<FieldErrors>(() => ({
  ...(createError.value?.body.fieldErrors ?? {}),
  ...localErrors.value,
}))

function validateCreateIdentity(): boolean {
  const errors: Record<string, string> = {}
  if (!SLUG.test(createSlug.value) || createSlug.value.length > 96) {
    errors.slug = 'Slug 只能包含小写英文字母、数字和连字符，创建时不能超过 96 个字符'
  }
  if (createNumber.value.trim().length === 0 || createNumber.value.length > 16) {
    errors.number = '项目编号不能为空，且不能超过 16 个字符'
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

async function createWorkspace(): Promise<void> {
  if (createdProjectId.value !== null) {
    await navigateToCreatedProject()
    return
  }
  if (createPending.value || !validateCreateIdentity()) return
  const operation = ++createGeneration
  createPending.value = true
  createError.value = null
  try {
    const projects = await props.listProjects()
    if (disposed || operation !== createGeneration) return
    const maximum = projects.reduce(
      (value, project) => Math.max(value, project.sortOrder),
      -1,
    )
    if (!Number.isSafeInteger(maximum) || maximum >= JAVA_INT_MAX) {
      throw clientProblem('项目排序空间已用尽', 'PROJECT_SORT_ORDER_EXHAUSTED')
    }
    const result = await props.createProject(
      freshWorkspace(createSlug.value, createNumber.value.trim(), maximum + 1),
    )
    if (disposed || operation !== createGeneration) return
    createdProjectId.value = result.value.id
    creationComplete.value = true
    await navigateToCreatedProject()
  } catch (cause) {
    if (disposed || operation !== createGeneration) return
    creationComplete.value = false
    createError.value = displayProblem(cause, '无法创建项目', 'PROJECT_CREATE_FAILED')
  } finally {
    if (!disposed && operation === createGeneration) createPending.value = false
  }
}

async function navigateToCreatedProject(): Promise<void> {
  const projectId = createdProjectId.value
  if (projectId === null || navigationPending.value) return
  const operation = ++navigationGeneration
  navigationPending.value = true
  navigationError.value = null
  try {
    const failure = await router.replace({
      name: 'project-edit',
      params: { projectId },
    })
    if (disposed || operation !== navigationGeneration) return
    if (failure !== undefined) {
      navigationError.value = clientProblem(
        '项目已创建，但页面跳转被中止；请重试进入编辑器',
        'PROJECT_NAVIGATION_ABORTED',
      )
    }
  } catch {
    if (disposed || operation !== navigationGeneration) return
    navigationError.value = clientProblem(
      '项目已创建，但暂时无法进入编辑器；请重试跳转',
      'PROJECT_NAVIGATION_FAILED',
    )
  } finally {
    if (!disposed && operation === navigationGeneration) navigationPending.value = false
  }
}

watch(
  () => [props.mode, props.projectId] as const,
  ([mode]) => {
    activeLocale.value = 'zh-CN'
    localErrors.value = {}
    if (mode === 'edit') {
      navigationGeneration += 1
      creationComplete.value = false
      createdProjectId.value = null
      navigationPending.value = false
      navigationError.value = null
      void reloadEverything()
      return
    }
    createGeneration += 1
    navigationGeneration += 1
    resetDraft()
    tags.value = []
    skills.value = []
    catalogError.value = null
    catalogLoading.value = false
    createSlug.value = ''
    createNumber.value = ''
    createError.value = null
    createPending.value = false
    creationComplete.value = false
    createdProjectId.value = null
    navigationPending.value = false
    navigationError.value = null
  },
  { immediate: true },
)

function hasUnpersistedCreateDraft(): boolean {
  return (
    props.mode === 'create' &&
    (createSlug.value.trim().length > 0 || createNumber.value.trim().length > 0)
  )
}

function confirmDiscard(): boolean {
  if (creationComplete.value) return true
  if (!dirty.value && !hasUnpersistedCreateDraft()) return true
  return window.confirm('当前页面有尚未保存的修改，确定离开吗？')
}

function beforeUnloadCreate(event: BeforeUnloadEvent): void {
  if (creationComplete.value || !hasUnpersistedCreateDraft()) return
  event.preventDefault()
}

onBeforeRouteUpdate(confirmDiscard)
onBeforeRouteLeave(confirmDiscard)

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', beforeUnloadCreate)
}

onBeforeUnmount(() => {
  disposed = true
  catalogGeneration += 1
  createGeneration += 1
  navigationGeneration += 1
  if (typeof window !== 'undefined') {
    window.removeEventListener('beforeunload', beforeUnloadCreate)
  }
})
</script>

<template>
  <section class="mx-auto max-w-6xl space-y-6" aria-labelledby="project-editor-title">
    <header class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm">
      <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">PROJECT WORKSPACE</p>
          <h1 id="project-editor-title" class="mt-2 text-3xl font-semibold text-slate-950">
            {{ mode === 'create' ? '创建项目' : '编辑项目' }}
          </h1>
          <p class="mt-2 text-sm leading-6 text-slate-600">
            {{ mode === 'create'
              ? '先建立稳定标识和目录位置，再进入完整的双语编辑工作区。'
              : '编辑双语元数据、分类与展示媒体；发布前可以持续自动保存。' }}
          </p>
        </div>
        <div v-if="mode === 'edit'" class="text-right">
          <p class="text-xs font-semibold tracking-wide text-slate-500">保存状态</p>
          <p class="mt-1 text-sm font-semibold text-slate-800" data-save-state>{{ saveState }}</p>
          <button
            class="mt-3 rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
            type="button"
            data-action="save"
            :disabled="loading || saving || draft === null || conflict !== null || !dirty"
            @click="manualSave"
          >
            {{ saving ? '正在保存…' : '立即保存' }}
          </button>
        </div>
      </div>
    </header>

    <form
      v-if="mode === 'create'"
      class="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm"
      novalidate
      @submit.prevent="createWorkspace"
    >
      <fieldset :disabled="createPending || navigationPending" class="space-y-5">
        <legend class="text-lg font-semibold text-slate-950">项目身份</legend>
        <p class="text-sm leading-6 text-slate-600">
          Slug 同时作为创建后的稳定标识。创建完成后，稳定标识不能再修改。
        </p>
        <div
          v-if="createError"
          class="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800"
          role="alert"
        >
          <p class="font-semibold">{{ createError.body.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ createError.body.traceId }}</p>
        </div>
        <div
          v-if="createdProjectId"
          class="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900"
          role="status"
        >
          <p class="font-semibold">项目已经创建，不会再次提交创建请求。</p>
          <p class="mt-1 break-all text-xs">项目编号：{{ createdProjectId }}</p>
        </div>
        <div
          v-if="navigationError"
          class="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
          role="alert"
        >
          <p class="font-semibold">{{ navigationError.body.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ navigationError.body.traceId }}</p>
        </div>
        <div class="grid gap-5 md:grid-cols-2">
          <label class="text-sm font-semibold text-slate-800">
            Slug
            <input
              v-model="createSlug"
              class="mt-2 w-full rounded-xl border border-slate-300 px-4 py-2.5 font-normal text-slate-950"
              name="slug"
              :disabled="createdProjectId !== null"
              autocomplete="off"
              placeholder="例如 unreal-game-prototype"
              :aria-invalid="createFieldErrors.slug ? 'true' : undefined"
              :aria-describedby="createFieldErrors.slug ? 'create-slug-help create-slug-error' : 'create-slug-help'"
            />
            <span id="create-slug-help" class="mt-2 block text-xs font-normal text-slate-500">
              仅支持小写英文字母、数字和单个连字符；创建时最多 96 个字符。
            </span>
            <span v-if="createFieldErrors.slug" id="create-slug-error" class="mt-2 block text-sm font-normal text-red-700" role="alert">
              {{ createFieldErrors.slug }}
            </span>
          </label>
          <label class="text-sm font-semibold text-slate-800">
            项目编号
            <input
              v-model="createNumber"
              class="mt-2 w-full rounded-xl border border-slate-300 px-4 py-2.5 font-normal text-slate-950"
              name="number"
              :disabled="createdProjectId !== null"
              autocomplete="off"
              maxlength="16"
              placeholder="例如 04"
              :aria-invalid="createFieldErrors.number ? 'true' : undefined"
              :aria-describedby="createFieldErrors.number ? 'create-number-error' : undefined"
            />
            <span v-if="createFieldErrors.number" id="create-number-error" class="mt-2 block text-sm font-normal text-red-700" role="alert">
              {{ createFieldErrors.number }}
            </span>
          </label>
        </div>
        <button
          class="rounded-xl bg-blue-700 px-5 py-3 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
          type="submit"
          data-action="create"
          :disabled="createPending || navigationPending"
        >
          {{ createPending
            ? '正在创建…'
            : navigationPending
              ? '正在进入编辑器…'
              : createdProjectId
                ? '重试进入已创建项目'
                : '创建并进入编辑器' }}
        </button>
      </fieldset>
    </form>

    <template v-else>
      <ConflictBanner
        v-if="conflict"
        :problem="conflict"
        :reloading="loading"
        @reload="reloadEverything"
      />

      <div
        v-if="error && draft !== null"
        class="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm text-red-800"
        role="alert"
      >
        <p class="font-semibold">{{ error.body.title }}</p>
        <p class="mt-1 text-xs">请求编号：{{ error.body.traceId }}</p>
      </div>

      <div
        v-if="catalogError && draft !== null"
        class="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-900"
        role="status"
      >
        <p class="font-semibold">{{ catalogError.body.title }}</p>
        <p class="mt-1">已有分类选择会被保留；重新加载目录后可继续调整。</p>
        <button class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold" type="button" @click="loadTaxonomies">
          重试分类目录
        </button>
      </div>

      <AsyncPanel
        :loading="loading"
        :error-title="draft === null ? error?.body.title : undefined"
        :trace-id="draft === null ? error?.body.traceId : undefined"
        :on-retry="reloadEverything"
      >
        <form v-if="draft" class="space-y-6" novalidate @submit.prevent="manualSave">
          <fieldset :disabled="loading || saving" class="space-y-6">
            <TranslationTabs
              v-model="activeLocale"
              :status="completion"
              :disabled="loading || saving"
            >
              <ProjectMetadataForm
                :model-value="draft"
                :locale="activeLocale"
                :tag-catalog="tags"
                :skill-catalog="skills"
                :disabled="loading || saving || catalogLoading"
                :field-errors="fieldErrors"
                @update:model-value="replaceDraft"
              />
            </TranslationTabs>

            <section
              class="rounded-2xl border border-dashed border-blue-300 bg-blue-50/60 p-6"
              data-block-placeholder
              aria-labelledby="project-blocks-title"
            >
              <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">CONTENT BLOCKS</p>
              <h2 id="project-blocks-title" class="mt-2 text-xl font-semibold text-slate-950">
                {{ draft.blocks.length }} 个内容模块
              </h2>
              <p class="mt-2 text-sm leading-6 text-slate-600">
                现有模块会在每次元数据保存时原样保留。下一阶段将在这里加入九种可排序模块编辑器。
              </p>
            </section>
          </fieldset>

          <div class="sticky bottom-4 z-10 flex items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-white/95 p-3 shadow-lg backdrop-blur">
            <p class="pl-2 text-sm text-slate-600" data-save-state>{{ saveState }}</p>
            <button
              class="rounded-xl bg-blue-700 px-5 py-3 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
              type="submit"
              data-action="save-bottom"
              :disabled="loading || saving || conflict !== null || !dirty"
            >
              {{ saving ? '正在保存…' : '保存项目资料' }}
            </button>
          </div>
        </form>
      </AsyncPanel>
    </template>
  </section>
</template>
