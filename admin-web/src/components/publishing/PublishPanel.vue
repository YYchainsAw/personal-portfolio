<script setup lang="ts">
import { computed, onUnmounted, ref, useId, watch } from 'vue'

import { publishingApi } from '@/api/publishingApi'
import { toApiProblem } from '@/api/http'
import { ApiProblem } from '@/types/api'
import { locales, type Locale, type TranslationStatus } from '@/types/content'
import type {
  PreviewTokenRequest,
  PreviewTokenResponse,
  PublicationResultDto,
  PublishTarget,
} from '@/types/publishing'

type CreatePreview = (request: PreviewTokenRequest) => Promise<PreviewTokenResponse>
type PreflightPreview = (token: string) => Promise<void>
type PreviewUrl = (token: string) => string
type Publish = (target: PublishTarget) => Promise<PublicationResultDto>
type OperationKind = 'preview' | 'publish'

const props = withDefaults(
  defineProps<{
    target: PublishTarget
    locale: Locale
    completion: TranslationStatus
    disabled?: boolean
    createPreview?: CreatePreview
    preflightPreview?: PreflightPreview
    previewUrl?: PreviewUrl
    publishTarget?: Publish
  }>(),
  {
    disabled: false,
    createPreview: (request: PreviewTokenRequest) => publishingApi.createPreview(request),
    preflightPreview: (token: string) => publishingApi.preflightPreview(token),
    previewUrl: (token: string) => publishingApi.previewUrl(token),
    publishTarget: (target: PublishTarget) => publishingApi.publishTarget(target),
  },
)

const emit = defineEmits<{
  'busy-change': [busy: boolean]
  published: [result: PublicationResultDto]
  'reload-requested': []
}>()

const localeLabels: Readonly<Record<Locale, string>> = Object.freeze({
  'zh-CN': '中文',
  en: 'English',
})

const panelId = useId()
const titleId = `${panelId}-title`
const completionId = `${panelId}-completion`
const statusId = `${panelId}-status`

const busy = ref(false)
const operation = ref<OperationKind | null>(null)
const problem = ref<ApiProblem | null>(null)
const localError = ref<string | null>(null)
const statusMessage = ref('')
const reloadRequested = ref(false)

let mounted = true
let sequence = 0
let activeSequence: number | null = null

interface OperationContext {
  readonly id: number
  readonly targetSignature: string
}

const targetSignature = computed(() => JSON.stringify(props.target))
const completionRows = computed(() =>
  locales.map((locale) => ({ locale, ...props.completion[locale] })),
)
const publicationReady = computed(() =>
  completionRows.value.every(
    ({ complete, total }) =>
      Number.isInteger(complete) &&
      Number.isInteger(total) &&
      complete >= 0 &&
      total >= 0 &&
      complete === total,
  ),
)
const conflict = computed(() =>
  problem.value?.body.status === 409 ? problem.value : null,
)
const actionsDisabled = computed(
  () => props.disabled || busy.value || conflict.value !== null,
)
const generalProblem = computed(() =>
  problem.value !== null && problem.value.body.status !== 409 ? problem.value : null,
)
const fieldErrors = computed(() =>
  Object.entries(generalProblem.value?.body.fieldErrors ?? {}).sort(([left], [right]) =>
    left.localeCompare(right),
  ),
)

function beginOperation(kind: OperationKind): OperationContext | null {
  if (actionsDisabled.value) return null
  sequence += 1
  activeSequence = sequence
  busy.value = true
  operation.value = kind
  problem.value = null
  localError.value = null
  statusMessage.value = ''
  emit('busy-change', true)
  return { id: sequence, targetSignature: targetSignature.value }
}

function isCurrent(context: OperationContext): boolean {
  return (
    mounted &&
    activeSequence === context.id &&
    targetSignature.value === context.targetSignature
  )
}

function finishOperation(context: OperationContext): void {
  if (!isCurrent(context)) return
  activeSequence = null
  busy.value = false
  operation.value = null
  emit('busy-change', false)
}

