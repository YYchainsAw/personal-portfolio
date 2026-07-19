import { effectScope, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import { useAsyncRouteData } from '@/composables/useAsyncRouteData'

it('loads ready data and retries a safe error', async () => {
  const key = ref('a')
  const load = vi.fn().mockRejectedValueOnce(new Error('synthetic')).mockResolvedValueOnce('ready')
  const scope = effectScope()
  const resource = scope.run(() => useAsyncRouteData(key, load))!
  await flushPromises(); expect(resource.state.value).toBe('error')
  await resource.retry(); expect(resource.state.value).toBe('ready'); expect(resource.value.value).toBe('ready')
  scope.stop()
})

it('aborts a superseded locale load and ignores its late result', async () => {
  const key = ref('zh-CN')
  const signals: AbortSignal[] = []
  let release!: (value: string) => void
  const load = vi.fn((signal: AbortSignal) => { signals.push(signal); return new Promise<string>((resolve) => { release = resolve }) })
  const scope = effectScope(); const resource = scope.run(() => useAsyncRouteData(key, load))!
  await flushPromises(); key.value = 'en'; await flushPromises()
  expect(signals[0]?.aborted).toBe(true)
  release('en-ready'); await flushPromises()
  expect(resource.value.value).toBe('en-ready')
  scope.stop(); expect(signals.at(-1)?.aborted).toBe(true)
})

it('clears the previous route value as soon as a replacement starts loading', async () => {
  const key = ref('a')
  let release!: (value: string) => void
  const load = vi.fn((_: AbortSignal) => key.value === 'a' ? Promise.resolve('first') : new Promise<string>((resolve) => { release = resolve }))
  const scope = effectScope(); const resource = scope.run(() => useAsyncRouteData(key, load))!
  await flushPromises(); expect(resource.value.value).toBe('first')
  key.value = 'b'; await flushPromises()
  expect(resource.state.value).toBe('loading'); expect(resource.value.value).toBeNull()
  release('second'); await flushPromises(); expect(resource.value.value).toBe('second')
  scope.stop()
})
