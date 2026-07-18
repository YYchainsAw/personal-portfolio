<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiProblem } from '@/api/http'
import { sanitizeAdminRedirect } from '@/router/redirect'
import { sessionStore } from '@/stores/sessionInstance'
import { ApiProblem, type ApiProblemBody } from '@/types/api'
import type { SecondFactorMethod } from '@/types/auth'

interface TotpPort {
  readonly state?: {
    readonly phase: string
    readonly secondFactorExpiresAt: string | null
  }
  verifySecondFactor(method: SecondFactorMethod, code: string): Promise<void>
  invalidate(): void
}

type Completion = () => void | Promise<void>

const props = defineProps<{
  session?: TotpPort
  onAuthenticated?: Completion
  onRestart?: Completion
}>()

const needsRouter = props.onAuthenticated === undefined || props.onRestart === undefined
const router = needsRouter ? useRouter() : null
const route = needsRouter ? useRoute() : null
const activeSession = computed(() => props.session ?? sessionStore)
const mode = ref<SecondFactorMethod>('TOTP')
const code = ref('')
const busy = ref(false)
const authenticationCompleted = ref(false)
const retryBlocked = ref(false)
const challengeExpired = ref(false)
const expiryPending = ref(false)
const problem = ref<Readonly<ApiProblemBody> | null>(null)
let retryTimer: ReturnType<typeof setTimeout> | null = null
let expiryTimer: ReturnType<typeof setTimeout> | null = null

const recoveryCode = /^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2}$/

function trimAsciiWhitespace(value: string): string {
  return value.replace(/^[\t-\r ]+|[\t-\r ]+$/g, '')
}

function clientProblem(title: string, problemCode: string): ApiProblem {
  return new ApiProblem({
    type: 'client_validation_error',
    title,
    status: 0,
    code: problemCode,
    traceId: 'client',
  })
}

function scheduleRetry(seconds: number | undefined): void {
  if (retryTimer !== null) clearTimeout(retryTimer)
  retryTimer = null
  retryBlocked.value = false
  if (seconds === undefined) return
  retryBlocked.value = true
  retryTimer = setTimeout(() => {
    retryBlocked.value = false
    retryTimer = null
  }, seconds * 1000)
}

function showProblem(cause: unknown): void {
  const safe = toApiProblem(cause)
  problem.value = safe.body
  scheduleRetry(safe.body.retryAfterSeconds)
}

function showNavigationProblem(): void {
  showProblem(
    clientProblem(
      '验证已完成，但暂时无法进入后台，请重试',
      'AUTHENTICATED_NAVIGATION_FAILED',
    ),
  )
}

function expireChallenge(): void {
  if (authenticationCompleted.value || challengeExpired.value) return
  if (busy.value) {
    expiryPending.value = true
    return
  }
  expiryPending.value = false
  challengeExpired.value = true
  code.value = ''
  showProblem(clientProblem('二次验证已失效，请重新登录', 'SECOND_FACTOR_EXPIRED'))
  activeSession.value.invalidate()
}

function scheduleChallengeExpiry(): void {
  if (expiryTimer !== null) clearTimeout(expiryTimer)
  expiryTimer = null
  if (authenticationCompleted.value) return
  const state = activeSession.value.state
  if (state === undefined || state.phase !== 'TOTP_REQUIRED') return
  const expiry = state.secondFactorExpiresAt
  if (typeof expiry !== 'string') {
    expireChallenge()
    return
  }
  const expiresAt = Date.parse(expiry)
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    expireChallenge()
    return
  }
  challengeExpired.value = false
  expiryTimer = setTimeout(expireChallenge, Math.min(expiresAt - Date.now(), 2_147_483_647))
}

function challengeIsExpired(): boolean {
  if (authenticationCompleted.value) return false
  const state = activeSession.value.state
  if (state === undefined) return false
  if (state.phase !== 'TOTP_REQUIRED') return true
  const expiry = state.secondFactorExpiresAt
  if (typeof expiry !== 'string') return true
  const expiresAt = Date.parse(expiry)
  return !Number.isFinite(expiresAt) || expiresAt <= Date.now()
}

function switchMode(): void {
  if (busy.value || challengeExpired.value || authenticationCompleted.value) return
  mode.value = mode.value === 'TOTP' ? 'RECOVERY_CODE' : 'TOTP'
  code.value = ''
  problem.value = null
}

async function completeAuthentication(): Promise<void> {
  if (props.onAuthenticated !== undefined) {
    await props.onAuthenticated()
    return
  }
  await router?.replace(sanitizeAdminRedirect(route?.query.redirect))
}

async function restartLogin(): Promise<void> {
  if (busy.value || authenticationCompleted.value) return
  code.value = ''
  activeSession.value.invalidate()
  await nextTick()
  if (props.onRestart !== undefined) {
    await props.onRestart()
    return
  }
  await router?.replace({
    name: 'login',
    query: { redirect: sanitizeAdminRedirect(route?.query.redirect) },
  })
}

