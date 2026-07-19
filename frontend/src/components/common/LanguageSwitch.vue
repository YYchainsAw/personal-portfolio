<script setup lang="ts">
import { useLocale } from '@/composables/useLocale'

const { locale, setLocale } = useLocale()
</script>

<template>
  <div class="language-switch" role="group" aria-label="Language / 语言">
    <button type="button" lang="zh-CN" aria-label="中文" :aria-pressed="locale === 'zh-CN'" @click="setLocale('zh-CN')">中</button>
    <button type="button" lang="en" aria-label="English" :aria-pressed="locale === 'en'" @click="setLocale('en')">EN</button>
  </div>
</template>

<style scoped>
.language-switch { display: inline-flex; gap: .2rem; padding: .2rem; border: 1px solid var(--line); border-radius: 999px; background: #fff; }
button { min-width: 2.4rem; padding: .45rem .65rem; border: 0; border-radius: 999px; color: var(--muted); background: transparent; cursor: pointer; }
button[aria-pressed="true"] { color: #fff; background: var(--accent); }
</style>
