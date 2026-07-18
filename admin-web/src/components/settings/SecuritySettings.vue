<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import { settingsApi } from '@/api/settingsApi'
import { toApiProblem } from '@/api/http'
import { ApiProblem } from '@/types/api'
import type {
  PasswordChangeRequest,
  ReauthenticationRequest,
  RecoveryCodesResponse,
  TotpConfirmRequest,
  TotpEnrollmentResponse,
} from '@/types/settings'

import OneTimeRecoveryCodes from './OneTimeRecoveryCodes.vue'
import TotpEnrollmentPanel from './TotpEnrollmentPanel.vue'

type SecurityMutation = 'password' | 'enrollment' | 'confirm' | 'recovery'
type SensitiveAction = () => void | Promise<void>
type ChangePassword = (body: Readonly<PasswordChangeRequest>) => Promise<void>
type BeginEnrollment = (
  body: Readonly<ReauthenticationRequest>,
) => Promise<TotpEnrollmentResponse>
type ConfirmEnrollment = (
  body: Readonly<TotpConfirmRequest>,
) => Promise<RecoveryCodesResponse>
type RegenerateRecoveryCodes = (
  body: Readonly<ReauthenticationRequest>,
) => Promise<RecoveryCodesResponse>

interface TotpPanelHandle {
  clearSensitive: () => void
  focusCode: () => void
  focusConfirm: () => void
}

const RECOVERY_CONFIRMATION = 'REGENERATE RECOVERY CODES'
const MAX_COOLDOWN_SECONDS = 3_600
const SESSION_NOTICE =
  '安全操作已完成：其他会话已撤销，当前会话保持有效。 / All other sessions were revoked; this session remains active.'

const props = defineProps<{
  changePassword?: ChangePassword
  beginEnrollment?: BeginEnrollment
  confirmEnrollment?: ConfirmEnrollment
  regenerate?: RegenerateRecoveryCodes
  refreshSessions?: SensitiveAction
}>()

const emit = defineEmits<{
  'sessions-changed': []
  'authentication-required': [problem: ApiProblem]
}>()

const currentPassword = ref('')
const currentTotp = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const newTotp = ref('')
const recoveryConfirmation = ref('')
const enrollment = ref<TotpEnrollmentResponse | null>(null)
const recoveryCodes = ref<string[]>([])
const recoveryOrigin = ref<'confirm' | 'regenerate' | null>(null)
const successNotice = ref('')
const sessionRefreshWarning = ref('')
const enrollmentLocalNotice = ref('')
const pendingLeaveNotice = ref('')
const localErrors = reactive<Record<string, string>>({})
const problems = reactive<Record<SecurityMutation, ApiProblem | null>>({
  password: null,
  enrollment: null,
  confirm: null,
  recovery: null,
})
const busy = reactive<Record<SecurityMutation, boolean>>({
  password: false,
  enrollment: false,
  confirm: false,
  recovery: false,
})
const cooldownUntil = reactive<Record<SecurityMutation, number>>({
  password: 0,
  enrollment: 0,
  confirm: 0,
  recovery: 0,
})
const clockNow = ref(Date.now())
const totpPanel = ref<TotpPanelHandle | null>(null)
const currentPasswordInput = ref<HTMLInputElement | null>(null)
const startEnrollmentButton = ref<HTMLButtonElement | null>(null)
const regenerateButton = ref<HTMLButtonElement | null>(null)
let clockTimer: number | undefined
let disposed = false
let mutationGeneration = 0
let authenticationRedirectRequired = false

const anyBusy = computed(() => Object.values(busy).some(Boolean))
const passwordPolicy = computed(() => inspectPassword(newPassword.value))
const recoveryConfirmationReady = computed(
  () => recoveryConfirmation.value === RECOVERY_CONFIRMATION,
)

interface PasswordInspection {
  readonly validUtf16: boolean
  readonly length: boolean
  readonly uppercase: boolean
  readonly lowercase: boolean
  readonly digit: boolean
  readonly punctuationOrSymbol: boolean
  readonly valid: boolean
}