async function submit(): Promise<void> {
  if (busy.value || retryBlocked.value) return

  if (authenticationCompleted.value) {
    busy.value = true
    problem.value = null
    try {
      await completeAuthentication()
    } catch {
      showNavigationProblem()
    } finally {
      busy.value = false
    }
    return
  }

  if (challengeExpired.value || challengeIsExpired()) {
    expireChallenge()
    return
  }

  const normalized =
    mode.value === 'TOTP'
      ? code.value
      : trimAsciiWhitespace(code.value).toUpperCase()
  if (mode.value === 'TOTP' && !/^[0-9]{6}$/.test(normalized)) {
    showProblem(clientProblem('请输入 6 位数字验证码', 'TOTP_FORMAT_INVALID'))
    return
  }
  if (mode.value === 'RECOVERY_CODE' && !recoveryCode.test(normalized)) {
    showProblem(clientProblem('恢复码格式不正确', 'RECOVERY_CODE_FORMAT_INVALID'))
    return
  }

  busy.value = true
  problem.value = null
  try {
    await activeSession.value.verifySecondFactor(mode.value, normalized)
  } catch (cause) {
    code.value = ''
    busy.value = false
    showProblem(cause)
    if (expiryPending.value || challengeIsExpired()) expireChallenge()
    return
  }

  authenticationCompleted.value = true
  expiryPending.value = false
  challengeExpired.value = false
  if (expiryTimer !== null) clearTimeout(expiryTimer)
  expiryTimer = null
  code.value = ''
  try {
    await nextTick()
    await completeAuthentication()
  } catch {
    showNavigationProblem()
  } finally {
    code.value = ''
    busy.value = false
  }
}

watch(
  () => [activeSession.value.state?.phase, activeSession.value.state?.secondFactorExpiresAt],
  scheduleChallengeExpiry,
  { immediate: true },
)

onBeforeUnmount(() => {
  code.value = ''
  if (retryTimer !== null) clearTimeout(retryTimer)
  if (expiryTimer !== null) clearTimeout(expiryTimer)
})
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-slate-50 px-5 py-10" aria-labelledby="factor-title">
    <section class="w-full max-w-md rounded-3xl border border-slate-200 bg-white p-8 shadow-sm sm:p-10">
      <p class="text-sm font-semibold tracking-[0.2em] text-blue-600">SECURITY CHECK</p>
      <h1 id="factor-title" class="mt-3 text-3xl font-semibold text-slate-950">完成二次验证</h1>
      <p class="mt-3 text-sm leading-6 text-slate-600">
        {{ mode === 'TOTP' ? '输入认证器中的 6 位动态验证码。' : '输入一条尚未使用的恢复码。' }}
      </p>

      <form class="mt-8 space-y-5" :aria-busy="busy" @submit.prevent="submit">
        <fieldset class="space-y-5" :disabled="busy || retryBlocked">
          <legend class="sr-only">验证方式</legend>
          <div>
            <label class="text-sm font-medium text-slate-800" for="second-factor-code">
              {{ mode === 'TOTP' ? '动态验证码' : '恢复码' }}
            </label>
            <input
              id="second-factor-code"
              v-model="code"
              class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-4 py-3 font-mono tracking-wider text-slate-950 shadow-sm"
              name="code"
              type="text"
              :autocomplete="mode === 'TOTP' ? 'one-time-code' : 'off'"
              :inputmode="mode === 'TOTP' ? 'numeric' : 'text'"
              :maxlength="mode === 'TOTP' ? 6 : 32"
              :pattern="mode === 'TOTP' ? '[0-9]{6}' : undefined"
              autocapitalize="characters"
              :spellcheck="false"
              :disabled="challengeExpired || authenticationCompleted"
              required
            />
          </div>
          <button
            class="w-full rounded-xl bg-blue-600 px-4 py-3 font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            type="submit"
            :disabled="busy || retryBlocked || (challengeExpired && !authenticationCompleted)"
          >
            {{
              busy
                ? authenticationCompleted
                  ? '正在进入后台…'
                  : '正在验证…'
                : retryBlocked
                  ? '请稍后重试'
                  : authenticationCompleted
                    ? '进入后台'
                    : '验证并登录'
            }}
          </button>
          <button
            class="w-full rounded-xl border border-slate-300 px-4 py-3 font-medium text-slate-700 hover:bg-slate-50"
            type="button"
            data-recovery-mode
            :disabled="busy || challengeExpired || authenticationCompleted"
            @click="switchMode"
          >
            {{ mode === 'TOTP' ? '改用恢复码' : '改用动态验证码' }}
          </button>
        </fieldset>
      </form>

      <div v-if="problem" class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
        <p data-error-title>{{ problem.title }}</p>
        <p class="mt-1 text-xs text-red-700">请求编号：{{ problem.traceId }}</p>
      </div>

      <button
        v-if="!authenticationCompleted"
        class="mt-6 text-sm font-medium text-blue-700 hover:text-blue-800 disabled:cursor-not-allowed disabled:opacity-60"
        type="button"
        data-restart-login
        :disabled="busy"
        @click="restartLogin"
      >
        返回并重新登录
      </button>
    </section>
  </main>
</template>
