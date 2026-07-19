<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import { toApiProblem } from '@/api/http'
import { mediaApi } from '@/api/mediaApi'
import { publishingApi } from '@/api/publishingApi'
import { siteApi } from '@/api/siteApi'
import AsyncPanel from '@/components/common/AsyncPanel.vue'
import ConflictBanner from '@/components/common/ConflictBanner.vue'
import TranslationTabs from '@/components/common/TranslationTabs.vue'
import MediaPickerDialog from '@/components/media/MediaPickerDialog.vue'
import PublishPanel from '@/components/publishing/PublishPanel.vue'
import OrderedLocalizedList from '@/components/site/OrderedLocalizedList.vue'
import SiteIdentityForm from '@/components/site/SiteIdentityForm.vue'
import SiteTranslationForm from '@/components/site/SiteTranslationForm.vue'
import { translationStatus } from '@/composables/useTranslationStatus'
import { useVersionedDraft } from '@/composables/useVersionedDraft'
import { ApiProblem, type Page, type VersionedDraft } from '@/types/api'
import type {
  Locale,
  MediaAssetSummaryDto,
  MediaKind,
  NavigationItem,
  ProfileFact,
  ProfileSkill,
  RoadmapOutcome,
  RoadmapStage,
  SaveWorkspaceRequest,
  SiteWorkspaceDto,
  SocialLink,
} from '@/types/content'
import {
  SITE_ID,
  type PublicationResultDto,
  type PublicationStateDto,
  type SitePublishTarget,
} from '@/types/publishing'

type LoadSite = () => Promise<VersionedDraft<SiteWorkspaceDto>>
type SaveSite = (
  request: SaveWorkspaceRequest<SiteWorkspaceDto>,
) => Promise<VersionedDraft<SiteWorkspaceDto>>
type LoadSitePublicationState = () => Promise<PublicationStateDto>

const props = withDefaults(
  defineProps<{
    load?: LoadSite
    save?: SaveSite
    loadPublicationState?: LoadSitePublicationState
  }>(),
  {
    load: () => siteApi.get(),
    save: (request: SaveWorkspaceRequest<SiteWorkspaceDto>) => siteApi.save(request),
    loadPublicationState: () => publishingApi.state('SITE', SITE_ID),
  },
)

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
} = useVersionedDraft<SiteWorkspaceDto>({
  load: () => props.load(),
  save: (request) => saveValidated(request),
  retryValidationAfterEdit: (problem) =>
    problem.body.status === 0 &&
    problem.body.type === 'client_validation' &&
    problem.body.code === 'CLIENT_SITE_VALIDATION_FAILED',
})

const locale = ref<Locale>('zh-CN')
const clientErrors = ref<Readonly<Record<string, string>>>(Object.freeze({}))
const suppressedFieldErrorPrefixes = ref<readonly string[]>(Object.freeze([]))
const fieldErrorIdPrefix = `site-field-error-${useId()}`

type PickerTarget =
  | { readonly type: 'hero' }
  | { readonly type: 'resume'; readonly resumeId: string }

const pickerTarget = ref<PickerTarget | null>(null)
const publicationState = ref<PublicationStateDto | null>(null)
const publicationStateError = ref<ApiProblem | null>(null)
const publicationStateLoading = ref(false)
const publishingBusy = ref(false)
const postPublishRefreshing = ref(false)
const postPublishRefreshError = ref<ApiProblem | null>(null)
const publicationNotice = ref('')
const publishedResult = ref<PublicationResultDto | null>(null)
const publishedWorkspaceVersionFloor = ref<number | null>(null)

let componentMounted = true
let publicationStateGeneration = 0
let postPublishGeneration = 0

const identityFields = [
  { key: 'displayName', label: '显示姓名', required: true },
  { key: 'secondaryName', label: '辅助姓名', required: true },
] as const
const seoFields = [
  { key: 'title', label: '页面标题', required: true },
  { key: 'description', label: '页面描述', required: true, multiline: true, rows: 3 },
] as const
const accessibilityFields = [
  { key: 'skip', label: '跳到主要内容', required: true },
  { key: 'primaryNav', label: '主导航名称', required: true },
  { key: 'mobileNav', label: '移动导航名称', required: true },
  { key: 'openMenu', label: '打开菜单', required: true },
  { key: 'closeMenu', label: '关闭菜单', required: true },
  { key: 'language', label: '语言切换', required: true },
  { key: 'backToTop', label: '返回顶部', required: true },
  { key: 'projectTags', label: '项目标签', required: true },
] as const
const heroFields = [
  { key: 'eyebrow', label: '眉题', required: true },
  { key: 'displayName', label: '显示姓名', required: true },
  { key: 'secondaryName', label: '辅助姓名', required: true },
  { key: 'role', label: '职业角色', required: true },
  { key: 'headline', label: '主标题', required: true, multiline: true, rows: 2 },
  { key: 'introduction', label: '简介', required: true, multiline: true, rows: 4 },
  { key: 'availability', label: '合作状态', required: true },
  { key: 'primaryCta', label: '主按钮', required: true },
  { key: 'secondaryCta', label: '次按钮', required: true },
  { key: 'visualLabel', label: '视觉说明', required: true },
  { key: 'stageLabel', label: '阶段说明', required: true },
] as const
const aboutFields = [
  { key: 'label', label: '区块标签', required: true },
  { key: 'title', label: '标题', required: true },
  { key: 'statement', label: '自我陈述', required: true, multiline: true, rows: 4 },
  { key: 'focusLabel', label: '专注标签', required: true },
  { key: 'focusTitle', label: '专注标题', required: true },
  { key: 'focusIntro', label: '专注介绍', required: true, multiline: true, rows: 3 },
] as const
const workFields = [
  { key: 'label', label: '区块标签', required: true },
  { key: 'title', label: '标题', required: true },
  { key: 'introduction', label: '介绍', required: true, multiline: true, rows: 3 },
  { key: 'imageNotice', label: '图片说明', required: true },
  { key: 'openSlotLabel', label: '扩展位标签', required: true },
  { key: 'openSlotTitle', label: '扩展位标题', required: true },
  { key: 'openSlotText', label: '扩展位正文', required: true, multiline: true, rows: 3 },
  { key: 'openSlotMeta', label: '扩展位元信息', required: true },
] as const
const roadmapHeaderFields = [
  { key: 'label', label: '区块标签', required: true },
  { key: 'title', label: '标题', required: true },
  { key: 'introduction', label: '介绍', required: true, multiline: true, rows: 3 },
] as const
const contactFields = [
  { key: 'label', label: '区块标签', required: true },
  { key: 'title', label: '标题', required: true },
  { key: 'introduction', label: '介绍', required: true, multiline: true, rows: 3 },
  { key: 'emailLabel', label: '邮箱标签', required: true },
  { key: 'workCta', label: '作品按钮', required: true },
  { key: 'roadmapCta', label: '路线图按钮', required: true },
  { key: 'footerNote', label: '页脚说明', required: true },
] as const
const privacyFields = [
  { key: 'title', label: '标题', required: true },
  { key: 'bodyMarkdown', label: 'Markdown 正文', required: true, multiline: true, rows: 7 },
] as const

