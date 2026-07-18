import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref, type ShallowRef } from 'vue'
import { describe, expect, it, vi } from 'vitest'

import type { MediaAssetView } from '@/types/content'

import {
  useResolvedMedia,
  type MediaResolver,
  type ResolvedMediaState,
} from './useResolvedMedia'

const firstId = '20000000-0000-4000-8000-000000000001'
const secondId = '20000000-0000-4000-8000-000000000002'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function asset(id: string): MediaAssetView {
  return {
    id,
    originalFilename: `${id}.jpg`,
    mimeType: 'image/jpeg',
    byteSize: 10,
    width: 100,
    height: 100,
    sha256: 'a'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      { locale: 'zh-CN', altText: '', caption: '', credit: '', sourceUrl: null },
      { locale: 'en', altText: '', caption: '', credit: '', sourceUrl: null },
    ],
    variants: [],
  }
}

describe('useResolvedMedia', () => {
  it('ignores late responses after ids change and keeps the latest result', async () => {
    const first = deferred<MediaAssetView>()
    const second = deferred<MediaAssetView>()
    const resolver = vi.fn<MediaResolver>((id) =>
      id === firstId ? first.promise : second.promise,
    )
    const ids = ref<readonly string[]>([firstId])

    const Harness = defineComponent({
      setup() {
        const resolved = useResolvedMedia(
          () => ids.value,
          () => resolver,
          (value, requestedId) => value.id === requestedId,
        )
        return { ids, stateFor: resolved.stateFor }
      },
      template: '<p :data-state="stateFor(ids[0]).status"></p>',
    })
    const wrapper = mount(Harness)

    expect(resolver).toHaveBeenCalledWith(firstId)
    ids.value = [secondId]
    await flushPromises()
    expect(resolver).toHaveBeenCalledWith(secondId)

    second.resolve(asset(secondId))
    await flushPromises()
    expect(wrapper.get('p').attributes('data-state')).toBe('ready')

    first.resolve(asset(firstId))
    await flushPromises()
    expect(wrapper.get('p').attributes('data-state')).toBe('ready')
    expect((wrapper.vm as unknown as { stateFor: (id: string) => ResolvedMediaState }).stateFor(secondId)).toMatchObject({
      status: 'ready',
      asset: { id: secondId },
    })
    expect((wrapper.vm as unknown as { stateFor: (id: string) => ResolvedMediaState }).stateFor(firstId).status).toBe('idle')
  })

  it('marks rejection and invalid resolved values as unreadable', async () => {
    const resolver = vi.fn<MediaResolver>()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(asset(secondId))
    const ids = ref<readonly string[]>([firstId])

    let states!: ShallowRef<ReadonlyMap<string, ResolvedMediaState>>
    const Harness = defineComponent({
      setup() {
        const resolved = useResolvedMedia(
          () => ids.value,
          () => resolver,
          () => false,
        )
        states = resolved.states
        return { stateFor: resolved.stateFor }
      },
      template: '<div />',
    })
    mount(Harness)

    await flushPromises()
    expect(states.value.get(firstId)?.status).toBe('error')

    ids.value = [secondId]
    await flushPromises()
    expect(states.value.get(secondId)?.status).toBe('error')
  })

  it('does not publish a pending response after unmount', async () => {
    const gate = deferred<MediaAssetView>()
    const resolver = vi.fn<MediaResolver>().mockReturnValue(gate.promise)
    let states!: ShallowRef<ReadonlyMap<string, ResolvedMediaState>>
    const Harness = defineComponent({
      setup() {
        const resolved = useResolvedMedia(
          () => [firstId],
          () => resolver,
          () => true,
        )
        states = resolved.states
        return {}
      },
      template: '<div />',
    })
    const wrapper = mount(Harness)
    const loadingSnapshot = states.value

    wrapper.unmount()
    gate.resolve(asset(firstId))
    await flushPromises()

    expect(states.value).toBe(loadingSnapshot)
    expect(states.value.get(firstId)?.status).toBe('loading')
  })
})
