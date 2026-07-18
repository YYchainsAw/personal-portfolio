<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-slate-50 px-6" aria-labelledby="not-found-title">
    <div class="max-w-lg rounded-3xl border border-slate-200 bg-white p-10 text-center shadow-sm">
      <p class="text-sm font-semibold tracking-[0.2em] text-blue-600">404</p>
      <h1 id="not-found-title" class="mt-3 text-3xl font-semibold text-slate-950">页面不存在</h1>
      <p class="mt-3 text-slate-600">这个管理页面不存在，或它已经移动到新的位置。</p>
      <RouterLink
        class="mt-7 inline-flex rounded-full bg-blue-600 px-5 py-3 font-medium text-white hover:bg-blue-700"
        :to="{ name: 'dashboard' }"
      >
        返回仪表盘
      </RouterLink>
    </div>
  </main>
</template>