const completionKeys = [
  'identity',
  'seo',
  'accessibility',
  'navigation',
  'hero',
  'about',
  'facts',
  'skills',
  'work',
  'roadmap',
  'contact',
  'privacy',
  'resume',
] as const
type CompletionKey = (typeof completionKeys)[number]

function completeWhen(values: readonly (string | null | undefined)[]): string {
  return values.every((value) => typeof value === 'string' && value.trim().length > 0)
    ? 'complete'
    : ''
}

function objectStrings(value: object): string[] {
  return Object.values(value).filter((item): item is string => typeof item === 'string')
}

function completionCopy(selectedLocale: Locale): Record<CompletionKey, string> {
  const site = draft.value
  if (site === null) {
    return Object.fromEntries(completionKeys.map((key) => [key, ''])) as Record<
      CompletionKey,
      string
    >
  }

  const roadmapValues = [
    ...objectStrings(site.roadmap.header[selectedLocale]),
    ...site.roadmap.stages.flatMap((stage) => [
      ...objectStrings(stage.copy[selectedLocale]),
      ...stage.outcomes.map((outcome) => outcome.text[selectedLocale]),
    ]),
  ]
  const resume = site.resumes.find(
    (candidate) => candidate.locale === selectedLocale && candidate.current,
  )

  return {
    identity: completeWhen(objectStrings(site.identity[selectedLocale])),
    seo: completeWhen(objectStrings(site.seo[selectedLocale])),
    accessibility: completeWhen(objectStrings(site.accessibility[selectedLocale])),
    navigation: completeWhen(site.navigation.map((item) => item.labels[selectedLocale])),
    hero: completeWhen(objectStrings(site.hero.copy[selectedLocale])),
    about: completeWhen(objectStrings(site.about[selectedLocale])),
    facts: completeWhen(
      site.facts.flatMap((item) => objectStrings(item.copy[selectedLocale])),
    ),
    skills: completeWhen(
      site.profileSkills.flatMap((item) => objectStrings(item.copy[selectedLocale])),
    ),
    work: completeWhen(objectStrings(site.work[selectedLocale])),
    roadmap: completeWhen(roadmapValues),
    contact: completeWhen(objectStrings(site.contact[selectedLocale])),
    privacy: completeWhen(objectStrings(site.privacy[selectedLocale])),
    resume:
      site.resumes.length === 0
        ? 'complete'
        : completeWhen([resume?.mediaAssetId, resume?.versionLabel, resume?.documentDate]),
  }
}

const completion = computed(() =>
  translationStatus(
    {
      'zh-CN': completionCopy('zh-CN'),
      en: completionCopy('en'),
    },
    completionKeys,
  ),
)

const publicationInteractionLocked = computed(
  () =>
    publishingBusy.value ||
    postPublishRefreshing.value ||
    postPublishRefreshError.value !== null,
)

const editorLocked = computed(
  () => loading.value || saving.value || publicationInteractionLocked.value,
)

const publicationDisabled = computed(
  () =>
    loading.value ||
    saving.value ||
    dirty.value ||
    conflict.value !== null ||
    error.value !== null ||
    publicationStateLoading.value ||
    publicationState.value === null ||
    publicationStateError.value !== null ||
    publicationInteractionLocked.value,
)

const sitePublishTarget = computed<SitePublishTarget>(() => ({
  aggregateType: 'SITE',
  aggregateId: SITE_ID,
  expectedWorkspaceVersion: version.value,
  expectedPublicationVersion: publicationState.value?.version ?? 0,
}))

const SAFE_MEDIA_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function normalizedFieldPath(path: string): string {
  return path.replace(/\[(\d+|zh-CN|en)\]/g, '.$1')
}

function fieldErrorIsSuppressed(path: string): boolean {
  const normalized = normalizedFieldPath(path)
  return suppressedFieldErrorPrefixes.value.some(
    (prefix) => normalized === prefix || normalized.startsWith(`${prefix}.`),
  )
}

const visibleServerFieldErrors = computed<Readonly<Record<string, string>>>(() =>
  Object.freeze(
    Object.fromEntries(
      Object.entries(error.value?.body.fieldErrors ?? {}).filter(
        ([path]) => !fieldErrorIsSuppressed(path),
      ),
    ),
  ),
)

const visibleClientFieldErrors = computed<Readonly<Record<string, string>>>(() =>
  Object.freeze(
    Object.fromEntries(
      Object.entries(clientErrors.value).filter(([path]) => !fieldErrorIsSuppressed(path)),
    ),
  ),
)

const allFieldErrors = computed<Readonly<Record<string, string>>>(() => ({
  ...visibleClientFieldErrors.value,
  ...visibleServerFieldErrors.value,
}))

function suppressFieldErrors(prefix: string): void {
  const normalized = normalizedFieldPath(prefix)
  if (suppressedFieldErrorPrefixes.value.includes(normalized)) return
  suppressedFieldErrorPrefixes.value = Object.freeze([
    ...suppressedFieldErrorPrefixes.value,
    normalized,
  ])
}

watch(error, () => {
  suppressedFieldErrorPrefixes.value = Object.freeze([])
})

const identityErrors = computed(() => ({
  ...(allFieldErrors.value.monogram === undefined
    ? {}
    : { monogram: allFieldErrors.value.monogram }),
  ...(allFieldErrors.value.email === undefined
    ? {}
    : { email: allFieldErrors.value.email }),
}))

function fieldPathCandidates(path: string): readonly string[] {
  const dotPath = path.replace(/\[(\d+)\]/g, '.$1')
  const bracketPath = dotPath.replace(/\.(\d+)(?=\.|$)/g, '[$1]')
  const candidates = new Set([path, dotPath, bracketPath])
  for (const candidate of [...candidates]) {
    candidates.add(candidate.replace(/\.(zh-CN|en)(?=\.|$)/g, '[$1]'))
  }
  return [...candidates]
}

