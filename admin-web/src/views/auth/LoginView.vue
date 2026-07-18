<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiProblem } from '@/api/http'
import { sanitizeAdminRedirect } from '@/router/redirect'
import { sessionStore } from '@/stores/sessionInstance'
import { ApiProblem, type ApiProblemBody } from '@/types/api'

interface LoginPort {
  readonly state?: {
    readonly phase: string
    readonly secondFactorExpiresAt: string | null
  }
  login(username: string, password: string): Promise<void>
}

type Completion = () => void | Promise<void>

const props = defineProps<{
  session?: LoginPort
  onChallenge?: Completion
}>()

const needsRouter = props.onChallenge === undefined
const router = needsRouter ? useRouter() : null
const route = needsRouter ? useRoute() : null
const activeSession = computed(() => props.session ?? sessionStore)
const username = ref('')
const password = ref('')
const busy = ref(false)
const challengeReady = ref(false)
const retryBlocked = ref(false)
const problem = ref<Readonly<ApiProblemBody> | null>(null)
let retryTimer: ReturnType<typeof setTimeout> | null = null
let challengeTimer: ReturnType<typeof setTimeout> | null = null

function clientProblem(title: string, code: string): ApiProblem {
  return new ApiProblem({
    type: 'client_validation_error',
    title,
    status: 0,
    code,
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
      '密码验证已完成，但暂时无法进入二次验证，请重试',
      'CHALLENGE_NAVIGATION_FAILED',
    ),
  )
}

function resetExpiredChallenge(): void {
  if (!challengeReady.value) return
  challengeReady.value = false
  password.value = ''
  showProblem(clientProblem('二次验证已失效，请重新输入密码', 'SECOND_FACTOR_EXPIRED'))
}

function scheduleChallengeReset(): void {
  if (challengeTimer !== null) clearTimeout(challengeTimer)
  challengeTimer = null
  if (!challengeReady.value) return
  const state = activeSession.value.state
  if (state === undefined) return
  if (state.phase !== 'TOTP_REQUIRED' || typeof state.secondFactorExpiresAt !== 'string') {
    resetExpiredChallenge()
    return
  }
  const expiresAt = Date.parse(state.secondFactorExpiresAt)
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    resetExpiredChallenge()
    return
  }
  challengeTimer = setTimeout(
    resetExpiredChallenge,
    Math.min(expiresAt - Date.now(), 2_147_483_647),
  )
}

async function continueToChallenge(): Promise<void> {
  if (props.onChallenge !== undefined) {
    await props.onChallenge()
    return
  }
  const redirect = sanitizeAdminRedirect(route?.query.redirect)
  await router?.replace({ name: 'totp', query: { redirect } })
}

async function submit(): Promise<void> {
  if (busy.value || retryBlocked.value) return

  if (challengeReady.value) {
    busy.value = true
    problem.value = null
    try {
      await continueToChallenge()
      scheduleChallengeReset()
    } catch {
      if (challengeReady.value) showNavigationProblem()
    } finally {
      busy.value = false
    }
    return
  }

  const normalizedUsername = username.value.trim()
  if (normalizedUsername.length === 0 || password.value.length === 0) {
    showProblem(clientProblem('请输入用户名和密码', 'LOGIN_FIELDS_REQUIRED'))
    return
  }

  busy.value = true
  problem.value = null
  try {
    await activeSession.value.login(normalizedUsername, password.value)
  } catch (cause) {
    showProblem(cause)
    password.value = ''
    busy.value = false
    return
  }

  challengeReady.value = true
  password.value = ''
  scheduleChallengeReset()
  try {
    await nextTick()
    await continueToChallenge()
  } catch {
    showNavigationProblem()
  } finally {
    password.value = ''
    busy.value = false
  }
}

watch(
  () => [
    challengeReady.value,
    activeSession.value.state?.phase,
    activeSession.value.state?.secondFactorExpiresAt,
  ],
  scheduleChallengeReset,
)

onBeforeUnmount(() => {
  password.value = ''
  if (retryTimer !== null) clearTimeout(retryTimer)
  if (challengeTimer !== null) clearTimeout(challengeTimer)
})
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-slate-50 px-5 py-10" aria-labelledby="login-title">
    <section class="w-full max-w-md rounded-3xl border border-slate-200 bg-white p-8 shadow-sm sm:p-10">
      <p class="text-sm font-semibold tracking-[0.2em] text-blue-600">YI JIAXUAN</p>
      <h1 id="login-title" class="mt-3 text-3xl font-semibold text-slate-950">登录管理后台</h1>
      <p class="mt-3 text-sm leading-6 text-slate-600">使用管理员账号登录，下一步还需要动态验证码。</p>

      <form class="mt-8 space-y-5" :aria-busy="busy" @submit.prevent="submit">
        <fieldset class="space-y-5" :disabled="busy || retryBlocked">
          <legend class="sr-only">管理员凭据</legend>
          <div>
            <label class="text-sm font-medium text-slate-800" for="admin-username">用户名</label>
            <input
              id="admin-username"
              v-model="username"
              class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-slate-950 shadow-sm"
              name="username"
              type="text"
              autocomplete="username"
              maxlength="128"
              :disabled="challengeReady"
              required
            />
          </div>
          <div>
            <label class="text-sm font-medium text-slate-800" for="admin-password">密码</label>
            <input
              id="admin-password"
              v-model="password"
              class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-slate-950 shadow-sm"
              name="password"
              type="password"
              autocomplete="current-password"
              maxlength="256"
              :disabled="challengeReady"
              required
            />
          </div>
          <button
            class="w-full rounded-xl bg-blue-600 px-4 py-3 font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            type="submit"
            :disabled="busy || retryBlocked"
          >
            {{
              busy
                ? challengeReady
                  ? '正在进入二次验证…'
                  : '正在验证…'
                : retryBlocked
                  ? '请稍后重试'
                  : challengeReady
                    ? '进入二次验证'
                    : '继续'
            }}
          </button>
        </fieldset>
      </form>

      <div v-if="problem" class="mt-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
        <p data-error-title>{{ problem.title }}</p>
        <p class="mt-1 text-xs text-red-700">请求编号：{{ problem.traceId }}</p>
      </div>
    </section>
  </main>
</template>
