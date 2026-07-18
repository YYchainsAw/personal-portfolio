<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

const props = defineProps<{
  recoveryCodes: readonly string[]
}>()

const emit = defineEmits<{
  dismiss: []
}>()

const PRINT_MODE_CLASS = 'printing-recovery-codes'
const PRINT_EXCLUDED_CLASS = 'recovery-print-excluded'
const codes = ref<string[]>([])
const savedOffline = ref(false)
const copied = ref(false)
const copyError = ref('')
const heading = ref<HTMLElement | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
let printExcluded: HTMLElement[] = []

function overwriteCodes(): void {
  codes.value.fill('')
  codes.value = []
  copied.value = false
  copyError.value = ''
}

function replaceCodes(next: readonly string[]): void {
  overwriteCodes()
  codes.value = [...next]
  savedOffline.value = false
}

function shouldWarn(): boolean {
  return codes.value.length > 0 && !savedOffline.value
}

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!shouldWarn()) return
  event.preventDefault()
  event.returnValue = ''
}

async function copyAll(): Promise<void> {
  copied.value = false
  copyError.value = ''
  if (codes.value.length === 0) return
  try {
    if (navigator.clipboard?.writeText === undefined) {
      throw new Error('Clipboard is unavailable')
    }
    await navigator.clipboard.writeText(codes.value.join('\n'))
    copied.value = true
  } catch {
    copyError.value = '无法复制，请手动抄写。 / Copy unavailable; save the codes manually.'
  }
}

function cleanupPrintMode(): void {
  document.body.classList.remove(PRINT_MODE_CLASS)
  for (const element of printExcluded) element.classList.remove(PRINT_EXCLUDED_CLASS)
  printExcluded = []
}

function preparePrintMode(): void {
  const target = dialog.value
  if (codes.value.length === 0 || target === null) return
  cleanupPrintMode()
  printExcluded = [...document.body.querySelectorAll<HTMLElement>('*')].filter(
    (element) =>
      element !== target &&
      !element.contains(target) &&
      !target.contains(element),
  )
  for (const element of printExcluded) element.classList.add(PRINT_EXCLUDED_CLASS)
  document.body.classList.add(PRINT_MODE_CLASS)
}

function printCodes(): void {
  if (codes.value.length === 0 || dialog.value === null) return
  preparePrintMode()
  try {
    window.print()
  } finally {
    cleanupPrintMode()
  }
}

function dismiss(): void {
  if (!savedOffline.value || codes.value.length === 0) return
  overwriteCodes()
  emit('dismiss')
}

watch(
  () => props.recoveryCodes,
  async (next) => {
    replaceCodes(next)
    if (next.length === 0) return
    await nextTick()
    heading.value?.focus()
  },
  { immediate: true },
)

onBeforeRouteLeave(() => !shouldWarn())

onMounted(() => {
  const target = dialog.value
  if (target !== null) {
    try {
      if (typeof target.showModal === 'function' && !target.open) target.showModal()
      else target.setAttribute('open', '')
    } catch {
      target.setAttribute('open', '')
    }
  }
  window.addEventListener('beforeunload', warnBeforeUnload)
  window.addEventListener('beforeprint', preparePrintMode)
  window.addEventListener('afterprint', cleanupPrintMode)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', warnBeforeUnload)
  window.removeEventListener('beforeprint', preparePrintMode)
  window.removeEventListener('afterprint', cleanupPrintMode)
  cleanupPrintMode()
  const target = dialog.value
  if (target?.open && typeof target.close === 'function') {
    try {
      target.close()
    } catch {
      target.removeAttribute('open')
    }
  }
  overwriteCodes()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="fixed inset-0 z-50 m-0 h-full max-h-none w-full max-w-none place-items-center overflow-y-auto border-0 bg-slate-950/40 p-4 backdrop-blur-sm open:grid"
    aria-modal="true"
    aria-labelledby="recovery-codes-title"
    data-recovery-codes
    @cancel.prevent
  >
    <div class="w-full max-w-2xl rounded-3xl border border-slate-200 bg-white p-6 shadow-2xl sm:p-8">
      <p class="text-xs font-bold uppercase tracking-[0.2em] text-blue-700">One-time secret</p>
      <h3
        id="recovery-codes-title"
        ref="heading"
        class="mt-2 text-2xl font-semibold tracking-tight text-slate-950"
        tabindex="-1"
      >
        一次性恢复码 / One-time recovery codes
      </h3>
      <p class="mt-3 text-sm leading-6 text-slate-600">
        每个恢复码只能使用一次，关闭后不会再次显示。请立即离线保存；不要放入浏览器存储或聊天工具。
        Each code works once and will never be shown again after dismissal. Save it offline now.
      </p>

      <ol class="mt-5 grid gap-2 rounded-2xl border border-blue-100 bg-blue-50 p-4 font-mono text-sm sm:grid-cols-2" aria-label="一次性恢复码">
        <li
          v-for="(code, index) in codes"
          :key="index"
          class="select-all rounded-lg border border-blue-100 bg-white px-3 py-2 text-slate-950"
        >
          <span class="sr-only">恢复码 {{ index + 1 }}：</span>{{ code }}
        </li>
      </ol>

      <div class="mt-4 flex flex-wrap gap-3">
        <button
          class="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-800 hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
          type="button"
          data-action="copy-codes"
          @click="copyAll"
        >
          复制全部 / Copy all
        </button>
        <button
          class="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-800 hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
          type="button"
          data-action="print-codes"
          @click="printCodes"
        >
          打印 / Print
        </button>
      </div>
      <p class="mt-3 text-xs leading-5 text-slate-500" data-print-privacy-note>
        仅使用可信的本地打印机；系统打印队列或联网打印机可能保留副本。 / Use a trusted local printer; print queues or network printers may retain a copy.
      </p>
      <p v-if="copied" class="mt-3 text-sm text-emerald-700" role="status">已复制。 / Copied.</p>
      <p v-if="copyError" class="mt-3 text-sm text-red-700" role="alert">{{ copyError }}</p>

      <label class="mt-6 flex items-start gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-950">
        <input
          v-model="savedOffline"
          class="mt-1 size-4 accent-blue-700"
          type="checkbox"
          data-field="codes-saved-offline"
        />
        <span>
          我确认已将全部恢复码离线保存。 / I confirm that every code is saved offline.
        </span>
      </label>

      <div class="mt-5 flex justify-end">
        <button
          class="rounded-xl bg-slate-950 px-5 py-2.5 text-sm font-semibold text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-45 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
          type="button"
          data-action="dismiss-codes"
          :disabled="!savedOffline || codes.length === 0"
          @click="dismiss"
        >
          清除并关闭 / Clear &amp; dismiss
        </button>
      </div>
    </div>
  </dialog>
</template>

<style>
@media print {
  body.printing-recovery-codes .recovery-print-excluded {
    display: none !important;
  }

  body.printing-recovery-codes [data-recovery-codes] {
    position: absolute !important;
    inset: 0 !important;
    display: block !important;
    width: 100% !important;
    height: auto !important;
    overflow: visible !important;
    border: 0 !important;
    background: white !important;
    padding: 0 !important;
  }

  body.printing-recovery-codes [data-recovery-codes] button,
  body.printing-recovery-codes [data-recovery-codes] label,
  body.printing-recovery-codes [data-recovery-codes] [role='status'],
  body.printing-recovery-codes [data-recovery-codes] [role='alert'] {
    display: none !important;
  }
}
</style>
