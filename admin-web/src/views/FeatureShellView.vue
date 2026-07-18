<script setup lang="ts">
defineProps<{
  title: string
  projectId?: string
  aggregateType?: string
  aggregateId?: string
}>()
</script>

<template>
  <section
    class="min-h-screen bg-slate-50 px-6 py-10"
    data-feature-shell
    aria-labelledby="feature-shell-title"
  >
    <div class="mx-auto max-w-6xl rounded-3xl border border-slate-200 bg-white p-8 shadow-sm">
      <p class="text-sm font-semibold tracking-[0.2em] text-blue-600">PORTFOLIO ADMIN</p>
      <h1 id="feature-shell-title" class="mt-3 text-3xl font-semibold text-slate-950">{{ title }}</h1>
      <p class="mt-4 max-w-2xl text-slate-600">
        此功能入口已经接入安全路由，完整工作区将在后续切片中逐步替换。
      </p>
      <dl v-if="projectId || aggregateType || aggregateId" class="mt-6 grid gap-3 text-sm text-slate-600">
        <div v-if="projectId">
          <dt class="font-medium text-slate-900">项目 ID</dt>
          <dd>{{ projectId }}</dd>
        </div>
        <div v-if="aggregateType">
          <dt class="font-medium text-slate-900">聚合类型</dt>
          <dd>{{ aggregateType }}</dd>
        </div>
        <div v-if="aggregateId">
          <dt class="font-medium text-slate-900">聚合 ID</dt>
          <dd>{{ aggregateId }}</dd>
        </div>
      </dl>
    </div>
  </section>
</template>