function hasValidUtf16(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index)
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false
      index += 1
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return false
    }
  }
  return true
}

function inspectPassword(value: string): PasswordInspection {
  const validUtf16 = hasValidUtf16(value)
  const codePoints = validUtf16 ? Array.from(value).length : 0
  const result = {
    validUtf16,
    length: codePoints >= 14 && codePoints <= 128,
    uppercase: /\p{Uppercase}/u.test(value),
    lowercase: /\p{Lowercase}/u.test(value),
    digit: /\p{Nd}/u.test(value),
    punctuationOrSymbol: /[\p{P}\p{S}]/u.test(value),
  }
  return { ...result, valid: Object.values(result).every(Boolean) }
}

function secondsRemaining(action: SecurityMutation): number {
  return Math.max(0, Math.ceil((cooldownUntil[action] - clockNow.value) / 1_000))
}

function clearLocalErrors(...names: string[]): void {
  for (const name of names) delete localErrors[name]
}

function clearAllInputSecrets(): void {
  currentPassword.value = ''
  currentTotp.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  newTotp.value = ''
  recoveryConfirmation.value = ''
}

function clearEnrollment(): void {
  totpPanel.value?.clearSensitive()
  newTotp.value = ''
  enrollment.value = null
}

function clearRecoveryCodes(): void {
  recoveryCodes.value.fill('')
  recoveryCodes.value = []
}

function scrubEnrollmentResponse(response: TotpEnrollmentResponse): void {
  try {
    const mutable = response as {
      enrollmentId: string
      provisioningUri: string
      expiresAt: string
    }
    mutable.enrollmentId = ''
    mutable.provisioningUri = ''
    mutable.expiresAt = ''
  } catch {
    // A frozen injected response cannot be overwritten; dropping every reference is the fallback.
  }
}

function scrubRecoveryResponse(response: RecoveryCodesResponse): void {
  try {
    ;(response.recoveryCodes as string[]).fill('')
  } catch {
    // A frozen injected response cannot be overwritten; dropping every reference is the fallback.
  }
}

function setRecoveryCodes(response: RecoveryCodesResponse, origin: 'confirm' | 'regenerate'): void {
  const deliveredCodes = [...response.recoveryCodes]
  scrubRecoveryResponse(response)
  clearRecoveryCodes()
  recoveryCodes.value = deliveredCodes
  recoveryOrigin.value = origin
}

function isCurrentMutation(operation: number): boolean {
  return !disposed && operation === mutationGeneration
}

function mutationBlocked(): boolean {
  if (recoveryCodes.value.length === 0) return false
  pendingLeaveNotice.value =
    '请先离线保存并清除当前一次性恢复码，再执行其他安全操作。 / Save and dismiss the current one-time codes before another security action.'
  return true
}

function fieldError(action: SecurityMutation, ...fields: string[]): string | undefined {
  for (const field of fields) {
    const local = localErrors[`${action}.${field}`]
    if (local !== undefined) return local
    const remote = problems[action]?.body.fieldErrors?.[field]
    if (remote !== undefined) return remote
  }
  return undefined
}

function prepare(action: SecurityMutation): void {
  problems[action] = null
  successNotice.value = ''
  sessionRefreshWarning.value = ''
  enrollmentLocalNotice.value = ''
  pendingLeaveNotice.value = ''
  authenticationRedirectRequired = false
  clearLocalErrors(
    `${action}.currentPassword`,
    `${action}.currentTotp`,
    `${action}.newPassword`,
    `${action}.confirmPassword`,
    `${action}.newTotp`,
    `${action}.confirmation`,
  )
}

function setCooldown(problem: ApiProblem): void {
  const seconds = Math.min(
    MAX_COOLDOWN_SECONDS,
    Math.max(1, problem.body.retryAfterSeconds ?? 60),
  )
  const until = Date.now() + seconds * 1_000
  for (const protectedAction of Object.keys(cooldownUntil) as SecurityMutation[]) {
    cooldownUntil[protectedAction] = until
  }
  clockNow.value = Date.now()
}