function fieldErrorKey(path: string): string | undefined {
  return fieldPathCandidates(path).find(
    (candidate) => allFieldErrors.value[candidate] !== undefined,
  )
}

function fieldErrorId(path: string): string {
  const encoded = [...path]
    .map((character) => character.codePointAt(0)!.toString(16))
    .join('-')
  return `${fieldErrorIdPrefix}-${encoded}`
}

function fieldErrorAttributes(path: string): Readonly<Record<string, string>> {
  const key = fieldErrorKey(path)
  return key === undefined
    ? Object.freeze({})
    : Object.freeze({ 'aria-invalid': 'true', 'aria-describedby': fieldErrorId(key) })
}

function fieldErrorMessage(path: string): string | undefined {
  const key = fieldErrorKey(path)
  return key === undefined ? undefined : allFieldErrors.value[key]
}

function fieldName(path: string): string {
  if (path === 'email') return '联系邮箱'
  if (path === 'monogram') return '姓名缩写'
  if (path.startsWith('hero.objectPosition')) return 'Hero 图片位置'
  if (path.startsWith('hero.credit')) return 'Hero 图片署名'
  if (path.startsWith('hero.sourceUrl')) return 'Hero 图片来源链接'
  if (path.startsWith('hero.media')) return 'Hero 图片'
  if (path.startsWith('navigation')) return '导航项'
  if (path.startsWith('facts')) return '资料卡'
  if (path.startsWith('profileSkills')) return '技能状态'
  if (path.startsWith('roadmap')) return '路线图'
  if (path.startsWith('socialLinks')) return '社交链接'
  if (path.startsWith('resumes')) return '简历'
  if (path.startsWith('identity')) return '姓名文案'
  if (path.startsWith('seo')) return 'SEO 文案'
  if (path.startsWith('accessibility')) return '无障碍文案'
  if (path.startsWith('about')) return '关于文案'
  if (path.startsWith('work')) return '作品区文案'
  if (path.startsWith('contact')) return '联系文案'
  if (path.startsWith('privacy')) return '隐私说明'
  return '站点内容字段'
}

const saveState = computed(() => {
  if (loading.value) return draft.value === null ? '正在加载…' : '正在重新载入…'
  if (saving.value) return '正在保存…'
  if (conflict.value !== null) return '版本冲突，自动保存已停止'
  if (dirty.value) return '有未保存修改'
  return '已保存'
})

const pickerAccept = computed<readonly MediaKind[]>(() =>
  pickerTarget.value?.type === 'resume' ? ['PDF'] : ['IMAGE'],
)

function translatedErrors(
  prefix: string,
  selectedLocale: Locale,
  fields: readonly { readonly key: string }[],
): Readonly<Record<string, string>> {
  const result: Record<string, string> = {}
  for (const field of fields) {
    const candidates = [
      `${prefix}.${selectedLocale}.${field.key}`,
      `${prefix}[${selectedLocale}].${field.key}`,
      `${prefix}.${field.key}`,
    ]
    const message = candidates
      .map((candidate) => allFieldErrors.value[candidate])
      .find((candidate): candidate is string => candidate !== undefined)
    if (message !== undefined) result[field.key] = message
  }
  return result
}

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLTextAreaElement).value
}

function nullableInputValue(event: Event): string | null {
  const value = inputValue(event).trim()
  return value.length === 0 ? null : value
}

function checkedValue(event: Event): boolean {
  return (event.target as HTMLInputElement).checked
}

function navigationLabel(item: NavigationItem, selectedLocale: Locale): string {
  return item.labels[selectedLocale] || item.target
}

function factLabel(item: ProfileFact, selectedLocale: Locale): string {
  return item.copy[selectedLocale].label || item.externalKey
}

function skillLabel(item: ProfileSkill, selectedLocale: Locale): string {
  return item.copy[selectedLocale].name || item.externalKey
}

function stageLabel(item: RoadmapStage, selectedLocale: Locale): string {
  return item.copy[selectedLocale].title || item.number
}

function outcomeLabel(item: RoadmapOutcome, selectedLocale: Locale): string {
  return item.text[selectedLocale]
}

function socialLabel(item: SocialLink): string {
  return item.platform || item.url
}

function validEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

function validHttps(value: string | null): boolean {
  if (value === null || value.trim().length === 0) return true
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'https:' && parsed.hostname.length > 0
  } catch {
    return false
  }
}

function trustedReadyMedia(asset: MediaAssetSummaryDto, expectedKind: 'IMAGE' | 'PDF'): boolean {
  if (
    asset.status !== 'READY' ||
    asset.kind !== expectedKind ||
    !SAFE_MEDIA_ID.test(asset.id)
  ) {
    return false
  }
  return expectedKind === 'IMAGE'
    ? asset.mimeType === 'image/jpeg' || asset.mimeType === 'image/png'
    : asset.mimeType === 'application/pdf'
}

function validationErrors(site: SiteWorkspaceDto): Readonly<Record<string, string>> {
  const next: Record<string, string> = {}
  if (!validEmail(site.email)) next.email = '请输入有效的邮箱地址'
  if (site.hero.mediaAssetId === null) {
    if (
      site.hero.objectPosition !== null ||
      site.hero.credit !== null ||
      site.hero.sourceUrl !== null
    ) {
      next['hero.media'] = '未选择图片时，请一并清空图片位置、署名和来源链接'
    }
  } else {
    if (!site.hero.objectPosition?.trim()) {
      next['hero.objectPosition'] = '选择图片后必须填写图片位置'
    }
    if (!site.hero.credit?.trim()) {
      next['hero.credit'] = '选择图片后必须填写图片署名'
    }
    if (!site.hero.sourceUrl?.trim() || !validHttps(site.hero.sourceUrl)) {
      next['hero.sourceUrl'] = '选择图片后必须填写 HTTPS 来源链接'
    }
  }
  site.socialLinks.forEach((link, index) => {
    if (!validHttps(link.url) || link.url.trim().length === 0) {
      next[`socialLinks[${index}].url`] = '社交链接必须使用 HTTPS 地址'
    }
  })
  return Object.freeze(next)
}

function validateClient(): boolean {
  const site = draft.value
  if (site === null) return false
  const next = validationErrors(site)
  clientErrors.value = next
  return Object.keys(next).length === 0
}

