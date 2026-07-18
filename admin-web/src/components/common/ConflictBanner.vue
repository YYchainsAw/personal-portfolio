<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'

import type { ApiProblem } from '@/types/api'

const props = defineProps<{
  problem: ApiProblem
  reloading: boolean
}>()

const emit = defineEmits<{
  reload: []
}>()

const confirming = ref(false)
const reloadRequested = ref(false)
const reloadButton = ref<HTMLButtonElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)
const confirmationGroup = ref<HTMLElement | null>(null)
const alertRegion = ref<HTMLElement | null>(null)
const titleId = `conflict-title-${useId()}`

function keepCurrent(): void {
  confirming.value = false
}

async function requestReload(): Promise<void> {
  if (reloadRequested.value || props.reloading) return
  confirming.value = true
  await nextTick()
  cancelButton.value?.focus()
}

async function cancelReload(): Promise<void> {
  if (reloadRequested.value || props.reloading) return
  confirming.value = false
  await nextTick()
  reloadButton.value?.focus()
}

async function confirmReload(): Promise<void> {
  if (reloadRequested.value || props.reloading) return
  reloadRequested.value = true
  emit('reload')
  await nextTick()
  confirmationGroup.value?.focus()
}

watch(
  () => props.problem,
  async () => {
    const restoreFocus = alertRegion.value?.contains(document.activeElement) ?? false
    reloadRequested.value = false
    confirming.value = false
    await nextTick()
    if (!restoreFocus) return
    if (props.reloading) alertRegion.value?.focus()
    else reloadButton.value?.focus()
  },
)

watch(
  () => props.reloading,
  async (reloading, wasReloading) => {
    if (reloading || !wasReloading) return
    const restoreFocus = alertRegion.value?.contains(document.activeElement) ?? false
    reloadRequested.value = false
    confirming.value = false
    await nextTick()
    if (restoreFocus) reloadButton.value?.focus()
  },
)
</script>

<template>
  <section
    ref="alertRegion"
    class="rounded-2xl border border-amber-300 bg-amber-50 p-5 text-amber-950"
    role="alert"
    :aria-labelledby="titleId"
    tabindex="-1"
  >
    <p class="text-xs font-semibold tracking-[0.16em] text-amber-800">VERSION CONFLICT</p>
    <h2 :id="titleId" class="mt-2 text-lg font-semibold">{{ problem.body.title }}</h2>
    <p class="mt-2 text-sm leading-6 text-amber-900">
      当前页面不会继续自动保存，以免覆盖服务器上的新版本。
    </p>
    <p class="mt-1 text-xs text-amber-800">请求编号：{{ problem.body.traceId }}</p>

    <div v-if="!confirming" class="mt-4 flex flex-wrap gap-3">
      <button
        class="rounded-lg border border-amber-300 bg-white px-3 py-2 text-sm font-semibold hover:bg-amber-100"
        type="button"
        @click="keepCurrent"
      >
        保留当前页面
      </button>
      <button
        ref="reloadButton"
        class="rounded-lg bg-amber-800 px-3 py-2 text-sm font-semibold text-white hover:bg-amber-900"
        type="button"
        :disabled="reloading"
        @click="requestReload"
      >
        重新载入服务器版本
      </button>
    </div>

    <div
      v-else
      ref="confirmationGroup"
      class="mt-4 rounded-xl border border-amber-300 bg-white p-4"
      role="group"
      aria-label="确认重新载入服务器版本"
      :aria-busy="reloadRequested || reloading"
      tabindex="-1"
    >
      <p class="text-sm leading-6">
        重新载入将放弃当前页面尚未保存的修改，并以服务器版本为准。
      </p>
      <div class="mt-3 flex flex-wrap gap-3">
        <button
          ref="cancelButton"
          class="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
          type="button"
          :disabled="reloadRequested || reloading"
          @click="cancelReload"
        >
          取消
        </button>
        <button
          class="rounded-lg bg-amber-800 px-3 py-2 text-sm font-semibold text-white hover:bg-amber-900"
          type="button"
          :disabled="reloadRequested || reloading"
          @click="confirmReload"
        >
          {{ reloadRequested || reloading ? '正在重新载入…' : '确认重新载入' }}
        </button>
      </div>
    </div>
  </section>
</template>