function handleProblem(action: SecurityMutation, error: unknown): ApiProblem {
  const problem = toApiProblem(error)
  problems[action] = problem
  if (problem.body.status === 429) setCooldown(problem)
  if (problem.body.status === 401 && problem.body.code === 'AUTHENTICATION_REQUIRED') {
    authenticationRedirectRequired = true
    emit('authentication-required', problem)
  }
  if (problem.body.status === 409) clearEnrollment()
  return problem
}

function announceSuccess(): void {
  successNotice.value = SESSION_NOTICE
  emit('sessions-changed')
  if (props.refreshSessions === undefined) return
  try {
    void Promise.resolve(props.refreshSessions()).catch(() => {
      sessionRefreshWarning.value =
        '安全操作已成功，但会话列表暂时无法刷新。 / Security succeeded, but the session list could not refresh.'
    })
  } catch {
    sessionRefreshWarning.value =
      '安全操作已成功，但会话列表暂时无法刷新。 / Security succeeded, but the session list could not refresh.'
  }
}

function validateReauthentication(action: SecurityMutation): boolean {
  let valid = true
  if (
    currentPassword.value.length === 0 ||
    currentPassword.value.length > 256 ||
    !hasValidUtf16(currentPassword.value)
  ) {
    localErrors[`${action}.currentPassword`] =
      '请输入有效的当前密码。 / Enter a valid current password.'
    valid = false
  }
  if (!/^\d{6}$/.test(currentTotp.value)) {
    localErrors[`${action}.currentTotp`] =
      '请输入当前验证器上的 6 位数字。 / Enter the current six-digit TOTP.'
    valid = false
  }
  return valid
}

function reauthenticationBody(): ReauthenticationRequest {
  return {
    currentPassword: currentPassword.value,
    currentTotp: currentTotp.value,
  }
}

async function submitPassword(): Promise<void> {
  const action: SecurityMutation = 'password'
  if (anyBusy.value || secondsRemaining(action) > 0 || mutationBlocked()) return
  prepare(action)
  let valid = validateReauthentication(action)
  if (!passwordPolicy.value.valid) {
    localErrors['password.newPassword'] =
      '新密码未满足全部本地安全规则。 / The new password does not meet every local rule.'
    valid = false
  }
  if (newPassword.value !== confirmPassword.value) {
    localErrors['password.confirmPassword'] =
      '两次输入的新密码不一致。 / The new passwords do not match.'
    valid = false
  }
  if (!valid) return

  const body: PasswordChangeRequest = {
    currentPassword: currentPassword.value,
    currentTotp: currentTotp.value,
    newPassword: newPassword.value,
  }
  busy[action] = true
  const operation = ++mutationGeneration
  try {
    await (props.changePassword ?? settingsApi.changePassword)(body)
    if (!isCurrentMutation(operation)) return
    clearEnrollment()
    clearAllInputSecrets()
    announceSuccess()
  } catch (error: unknown) {
    if (isCurrentMutation(operation)) handleProblem(action, error)
  } finally {
    if (isCurrentMutation(operation)) {
      clearAllInputSecrets()
      busy[action] = false
      pendingLeaveNotice.value = ''
      await nextTick()
      currentPasswordInput.value?.focus()
    }
  }
}

async function beginEnrollment(): Promise<void> {
  const action: SecurityMutation = 'enrollment'
  if (anyBusy.value || secondsRemaining(action) > 0 || mutationBlocked()) return
  prepare(action)
  clearEnrollment()
  if (!validateReauthentication(action)) return
  const body = reauthenticationBody()
  busy[action] = true
  const operation = ++mutationGeneration
  try {
    const response = await (
      props.beginEnrollment ?? settingsApi.beginTotpEnrollment
    )(body)
    if (!isCurrentMutation(operation)) {
      scrubEnrollmentResponse(response)
      return
    }
    enrollment.value = { ...response }
    scrubEnrollmentResponse(response)
    clearAllInputSecrets()
    announceSuccess()
    await nextTick()
    totpPanel.value?.focusCode()
  } catch (error: unknown) {
    if (isCurrentMutation(operation)) handleProblem(action, error)
  } finally {
    if (isCurrentMutation(operation)) {
      clearAllInputSecrets()
      busy[action] = false
      pendingLeaveNotice.value = ''
    }
  }
}