function showProblem(context: OperationContext, error: unknown): void {
  if (!isCurrent(context)) return
  problem.value = toApiProblem(error)
}

function requestConflictReload(): void {
  if (conflict.value === null || busy.value || reloadRequested.value) return
  reloadRequested.value = true
  emit('reload-requested')
}

function previewRequest(target: PublishTarget): PreviewTokenRequest {
  return {
    aggregateType: target.aggregateType,
    aggregateId: target.aggregateId,
    workspaceVersion: target.expectedWorkspaceVersion,
  }
}

function tokenExpiry(response: PreviewTokenResponse): number | null {
  if (typeof response.token !== 'string' || response.token.trim().length === 0) return null
  const expiresAt = Date.parse(response.expiresAt)
  return Number.isFinite(expiresAt) ? expiresAt : null
}

function requireUsableToken(
  context: OperationContext,
  response: PreviewTokenResponse,
): boolean {
  const expiresAt = tokenExpiry(response)
  if (expiresAt !== null && expiresAt > Date.now()) return true
  if (isCurrent(context)) {
    localError.value =
      expiresAt === null
        ? '服务器返回的预览链接不可用，请重新生成。'
        : '预览令牌已过期，请重新生成。'
  }
  return false
}

function isSameOriginPath(value: string): boolean {
  return /^\/(?!\/)[^\\\u0000-\u001f\u007f]*$/.test(value)
}

async function preview(): Promise<void> {
  const target = props.target
  const context = beginOperation('preview')
  if (context === null) return

  try {
    const response = await props.createPreview(previewRequest(target))
    if (!isCurrent(context) || !requireUsableToken(context, response)) return

    await props.preflightPreview(response.token)
    if (!isCurrent(context) || !requireUsableToken(context, response)) return

    const url = props.previewUrl(response.token)
    if (!isSameOriginPath(url)) {
      localError.value = '已阻止不安全的预览地址，请重新生成预览。'
      return
    }

    try {
      window.open(url, '_blank', 'noopener,noreferrer')
    } catch {
      if (isCurrent(context)) {
        localError.value = '浏览器未能打开预览窗口，请允许本站打开新窗口后重试。'
      }
      return
    }
    if (!isCurrent(context)) return
    statusMessage.value = '已请求打开预览；若未出现新标签页，请允许本站弹窗后重试。'
  } catch (error: unknown) {
    showProblem(context, error)
  } finally {
    finishOperation(context)
  }
}

async function publish(): Promise<void> {
  if (actionsDisabled.value || !publicationReady.value) return
  if (!window.confirm('确认发布当前已保存版本？发布后访客将看到这次更新。')) return

  const target = props.target
  const context = beginOperation('publish')
  if (context === null) return

  try {
    const result = await props.publishTarget(target)
    if (!isCurrent(context)) return
    statusMessage.value = '发布成功。'
    emit('published', result)
  } catch (error: unknown) {
    showProblem(context, error)
  } finally {
    finishOperation(context)
  }
}

watch(
  targetSignature,
  () => {
    sequence += 1
    activeSequence = null
    if (busy.value) emit('busy-change', false)
    busy.value = false
    operation.value = null
    problem.value = null
    localError.value = null
    statusMessage.value = ''
    reloadRequested.value = false
  },
  { flush: 'sync' },
)

onUnmounted(() => {
  mounted = false
  sequence += 1
  activeSequence = null
  busy.value = false
  operation.value = null
})
</script>

