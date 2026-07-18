import {
  getCurrentScope,
  onScopeDispose,
  ref,
  toRaw,
  watch,
  type Ref,
} from 'vue'

import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { SaveWorkspaceRequest } from '@/types/content'

const DEFAULT_AUTOSAVE_INTERVAL_MS = 15_000

export interface VersionedDraftOptions<T> {
  load(): Promise<VersionedDraft<T>>
  save(request: SaveWorkspaceRequest<T>): Promise<VersionedDraft<T>>
  readonly intervalMs?: number
}

function safeProblem(title: string, code: string): ApiProblem {
  return new ApiProblem({
    type: 'client_error',
    title,
    status: 0,
    code,
    traceId: 'client',
  })
}

function cloneWorkspace<T>(value: T): T {
  return structuredClone(deepUnwrap(value, new WeakMap<object, unknown>())) as T
}

function deepUnwrap(value: unknown, seen: WeakMap<object, unknown>): unknown {
  if (value === null || typeof value !== 'object') return value

  const raw = toRaw(value)
  const existing = seen.get(raw)
  if (existing !== undefined) return existing

  if (Array.isArray(raw)) {
    const result: unknown[] = []
    seen.set(raw, result)
    for (const item of raw) result.push(deepUnwrap(item, seen))
    return result
  }

  if (raw instanceof Map) {
    const result = new Map<unknown, unknown>()
    seen.set(raw, result)
    for (const [key, item] of raw) {
      result.set(deepUnwrap(key, seen), deepUnwrap(item, seen))
    }
    return result
  }

  if (raw instanceof Set) {
    const result = new Set<unknown>()
    seen.set(raw, result)
    for (const item of raw) result.add(deepUnwrap(item, seen))
    return result
  }

  const prototype = Object.getPrototypeOf(raw)
  if (prototype !== Object.prototype && prototype !== null) return raw

  const result: Record<string, unknown> = {}
  seen.set(raw, result)
  for (const key of Object.keys(raw)) {
    result[key] = deepUnwrap((raw as Record<string, unknown>)[key], seen)
  }
  return result
}

function autosaveInterval(value: number | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? Math.max(1, Math.floor(value))
    : DEFAULT_AUTOSAVE_INTERVAL_MS
}

export function useVersionedDraft<T>(options: VersionedDraftOptions<T>) {
  const draft = ref<T | null>(null) as Ref<T | null>
  const version = ref(0)
  const loading = ref(false)
  const saving = ref(false)
  const dirty = ref(false)
  const error = ref<ApiProblem | null>(null)
  const conflict = ref<ApiProblem | null>(null)

  const intervalMs = autosaveInterval(options.intervalMs)
  let disposed = false
  let hydrating = false
  let editRevision = 0
  let operationGeneration = 0
  let autosaveTimer: ReturnType<typeof setTimeout> | null = null

  function clearAutosave(): void {
    if (autosaveTimer !== null) clearTimeout(autosaveTimer)
    autosaveTimer = null
  }

  function canAutosave(): boolean {
    return (
      !disposed &&
      draft.value !== null &&
      dirty.value &&
      !loading.value &&
      !saving.value &&
      error.value === null &&
      conflict.value === null
    )
  }

  function scheduleAutosave(): void {
    if (autosaveTimer !== null || !canAutosave()) return
    autosaveTimer = setTimeout(() => {
      autosaveTimer = null
      void attemptSave(false)
    }, intervalMs)
  }

  function applyLoaded(result: VersionedDraft<T>): void {
    const detached = cloneWorkspace(result.value)
    clearAutosave()
    hydrating = true
    try {
      draft.value = detached
      version.value = result.version
      dirty.value = false
      error.value = null
      conflict.value = null
      editRevision += 1
    } finally {
      hydrating = false
    }
  }

  const stopDraftWatch = watch(
    draft,
    () => {
      if (disposed || hydrating || draft.value === null) return
      editRevision += 1
      dirty.value = true
      scheduleAutosave()
    },
    { deep: true, flush: 'sync' },
  )

  async function reload(): Promise<void> {
    if (disposed) return
    const operation = ++operationGeneration
    clearAutosave()
    loading.value = true
    error.value = null
    try {
      const result = await options.load()
      if (disposed || operation !== operationGeneration) return
      applyLoaded(result)
    } catch (cause) {
      if (disposed || operation !== operationGeneration) return
      error.value = cause instanceof ApiProblem ? cause : safeProblem('加载失败', 'LOAD_FAILED')
    } finally {
      if (!disposed && operation === operationGeneration) loading.value = false
    }
  }

  async function attemptSave(manual: boolean): Promise<void> {
    if (
      disposed ||
      draft.value === null ||
      !dirty.value ||
      saving.value ||
      loading.value ||
      conflict.value !== null ||
      (!manual && error.value !== null)
    ) {
      return
    }

    clearAutosave()
    saving.value = true
    error.value = null
    const operation = operationGeneration
    const savedRevision = editRevision
    const expectedVersion = version.value

    try {
      const workspace = cloneWorkspace(draft.value)
      const result = await options.save({ expectedVersion, workspace })
      if (disposed || operation !== operationGeneration) return

      if (editRevision === savedRevision) {
        applyLoaded(result)
      } else {
        version.value = result.version
        dirty.value = true
      }
    } catch (cause) {
      if (disposed || operation !== operationGeneration) return
      const problem =
        cause instanceof ApiProblem ? cause : safeProblem('保存失败', 'SAVE_FAILED')
      if (problem.body.status === 409) {
        conflict.value = problem
        error.value = null
      } else {
        error.value = problem
      }
    } finally {
      saving.value = false
      if (!disposed) scheduleAutosave()
    }
  }

  function saveNow(): Promise<void> {
    return attemptSave(true)
  }

  function beforeUnload(event: BeforeUnloadEvent): void {
    if (disposed || !dirty.value) return
    event.preventDefault()
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', beforeUnload)
  }

  function stop(): void {
    if (disposed) return
    disposed = true
    operationGeneration += 1
    clearAutosave()
    stopDraftWatch()
    loading.value = false
    saving.value = false
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', beforeUnload)
    }
  }

  if (getCurrentScope() !== undefined) onScopeDispose(stop)

  return {
    draft,
    version,
    loading,
    saving,
    dirty,
    error,
    conflict,
    reload,
    saveNow,
    stop,
  }
}
