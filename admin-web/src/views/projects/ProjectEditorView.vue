<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'

import { projectApi } from '@/api/projectApi'
import { publishingApi } from '@/api/publishingApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import ConflictBanner from '@/components/common/ConflictBanner.vue'
import TranslationTabs from '@/components/common/TranslationTabs.vue'
import BlockEditor from '@/components/editor/BlockEditor.vue'
import { validateBlocks } from '@/components/editor/blockValidation'
import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import ProjectMetadataForm from '@/components/projects/ProjectMetadataForm.vue'
import PublishPanel from '@/components/publishing/PublishPanel.vue'
import { projectPublicationCompletion } from '@/components/publishing/publicationCompletion'
import { useVersionedDraft } from '@/composables/useVersionedDraft'
import { ApiProblem, type FieldErrors, type VersionedDraft } from '@/types/api'
import type { ContentBlockDto } from '@/types/blocks'
import type {
  Locale,
  MediaAssetView,
  ProjectWorkspaceDto,
  SaveWorkspaceRequest,
  TaxonomyWorkspaceDto,
  TranslationStatus,
} from '@/types/content'
import {
  PROJECT_CATALOG_ID,
  type AggregateType,
  type ArchiveProjectCommand,
  type PreviewTokenRequest,
  type PreviewTokenResponse,
  type PublicationResultDto,
  type PublicationStateDto,
  type PublishTarget,
} from '@/types/publishing'

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
type LoadPublicationState = (
  aggregateType: AggregateType,
  aggregateId: string,
) => Promise<PublicationStateDto>
type CreatePreview = (request: PreviewTokenRequest) => Promise<PreviewTokenResponse>
type PreflightPreview = (token: string) => Promise<void>
type PreviewUrl = (token: string) => string
type PublishTargetRequest = (target: PublishTarget) => Promise<PublicationResultDto>
type ArchiveProjectRequest = (command: ArchiveProjectCommand) => Promise<PublicationResultDto>

interface ProjectPublicationOutcome {
  readonly operation: 'publish' | 'archive'
  readonly projectId: string
  readonly expectedWorkspaceVersion: number
  readonly expectedProjectPublicationVersion: number
  readonly expectedCatalogVersion: number
  readonly result: PublicationResultDto | null
  readonly requestProblem: ApiProblem | null
}

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
    loadMedia?: MediaPickerLoad
    resolveMedia?: (id: string) => Promise<MediaAssetView>
    loadPublicationState?: LoadPublicationState
    createPreview?: CreatePreview
    preflightPreview?: PreflightPreview
    previewUrl?: PreviewUrl
    publishTarget?: PublishTargetRequest
    archiveProject?: ArchiveProjectRequest
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
    loadPublicationState: (aggregateType: AggregateType, aggregateId: string) =>
      publishingApi.state(aggregateType, aggregateId),
    createPreview: (request: PreviewTokenRequest) => publishingApi.createPreview(request),
    preflightPreview: (token: string) => publishingApi.preflightPreview(token),
    previewUrl: (token: string) => publishingApi.previewUrl(token),
    publishTarget: (target: PublishTarget) => publishingApi.publishTarget(target),
    archiveProject: (command: ArchiveProjectCommand) => publishingApi.archiveProject(command),
  },
)

