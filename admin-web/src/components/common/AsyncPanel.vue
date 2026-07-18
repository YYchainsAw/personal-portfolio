<script setup lang="ts">
import { computed, ref } from 'vue'

type Retry = () => void | Promise<void>

const props = withDefaults(
  defineProps<{
    loading?: boolean
    errorTitle?: string
    traceId?: string
    empty?: boolean
    onRetry?: Retry
  }>(),
  {
    loading: false,
    errorTitle: undefined,
    traceId: undefined,
    empty: false,
    onRetry: undefined,
  },
)

const retrying = ref(false)
const hasError = computed(
  () => typeof props.errorTitle === 'string' && props.errorTitle.trim().length > 0,
)
const busy = computed(() => props.loading || retrying.value)

async function retry(): Promise<void> {
  if (retrying.value || props.onRetry === undefined) return
  retrying.value = true
  try {
    await props.onRetry()
  } catch {
    // The parent owns the next allowlisted problem state; retain the current safe error.
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <section data-async-panel :aria-busy="busy">
    <div
      v-if="loading"
      class="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-600 shadow-sm"
      role="status"
      aria-live="polite"
    >
      <span class="inline-block size-2 animate-pulse rounded-full bg-blue-600 motion-reduce:animate-none" aria-hidden="true" />
      <span class="ml-2">正在加载…</span>
    </div>

    <div
      v-else-if="hasError"
      class="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm text-red-800"
      role="alert"
    >
      <p class="font-medium">{{ errorTitle }}</p>
      <p v-if="traceId" class="mt-1 text-xs text-red-700">请求编号：{{ traceId }}</p>
      <button
        v-if="onRetry"
        class="mt-4 rounded-lg border border-red-300 bg-white px-3 py-2 font-semibold text-red-800 hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-60"
        type="button"
        :disabled="retrying"
        @click="retry"
      >
        {{ retrying ? '正在重试…' : '重试' }}
      </button>
    </div>

    <div
      v-else-if="empty"
      class="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center text-sm text-slate-500"
    >
      <slot name="empty">暂无数据</slot>
    </div>

    <slot v-else />
  </section>
</template>
