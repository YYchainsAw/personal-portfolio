<script setup lang="ts">
import type { Locale } from '@/types/public'
import { useAnalyticsConsent } from '@/composables/useAnalyticsConsent'

defineProps<{ locale: Locale }>()
const consent = useAnalyticsConsent()
</script>

<template>
  <aside v-if="consent.promptVisible.value" class="consent" role="region" :aria-label="locale === 'zh-CN' ? '隐私统计选择' : 'Analytics privacy choice'">
    <p>{{ locale === 'zh-CN' ? '是否允许匿名访问统计？标识最长保留 30 天，不收集留言内容。' : 'Allow privacy-preserving visit analytics? Browser identifiers rotate within 30 days and message content is never collected.' }}</p>
    <div><button type="button" @click="consent.accept">{{ locale === 'zh-CN' ? '允许' : 'Accept' }}</button><button type="button" @click="consent.reject">{{ locale === 'zh-CN' ? '拒绝' : 'Reject' }}</button></div>
  </aside>
  <div v-else-if="!consent.suppressedByDnt.value" class="privacy-control">
    <button v-if="consent.choice.value === 'denied'" type="button" @click="consent.accept">{{ locale === 'zh-CN' ? '启用匿名统计' : 'Enable analytics' }}</button>
    <button v-else-if="consent.choice.value === 'granted'" type="button" @click="consent.withdraw">{{ locale === 'zh-CN' ? '撤回统计同意' : 'Withdraw analytics consent' }}</button>
  </div>
</template>

<style scoped>
.consent { position: fixed; z-index: 80; right: 1rem; bottom: 1rem; width: min(calc(100% - 2rem), 420px); padding: 1rem; border: 1px solid var(--line); border-radius: 1rem; background: #fff; box-shadow: 0 18px 50px rgb(36 48 73 / 16%); }
.consent p { margin: 0 0 .8rem; color: var(--muted); font-size: .86rem; }
.consent div { display: flex; gap: .5rem; }
button { padding: .55rem .8rem; border: 1px solid var(--line); border-radius: 999px; background: #fff; cursor: pointer; }
.privacy-control { position: fixed; z-index: 40; right: 1rem; bottom: 1rem; }
</style>
