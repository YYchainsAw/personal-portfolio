<script setup lang="ts">
import type { Locale } from '@/types/public'
import { useAnalyticsConsent } from '@/composables/useAnalyticsConsent'

defineProps<{ locale: Locale }>()
const consent = useAnalyticsConsent()
</script>

<template>
  <aside
    v-if="consent.promptVisible.value"
    class="consent"
    role="region"
    aria-live="polite"
    :aria-label="locale === 'zh-CN' ? '隐私统计选择' : 'Analytics privacy choice'"
  >
    <span class="consent__eyebrow">PRIVACY / ANALYTICS</span>
    <p>
      {{
        locale === 'zh-CN'
          ? '是否允许匿名访问统计？浏览器标识最长保留 30 天，且不会收集留言内容。'
          : 'Allow privacy-preserving visit analytics? Browser identifiers rotate within 30 days and message content is never collected.'
      }}
    </p>
    <div class="consent__actions">
      <button class="consent__accept" type="button" @click="consent.accept">
        {{ locale === 'zh-CN' ? '允许' : 'Accept' }}
      </button>
      <button type="button" @click="consent.reject">
        {{ locale === 'zh-CN' ? '拒绝' : 'Reject' }}
      </button>
    </div>
  </aside>
  <div v-else-if="!consent.suppressedByDnt.value" class="privacy-control">
    <button v-if="consent.choice.value === 'denied'" type="button" @click="consent.accept">
      {{ locale === 'zh-CN' ? '启用匿名统计' : 'Enable analytics' }}
    </button>
    <button
      v-else-if="consent.choice.value === 'granted'"
      type="button"
      @click="consent.withdraw"
    >
      {{ locale === 'zh-CN' ? '撤回统计同意' : 'Withdraw analytics consent' }}
    </button>
  </div>
</template>

<style scoped>
.consent {
  position: fixed;
  z-index: 80;
  bottom: 1rem;
  left: 1rem;
  width: min(calc(100% - 2rem), 27rem);
  padding: 1rem;
  border: 1px solid var(--line-strong);
  border-left: 2px solid var(--accent);
  border-radius: 0.25rem;
  color: var(--ink);
  background: rgb(15 22 29 / 96%);
  box-shadow: 0 1.5rem 4rem rgb(0 0 0 / 38%);
  backdrop-filter: blur(18px);
}

.consent__eyebrow {
  display: block;
  margin-bottom: 0.65rem;
  color: var(--accent);
  font-size: 0.59rem;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.consent p {
  margin: 0 0 0.9rem;
  color: var(--muted-strong);
  font-size: 0.78rem;
  line-height: 1.7;
}

.consent__actions {
  display: flex;
  gap: 0.5rem;
}

button {
  min-height: 2.75rem;
  padding: 0.58rem 0.9rem;
  border: 1px solid var(--line-strong);
  border-radius: 0.2rem;
  color: var(--muted-strong);
  background: transparent;
  font-size: 0.72rem;
  font-weight: 700;
  transition:
    color 180ms ease,
    border-color 180ms ease,
    background 180ms ease;
}

button:hover,
button:focus-visible {
  border-color: var(--accent);
  color: var(--accent);
}

.consent__accept {
  border-color: var(--accent);
  color: var(--accent-contrast);
  background: var(--accent);
}

.consent__accept:hover,
.consent__accept:focus-visible {
  color: var(--accent-contrast);
  background: #83e4ef;
}

.privacy-control {
  position: fixed;
  z-index: 40;
  bottom: 1rem;
  left: 1rem;
}

.privacy-control button {
  border-color: rgb(94 216 231 / 45%);
  color: var(--accent);
  background: rgb(8 13 18 / 88%);
  backdrop-filter: blur(12px);
}

@media (max-width: 520px) {
  .consent {
    right: 0.75rem;
    bottom: 0.75rem;
    left: 0.75rem;
    width: auto;
  }

  .privacy-control {
    bottom: 0.75rem;
    left: 0.75rem;
  }
}
</style>
