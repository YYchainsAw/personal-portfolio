<script setup lang="ts" generic="T extends { readonly id: string; readonly sortOrder: number }">
import { nextTick, ref, useId } from 'vue'

import type { Locale } from '@/types/content'

const props = withDefaults(
  defineProps<{
    items: readonly T[]
    locale: Locale
    listLabel: string
    reorderKey?: string
    itemLabel: (item: T, locale: Locale) => string
    disabled?: boolean
  }>(),
  { disabled: false, reorderKey: undefined },
)

const emit = defineEmits<{
  'update:items': [items: T[]]
}>()

defineSlots<{
  default?(props: { item: T; index: number; locale: Locale }): unknown
  empty?(): unknown
}>()

const instanceId = useId()
const headingId = `${instanceId}-ordered-list-heading`
const announcement = ref('')

function labelFor(item: T): string {
  const label = props.itemLabel(item, props.locale).trim()
  return label.length > 0 ? label : '未命名项目'
}

function canMove(index: number, offset: -1 | 1): boolean {
  const target = index + offset
  return !props.disabled && target >= 0 && target < props.items.length
}

async function move(index: number, offset: -1 | 1, event: MouseEvent): Promise<void> {
  if (!canMove(index, offset)) return

  const target = index + offset
  const reordered = [...props.items]
  const current = reordered[index]
  const adjacent = reordered[target]
  if (current === undefined || adjacent === undefined) return

  reordered[index] = adjacent
  reordered[target] = current
  const normalized = reordered.map(
    (item, sortOrder) => ({ ...item, sortOrder }) as T,
  )

  announcement.value = `${labelFor(current)}已移动到第 ${target + 1} 项，共 ${normalized.length} 项`
  emit('update:items', normalized)

  const trigger = event.currentTarget as HTMLButtonElement | null
  if (trigger === null) return
  await nextTick()
  if (!trigger.isConnected) return
  if (!trigger.disabled) {
    trigger.focus()
    return
  }
  const fallbackDirection = offset === -1 ? 'down' : 'up'
  trigger
    .closest('[data-item-id]')
    ?.querySelector<HTMLButtonElement>(`[data-direction="${fallbackDirection}"]:not(:disabled)`)
    ?.focus()
}
</script>

<template>
  <section :aria-labelledby="headingId">
    <h3 :id="headingId" class="text-base font-semibold text-slate-950">
      {{ listLabel }}
    </h3>

    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>

    <div
      v-if="items.length === 0"
      class="mt-3 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-slate-500"
    >
      <slot name="empty">暂无项目</slot>
    </div>

    <ol v-else class="mt-3 grid list-none gap-3 p-0" :aria-label="listLabel">
      <li
        v-for="(item, index) in items"
        :key="item.id"
        class="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
        :data-item-id="item.id"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="min-w-0">
            <p class="truncate text-sm font-semibold text-slate-900" :lang="locale">{{ labelFor(item) }}</p>
            <p class="mt-0.5 text-xs text-slate-500">
              第 {{ index + 1 }} 项，共 {{ items.length }} 项
            </p>
          </div>

          <div class="flex gap-2" role="group" :aria-label="`${labelFor(item)}排序操作`">
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:border-blue-300 hover:text-blue-800 disabled:cursor-not-allowed disabled:opacity-45"
              type="button"
              :aria-label="`${labelFor(item)}：上移`"
              :disabled="!canMove(index, -1)"
              :data-reorder="reorderKey ?? listLabel"
              :data-index="index"
              data-direction="up"
              @click="move(index, -1, $event)"
            >
              上移
            </button>
            <button
              class="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:border-blue-300 hover:text-blue-800 disabled:cursor-not-allowed disabled:opacity-45"
              type="button"
              :aria-label="`${labelFor(item)}：下移`"
              :disabled="!canMove(index, 1)"
              :data-reorder="reorderKey ?? listLabel"
              :data-index="index"
              data-direction="down"
              @click="move(index, 1, $event)"
            >
              下移
            </button>
          </div>
        </div>

        <div class="mt-4">
          <slot :item="item" :index="index" :locale="locale" />
        </div>
      </li>
    </ol>
  </section>
</template>
