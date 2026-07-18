import { effectScope, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'

import { useVersionedDraft } from './useVersionedDraft'

interface Workspace {
  title: string
  meta: {
    label: string
  }
}

interface OrderedWorkspace {
  items: Array<{ id: string; sortOrder: number }>
}

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T | PromiseLike<T>) => void
  readonly reject: (cause?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (cause?: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

function workspace(version: number, title: string, label = `${title}-label`) {
  return {
    version,
    value: {
      title,
      meta: { label },
    },
  }
}

const activeStops: Array<() => void> = []

function tracked<T extends { stop(): void }>(model: T): T {
  activeStops.push(() => model.stop())
  return model
}

describe('useVersionedDraft', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    for (const stop of activeStops.splice(0)) stop()
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('loads a detached clone and marks only later deep edits dirty', async () => {
    const gate = deferred<ReturnType<typeof workspace>>()
    const loaded = workspace(7, 'server', 'server-label')
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockReturnValue(gate.promise),
        save: vi.fn(),
      }),
    )

    const reload = model.reload()
    expect(model.loading.value).toBe(true)
    gate.resolve(loaded)
    await reload

    expect(model.loading.value).toBe(false)
    expect(model.version.value).toBe(7)
    expect(model.draft.value).toEqual(loaded.value)
    expect(model.draft.value).not.toBe(loaded.value)
    expect(model.draft.value?.meta).not.toBe(loaded.value.meta)
    expect(model.dirty.value).toBe(false)
    expect(model.error.value).toBeNull()
    expect(model.conflict.value).toBeNull()

    model.draft.value!.meta.label = 'local-label'
    await nextTick()

    expect(model.dirty.value).toBe(true)
    expect(loaded.value.meta.label).toBe('server-label')
  })

  it('autosaves exactly once at 15 seconds with the loaded expected version', async () => {
    const saved = workspace(8, 'new', 'new-label')
    const save = vi.fn().mockResolvedValue(saved)
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(7, 'old', 'old-label')),
        save,
      }),
    )
    await model.reload()
    model.draft.value!.title = 'new'
    model.draft.value!.meta.label = 'new-label'
    const liveDraft = model.draft.value
    await nextTick()

    await vi.advanceTimersByTimeAsync(14_999)
    expect(save).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(save).toHaveBeenCalledOnce()
    expect(save).toHaveBeenCalledWith({
      expectedVersion: 7,
      workspace: { title: 'new', meta: { label: 'new-label' } },
    })
    expect(save.mock.calls[0]?.[0].workspace).not.toBe(liveDraft)
    expect(model.version.value).toBe(8)
    expect(model.draft.value).toEqual(saved.value)
    expect(model.draft.value).not.toBe(saved.value)
    expect(model.dirty.value).toBe(false)

    await vi.advanceTimersByTimeAsync(15_000)
    expect(save).toHaveBeenCalledOnce()
  })

  it('preserves edits made during a save and serializes later saves onto the new version', async () => {
    const first = deferred<ReturnType<typeof workspace>>()
    const save = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(workspace(9, 'third', 'third-label'))
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(7, 'first', 'first-label')),
        save,
      }),
    )
    await model.reload()
    model.draft.value!.title = 'second'
    model.draft.value!.meta.label = 'second-label'
    await nextTick()

    const firstSave = model.saveNow()
    expect(model.saving.value).toBe(true)
    expect(save).toHaveBeenCalledOnce()
    const firstRequest = save.mock.calls[0]?.[0]

    model.draft.value!.title = 'third'
    model.draft.value!.meta.label = 'third-label'
    await nextTick()
    await model.saveNow()
    await vi.advanceTimersByTimeAsync(30_000)

    expect(save).toHaveBeenCalledOnce()
    expect(firstRequest).toEqual({
      expectedVersion: 7,
      workspace: { title: 'second', meta: { label: 'second-label' } },
    })

    first.resolve(workspace(8, 'second', 'second-label'))
    await firstSave

    expect(model.version.value).toBe(8)
    expect(model.draft.value).toEqual({ title: 'third', meta: { label: 'third-label' } })
    expect(model.dirty.value).toBe(true)
    expect(model.saving.value).toBe(false)

    await vi.advanceTimersByTimeAsync(15_000)

    expect(save).toHaveBeenCalledTimes(2)
    expect(save).toHaveBeenLastCalledWith({
      expectedVersion: 8,
      workspace: { title: 'third', meta: { label: 'third-label' } },
    })
    expect(model.version.value).toBe(9)
    expect(model.dirty.value).toBe(false)
  })

  it('deeply unwraps reactive children introduced by an ordered-list reorder', async () => {
    const save = vi.fn().mockResolvedValue({
      version: 2,
      value: {
        items: [
          { id: 'second', sortOrder: 0 },
          { id: 'first', sortOrder: 1 },
        ],
      },
    })
    const model = tracked(
      useVersionedDraft<OrderedWorkspace>({
        load: vi.fn().mockResolvedValue({
          version: 1,
          value: {
            items: [
              { id: 'first', sortOrder: 0 },
              { id: 'second', sortOrder: 1 },
            ],
          },
        }),
        save,
      }),
    )
    await model.reload()

    const reordered = [...model.draft.value!.items].reverse()
    reordered.forEach((item, sortOrder) => {
      item.sortOrder = sortOrder
    })
    model.draft.value!.items = reordered
    await model.saveNow()

    expect(save).toHaveBeenCalledWith({
      expectedVersion: 1,
      workspace: {
        items: [
          { id: 'second', sortOrder: 0 },
          { id: 'first', sortOrder: 1 },
        ],
      },
    })
    expect(model.error.value).toBeNull()
    expect(model.dirty.value).toBe(false)
  })

  it('stops future autosaves and ignores a save response that settles after stop', async () => {
    const gate = deferred<ReturnType<typeof workspace>>()
    const save = vi.fn().mockReturnValue(gate.promise)
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(1, 'server')),
        save,
      }),
    )
    await model.reload()
    model.draft.value!.title = 'local'
    await nextTick()

    const pending = model.saveNow()
    expect(save).toHaveBeenCalledOnce()
    model.stop()
    model.stop()
    gate.resolve(workspace(2, 'saved'))
    await pending

    expect(model.version.value).toBe(1)
    expect(model.draft.value?.title).toBe('local')
    expect(model.dirty.value).toBe(true)
    expect(model.saving.value).toBe(false)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(save).toHaveBeenCalledOnce()
  })

  it('keeps a non-409 problem manually retryable without an automatic mutation retry', async () => {
    const retryable = new ApiProblem({
      type: 'bad_request',
      title: '请求暂时无法保存',
      status: 400,
      code: 'VERSION_CONFLICT',
      traceId: 'not-a-409',
    })
    const save = vi
      .fn()
      .mockRejectedValueOnce(retryable)
      .mockResolvedValueOnce(workspace(2, 'retry', 'retry-label'))
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(1, 'server', 'server-label')),
        save,
      }),
    )
    await model.reload()
    model.draft.value!.title = 'retry'
    model.draft.value!.meta.label = 'retry-label'
    await nextTick()

    await model.saveNow()

    expect(model.error.value).toBe(retryable)
    expect(model.conflict.value).toBeNull()
    expect(model.dirty.value).toBe(true)

    await vi.advanceTimersByTimeAsync(15_000)

    expect(save).toHaveBeenCalledOnce()
    expect(model.error.value).toBe(retryable)

    await model.saveNow()

    expect(save).toHaveBeenCalledTimes(2)
    expect(model.error.value).toBeNull()
    expect(model.version.value).toBe(2)
    expect(model.dirty.value).toBe(false)
  })

  it('retries a local validation failure only after the draft changes again', async () => {
    const validation = new ApiProblem({
      type: 'client_validation',
      title: '请先修正草稿',
      status: 0,
      code: 'CLIENT_VALIDATION_FAILED',
      traceId: 'client',
    })
    const save = vi
      .fn()
      .mockRejectedValueOnce(validation)
      .mockResolvedValueOnce(workspace(2, 'valid', 'valid-label'))
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(1, 'server')),
        save,
        retryValidationAfterEdit: (problem) =>
          problem.body.code === 'CLIENT_VALIDATION_FAILED',
      }),
    )
    await model.reload()
    model.draft.value!.title = 'invalid'
    await nextTick()

    await vi.advanceTimersByTimeAsync(15_000)
    expect(save).toHaveBeenCalledOnce()
    expect(model.error.value).toBeNull()
    expect(model.dirty.value).toBe(true)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(save).toHaveBeenCalledOnce()

    model.draft.value!.title = 'valid'
    model.draft.value!.meta.label = 'valid-label'
    await nextTick()
    await vi.advanceTimersByTimeAsync(15_000)

    expect(save).toHaveBeenCalledTimes(2)
    expect(model.version.value).toBe(2)
    expect(model.dirty.value).toBe(false)
  })

  it('stops only on an exact 409 and resumes after an explicit server reload', async () => {
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '服务器版本已更新',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'conflict-409',
    })
    const load = vi
      .fn()
      .mockResolvedValueOnce(workspace(1, 'initial'))
      .mockResolvedValueOnce(workspace(5, 'server-new'))
    const save = vi
      .fn()
      .mockRejectedValueOnce(conflict)
      .mockResolvedValueOnce(workspace(6, 'after-reload'))
    const model = tracked(useVersionedDraft<Workspace>({ load, save }))
    await model.reload()
    model.draft.value!.title = 'conflicting-local'
    await nextTick()

    await model.saveNow()

    expect(model.conflict.value).toBe(conflict)
    expect(model.error.value).toBeNull()
    expect(model.dirty.value).toBe(true)
    await model.saveNow()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(save).toHaveBeenCalledOnce()

    await model.reload()

    expect(load).toHaveBeenCalledTimes(2)
    expect(model.conflict.value).toBeNull()
    expect(model.version.value).toBe(5)
    expect(model.draft.value?.title).toBe('server-new')
    expect(model.dirty.value).toBe(false)

    model.draft.value!.title = 'after-reload'
    await nextTick()
    await vi.advanceTimersByTimeAsync(15_000)

    expect(save).toHaveBeenCalledTimes(2)
    expect(save).toHaveBeenLastCalledWith({
      expectedVersion: 5,
      workspace: { title: 'after-reload', meta: { label: 'server-new-label' } },
    })
    expect(model.version.value).toBe(6)
    expect(model.dirty.value).toBe(false)
  })

  it('lets the newest concurrent reload win when older data arrives last', async () => {
    const first = deferred<ReturnType<typeof workspace>>()
    const second = deferred<ReturnType<typeof workspace>>()
    const load = vi
      .fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const model = tracked(
      useVersionedDraft<Workspace>({
        load,
        save: vi.fn(),
      }),
    )

    const olderReload = model.reload()
    const newerReload = model.reload()
    expect(load).toHaveBeenCalledTimes(2)

    second.resolve(workspace(9, 'newest'))
    await newerReload
    expect(model.loading.value).toBe(false)
    expect(model.version.value).toBe(9)

    first.resolve(workspace(3, 'stale'))
    await olderReload

    expect(model.version.value).toBe(9)
    expect(model.draft.value?.title).toBe('newest')
    expect(model.dirty.value).toBe(false)
  })

  it('does not let an older save response overwrite an explicit reload', async () => {
    const staleSave = deferred<ReturnType<typeof workspace>>()
    const load = vi
      .fn()
      .mockResolvedValueOnce(workspace(1, 'initial'))
      .mockResolvedValueOnce(workspace(9, 'reloaded'))
    const save = vi.fn().mockReturnValue(staleSave.promise)
    const model = tracked(useVersionedDraft<Workspace>({ load, save }))
    await model.reload()
    model.draft.value!.title = 'pending-save'
    await nextTick()
    const pending = model.saveNow()

    await model.reload()
    expect(model.version.value).toBe(9)
    expect(model.draft.value?.title).toBe('reloaded')
    expect(model.dirty.value).toBe(false)

    staleSave.resolve(workspace(2, 'stale-save'))
    await pending

    expect(model.version.value).toBe(9)
    expect(model.draft.value?.title).toBe('reloaded')
    expect(model.dirty.value).toBe(false)
    expect(model.saving.value).toBe(false)
  })

  it('autosaves edits made after a reload while an invalidated save is still settling', async () => {
    const staleSave = deferred<ReturnType<typeof workspace>>()
    const load = vi
      .fn()
      .mockResolvedValueOnce(workspace(1, 'initial'))
      .mockResolvedValueOnce(workspace(9, 'reloaded'))
    const save = vi
      .fn()
      .mockReturnValueOnce(staleSave.promise)
      .mockResolvedValueOnce(workspace(10, 'edited-after-reload'))
    const model = tracked(useVersionedDraft<Workspace>({ load, save }))
    await model.reload()
    model.draft.value!.title = 'pending-save'
    await nextTick()
    const pending = model.saveNow()

    await model.reload()
    model.draft.value!.title = 'edited-after-reload'
    await nextTick()
    expect(model.dirty.value).toBe(true)

    staleSave.resolve(workspace(2, 'stale-save'))
    await pending
    await vi.advanceTimersByTimeAsync(14_999)
    expect(save).toHaveBeenCalledOnce()

    await vi.advanceTimersByTimeAsync(1)
    expect(save).toHaveBeenCalledTimes(2)
    expect(save).toHaveBeenLastCalledWith({
      expectedVersion: 9,
      workspace: {
        title: 'edited-after-reload',
        meta: { label: 'reloaded-label' },
      },
    })
    expect(model.version.value).toBe(10)
    expect(model.dirty.value).toBe(false)
  })

  it('disposes timers, unload protection, and pending responses with its Vue scope', async () => {
    const staleSave = deferred<ReturnType<typeof workspace>>()
    const save = vi.fn().mockReturnValue(staleSave.promise)
    const scope = effectScope()
    const scoped = scope.run(() =>
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(1, 'server')),
        save,
      }),
    )
    if (scoped === undefined) throw new Error('effect scope did not create the draft model')
    const model = tracked(scoped)
    await model.reload()
    model.draft.value!.title = 'local'
    await nextTick()
    const pending = model.saveNow()

    scope.stop()
    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)

    staleSave.resolve(workspace(2, 'stale-after-unmount'))
    await pending
    await vi.advanceTimersByTimeAsync(30_000)

    expect(save).toHaveBeenCalledOnce()
    expect(model.version.value).toBe(1)
    expect(model.draft.value?.title).toBe('local')
    expect(model.dirty.value).toBe(true)
    expect(model.saving.value).toBe(false)
  })

  it('prevents beforeunload only while a live model has unsaved changes', async () => {
    const model = tracked(
      useVersionedDraft<Workspace>({
        load: vi.fn().mockResolvedValue(workspace(1, 'clean')),
        save: vi.fn().mockResolvedValue(workspace(2, 'saved')),
      }),
    )
    await model.reload()

    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    model.draft.value!.title = 'dirty'
    await nextTick()
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)

    await model.saveNow()
    const saved = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(saved)
    expect(saved.defaultPrevented).toBe(false)

    model.draft.value!.title = 'dirty-again'
    await nextTick()
    model.stop()
    const stopped = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(stopped)
    expect(stopped.defaultPrevented).toBe(false)
  })

  it('sanitizes unknown load and save failures without leaking their details', async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(new Error('postgres /etc/portfolio SECRET_VALUE'))
      .mockResolvedValueOnce(workspace(1, 'server'))
    const save = vi.fn().mockRejectedValue(new Error('filesystem SQL SECRET_VALUE'))
    const model = tracked(useVersionedDraft<Workspace>({ load, save }))

    await model.reload()

    expect(model.error.value?.body).toEqual({
      type: 'client_error',
      title: '加载失败',
      status: 0,
      code: 'LOAD_FAILED',
      traceId: 'client',
    })
    expect(JSON.stringify(model.error.value)).not.toContain('SECRET_VALUE')
    expect(model.loading.value).toBe(false)

    await model.reload()
    model.draft.value!.title = 'local'
    await nextTick()
    await model.saveNow()

    expect(model.error.value?.body).toEqual({
      type: 'client_error',
      title: '保存失败',
      status: 0,
      code: 'SAVE_FAILED',
      traceId: 'client',
    })
    expect(JSON.stringify(model.error.value)).not.toContain('SECRET_VALUE')
    expect(model.conflict.value).toBeNull()
    expect(model.dirty.value).toBe(true)
    expect(model.saving.value).toBe(false)
  })

  it('keeps edit tracking alive when a loaded workspace cannot be cloned', async () => {
    interface UncloneableWorkspace {
      title: string
      invalid?: () => void
    }

    const load = vi
      .fn()
      .mockResolvedValueOnce({ version: 1, value: { title: 'server' } })
      .mockResolvedValueOnce({
        version: 2,
        value: { title: 'uncloneable', invalid: () => undefined },
      })
    const model = tracked(
      useVersionedDraft<UncloneableWorkspace>({ load, save: vi.fn() }),
    )
    await model.reload()
    await model.reload()

    expect(model.error.value?.body.code).toBe('LOAD_FAILED')
    expect(model.draft.value?.title).toBe('server')
    model.draft.value!.title = 'local-after-clone-failure'
    await nextTick()

    expect(model.dirty.value).toBe(true)
    expect(model.draft.value?.title).toBe('local-after-clone-failure')
  })
})
