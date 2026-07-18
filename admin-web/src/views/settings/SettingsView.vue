<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AuditTable from '@/components/settings/AuditTable.vue'
import OperationsStatus from '@/components/settings/OperationsStatus.vue'
import SecuritySettings from '@/components/settings/SecuritySettings.vue'
import SessionTable from '@/components/settings/SessionTable.vue'
import { sessionStore } from '@/stores/sessionInstance'
import type { ApiProblem } from '@/types/api'

interface SessionTableHandle {
  refresh: () => Promise<void>
}

const router = useRouter()
const sessionTable = ref<SessionTableHandle | null>(null)

function refreshSessions(): void {
  void sessionTable.value?.refresh()
}

function returnToLogin(_problem?: ApiProblem): void {
  sessionStore.invalidate()
  void router.replace('/admin/login')
}
</script>

<template>
  <section class="space-y-8" aria-labelledby="settings-title">
    <header class="max-w-3xl">
      <p class="text-sm font-semibold tracking-[0.18em] text-blue-600">ADMIN SETTINGS</p>
      <h1 id="settings-title" class="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">
        设置中心
      </h1>
      <p class="mt-3 text-base leading-7 text-slate-600">
        管理登录安全、活动会话与只读运维记录。内容展示相关设置仍由双语站点编辑器统一维护。
      </p>
      <nav class="mt-5 flex flex-wrap gap-3" aria-label="站点展示设置快捷入口">
        <RouterLink
          class="rounded-xl border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-800 hover:bg-blue-100"
          to="/admin/site#seo"
        >SEO 设置</RouterLink>
        <RouterLink
          class="rounded-xl border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-800 hover:bg-blue-100"
          to="/admin/site#resumes"
        >简历设置</RouterLink>
      </nav>
    </header>

    <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-7">
      <SecuritySettings
        @sessions-changed="refreshSessions"
        @authentication-required="returnToLogin"
      />
    </div>

    <div class="rounded-3xl border border-slate-200 bg-slate-50/60 p-5 sm:p-7">
      <SessionTable
        ref="sessionTable"
        :handle-current-revoked="returnToLogin"
        @current-revoked="returnToLogin"
      />
    </div>

    <div class="rounded-3xl border border-slate-200 bg-slate-50/60 p-5 sm:p-7">
      <AuditTable />
    </div>

    <div class="rounded-3xl border border-slate-200 bg-slate-50/60 p-5 sm:p-7">
      <OperationsStatus />
    </div>
  </section>
</template>