const router = useRouter()
const editorForm = ref<HTMLFormElement | null>(null)
const publicationRefreshRetry = ref<HTMLButtonElement | null>(null)
const saveStatusId = `${useId()}-project-save-status`
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
const projectPublicationState = ref<PublicationStateDto | null>(null)
const catalogPublicationState = ref<PublicationStateDto | null>(null)
const publicationStateLoading = ref(false)
const publicationStateError = ref<ApiProblem | null>(null)
const publicationBusy = ref(false)
const archivePending = ref(false)
const publicationApplied = ref(false)
const publicationRefreshPending = ref(false)
const publicationRefreshProblem = ref<ApiProblem | null>(null)
const publicationAnnouncement = ref('')
let catalogGeneration = 0
let createGeneration = 0
let navigationGeneration = 0
let reloadGeneration = 0
let publicationStateGeneration = 0
let publicationOutcomeGeneration = 0
let archiveGeneration = 0
let publicationOutcome: ProjectPublicationOutcome | null = null
let activePublicationTarget: PublishTarget | null = null
let disposed = false

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const JAVA_INT_MAX = 2_147_483_647
const EMPTY_TRANSLATION_STATUS: TranslationStatus = Object.freeze({
  'zh-CN': Object.freeze({ complete: 0, total: 0 }),
  en: Object.freeze({ complete: 0, total: 0 }),
})

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
  Object.assign(errors, validateBlocks(workspace.blocks))
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
    : projectPublicationCompletion(draft.value),
)

const publicationTarget = computed<PublishTarget>(() => ({
  aggregateType: 'PROJECT',
  aggregateId: props.projectId,
  expectedWorkspaceVersion: version.value,
  expectedProjectPublicationVersion: projectPublicationState.value?.version ?? 0,
  expectedCatalogVersion: catalogPublicationState.value?.version ?? 0,
}))

const editorLocked = computed(
  () =>
    loading.value ||
    saving.value ||
    publicationBusy.value ||
    archivePending.value ||
    publicationRefreshPending.value ||
    publicationApplied.value,
)

const publicationDisabled = computed(
  () =>
    editorLocked.value ||
    dirty.value ||
    conflict.value !== null ||
    error.value !== null ||
    publicationStateLoading.value ||
    publicationStateError.value !== null ||
    projectPublicationState.value === null ||
    catalogPublicationState.value === null,
)

const archiveVisible = computed(
  () =>
    props.mode === 'edit' &&
    projectPublicationState.value?.status === 'PUBLISHED',
)

const archiveDisabled = computed(
  () => publicationDisabled.value || archivePending.value,
)

