<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiProblem } from '@/api/http'
import { sessionStore } from '@/stores/sessionInstance'
import { ApiProblem, type ApiProblemBody } from '@/types/api'

type Logout = () => void | Promise<void>

const props = withDefaults(
  defineProps<{
    username?: string
    onLogout?: Logout
  }>(),
  {
    username: '',
    onLogout: undefined,
  },
)

const router = useRouter()
const route = useRoute()
const logoutBusy = ref(false)
const logoutCompleted = ref(false)
const problem = ref<Readonly<ApiProblemBody> | null>(null)

const links = [
  { name: 'dashboard', label: '仪表盘' },
  { name: 'site', label: '站点内容' },
  { name: 'projects', label: '项目' },
  { name: 'media', label: '媒体库' },
  { name: 'messages', label: '留言' },
  { name: 'analytics', label: '访问统计' },
  { name: 'settings', label: '设置' },
] as const

const displayUsername = computed(() => {
  const supplied = props.username.trim()
  if (supplied.length > 0) return supplied
  return sessionStore.state.phase === 'AUTHENTICATED'
    ? sessionStore.state.user.username
    : '管理员'
})

const logoutLabel = computed(() => {
  if (logoutBusy.value) return logoutCompleted.value ? '正在返回登录页…' : '正在退出…'
  return logoutCompleted.value ? '返回登录页' : '安全退出'
})

function isNavigationActive(name: (typeof links)[number]['name']): boolean {
  if (route.name === name) return true
  if (name === 'projects' && (route.name === 'project-new' || route.name === 'project-edit')) {
    return true
  }
  if (route.name !== 'publishing-history') return false
  const aggregateType =
    typeof route.params.aggregateType === 'string'
      ? route.params.aggregateType.toUpperCase()
      : ''
  return (
    (name === 'site' && aggregateType === 'SITE') ||
    (name === 'projects' && aggregateType === 'PROJECT')
  )
}

function navigationProblem(): ApiProblem {
  return new ApiProblem({
    type: 'client_navigation_error',
    title: '已安全退出，但暂时无法返回登录页，请重试',
    status: 0,
    code: 'LOGOUT_NAVIGATION_FAILED',
    traceId: 'client',
  })
}

function showProblem(cause: unknown): void {
  problem.value = toApiProblem(cause).body
}

async function performLogout(): Promise<void> {
  if (props.onLogout !== undefined) {
    await props.onLogout()
    return
  }
  await sessionStore.logout()
}

async function logout(): Promise<void> {
  if (logoutBusy.value) return
  logoutBusy.value = true
  problem.value = null

  if (!logoutCompleted.value) {
    try {
      await performLogout()
      logoutCompleted.value = true
    } catch (cause) {
      showProblem(cause)
      logoutBusy.value = false
      return
    }
  }

  try {
    await router.replace({ name: 'login' })
  } catch {
    showProblem(navigationProblem())
  } finally {
    logoutBusy.value = false
  }
}
</script>

<template>
  <div class="min-h-screen bg-slate-50 text-slate-950 lg:grid lg:grid-cols-[17rem_minmax(0,1fr)]">
    <a
      class="fixed left-4 top-3 z-50 -translate-y-20 rounded-lg bg-blue-700 px-4 py-2 text-sm font-semibold text-white shadow-lg transition-transform focus:translate-y-0 motion-reduce:transition-none"
      href="#admin-main"
    >
      跳到主要内容
    </a>

    <aside class="border-b border-slate-200 bg-white px-5 py-6 lg:min-h-screen lg:border-b-0 lg:border-r lg:px-6">
      <div class="flex items-center justify-between gap-4 lg:block">
        <div>
          <p class="text-xs font-semibold tracking-[0.2em] text-blue-600">YI JIAXUAN</p>
          <p class="mt-2 text-lg font-semibold">Portfolio Admin</p>
        </div>
        <div v-if="!logoutCompleted" class="text-right lg:mt-8 lg:text-left">
          <p class="text-xs text-slate-500">当前管理员</p>
          <p class="mt-1 max-w-44 truncate text-sm font-medium text-slate-800" :title="displayUsername">
            {{ displayUsername }}
          </p>
        </div>
        <p v-else class="text-sm font-medium text-emerald-700 lg:mt-8">会话已安全结束</p>
      </div>

      <nav
        v-if="!logoutCompleted"
        aria-label="后台导航"
        class="mt-6 grid grid-cols-2 gap-1 sm:grid-cols-4 lg:grid-cols-1"
      >
        <RouterLink
          v-for="link in links"
          :key="link.name"
          :to="{ name: link.name }"
          class="rounded-xl px-3 py-2.5 text-sm font-medium text-slate-600 transition-colors hover:bg-blue-50 hover:text-blue-800"
          :class="{ 'bg-blue-50 text-blue-800': isNavigationActive(link.name) }"
          :aria-current="isNavigationActive(link.name) ? 'page' : undefined"
        >
          {{ link.label }}
        </RouterLink>
      </nav>

      <div class="mt-6 border-t border-slate-200 pt-5 lg:mt-10">
        <button
          class="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          type="button"
          :disabled="logoutBusy"
          :aria-busy="logoutBusy"
          @click="logout"
        >
          {{ logoutLabel }}
        </button>

        <div
          v-if="problem"
          class="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800"
          role="alert"
        >
          <p>{{ problem.title }}</p>
          <p class="mt-1 text-xs text-red-700">请求编号：{{ problem.traceId }}</p>
        </div>
      </div>
    </aside>

    <main id="admin-main" class="min-w-0 px-5 py-7 sm:px-7 lg:px-10 lg:py-9" tabindex="-1">
      <RouterView v-if="!logoutCompleted" />
      <section
        v-else
        class="mx-auto max-w-xl rounded-3xl border border-slate-200 bg-white p-8 shadow-sm"
        aria-labelledby="signed-out-title"
      >
        <p class="text-sm font-semibold tracking-[0.18em] text-emerald-700">SIGNED OUT</p>
        <h1 id="signed-out-title" class="mt-3 text-3xl font-semibold text-slate-950">已安全退出</h1>
        <p class="mt-3 leading-7 text-slate-600">
          受保护的后台内容已从当前页面移除。若登录页没有自动打开，请使用左侧按钮重试。
        </p>
      </section>
    </main>
  </div>
</template>
