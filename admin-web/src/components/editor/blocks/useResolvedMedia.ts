import { onBeforeUnmount, shallowRef, watch, type ShallowRef } from 'vue'

import type { MediaAssetView } from '@/types/content'

export type MediaResolver = (id: string) => Promise<MediaAssetView>
export type ResolvedMediaState =
  | { readonly status: 'idle'; readonly asset: null }
  | { readonly status: 'loading'; readonly asset: null }
  | { readonly status: 'ready'; readonly asset: MediaAssetView }
  | { readonly status: 'error'; readonly asset: null }

export interface ResolvedMediaCollection {
  readonly states: ShallowRef<ReadonlyMap<string, ResolvedMediaState>>
  stateFor(id: string | null | undefined): ResolvedMediaState
}

const idleState: ResolvedMediaState = Object.freeze({
  status: 'idle',
  asset: null,
})

function keyFor(id: string): string {
  return id.toLocaleLowerCase()
}

function uniqueIds(ids: readonly (string | null | undefined)[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const id of ids) {
    if (typeof id !== 'string' || id.length === 0) continue
    const key = keyFor(id)
    if (seen.has(key)) continue
    seen.add(key)
    result.push(id)
  }
  return result
}

export function useResolvedMedia(
  getIds: () => readonly (string | null | undefined)[],
  getResolver: () => MediaResolver | undefined,
  accept: (asset: MediaAssetView, requestedId: string) => boolean,
): ResolvedMediaCollection {
  const states = shallowRef<ReadonlyMap<string, ResolvedMediaState>>(new Map())
  let generation = 0
  let active = true

  function publish(
    operation: number,
    id: string,
    state: ResolvedMediaState,
  ): void {
    if (!active || operation !== generation) return
    const key = keyFor(id)
    if (!states.value.has(key)) return
    const next = new Map(states.value)
    next.set(key, state)
    states.value = next
  }

  watch(
    [
      () => uniqueIds(getIds()).map(keyFor).join('|'),
      getResolver,
    ],
    ([_idSignature, resolver]) => {
      const operation = ++generation
      const ids = uniqueIds(getIds())
      const next = new Map<string, ResolvedMediaState>()
      for (const id of ids) {
        next.set(
          keyFor(id),
          resolver === undefined
            ? { status: 'error', asset: null }
            : { status: 'loading', asset: null },
        )
      }
      states.value = next
      if (resolver === undefined) return

      for (const id of ids) {
        void resolver(id)
          .then((asset) => {
            publish(
              operation,
              id,
              accept(asset, id)
                ? { status: 'ready', asset }
                : { status: 'error', asset: null },
            )
          })
          .catch(() => {
            publish(operation, id, { status: 'error', asset: null })
          })
      }
    },
    { immediate: true },
  )

  function stateFor(id: string | null | undefined): ResolvedMediaState {
    if (typeof id !== 'string' || id.length === 0) return idleState
    return states.value.get(keyFor(id)) ?? idleState
  }

  onBeforeUnmount(() => {
    active = false
    generation += 1
  })

  return { states, stateFor }
}
