<script setup lang="ts">
import QRCode from 'qrcode'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  ref,
  watch,
} from 'vue'

import type { TotpEnrollmentResponse } from '@/types/settings'

const TEN_MINUTES_MS = 10 * 60 * 1000

const props = withDefaults(
  defineProps<{
    enrollment: TotpEnrollmentResponse
    modelValue: string
    busy?: boolean
    cooldownSeconds?: number
  }>(),
  { busy: false, cooldownSeconds: 0 },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  confirm: []
  expired: []
}>()

const canvas = ref<HTMLCanvasElement | null>(null)
const confirmButton = ref<HTMLButtonElement | null>(null)
const codeInput = ref<HTMLInputElement | null>(null)
const nowMs = ref(Date.now())
const effectiveExpiryMs = ref(0)
const qrError = ref('')
const uriCopied = ref(false)
const uriCopyError = ref('')
let expiryTimer: number | undefined
let renderGeneration = 0

const remainingSeconds = computed(() =>
  Math.max(0, Math.ceil((effectiveExpiryMs.value - nowMs.value) / 1000)),
)
const remainingLabel = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})

function scrubCanvas(target: HTMLCanvasElement | null): void {
  if (target === null) return
  const context = target.getContext('2d')
  context?.clearRect(0, 0, target.width, target.height)
  target.width = 0
  target.height = 0
}

function clearCanvas(): void {
  renderGeneration += 1
  scrubCanvas(canvas.value)
}

function clearSensitive(): void {
  clearCanvas()
  qrError.value = ''
  uriCopied.value = false
  uriCopyError.value = ''
  emit('update:modelValue', '')
}

function stopExpiryTimer(): void {
  if (expiryTimer !== undefined) window.clearInterval(expiryTimer)
  expiryTimer = undefined
}

function expireIfNeeded(): void {
  nowMs.value = Date.now()
  if (remainingSeconds.value > 0) return
  stopExpiryTimer()
  clearSensitive()
  emit('expired')
}

function startExpiryTimer(): void {
  stopExpiryTimer()
  nowMs.value = Date.now()
  const serverExpiry = Date.parse(props.enrollment.expiresAt)
  effectiveExpiryMs.value = Math.min(serverExpiry, nowMs.value + TEN_MINUTES_MS)
  if (!Number.isFinite(effectiveExpiryMs.value) || effectiveExpiryMs.value <= nowMs.value) {
    effectiveExpiryMs.value = nowMs.value
    expireIfNeeded()
    return
  }
  expiryTimer = window.setInterval(expireIfNeeded, 1_000)
}

async function renderQr(): Promise<void> {
  clearCanvas()
  qrError.value = ''
  const generation = renderGeneration
  const provisioningUri = props.enrollment.provisioningUri
  await nextTick()
  const visible = canvas.value
  if (visible === null || generation !== renderGeneration) return
  const scratch = document.createElement('canvas')
  try {
    await QRCode.toCanvas(scratch, provisioningUri, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 224,
    })
    if (
      generation !== renderGeneration ||
      canvas.value !== visible ||
      props.enrollment.provisioningUri !== provisioningUri
    ) {
      scrubCanvas(scratch)
      return
    }
    visible.width = scratch.width
    visible.height = scratch.height
    const context = visible.getContext('2d')
    if (context === null) throw new Error('Canvas context is unavailable')
    context.clearRect(0, 0, visible.width, visible.height)
    context.drawImage(scratch, 0, 0)
    scrubCanvas(scratch)
  } catch {
    scrubCanvas(scratch)
    if (
      generation !== renderGeneration ||
      canvas.value !== visible ||
      props.enrollment.provisioningUri !== provisioningUri
    ) {
      return
    }
    clearCanvas()
    qrError.value = '二维码生成失败，请重新开始绑定。 / QR rendering failed; start again.'
  }
}