const refreshOperationIsArchive = computed(
  () => {
    void publicationApplied.value
    return publicationOutcome?.operation === 'archive'
  },
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

async function loadTaxonomies(): Promise<boolean> {
  const operation = ++catalogGeneration
  catalogLoading.value = true
  catalogError.value = null
  try {
    const [nextTags, nextSkills] = await Promise.all([
      props.loadTags(),
      props.loadSkills(),
    ])
    if (disposed || operation !== catalogGeneration) return false
    tags.value = [...nextTags]
    skills.value = [...nextSkills]
    return true
  } catch (cause) {
    if (disposed || operation !== catalogGeneration) return false
    catalogError.value = displayProblem(
      cause,
      '分类目录暂时无法加载',
      'TAXONOMY_LOAD_FAILED',
    )
    return false
  } finally {
    if (!disposed && operation === catalogGeneration) catalogLoading.value = false
  }
}

function sameId(left: string, right: string): boolean {
  return left.toLowerCase() === right.toLowerCase()
}

function requirePublicationState(
  state: PublicationStateDto,
  aggregateType: AggregateType,
  aggregateId: string,
): void {
  if (state.aggregateType !== aggregateType || !sameId(state.aggregateId, aggregateId)) {
    throw clientProblem('发布状态响应与当前项目不匹配', 'PUBLICATION_STATE_MISMATCH')
  }
}

async function loadPublicationStates(projectId = props.projectId): Promise<boolean> {
  if (props.mode !== 'edit' || !UUID.test(projectId)) return false
  const operation = ++publicationStateGeneration
  publicationStateLoading.value = true
  publicationStateError.value = null
  projectPublicationState.value = null
  catalogPublicationState.value = null
  try {
    const [nextProjectState, nextCatalogState] = await Promise.all([
      props.loadPublicationState('PROJECT', projectId),
      props.loadPublicationState('PROJECT_CATALOG', PROJECT_CATALOG_ID),
    ])
    if (
      disposed ||
      operation !== publicationStateGeneration ||
      props.mode !== 'edit' ||
      !sameId(props.projectId, projectId)
    ) {
      return false
    }
    requirePublicationState(nextProjectState, 'PROJECT', projectId)
    requirePublicationState(nextCatalogState, 'PROJECT_CATALOG', PROJECT_CATALOG_ID)
    projectPublicationState.value = nextProjectState
    catalogPublicationState.value = nextCatalogState
    return true
  } catch (cause) {
    if (disposed || operation !== publicationStateGeneration) return false
    publicationStateError.value = displayProblem(
      cause,
      '发布状态暂时无法加载',
      'PUBLICATION_STATE_LOAD_FAILED',
    )
    return false
  } finally {
    if (!disposed && operation === publicationStateGeneration) {
      publicationStateLoading.value = false
    }
  }
}

async function reloadEverything(preserveDraft = false): Promise<boolean> {
  if (props.mode !== 'edit') return false
  const projectId = props.projectId
  const operation = ++reloadGeneration
  if (!preserveDraft) resetDraft()
  localErrors.value = {}
  const [, taxonomiesLoaded, publicationStatesLoaded] = await Promise.all([
    reload(),
    loadTaxonomies(),
    loadPublicationStates(projectId),
  ])
  if (
    disposed ||
    operation !== reloadGeneration ||
    props.mode !== 'edit' ||
    !sameId(props.projectId, projectId)
  ) {
    return false
  }
  return (
    draft.value !== null &&
    error.value === null &&
    taxonomiesLoaded &&
    publicationStatesLoaded
  )
}

async function retryEverything(): Promise<void> {
  await reloadEverything()
}

function replaceDraft(workspace: ProjectWorkspaceDto): void {
  if (editorLocked.value) return
  localErrors.value = {}
  draft.value = workspace
}

function replaceBlocks(blocks: ContentBlockDto[]): void {
  if (draft.value === null) return
  replaceDraft({ ...draft.value, blocks })
}

async function focusFirstValidationError(): Promise<void> {
  await nextTick()
  editorForm.value
    ?.querySelector<HTMLElement>(
      '[data-block-error-summary], [data-metric-error-summary], [aria-invalid="true"]',
    )
    ?.focus()
}

async function manualSave(): Promise<void> {
  if (draft.value === null || editorLocked.value) return
  const validation = validateWorkspace(draft.value)
  localErrors.value = validation
  if (Object.keys(validation).length > 0) {
    await focusFirstValidationError()
    return
  }
  await saveNow()
  if (Object.keys(error.value?.body.fieldErrors ?? {}).length > 0) {
    await focusFirstValidationError()
  }
}

function handlePublicationBusy(busy: boolean): void {
  publicationBusy.value = busy
  activePublicationTarget = busy ? Object.freeze({ ...publicationTarget.value }) : null
}

function refreshFailureProblem(): ApiProblem {
  const archive = publicationOutcome?.operation === 'archive'
  return (
    error.value ??
    catalogError.value ??
    publicationStateError.value ??
    clientProblem(
      archive
        ? '归档结果需要确认，但最新项目状态暂时无法载入；请仅重试刷新'
        : '项目已经发布，但最新工作区暂时无法载入；请仅重试刷新',
      archive ? 'ARCHIVE_REFRESH_FAILED' : 'PUBLICATION_REFRESH_FAILED',
    )
  )
}

function publicationRefreshOutcome(): 'exact' | 'newer' | null {
  const outcome = publicationOutcome
  const workspace = draft.value
  const projectState = projectPublicationState.value
  const catalogState = catalogPublicationState.value
  if (
    outcome === null ||
    workspace === null ||
    projectState === null ||
    catalogState === null
  ) {
    return null
  }
  const expectedWorkspaceVersion =
    outcome.expectedWorkspaceVersion + (outcome.operation === 'publish' ? 1 : 0)
  const expectedProjectPublicationVersion =
    outcome.expectedProjectPublicationVersion + 1
  const expectedCatalogVersion = outcome.expectedCatalogVersion + 1
  if (
    !Number.isSafeInteger(expectedWorkspaceVersion) ||
    !Number.isSafeInteger(expectedProjectPublicationVersion) ||
    !Number.isSafeInteger(expectedCatalogVersion) ||
    !sameId(props.projectId, outcome.projectId) ||
    !sameId(workspace.id, outcome.projectId) ||
    version.value !== workspace.version ||
    workspace.version < expectedWorkspaceVersion ||
    projectState.version < expectedProjectPublicationVersion ||
    catalogState.version < expectedCatalogVersion
  ) {
    return null
  }

  const result = outcome.result
  if (result !== null) {
    if (
      result.aggregateVersion !== expectedProjectPublicationVersion ||
      result.catalogVersion !== expectedCatalogVersion ||
      result.catalogRevisionId === null
    ) {
      return null
    }
  }

  if (outcome.operation === 'publish') {
    if (
      result === null ||
      workspace.version === expectedWorkspaceVersion &&
        workspace.publicationDirty !== false
    ) {
      return null
    }
  }

  const catalogStillContainsProject = catalogState.projectIdsInOrder.some((id) =>
    sameId(id, outcome.projectId),
  )
  if (
    outcome.operation === 'archive' &&
    result === null &&
    (projectState.status !== 'ARCHIVED' || catalogStillContainsProject)
  ) {
    return null
  }

  if (
    projectState.version === expectedProjectPublicationVersion &&
    (projectState.status !== (outcome.operation === 'archive' ? 'ARCHIVED' : 'PUBLISHED') ||
      projectState.currentRevisionId === null ||
      (result !== null && !sameId(projectState.currentRevisionId, result.revisionId)))
  ) {
    return null
  }
  if (
    catalogState.version === expectedCatalogVersion &&
    (catalogState.status !== 'PUBLISHED' ||
      catalogState.currentRevisionId === null ||
      (outcome.operation === 'archive' && catalogStillContainsProject) ||
      (result !== null &&
        (result.catalogRevisionId === null ||
          !sameId(catalogState.currentRevisionId, result.catalogRevisionId))))
  ) {
    return null
  }

  return workspace.version > expectedWorkspaceVersion ||
    projectState.version > expectedProjectPublicationVersion ||
    catalogState.version > expectedCatalogVersion
    ? 'newer'
    : 'exact'
}

async function refreshAfterPublication(): Promise<void> {
  if (!publicationApplied.value || publicationRefreshPending.value) return
  const projectId = props.projectId
  const archive = publicationOutcome?.operation === 'archive'
  const operation = ++publicationOutcomeGeneration
  publicationRefreshPending.value = true
  publicationRefreshProblem.value = null
  publicationAnnouncement.value = archive
    ? '归档请求已经提交，正在载入最新工作区与发布状态以确认结果。'
    : '项目已经发布，正在载入最新工作区与发布状态。'
  const refreshed = await reloadEverything(true)
  if (
    disposed ||
    operation !== publicationOutcomeGeneration ||
    props.mode !== 'edit' ||
    !sameId(props.projectId, projectId)
  ) {
    return
  }
  publicationRefreshPending.value = false
  const outcome = refreshed ? publicationRefreshOutcome() : null
  if (outcome !== null) {
    publicationApplied.value = false
    publicationOutcome = null
    publicationAnnouncement.value = archive
      ? outcome === 'newer'
        ? '本次项目归档已处理；刷新期间又有更新，已载入更高版本的工作区与发布状态。'
        : '项目已经下线，最新工作区与目录状态已经载入。'
      : outcome === 'newer'
        ? '本次项目发布成功；刷新期间又有更新，已载入更高版本的工作区与发布状态。'
        : '项目发布成功，最新工作区与目录状态已经载入。'
    return
  }
  publicationRefreshProblem.value = refreshed
    ? publicationOutcome?.requestProblem ??
      clientProblem(
        archive
          ? '归档结果尚未被最新状态证明；请仅重试刷新'
          : '项目已经发布，但重新载入的版本证明与发布结果不一致；请仅重试刷新',
        archive ? 'ARCHIVE_REFRESH_MISMATCH' : 'PUBLICATION_REFRESH_MISMATCH',
      )
    : refreshFailureProblem()
  publicationAnnouncement.value = archive
    ? '归档结果尚未确认；不会重复提交归档请求。'
    : '项目已经发布，但最新状态载入失败；不会重复提交发布请求。'
  await nextTick()
  publicationRefreshRetry.value?.focus()
}

function handlePublished(result: PublicationResultDto): void {
  if (props.mode !== 'edit' || publicationApplied.value) return
  const target = activePublicationTarget
  if (
    target === null ||
    target.aggregateType !== 'PROJECT' ||
    !sameId(target.aggregateId, props.projectId)
  ) {
    return
  }
  publicationOutcome = Object.freeze({
    operation: 'publish',
    projectId: target.aggregateId,
    expectedWorkspaceVersion: target.expectedWorkspaceVersion,
    expectedProjectPublicationVersion: target.expectedProjectPublicationVersion,
    expectedCatalogVersion: target.expectedCatalogVersion,
    result: Object.freeze({ ...result }),
    requestProblem: null,
  })
  publicationApplied.value = true
  publicationRefreshProblem.value = null
  void refreshAfterPublication()
}

function archiveResultIsExact(
  result: PublicationResultDto,
  command: ArchiveProjectCommand,
): boolean {
  return (
    Number.isSafeInteger(command.expectedProjectPublicationVersion + 1) &&
    Number.isSafeInteger(command.expectedCatalogVersion + 1) &&
    result.aggregateVersion === command.expectedProjectPublicationVersion + 1 &&
    result.catalogVersion === command.expectedCatalogVersion + 1 &&
    result.catalogRevisionId !== null
  )
}

async function archiveCurrentProject(): Promise<void> {
  const projectState = projectPublicationState.value
  const catalogState = catalogPublicationState.value
  const workspace = draft.value
  if (
    props.mode !== 'edit' ||
    !archiveVisible.value ||
    archiveDisabled.value ||
    projectState === null ||
    catalogState === null ||
    workspace === null ||
    !UUID.test(props.projectId)
  ) {
    return
  }
  if (
    !window.confirm(
      '确定下线这个已发布项目吗？项目会从公开目录移除，但后台内容和发布历史会保留。',
    )
  ) {
    return
  }

  const projectId = props.projectId
  const expectedWorkspaceVersion = version.value
  const command: ArchiveProjectCommand = Object.freeze({
    projectId,
    expectedProjectPublicationVersion: projectState.version,
    expectedCatalogVersion: catalogState.version,
  })
  const operation = ++archiveGeneration
  archivePending.value = true
  publicationAnnouncement.value = '正在下线项目并更新公开目录。'

  let result: PublicationResultDto | null = null
  let requestProblem: ApiProblem | null = null
  try {
    const response = await props.archiveProject(command)
    if (archiveResultIsExact(response, command)) {
      result = Object.freeze({ ...response })
    } else {
      requestProblem = clientProblem(
        '归档响应无法验证；请仅刷新最新状态',
        'ARCHIVE_RESULT_INVALID',
      )
    }
  } catch (cause) {
    requestProblem = displayProblem(
      cause,
      '归档请求结果无法确认；请仅刷新最新状态',
      'ARCHIVE_RESULT_UNCERTAIN',
    )
  }

  if (
    disposed ||
    operation !== archiveGeneration ||
    props.mode !== 'edit' ||
    !sameId(props.projectId, projectId)
  ) {
    return
  }

  archivePending.value = false
  publicationOutcome = Object.freeze({
    operation: 'archive',
    projectId,
    expectedWorkspaceVersion,
    expectedProjectPublicationVersion: command.expectedProjectPublicationVersion,
    expectedCatalogVersion: command.expectedCatalogVersion,
    result,
    requestProblem,
  })
  publicationApplied.value = true
  publicationRefreshProblem.value = null
  void refreshAfterPublication()
}

function reloadAfterPublicationConflict(): void {
  if (props.mode !== 'edit' || publicationBusy.value || publicationApplied.value) return
  void reloadEverything(true)
}

function retryPublicationRefresh(): void {
  void refreshAfterPublication()
}

function resetPublicationContext(): void {
  publicationStateGeneration += 1
  publicationOutcomeGeneration += 1
  archiveGeneration += 1
  projectPublicationState.value = null
  catalogPublicationState.value = null
  publicationStateLoading.value = false
  publicationStateError.value = null
  publicationBusy.value = false
  archivePending.value = false
  publicationApplied.value = false
  publicationRefreshPending.value = false
  publicationRefreshProblem.value = null
  publicationAnnouncement.value = ''
  publicationOutcome = null
  activePublicationTarget = null
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
    resetPublicationContext()
    if (mode === 'edit') {
      navigationGeneration += 1
      creationComplete.value = false
      createdProjectId.value = null
      navigationPending.value = false
      navigationError.value = null
      void reloadEverything()
      return
    }
    reloadGeneration += 1
    catalogGeneration += 1
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
  { immediate: true, flush: 'sync' },
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
  reloadGeneration += 1
  publicationStateGeneration += 1
  publicationOutcomeGeneration += 1
  archiveGeneration += 1
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
          <RouterLink
            v-if="UUID.test(projectId)"
            class="inline-flex items-center justify-center rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
            :to="{
              name: 'publishing-history',
              params: { aggregateType: 'PROJECT', aggregateId: projectId },
            }"
          >项目发布历史</RouterLink>
          <p class="mt-4 text-xs font-semibold tracking-wide text-slate-500">保存状态</p>
          <p
            :id="saveStatusId"
            class="mt-1 text-sm font-semibold text-slate-800"
            data-save-state
            role="status"
            aria-live="polite"
            aria-atomic="true"
          >{{ saveState }}</p>
          <button
            class="mt-3 rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
            type="button"
            data-action="save"
            :disabled="editorLocked || draft === null || conflict !== null || !dirty"
            :aria-describedby="saveStatusId"
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
      <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {{ publicationAnnouncement }}
      </p>

      <div
        v-if="publicationRefreshProblem"
        class="rounded-2xl border border-amber-300 bg-amber-50 p-5 text-sm text-amber-950"
        data-publication-refresh-problem
        role="alert"
      >
        <p class="font-semibold">
          {{ refreshOperationIsArchive
            ? '归档结果尚未确认。'
            : '项目已经发布，但最新状态载入失败。' }}
        </p>
        <p class="mt-1">
          {{ refreshOperationIsArchive
            ? '不会重复提交归档请求；请仅重试载入最新工作区与发布状态。'
            : '不会重复提交发布请求；请仅重试载入最新工作区。' }}
        </p>
        <p class="mt-1 text-xs">请求编号：{{ publicationRefreshProblem.body.traceId }}</p>
        <button
          ref="publicationRefreshRetry"
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold disabled:cursor-not-allowed disabled:opacity-55"
          type="button"
          data-action="retry-publication-refresh"
          :disabled="publicationRefreshPending"
          @click="retryPublicationRefresh"
        >{{ publicationRefreshPending ? '正在刷新…' : '仅重试刷新' }}</button>
      </div>

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

      <div
        v-if="publicationStateError && draft !== null && !publicationApplied"
        class="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-900"
        data-publication-state-error
        role="alert"
      >
        <p class="font-semibold">{{ publicationStateError.body.title }}</p>
        <p class="mt-1">编辑仍可继续；发布与预览会保持锁定，直到状态重新载入。</p>
        <p class="mt-1 text-xs">请求编号：{{ publicationStateError.body.traceId }}</p>
        <button
          class="mt-3 rounded-lg border border-amber-300 bg-white px-3 py-2 font-semibold"
          type="button"
          data-action="retry-publication-state"
          :disabled="publicationStateLoading"
          @click="loadPublicationStates()"
        >{{ publicationStateLoading ? '正在重试…' : '重试发布状态' }}</button>
      </div>

      <AsyncPanel
        :loading="loading && draft === null"
        :error-title="draft === null ? error?.body.title : undefined"
        :trace-id="draft === null ? error?.body.traceId : undefined"
        :on-retry="retryEverything"
      >
        <form
          v-if="draft"
          ref="editorForm"
          class="space-y-6"
          data-project-editor-form
          novalidate
          :aria-busy="editorLocked"
          @submit.prevent="manualSave"
        >
          <fieldset :disabled="editorLocked" class="space-y-6" data-project-editor-fields>
            <TranslationTabs
              v-model="activeLocale"
              :status="completion"
              status-label="元数据翻译完成度"
              :disabled="editorLocked"
            >
              <div class="space-y-6">
                <ProjectMetadataForm
                  :model-value="draft"
                  :locale="activeLocale"
                  :tag-catalog="tags"
                  :skill-catalog="skills"
                  :disabled="editorLocked || catalogLoading"
                  :field-errors="fieldErrors"
                  @update:model-value="replaceDraft"
                />

                <BlockEditor
                  data-project-block-editor
                  :blocks="draft.blocks"
                  :locale="activeLocale"
                  :disabled="editorLocked"
                  :field-errors="fieldErrors"
                  :load-media="loadMedia"
                  :resolve-media="resolveMedia"
                  @update:blocks="replaceBlocks"
                />
              </div>
            </TranslationTabs>
          </fieldset>

          <div class="sticky bottom-4 z-10 flex items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-white/95 p-3 shadow-lg backdrop-blur">
            <p class="pl-2 text-sm text-slate-600" data-save-state aria-hidden="true">{{ saveState }}</p>
            <button
              class="rounded-xl bg-blue-700 px-5 py-3 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
              type="submit"
              data-action="save-bottom"
              :disabled="editorLocked || conflict !== null || !dirty"
              :aria-describedby="saveStatusId"
            >
              {{ saving ? '正在保存…' : '保存项目资料' }}
            </button>
          </div>
        </form>

        <div v-if="draft" class="mt-6 space-y-3">
          <p class="text-xs font-semibold text-slate-500" data-publication-version>
            项目发布版本 {{ projectPublicationState?.version ?? '—' }} · 目录版本
            {{ catalogPublicationState?.version ?? '—' }}
          </p>
          <PublishPanel
            :target="publicationTarget"
            :locale="activeLocale"
            :completion="completion"
            :disabled="publicationDisabled"
            :create-preview="createPreview"
            :preflight-preview="preflightPreview"
            :preview-url="previewUrl"
            :publish-target="publishTarget"
            @busy-change="handlePublicationBusy"
            @published="handlePublished"
            @reload-requested="reloadAfterPublicationConflict"
          />
          <section
            v-if="archiveVisible"
            class="rounded-2xl border border-amber-200 bg-amber-50 p-5 shadow-sm sm:p-6"
            data-project-archive
            aria-labelledby="project-archive-title"
          >
            <p class="text-xs font-semibold tracking-[0.16em] text-amber-800">TAKE OFFLINE</p>
            <h2 id="project-archive-title" class="mt-2 text-lg font-semibold text-slate-950">
              下线已发布项目
            </h2>
            <p class="mt-2 text-sm leading-6 text-slate-700">
              项目会从公开目录移除；后台内容与发布历史仍会保留，之后可以重新发布。
            </p>
            <button
              class="mt-4 rounded-xl border border-amber-400 bg-white px-4 py-2.5 text-sm font-semibold text-amber-950 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-55"
              type="button"
              data-action="archive-project"
              :disabled="archiveDisabled"
              @click="archiveCurrentProject"
            >
              {{ archivePending ? '正在下线…' : '下线项目' }}
            </button>
          </section>
        </div>
      </AsyncPanel>
    </template>
  </section>
</template>