async function confirmEnrollment(): Promise<void> {
  const action: SecurityMutation = 'confirm'
  if (
    anyBusy.value ||
    secondsRemaining(action) > 0 ||
    enrollment.value === null ||
    mutationBlocked()
  ) {
    return
  }
  prepare(action)
  if (!/^\d{6}$/.test(newTotp.value)) {
    localErrors['confirm.newTotp'] =
      '请输入新验证器上的 6 位数字。 / Enter the new six-digit TOTP.'
    return
  }
  const body: TotpConfirmRequest = {
    enrollmentId: enrollment.value.enrollmentId,
    newTotp: newTotp.value,
  }
  let shouldRefocusEnrollmentStart = false
  busy[action] = true
  const operation = ++mutationGeneration
  try {
    const response = await (
      props.confirmEnrollment ?? settingsApi.confirmTotp
    )(body)
    if (!isCurrentMutation(operation)) {
      scrubRecoveryResponse(response)
      return
    }
    clearEnrollment()
    setRecoveryCodes(response, 'confirm')
    announceSuccess()
  } catch (error: unknown) {
    if (!isCurrentMutation(operation)) return
    const problem = handleProblem(action, error)
    if (problem.body.status === 409) {
      enrollmentLocalNotice.value =
        '绑定材料已失效，请重新输入当前凭据开始。 / Enrollment expired; reauthenticate and start again.'
      shouldRefocusEnrollmentStart = true
    }
  } finally {
    if (isCurrentMutation(operation)) {
      newTotp.value = ''
      busy[action] = false
      pendingLeaveNotice.value = ''
      if (shouldRefocusEnrollmentStart) {
        await nextTick()
        startEnrollmentButton.value?.focus()
      }
    }
  }
}

async function regenerateRecoveryCodes(): Promise<void> {
  const action: SecurityMutation = 'recovery'
  if (anyBusy.value || secondsRemaining(action) > 0 || mutationBlocked()) return
  prepare(action)
  let valid = validateReauthentication(action)
  if (!recoveryConfirmationReady.value) {
    localErrors['recovery.confirmation'] =
      `请输入 ${RECOVERY_CONFIRMATION} 以确认。 / Type ${RECOVERY_CONFIRMATION} to confirm.`
    valid = false
  }
  if (!valid) return
  const body = reauthenticationBody()
  busy[action] = true
  const operation = ++mutationGeneration
  try {
    const response = await (
      props.regenerate ?? settingsApi.regenerateRecoveryCodes
    )(body)
    if (!isCurrentMutation(operation)) {
      scrubRecoveryResponse(response)
      return
    }
    clearEnrollment()
    setRecoveryCodes(response, 'regenerate')
    clearAllInputSecrets()
    announceSuccess()
  } catch (error: unknown) {
    if (isCurrentMutation(operation)) handleProblem(action, error)
  } finally {
    if (isCurrentMutation(operation)) {
      clearAllInputSecrets()
      busy[action] = false
      pendingLeaveNotice.value = ''
    }
  }
}

function enrollmentExpired(): void {
  clearEnrollment()
  enrollmentLocalNotice.value =
    '绑定材料已在 10 分钟后清除。 / Enrollment material was cleared after 10 minutes.'
  void nextTick().then(() => startEnrollmentButton.value?.focus())
}