async function copyProvisioningUri(): Promise<void> {
  uriCopied.value = false
  uriCopyError.value = ''
  const provisioningUri = props.enrollment.provisioningUri
  const generation = renderGeneration
  try {
    if (navigator.clipboard?.writeText === undefined) {
      throw new Error('Clipboard is unavailable')
    }
    await navigator.clipboard.writeText(provisioningUri)
    if (
      generation !== renderGeneration ||
      props.enrollment.provisioningUri !== provisioningUri
    ) {
      return
    }
    uriCopied.value = true
  } catch {
    if (
      generation !== renderGeneration ||
      props.enrollment.provisioningUri !== provisioningUri
    ) {
      return
    }
    uriCopyError.value = '无法复制，请使用二维码。 / Copy unavailable; use the QR code.'
  }
}

function updateCode(event: Event): void {
  const value = (event.target as HTMLInputElement).value
  if (/^\d{0,6}$/.test(value)) emit('update:modelValue', value)
}

function confirm(): void {
  if (props.busy || props.cooldownSeconds > 0) return
  emit('confirm')
}

function focusCode(): void {
  codeInput.value?.focus()
}

function focusConfirm(): void {
  confirmButton.value?.focus()
}

watch(
  () => props.enrollment,
  () => {
    clearSensitive()
    startExpiryTimer()
    void renderQr()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  stopExpiryTimer()
  clearSensitive()
  effectiveExpiryMs.value = 0
})

defineExpose({ clearSensitive, focusCode, focusConfirm })
</script>

<template>
  <section class="mt-5 rounded-2xl border border-blue-200 bg-blue-50/70 p-5" data-totp-enrollment>
    <div class="flex flex-col gap-6 lg:flex-row lg:items-start">
      <div class="grid min-h-56 min-w-56 place-items-center overflow-hidden rounded-2xl border border-blue-100 bg-white p-3 shadow-sm">
        <canvas ref="canvas" aria-label="本地生成的身份验证器二维码" data-totp-canvas />
      </div>

      <div class="min-w-0 flex-1">
        <p class="text-xs font-bold uppercase tracking-[0.18em] text-blue-700">Local QR only</p>
        <h4 class="mt-2 text-lg font-semibold text-slate-950">扫描二维码 / Scan with your authenticator</h4>
        <p class="mt-2 text-sm leading-6 text-slate-600">
          二维码仅在本浏览器内生成，不会发送给图片或二维码服务。绑定材料将在
          <strong class="tabular-nums text-slate-950">{{ remainingLabel }}</strong>
          后失效。
        </p>

        <p v-if="qrError" class="mt-3 text-sm text-red-700" role="alert">{{ qrError }}</p>

        <details class="mt-4 rounded-xl border border-slate-200 bg-white p-3">
          <summary class="cursor-pointer text-sm font-semibold text-slate-800">
            无法扫描？显示手动绑定 URI / Manual setup URI
          </summary>
          <p class="mt-3 break-all rounded-lg bg-slate-100 p-3 font-mono text-xs text-slate-800" data-provisioning-uri>
            {{ enrollment.provisioningUri }}
          </p>
          <button
            class="mt-3 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-800 hover:bg-slate-50"
            type="button"
            data-action="copy-provisioning-uri"
            @click="copyProvisioningUri"
          >
            复制 URI / Copy URI
          </button>
          <p v-if="uriCopied" class="mt-2 text-sm text-emerald-700" role="status">已复制。 / Copied.</p>
          <p v-if="uriCopyError" class="mt-2 text-sm text-red-700" role="alert">{{ uriCopyError }}</p>
        </details>

        <form class="mt-5" @submit.prevent="confirm">
          <label class="block text-sm font-semibold text-slate-800">
            新验证器上的 6 位验证码 / New authenticator code
            <input
              ref="codeInput"
              class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 font-mono tracking-[0.28em] focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              data-field="new-totp"
              :value="modelValue"
              :disabled="busy || cooldownSeconds > 0"
              @input="updateCode"
            />
          </label>
          <p class="mt-2 text-xs text-slate-500">输入新验证器当前显示的验证码，不是旧验证码。</p>
          <button
            ref="confirmButton"
            class="mt-4 rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
            type="submit"
            data-action="confirm-totp"
            :disabled="busy || cooldownSeconds > 0"
          >
            {{ busy ? '正在确认… / Confirming…' : cooldownSeconds > 0 ? `请等待 ${cooldownSeconds}s` : '确认并替换 / Confirm & replace' }}
          </button>
        </form>
      </div>
    </div>
  </section>
</template>