async function saveValidated(
  request: SaveWorkspaceRequest<SiteWorkspaceDto>,
): Promise<VersionedDraft<SiteWorkspaceDto>> {
  const next = validationErrors(request.workspace)
  clientErrors.value = next
  if (Object.keys(next).length > 0) {
    throw new ApiProblem({
      type: 'client_validation',
      title: '请先修正站点内容',
      status: 0,
      code: 'CLIENT_SITE_VALIDATION_FAILED',
      traceId: 'client',
      fieldErrors: next,
    })
  }
  return props.save(request)
}

async function manualSave(): Promise<void> {
  if (editorLocked.value || conflict.value !== null) return
  if (!validateClient()) return
  await saveNow()
}

async function reloadWorkspace(): Promise<void> {
  clientErrors.value = Object.freeze({})
  closePicker()
  await reload()
}

function publicationStateMismatch(): ApiProblem {
  return new ApiProblem({
    type: 'publication_state',
    title: '发布状态响应与当前站点不匹配',
    status: 0,
    code: 'PUBLICATION_STATE_MISMATCH',
    traceId: 'client',
  })
}

async function reloadPublicationState(): Promise<void> {
  const operation = ++publicationStateGeneration
  publicationStateLoading.value = true
  publicationStateError.value = null
  try {
    const next = await props.loadPublicationState()
    if (!componentMounted || operation !== publicationStateGeneration) return
    if (
      next.aggregateType !== 'SITE' ||
      next.aggregateId.toLowerCase() !== SITE_ID
    ) {
      throw publicationStateMismatch()
    }
    publicationState.value = next
  } catch (cause: unknown) {
    if (!componentMounted || operation !== publicationStateGeneration) return
    publicationState.value = null
    publicationStateError.value = toApiProblem(cause)
  } finally {
    if (componentMounted && operation === publicationStateGeneration) {
      publicationStateLoading.value = false
    }
  }
}

async function reloadServer(): Promise<void> {
  await Promise.all([reloadWorkspace(), reloadPublicationState()])
}

function postPublishStateMismatch(): ApiProblem {
  return new ApiProblem({
    type: 'publication_refresh',
    title: '发布成功，但未能确认最新发布状态',
    status: 0,
    code: 'PUBLICATION_REFRESH_INCOMPLETE',
    traceId: 'client',
  })
}

async function refreshAfterPublish(result: PublicationResultDto): Promise<void> {
  const operation = ++postPublishGeneration
  postPublishRefreshing.value = true
  postPublishRefreshError.value = null
  publicationNotice.value = ''

  await Promise.all([reloadWorkspace(), reloadPublicationState()])
  if (!componentMounted || operation !== postPublishGeneration) return

  const state = publicationState.value
  const workspace = draft.value
  const workspaceFloor = publishedWorkspaceVersionFloor.value
  const refreshProblem = error.value ?? publicationStateError.value
  const workspaceMatches =
    workspace !== null &&
    workspaceFloor !== null &&
    version.value === workspace.version &&
    workspace.version >= workspaceFloor
  const exactPublishedState =
    state !== null &&
    state.status === 'PUBLISHED' &&
    state.version === result.aggregateVersion &&
    state.currentRevisionId?.toLowerCase() === result.revisionId.toLowerCase()
  const newerPublishedState =
    state !== null &&
    state.status === 'PUBLISHED' &&
    state.version > result.aggregateVersion

  if (
    refreshProblem !== null ||
    !workspaceMatches ||
    (!exactPublishedState && !newerPublishedState)
  ) {
    postPublishRefreshError.value = refreshProblem ?? postPublishStateMismatch()
  } else {
    publishedResult.value = null
    publishedWorkspaceVersionFloor.value = null
    publicationNotice.value = newerPublishedState
      ? `本次发布成功；刷新期间又有更新，已载入较新的发布版本 v${state.version}。`
      : `发布成功，已载入发布版本 v${state.version}。`
  }
  postPublishRefreshing.value = false
}

function handlePublicationBusy(busy: boolean): void {
  publishingBusy.value = busy
  if (busy) closePicker()
}

function handlePublished(result: PublicationResultDto): void {
  if (postPublishRefreshing.value || postPublishRefreshError.value !== null) return
  publishedResult.value = result
  publishedWorkspaceVersionFloor.value = sitePublishTarget.value.expectedWorkspaceVersion
  void refreshAfterPublish(result)
}

function reloadAfterPublicationConflict(): void {
  if (publishingBusy.value || postPublishRefreshing.value || postPublishRefreshError.value !== null) {
    return
  }
  void reloadServer()
}

function retryPostPublishRefresh(): void {
  const result = publishedResult.value
  if (result === null || postPublishRefreshing.value) return
  void refreshAfterPublish(result)
}

function openHeroPicker(): void {
  if (editorLocked.value) return
  pickerTarget.value = { type: 'hero' }
}

function openResumePicker(resumeId: string): void {
  if (editorLocked.value) return
  pickerTarget.value = { type: 'resume', resumeId }
}

function clearHeroMedia(): void {
  const site = draft.value
  if (site === null) return
  site.hero.mediaAssetId = null
  site.hero.objectPosition = null
  site.hero.credit = null
  site.hero.sourceUrl = null
}

function closePicker(): void {
  pickerTarget.value = null
}

function selectMedia(asset: MediaAssetSummaryDto): void {
  const target = pickerTarget.value
  const site = draft.value
  if (
    target === null ||
    site === null ||
    loading.value ||
    publicationInteractionLocked.value
  ) {
    return
  }

  if (target.type === 'hero') {
    if (!trustedReadyMedia(asset, 'IMAGE')) return
    if (site.hero.mediaAssetId !== asset.id) {
      site.hero.mediaAssetId = asset.id
      site.hero.objectPosition = '50% 50%'
      site.hero.credit = ''
      site.hero.sourceUrl = ''
    }
  } else {
    if (!trustedReadyMedia(asset, 'PDF')) return
    const resume = site.resumes.find((candidate) => candidate.id === target.resumeId)
    if (resume === undefined) return
    resume.mediaAssetId = asset.id
  }
  closePicker()
}

function loadMediaPage(request: {
  readonly page: number
  readonly size: number
}): Promise<Page<MediaAssetSummaryDto>> {
  return mediaApi.search({ page: request.page, size: request.size })
}

onMounted(() => {
  void reloadServer()
})