async function dismissRecoveryCodes(): Promise<void> {
  const origin = recoveryOrigin.value
  clearRecoveryCodes()
  recoveryOrigin.value = null
  await nextTick()
  if (origin === 'regenerate') regenerateButton.value?.focus()
  else startEnrollmentButton.value?.focus()
}

function warnPendingBeforeUnload(event: BeforeUnloadEvent): void {
  if (!anyBusy.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (!anyBusy.value || authenticationRedirectRequired) return true
  pendingLeaveNotice.value =
    '安全操作仍在等待服务器结果。为避免丢失一次性材料，请等待完成后再离开。 / A security action is still pending; wait for its one-time result before leaving.'
  return false
})

onMounted(() => {
  window.addEventListener('beforeunload', warnPendingBeforeUnload)
  clockTimer = window.setInterval(() => {
    clockNow.value = Date.now()
  }, 1_000)
})

onBeforeUnmount(() => {
  disposed = true
  mutationGeneration += 1
  window.removeEventListener('beforeunload', warnPendingBeforeUnload)
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
  clockTimer = undefined
  clearAllInputSecrets()
  clearEnrollment()
  clearRecoveryCodes()
  recoveryOrigin.value = null
  successNotice.value = ''
  pendingLeaveNotice.value = ''
  for (const action of Object.keys(problems) as SecurityMutation[]) {
    problems[action] = null
    cooldownUntil[action] = 0
  }
})
</script>

