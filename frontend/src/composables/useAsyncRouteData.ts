import { onScopeDispose, ref, watch, type Ref, type WatchSource } from 'vue'
import { PublicApiProblem } from '@/services/portfolioApi'

export function useAsyncRouteData<T>(key: WatchSource<unknown>, load: (signal: AbortSignal) => Promise<T>) {
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  const value = ref<T | null>(null) as Ref<T | null>
  const problem = ref<PublicApiProblem | null>(null)
  let controller: AbortController | null = null
  let run = 0

  async function execute() {
    controller?.abort()
    controller = new AbortController()
    const current = ++run
    state.value = 'loading'
    value.value = null
    problem.value = null
    try {
      const next = await load(controller.signal)
      if (current !== run) return
      value.value = next
      state.value = 'ready'
    } catch (cause) {
      if (current !== run || (cause instanceof DOMException && cause.name === 'AbortError')) return
      problem.value = cause instanceof PublicApiProblem ? cause : new PublicApiProblem({
        type: 'network_error', title: 'Unable to load', status: 0, code: 'NETWORK_ERROR', traceId: 'client',
      })
      state.value = 'error'
    }
  }

  watch(key, () => void execute(), { immediate: true })
  onScopeDispose(() => { run += 1; controller?.abort() })
  return { state, value, problem, retry: execute }
}