onBeforeUnmount(() => {
  componentMounted = false
  publicationStateGeneration += 1
  postPublishGeneration += 1
})

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm('当前页面有尚未保存的修改，确定离开吗？')
})
</script>

<template>
  <section class="mx-auto max-w-6xl" aria-labelledby="site-editor-title">
    <header class="mb-6 flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold tracking-[0.18em] text-blue-700">SITE WORKSPACE</p>
        <h1 id="site-editor-title" class="mt-2 text-3xl font-semibold tracking-tight text-slate-950">
          站点内容
        </h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
          管理公开主页的中英文内容、展示顺序、媒体和简历。有未保存修改时约每 15 秒自动保存。
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <p
          class="rounded-full bg-slate-100 px-3 py-2 text-sm font-medium text-slate-700"
          data-save-state
          role="status"
          aria-live="polite"
        >
          {{ saveState }} · v{{ version }}
        </p>
        <button
          class="rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
          type="button"
          data-action="save"
          :disabled="editorLocked || draft === null || conflict !== null"
          @click="manualSave"
        >
          {{ saving ? '正在保存…' : '立即保存' }}
        </button>
      </div>
    </header>

    <AsyncPanel
      :loading="loading && draft === null"
      :error-title="draft === null ? error?.body.title : undefined"
      :trace-id="draft === null ? error?.body.traceId : undefined"
      :on-retry="reloadServer"
    >
      <template v-if="draft !== null">
        <ConflictBanner
          v-if="conflict !== null"
          class="mb-6"
          :problem="conflict"
          :reloading="loading"
          @reload="reloadServer"
        />

        <div
          v-if="error !== null"
          class="mb-6 rounded-2xl border border-red-200 bg-red-50 p-5 text-red-900"
          role="alert"
        >
          <p class="font-semibold">{{ error.body.title }}</p>
          <p class="mt-1 text-xs">请求编号：{{ error.body.traceId }}</p>
          <ul v-if="Object.keys(visibleServerFieldErrors).length > 0" class="mt-3 list-disc space-y-1 pl-5 text-sm">
            <li v-for="(message, path) in visibleServerFieldErrors" :key="path">
              {{ fieldName(path) }}：{{ message }}
              <code class="ml-1 text-xs opacity-75" aria-label="接口字段">{{ path }}</code>
            </li>
          </ul>
        </div>

        <div
          v-if="Object.keys(visibleClientFieldErrors).length > 0"
          class="mb-6 rounded-2xl border border-amber-300 bg-amber-50 p-5 text-amber-950"
          role="alert"
        >
          <p class="font-semibold">请先修正以下内容</p>
          <ul class="mt-2 list-disc space-y-1 pl-5 text-sm">
            <li v-for="(message, path) in visibleClientFieldErrors" :key="path">
              {{ fieldName(path) }}：{{ message }}
            </li>
          </ul>
        </div>

        <div class="sr-only">
          <p v-for="(message, path) in allFieldErrors" :id="fieldErrorId(path)" :key="path">
            {{ fieldName(path) }}：{{ message }}
          </p>
        </div>

        <section class="mb-8 space-y-4" aria-label="站点预览与发布">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <p class="text-sm text-slate-600" data-publication-state role="status" aria-live="polite">
              <template v-if="publicationStateLoading">正在载入发布状态…</template>
              <template v-else-if="publicationState !== null">
                发布状态：{{ publicationState.status }} · v{{ publicationState.version }}
              </template>
              <template v-else>发布状态暂不可用</template>
            </p>
            <RouterLink
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
              data-action="publication-history"
              :to="{
                name: 'publishing-history',
                params: { aggregateType: 'SITE', aggregateId: SITE_ID },
              }"
            >
              查看发布历史
            </RouterLink>
          </div>

          <div
            v-if="publicationStateError !== null && postPublishRefreshError === null"
            class="rounded-2xl border border-red-200 bg-red-50 p-5 text-red-900"
            data-publication-state-error
            role="alert"
          >
            <p class="font-semibold">{{ publicationStateError.body.title }}</p>
            <p class="mt-1 text-xs">请求编号：{{ publicationStateError.body.traceId }}</p>
            <button
              class="mt-3 rounded-lg border border-red-300 bg-white px-3 py-2 text-sm font-semibold text-red-800 disabled:opacity-55"
              type="button"
              data-action="retry-publication-state"
              :disabled="publicationStateLoading || publicationInteractionLocked"
              @click="reloadPublicationState"
            >
              重试发布状态
            </button>
          </div>

          <div
            v-if="postPublishRefreshError !== null"
            class="rounded-2xl border border-amber-300 bg-amber-50 p-5 text-amber-950"
            data-post-publish-refresh-error
            role="alert"
          >
            <p class="font-semibold">内容已发布，但最新工作区或发布状态载入失败</p>
            <p class="mt-2 text-sm">{{ postPublishRefreshError.body.title }}</p>
            <p class="mt-1 text-xs">请求编号：{{ postPublishRefreshError.body.traceId }}</p>
            <button
              class="mt-3 rounded-lg border border-amber-400 bg-white px-3 py-2 text-sm font-semibold text-amber-900 disabled:opacity-55"
              type="button"
              data-action="retry-post-publish-refresh"
              :disabled="postPublishRefreshing"
              @click="retryPostPublishRefresh"
            >
              {{ postPublishRefreshing ? '正在重新载入…' : '仅重试载入最新状态' }}
            </button>
          </div>

          <p
            v-if="publicationNotice"
            class="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
            data-publication-notice
            role="status"
            aria-live="polite"
          >
            {{ publicationNotice }}
          </p>

          <PublishPanel
            :target="sitePublishTarget"
            :locale="locale"
            :completion="completion"
            :disabled="publicationDisabled"
            @busy-change="handlePublicationBusy"
            @published="handlePublished"
            @reload-requested="reloadAfterPublicationConflict"
          />
        </section>

        <form novalidate :aria-busy="editorLocked" @submit.prevent="manualSave">
          <fieldset :disabled="editorLocked" class="min-w-0 space-y-8">
          <section aria-labelledby="identity-heading">
            <h2 id="identity-heading" class="mb-4 text-2xl font-semibold text-slate-950">身份</h2>
            <SiteIdentityForm
              :monogram="draft.monogram"
              :email="draft.email"
              :disabled="editorLocked"
              :field-errors="identityErrors"
              @update:monogram="draft.monogram = $event"
              @update:email="draft.email = $event"
            />
          </section>

          <TranslationTabs v-model="locale" :status="completion" :disabled="editorLocked">
            <template #default="{ locale: activeLocale }">
              <div class="mt-6 space-y-8">
                <section class="space-y-4" aria-labelledby="localized-identity-heading">
                  <h2 id="localized-identity-heading" class="text-2xl font-semibold text-slate-950">
                    姓名
                  </h2>
                  <SiteTranslationForm
                    :model-value="draft.identity[activeLocale]"
                    :locale="activeLocale"
                    title="姓名"
                    :fields="identityFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('identity', activeLocale, identityFields)"
                    @update:model-value="draft.identity[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-4" aria-labelledby="seo-heading">
                  <h2 id="seo-heading" class="text-2xl font-semibold text-slate-950">SEO</h2>
                  <SiteTranslationForm
                    :model-value="draft.seo[activeLocale]"
                    :locale="activeLocale"
                    title="SEO"
                    :fields="seoFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('seo', activeLocale, seoFields)"
                    @update:model-value="draft.seo[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-4" aria-labelledby="accessibility-heading">
                  <h2 id="accessibility-heading" class="text-2xl font-semibold text-slate-950">
                    无障碍
                  </h2>
                  <SiteTranslationForm
                    :model-value="draft.accessibility[activeLocale]"
                    :locale="activeLocale"
                    title="无障碍文案"
                    :fields="accessibilityFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('accessibility', activeLocale, accessibilityFields)"
                    @update:model-value="draft.accessibility[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-4" aria-labelledby="navigation-heading">
                  <h2 id="navigation-heading" class="text-2xl font-semibold text-slate-950">导航</h2>
                  <OrderedLocalizedList
                    :items="draft.navigation"
                    :locale="activeLocale"
                    list-label="导航项"
                    reorder-key="navigation"
                    :item-label="navigationLabel"
                    :disabled="editorLocked"
                    @update:items="draft.navigation = $event; suppressFieldErrors('navigation')"
                  >
                    <template #default="{ item, index }">
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">
                          目标锚点
                          <input
                            class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                            :value="item.target"
                            :data-field="`navigation.${index}.target`"
                            v-bind="fieldErrorAttributes(`navigation.${index}.target`)"
                            @input="item.target = inputValue($event)"
                          />
                        </label>
                        <label class="text-sm font-medium text-slate-700">
                          {{ activeLocale === 'zh-CN' ? '中文标签' : '英文标签' }}
                          <input
                            class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                            :lang="activeLocale"
                            :value="item.labels[activeLocale]"
                            :data-field="`navigation.${index}.label`"
                            v-bind="fieldErrorAttributes(`navigation.${index}.labels.${activeLocale}`)"
                            @input="item.labels[activeLocale] = inputValue($event)"
                          />
                        </label>
                        <label class="mt-7 flex items-center gap-2 text-sm font-medium text-slate-700">
                          <input
                            type="checkbox"
                            :checked="item.visible"
                            v-bind="fieldErrorAttributes(`navigation.${index}.visible`)"
                            @change="item.visible = checkedValue($event)"
                          />
                          公开显示
                        </label>
                      </div>
                    </template>
                  </OrderedLocalizedList>
                </section>

                <section class="space-y-4" aria-labelledby="hero-heading">
                  <h2 id="hero-heading" class="text-2xl font-semibold text-slate-950">Hero</h2>
                  <div class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
                    <div class="grid gap-4 md:grid-cols-2">
                      <label class="text-sm font-medium text-slate-700">
                        图片位置
                        <input
                          class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                          data-field="hero.objectPosition"
                          :value="draft.hero.objectPosition ?? ''"
                          v-bind="fieldErrorAttributes('hero.objectPosition')"
                          @input="draft.hero.objectPosition = nullableInputValue($event)"
                        />
                        <span v-if="allFieldErrors['hero.objectPosition']" class="mt-2 block text-sm text-red-700" role="alert">{{ allFieldErrors['hero.objectPosition'] }}</span>
                      </label>
                      <label class="text-sm font-medium text-slate-700">
                        图片署名
                        <input
                          class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                          data-field="hero.credit"
                          :value="draft.hero.credit ?? ''"
                          v-bind="fieldErrorAttributes('hero.credit')"
                          @input="draft.hero.credit = nullableInputValue($event)"
                        />
                        <span v-if="allFieldErrors['hero.credit']" class="mt-2 block text-sm text-red-700" role="alert">{{ allFieldErrors['hero.credit'] }}</span>
                      </label>
                      <label class="text-sm font-medium text-slate-700 md:col-span-2">
                        来源链接（HTTPS）
                        <input
                          class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                          data-field="hero.sourceUrl"
                          type="url"
                          :value="draft.hero.sourceUrl ?? ''"
                          v-bind="fieldErrorAttributes('hero.sourceUrl')"
                          @input="draft.hero.sourceUrl = nullableInputValue($event)"
                        />
                        <span v-if="allFieldErrors['hero.sourceUrl']" class="mt-2 block text-sm text-red-700" role="alert">
                          {{ allFieldErrors['hero.sourceUrl'] }}
                        </span>
                      </label>
                    </div>
                    <div class="mt-4 flex flex-wrap items-center gap-3">
                      <button
                        class="rounded-lg border border-blue-300 bg-blue-50 px-3 py-2 text-sm font-semibold text-blue-800"
                        type="button"
                        data-media-target="hero"
                        v-bind="fieldErrorAttributes('hero.media')"
                        @click="openHeroPicker"
                      >
                        从媒体库选择 Hero 图片
                      </button>
                      <button
                        v-if="draft.hero.mediaAssetId !== null"
                        class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700"
                        type="button"
                        data-action="clear-hero-media"
                        @click="clearHeroMedia"
                      >
                        移除 Hero 图片
                      </button>
                      <code class="break-all text-xs text-slate-500">{{ draft.hero.mediaAssetId ?? '未选择' }}</code>
                    </div>
                    <p v-if="allFieldErrors['hero.media']" class="mt-3 text-sm text-red-700" role="alert">{{ allFieldErrors['hero.media'] }}</p>
                  </div>
                  <div data-section="hero-copy">
                    <SiteTranslationForm
                      :model-value="draft.hero.copy[activeLocale]"
                      :locale="activeLocale"
                      title="Hero 文案"
                      :fields="heroFields"
                      :disabled="editorLocked"
                      :field-errors="translatedErrors('hero.copy', activeLocale, heroFields)"
                      @update:model-value="draft.hero.copy[activeLocale] = $event"
                    />
                  </div>
                </section>

                <section class="space-y-5" aria-labelledby="about-heading">
                  <h2 id="about-heading" class="text-2xl font-semibold text-slate-950">关于</h2>
                  <SiteTranslationForm
                    :model-value="draft.about[activeLocale]"
                    :locale="activeLocale"
                    title="关于文案"
                    :fields="aboutFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('about', activeLocale, aboutFields)"
                    @update:model-value="draft.about[activeLocale] = $event"
                  />

                  <OrderedLocalizedList
                    :items="draft.facts"
                    :locale="activeLocale"
                    list-label="资料卡"
                    reorder-key="facts"
                    :item-label="factLabel"
                    :disabled="editorLocked"
                    @update:items="draft.facts = $event; suppressFieldErrors('facts')"
                  >
                    <template #default="{ item, index }">
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">
                          稳定键
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="item.externalKey" v-bind="fieldErrorAttributes(`facts.${index}.externalKey`)" @input="item.externalKey = inputValue($event)" />
                        </label>
                        <label class="text-sm font-medium text-slate-700">
                          标签
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`facts.${index}.label`" :value="item.copy[activeLocale].label" v-bind="fieldErrorAttributes(`facts.${index}.copy.${activeLocale}.label`)" @input="item.copy[activeLocale].label = inputValue($event)" />
                        </label>
                        <label class="text-sm font-medium text-slate-700">
                          内容
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`facts.${index}.value`" :value="item.copy[activeLocale].value" v-bind="fieldErrorAttributes(`facts.${index}.copy.${activeLocale}.value`)" @input="item.copy[activeLocale].value = inputValue($event)" />
                        </label>
                      </div>
                    </template>
                  </OrderedLocalizedList>

                  <OrderedLocalizedList
                    :items="draft.profileSkills"
                    :locale="activeLocale"
                    list-label="技能状态"
                    reorder-key="profileSkills"
                    :item-label="skillLabel"
                    :disabled="editorLocked"
                    @update:items="draft.profileSkills = $event; suppressFieldErrors('profileSkills')"
                  >
                    <template #default="{ item, index }">
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">
                          稳定键
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="item.externalKey" v-bind="fieldErrorAttributes(`profileSkills.${index}.externalKey`)" @input="item.externalKey = inputValue($event)" />
                        </label>
                        <label class="text-sm font-medium text-slate-700">
                          技能
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`profileSkills.${index}.name`" :value="item.copy[activeLocale].name" v-bind="fieldErrorAttributes(`profileSkills.${index}.copy.${activeLocale}.name`)" @input="item.copy[activeLocale].name = inputValue($event)" />
                        </label>
                        <label class="text-sm font-medium text-slate-700">
                          状态
                          <input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`profileSkills.${index}.status`" :value="item.copy[activeLocale].status" v-bind="fieldErrorAttributes(`profileSkills.${index}.copy.${activeLocale}.status`)" @input="item.copy[activeLocale].status = inputValue($event)" />
                        </label>
                      </div>
                    </template>
                  </OrderedLocalizedList>
                </section>

                <section class="space-y-4" aria-labelledby="work-heading">
                  <h2 id="work-heading" class="text-2xl font-semibold text-slate-950">作品区</h2>
                  <SiteTranslationForm
                    :model-value="draft.work[activeLocale]"
                    :locale="activeLocale"
                    title="作品区文案"
                    :fields="workFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('work', activeLocale, workFields)"
                    @update:model-value="draft.work[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-5" aria-labelledby="roadmap-heading">
                  <h2 id="roadmap-heading" class="text-2xl font-semibold text-slate-950">路线图</h2>
                  <SiteTranslationForm
                    :model-value="draft.roadmap.header[activeLocale]"
                    :locale="activeLocale"
                    title="路线图标题"
                    :fields="roadmapHeaderFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('roadmap.header', activeLocale, roadmapHeaderFields)"
                    @update:model-value="draft.roadmap.header[activeLocale] = $event"
                  />

                  <OrderedLocalizedList
                    :items="draft.roadmap.stages"
                    :locale="activeLocale"
                    list-label="路线图阶段"
                    reorder-key="roadmapStages"
                    :item-label="stageLabel"
                    :disabled="editorLocked"
                    @update:items="draft.roadmap.stages = $event; suppressFieldErrors('roadmap.stages')"
                  >
                    <template #default="{ item: stage, index: stageIndex }">
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">稳定键<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="stage.externalKey" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.externalKey`)" @input="stage.externalKey = inputValue($event)" /></label>
                        <label class="text-sm font-medium text-slate-700">编号<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="stage.number" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.number`)" @input="stage.number = inputValue($event)" /></label>
                        <label class="mt-7 flex items-center gap-2 text-sm font-medium text-slate-700"><input type="checkbox" :checked="stage.visible" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.visible`)" @change="stage.visible = checkedValue($event)" />公开显示</label>
                        <label class="text-sm font-medium text-slate-700">阶段<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`roadmap.stages.${stageIndex}.period`" :value="stage.copy[activeLocale].period" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.copy.${activeLocale}.period`)" @input="stage.copy[activeLocale].period = inputValue($event)" /></label>
                        <label class="text-sm font-medium text-slate-700">标题<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="activeLocale" :data-field="`roadmap.stages.${stageIndex}.title`" :value="stage.copy[activeLocale].title" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.copy.${activeLocale}.title`)" @input="stage.copy[activeLocale].title = inputValue($event)" /></label>
                        <label class="text-sm font-medium text-slate-700">摘要<textarea class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" rows="3" :lang="activeLocale" :data-field="`roadmap.stages.${stageIndex}.summary`" :value="stage.copy[activeLocale].summary" v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.copy.${activeLocale}.summary`)" @input="stage.copy[activeLocale].summary = inputValue($event)" /></label>
                      </div>

                      <div class="mt-5">
                        <OrderedLocalizedList
                          :items="stage.outcomes"
                          :locale="activeLocale"
                          list-label="阶段成果"
                          reorder-key="roadmapOutcomes"
                          :item-label="outcomeLabel"
                          :disabled="editorLocked"
                          @update:items="stage.outcomes = $event; suppressFieldErrors(`roadmap.stages.${stageIndex}.outcomes`)"
                        >
                          <template #default="{ item: outcome, index: outcomeIndex }">
                            <label class="text-sm font-medium text-slate-700">
                              成果
                              <input
                                class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2"
                                :lang="activeLocale"
                                :data-field="`roadmap.stages.${stageIndex}.outcomes.${outcomeIndex}`"
                                :value="outcome.text[activeLocale]"
                                v-bind="fieldErrorAttributes(`roadmap.stages.${stageIndex}.outcomes.${outcomeIndex}.text.${activeLocale}`)"
                                @input="outcome.text[activeLocale] = inputValue($event)"
                              />
                            </label>
                          </template>
                        </OrderedLocalizedList>
                      </div>
                    </template>
                  </OrderedLocalizedList>
                </section>

                <section class="space-y-4" aria-labelledby="contact-heading">
                  <h2 id="contact-heading" class="text-2xl font-semibold text-slate-950">联系</h2>
                  <SiteTranslationForm
                    :model-value="draft.contact[activeLocale]"
                    :locale="activeLocale"
                    title="联系文案"
                    :fields="contactFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('contact', activeLocale, contactFields)"
                    @update:model-value="draft.contact[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-4" aria-labelledby="privacy-heading">
                  <h2 id="privacy-heading" class="text-2xl font-semibold text-slate-950">隐私</h2>
                  <SiteTranslationForm
                    :model-value="draft.privacy[activeLocale]"
                    :locale="activeLocale"
                    title="隐私说明"
                    :fields="privacyFields"
                    :disabled="editorLocked"
                    :field-errors="translatedErrors('privacy', activeLocale, privacyFields)"
                    @update:model-value="draft.privacy[activeLocale] = $event"
                  />
                </section>

                <section class="space-y-4" aria-labelledby="social-heading">
                  <h2 id="social-heading" class="text-2xl font-semibold text-slate-950">社交链接</h2>
                  <OrderedLocalizedList
                    :items="draft.socialLinks"
                    :locale="activeLocale"
                    list-label="社交链接排序"
                    reorder-key="socialLinks"
                    :item-label="socialLabel"
                    :disabled="editorLocked"
                    @update:items="draft.socialLinks = $event; suppressFieldErrors('socialLinks')"
                  >
                    <template #default="{ item, index }">
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">平台<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="item.platform" v-bind="fieldErrorAttributes(`socialLinks.${index}.platform`)" @input="item.platform = inputValue($event)" /></label>
                        <label class="text-sm font-medium text-slate-700">HTTPS 地址<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" type="url" :data-field="`socialLinks.${index}.url`" :value="item.url" v-bind="fieldErrorAttributes(`socialLinks.${index}.url`)" @input="item.url = inputValue($event)" /><span v-if="fieldErrorMessage(`socialLinks.${index}.url`)" class="mt-2 block text-sm text-red-700" role="alert">{{ fieldErrorMessage(`socialLinks.${index}.url`) }}</span></label>
                        <label class="mt-7 flex items-center gap-2 text-sm font-medium text-slate-700"><input type="checkbox" :checked="item.visible" v-bind="fieldErrorAttributes(`socialLinks.${index}.visible`)" @change="item.visible = checkedValue($event)" />公开显示</label>
                      </div>
                    </template>
                  </OrderedLocalizedList>
                </section>

                <section class="space-y-4" aria-labelledby="resume-heading">
                  <h2 id="resume-heading" class="text-2xl font-semibold text-slate-950">双语简历</h2>
                  <div class="grid gap-4">
                    <article
                      v-for="(resume, resumeIndex) in draft.resumes"
                      :key="resume.id"
                      class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
                      :data-resume-id="resume.id"
                    >
                      <div class="grid gap-4 md:grid-cols-3">
                        <label class="text-sm font-medium text-slate-700">语言<select class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :value="resume.locale" v-bind="fieldErrorAttributes(`resumes.${resumeIndex}.locale`)" @change="resume.locale = inputValue($event) as Locale"><option value="zh-CN">中文</option><option value="en">English</option></select></label>
                        <label class="text-sm font-medium text-slate-700">版本标签<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" :lang="resume.locale" :value="resume.versionLabel" v-bind="fieldErrorAttributes(`resumes.${resumeIndex}.versionLabel`)" @input="resume.versionLabel = inputValue($event)" /></label>
                        <label class="text-sm font-medium text-slate-700">文档日期<input class="mt-2 w-full rounded-lg border border-slate-300 px-3 py-2" type="date" :value="resume.documentDate" v-bind="fieldErrorAttributes(`resumes.${resumeIndex}.documentDate`)" @input="resume.documentDate = inputValue($event)" /></label>
                        <label class="flex items-center gap-2 text-sm font-medium text-slate-700"><input type="checkbox" :checked="resume.current" v-bind="fieldErrorAttributes(`resumes.${resumeIndex}.current`)" @change="resume.current = checkedValue($event)" />当前版本</label>
                        <div class="md:col-span-2">
                          <button
                            v-if="resume.locale === activeLocale && resume.current"
                            class="rounded-lg border border-blue-300 bg-blue-50 px-3 py-2 text-sm font-semibold text-blue-800"
                            type="button"
                            data-media-target="resume"
                            v-bind="fieldErrorAttributes(`resumes.${resumeIndex}.mediaAssetId`)"
                            @click="openResumePicker(resume.id)"
                          >
                            选择 {{ activeLocale === 'zh-CN' ? '中文' : '英文' }} PDF 简历
                          </button>
                          <code class="ml-3 break-all text-xs text-slate-500">{{ resume.mediaAssetId }}</code>
                        </div>
                      </div>
                    </article>
                  </div>
                </section>
              </div>
            </template>
          </TranslationTabs>

          <div class="sticky bottom-4 z-10 flex justify-end rounded-2xl border border-slate-200 bg-white/95 p-3 shadow-lg backdrop-blur">
            <button
              class="rounded-xl bg-blue-700 px-5 py-3 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
              type="submit"
              :disabled="editorLocked || conflict !== null"
            >
              {{ saving ? '正在保存…' : '保存站点内容' }}
            </button>
          </div>
          </fieldset>
        </form>
      </template>
    </AsyncPanel>

    <MediaPickerDialog
      :open="pickerTarget !== null && !publicationInteractionLocked"
      :accept="pickerAccept"
      :load="loadMediaPage"
      @select="selectMedia"
      @close="closePicker"
      @update:open="($event) => { if (!$event) closePicker() }"
    />
  </section>
</template>