<template>
  <section
    class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
    data-publish-panel
    :aria-busy="busy"
    :aria-labelledby="titleId"
  >
    <div class="flex flex-wrap items-start justify-between gap-4">
      <div>
        <p class="text-xs font-semibold tracking-[0.16em] text-blue-700">PUBLISH</p>
        <h2 :id="titleId" class="mt-2 text-xl font-semibold text-slate-950">预览与发布</h2>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-slate-600">
          预览会先检查服务器上的当前草稿；正式发布需要中英文内容都完整。
        </p>
      </div>
      <span
        class="rounded-full px-3 py-1 text-xs font-semibold"
        :class="publicationReady ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-900'"
      >
        {{ publicationReady ? '可以发布' : '内容待补全' }}
      </span>
    </div>

    <dl class="mt-5 grid gap-3 sm:grid-cols-2" aria-label="双语内容完成度">
      <div
        v-for="row in completionRows"
        :key="row.locale"
        class="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3"
        :data-locale-completion="row.locale"
        :aria-current="locale === row.locale ? 'true' : undefined"
      >
        <dt class="flex items-center justify-between gap-3 text-sm font-medium text-slate-700">
          <span :lang="row.locale">{{ localeLabels[row.locale] }}</span>
          <span v-if="locale === row.locale" class="text-xs text-blue-700">当前编辑</span>
        </dt>
        <dd class="mt-1 text-lg font-semibold text-slate-950">
          {{ row.complete }}/{{ row.total }}
        </dd>
      </div>
    </dl>

    <p :id="completionId" class="mt-4 text-sm leading-6 text-slate-600">
      {{
        publicationReady
          ? '双语内容已完整，可以预览或发布。'
          : '发布前请补齐中英文内容；结构预览仍然可用。'
      }}
    </p>

    <section
      v-if="conflict"
      class="mt-5 rounded-xl border border-amber-300 bg-amber-50 p-4 text-amber-950"
      data-publication-conflict
      role="alert"
    >
      <p class="text-xs font-semibold tracking-[0.14em] text-amber-800">VERSION CONFLICT</p>
      <h3 class="mt-2 font-semibold">{{ conflict.body.title }}</h3>
      <p class="mt-2 text-sm leading-6">
        当前发布版本已经变化。请重新载入最新状态，确认后再手动发布。
      </p>
      <p class="mt-1 text-xs text-amber-800">请求编号：{{ conflict.body.traceId }}</p>
      <button
        class="mt-3 rounded-lg border border-amber-400 bg-white px-3 py-2 text-sm font-semibold text-amber-950 disabled:cursor-not-allowed disabled:opacity-55"
        type="button"
        data-action="reload-publication-conflict"
        :disabled="reloadRequested"
        @click="requestConflictReload"
      >{{ reloadRequested ? '正在重新载入…' : '重新载入最新状态' }}</button>
    </section>

    <section
      v-else-if="generalProblem"
      class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-red-900"
      data-publication-error
      role="alert"
    >
      <h3 class="font-semibold">{{ generalProblem.body.title }}</h3>
      <p class="mt-1 text-xs text-red-800">请求编号：{{ generalProblem.body.traceId }}</p>
      <ul v-if="fieldErrors.length > 0" class="mt-3 grid gap-2 pl-5 text-sm">
        <li v-for="([path, message]) in fieldErrors" :key="path">
          <code class="break-all font-semibold">{{ path }}</code>
          <span>：{{ message }}</span>
        </li>
      </ul>
    </section>

    <p
      v-if="localError"
      class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
      data-preview-error
      role="alert"
    >
      {{ localError }}
    </p>

    <p
      :id="statusId"
      class="mt-4 min-h-5 text-sm text-slate-600"
      data-publish-status
      role="status"
      aria-live="polite"
    >
      {{ statusMessage }}
    </p>

    <fieldset class="mt-4" :disabled="actionsDisabled">
      <legend class="sr-only">预览与发布操作</legend>
      <div class="flex flex-wrap gap-3">
        <button
          class="rounded-xl border border-blue-300 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-800 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-55"
          type="button"
          data-action="preview"
          :disabled="actionsDisabled"
          :aria-describedby="`${completionId} ${statusId}`"
          @click="preview"
        >
          {{ operation === 'preview' ? '正在准备预览…' : '预览已保存草稿' }}
        </button>
        <button
          class="rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-55"
          type="button"
          data-action="publish"
          :disabled="actionsDisabled || !publicationReady"
          :aria-describedby="`${completionId} ${statusId}`"
          @click="publish"
        >
          {{ operation === 'publish' ? '正在发布…' : '正式发布' }}
        </button>
      </div>
    </fieldset>
  </section>
</template>