<template>
  <section class="space-y-6" aria-labelledby="security-settings-title" data-security-settings>
    <header class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
      <p class="text-xs font-bold uppercase tracking-[0.2em] text-blue-700">Administrator security</p>
      <h2 id="security-settings-title" class="mt-2 text-2xl font-semibold tracking-tight text-slate-950">
        管理员安全 / Security
      </h2>
      <p class="mt-3 max-w-3xl text-sm leading-6 text-slate-600">
        所有安全操作都需要当前密码与当前验证器验证码。凭据仅保存在本页内存中，并在每次提交后立即清空。
        Every action requires fresh reauthentication and clears local credential fields after submission.
      </p>

      <div class="mt-6 grid gap-4 sm:grid-cols-2">
        <label class="text-sm font-semibold text-slate-800">
          当前密码 / Current password
          <input
            ref="currentPasswordInput"
            v-model="currentPassword"
            class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"
            type="password"
            autocomplete="current-password"
            maxlength="256"
            data-field="current-password"
            :disabled="anyBusy"
            :aria-invalid="fieldError('password', 'currentPassword') || fieldError('enrollment', 'currentPassword') || fieldError('recovery', 'currentPassword') ? 'true' : undefined"
          />
          <span
            v-if="fieldError('password', 'currentPassword') || fieldError('enrollment', 'currentPassword') || fieldError('recovery', 'currentPassword')"
            class="mt-2 block text-sm text-red-700"
            role="alert"
          >{{ fieldError('password', 'currentPassword') ?? fieldError('enrollment', 'currentPassword') ?? fieldError('recovery', 'currentPassword') }}</span>
        </label>

        <label class="text-sm font-semibold text-slate-800">
          当前 6 位验证码 / Current TOTP
          <input
            v-model="currentTotp"
            class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-mono tracking-[0.28em] focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength="6"
            data-field="current-totp"
            :disabled="anyBusy"
            @input="currentTotp = currentTotp.replace(/\D/g, '').slice(0, 6)"
          />
          <span
            v-if="fieldError('password', 'currentTotp') || fieldError('enrollment', 'currentTotp') || fieldError('recovery', 'currentTotp')"
            class="mt-2 block text-sm text-red-700"
            role="alert"
          >{{ fieldError('password', 'currentTotp') ?? fieldError('enrollment', 'currentTotp') ?? fieldError('recovery', 'currentTotp') }}</span>
        </label>
      </div>
    </header>

    <div v-if="successNotice" class="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm font-medium text-emerald-900" role="status" data-security-success>
      {{ successNotice }}
    </div>
    <p v-if="sessionRefreshWarning" class="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900" role="alert">
      {{ sessionRefreshWarning }}
    </p>
    <p v-if="pendingLeaveNotice" class="rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm font-medium text-amber-950" role="alert" data-pending-security-warning>
      {{ pendingLeaveNotice }}
    </p>

    <article class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8" aria-labelledby="password-settings-title">
      <h3 id="password-settings-title" class="text-xl font-semibold text-slate-950">修改密码 / Change password</h3>
      <p class="mt-2 text-sm leading-6 text-slate-600">密码规则在发送前于本地完整验证，确认字段永远不会发送到服务器。</p>

      <form class="mt-5" @submit.prevent="submitPassword">
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="text-sm font-semibold text-slate-800">
            新密码 / New password
            <input
              v-model="newPassword"
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"
              type="password"
              autocomplete="new-password"
              maxlength="256"
              data-field="new-password"
              :disabled="anyBusy || secondsRemaining('password') > 0"
              :aria-invalid="fieldError('password', 'newPassword', 'password') ? 'true' : undefined"
            />
            <span v-if="fieldError('password', 'newPassword', 'password')" class="mt-2 block text-sm text-red-700" role="alert">{{ fieldError('password', 'newPassword', 'password') }}</span>
          </label>

          <label class="text-sm font-semibold text-slate-800">
            再次输入 / Repeat new password
            <input
              v-model="confirmPassword"
              class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-100"
              type="password"
              autocomplete="new-password"
              maxlength="256"
              data-field="confirm-password"
              :disabled="anyBusy || secondsRemaining('password') > 0"
              :aria-invalid="fieldError('password', 'confirmPassword') ? 'true' : undefined"
            />
            <span v-if="fieldError('password', 'confirmPassword')" class="mt-2 block text-sm text-red-700" role="alert">{{ fieldError('password', 'confirmPassword') }}</span>
          </label>
        </div>

        <ul class="mt-4 grid gap-2 text-xs text-slate-600 sm:grid-cols-2" aria-label="新密码规则">
          <li :class="passwordPolicy.length ? 'text-emerald-700' : ''">14–128 个 Unicode 字符 / code points</li>
          <li :class="passwordPolicy.validUtf16 ? 'text-emerald-700' : ''">合法 UTF-16 / valid UTF-16</li>
          <li :class="passwordPolicy.uppercase ? 'text-emerald-700' : ''">至少一个大写字母 / uppercase</li>
          <li :class="passwordPolicy.lowercase ? 'text-emerald-700' : ''">至少一个小写字母 / lowercase</li>
          <li :class="passwordPolicy.digit ? 'text-emerald-700' : ''">至少一个 Unicode 数字 / Unicode Nd</li>
          <li :class="passwordPolicy.punctuationOrSymbol ? 'text-emerald-700' : ''">至少一个标点或符号 / punctuation or symbol</li>
        </ul>
        <p class="mt-3 text-xs leading-5 text-slate-500">
          本地规则用于即时预检；极少数字符可能因浏览器与 Java 17 的 Unicode 数据版本不同而由服务器最终判定。
          The Java 17 server remains authoritative for rare Unicode-version differences.
        </p>

        <div v-if="problems.password" class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
          <p>{{ problems.password.body.title }}</p>
          <p class="mt-1 text-xs">请求编号 / Trace: {{ problems.password.body.traceId }}</p>
        </div>

        <button
          class="mt-5 rounded-xl bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
          type="submit"
          data-action="change-password"
          :disabled="anyBusy || secondsRemaining('password') > 0"
          @click="submitPassword"
        >
          {{ busy.password ? '正在修改… / Changing…' : secondsRemaining('password') > 0 ? `请等待 ${secondsRemaining('password')}s` : '修改密码 / Change password' }}
        </button>
      </form>
    </article>

    <article class="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8" aria-labelledby="totp-settings-title">
      <h3 id="totp-settings-title" class="text-xl font-semibold text-slate-950">替换验证器 / Replace authenticator</h3>
      <p class="mt-2 text-sm leading-6 text-slate-600">开始后会在本地生成二维码；绑定材料最多保留 10 分钟。</p>

      <div v-if="problems.enrollment || problems.confirm" class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
        <p>{{ (problems.confirm ?? problems.enrollment)?.body.title }}</p>
        <p class="mt-1 text-xs">请求编号 / Trace: {{ (problems.confirm ?? problems.enrollment)?.body.traceId }}</p>
      </div>
      <p v-if="enrollmentLocalNotice" class="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900" role="alert">{{ enrollmentLocalNotice }}</p>
      <span v-if="fieldError('confirm', 'newTotp')" class="mt-3 block text-sm text-red-700" role="alert">{{ fieldError('confirm', 'newTotp') }}</span>

      <button
        ref="startEnrollmentButton"
        class="mt-5 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-900 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50"
        type="button"
        data-action="begin-totp-enrollment"
        :disabled="anyBusy || secondsRemaining('enrollment') > 0"
        @click="beginEnrollment"
      >
        {{ busy.enrollment ? '正在开始… / Starting…' : secondsRemaining('enrollment') > 0 ? `请等待 ${secondsRemaining('enrollment')}s` : '开始替换 / Begin replacement' }}
      </button>

      <TotpEnrollmentPanel
        v-if="enrollment"
        ref="totpPanel"
        v-model="newTotp"
        :enrollment="enrollment"
        :busy="busy.confirm"
        :cooldown-seconds="secondsRemaining('confirm')"
        @confirm="confirmEnrollment"
        @expired="enrollmentExpired"
      />
    </article>

    <article class="rounded-3xl border border-red-200 bg-white p-6 shadow-sm sm:p-8" aria-labelledby="recovery-settings-title">
      <p class="text-xs font-bold uppercase tracking-[0.18em] text-red-700">Destructive action</p>
      <h3 id="recovery-settings-title" class="mt-2 text-xl font-semibold text-slate-950">重生成恢复码 / Regenerate recovery codes</h3>
      <p class="mt-2 text-sm leading-6 text-slate-600">旧恢复码会立即全部失效。输入确认短语后才能继续。</p>

      <label class="mt-5 block text-sm font-semibold text-slate-800">
        输入 <code class="rounded bg-slate-100 px-1.5 py-0.5">{{ RECOVERY_CONFIRMATION }}</code>
        <input
          v-model="recoveryConfirmation"
          class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-mono focus:border-red-600 focus:outline-none focus:ring-2 focus:ring-red-100"
          autocomplete="off"
          data-field="recovery-confirmation"
          :disabled="anyBusy || secondsRemaining('recovery') > 0"
          :aria-invalid="fieldError('recovery', 'confirmation') ? 'true' : undefined"
        />
        <span v-if="fieldError('recovery', 'confirmation')" class="mt-2 block text-sm text-red-700" role="alert">{{ fieldError('recovery', 'confirmation') }}</span>
      </label>

      <div v-if="problems.recovery" class="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
        <p>{{ problems.recovery.body.title }}</p>
        <p class="mt-1 text-xs">请求编号 / Trace: {{ problems.recovery.body.traceId }}</p>
      </div>

      <button
        ref="regenerateButton"
        class="mt-5 rounded-xl bg-red-700 px-4 py-2.5 text-sm font-semibold text-white hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-50"
        type="button"
        data-action="regenerate-recovery"
        :disabled="anyBusy || secondsRemaining('recovery') > 0"
        @click="regenerateRecoveryCodes"
      >
        {{ busy.recovery ? '正在重生成… / Regenerating…' : secondsRemaining('recovery') > 0 ? `请等待 ${secondsRemaining('recovery')}s` : '使旧码失效并重生成 / Invalidate & regenerate' }}
      </button>
    </article>

    <OneTimeRecoveryCodes
      v-if="recoveryCodes.length > 0"
      :recovery-codes="recoveryCodes"
      @dismiss="dismissRecoveryCodes"
    />
  </section>
</template>
