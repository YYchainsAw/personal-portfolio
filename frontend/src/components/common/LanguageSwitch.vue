<script setup lang="ts">
import { useLocale } from '@/composables/useLocale'

const { locale, setLocale } = useLocale()
</script>

<template>
  <div class="language-switch" role="group" aria-label="Language / 语言">
    <button
      type="button"
      lang="zh-CN"
      aria-label="中文"
      :aria-pressed="locale === 'zh-CN'"
      @click="setLocale('zh-CN')"
    >
      中
    </button>
    <span aria-hidden="true">/</span>
    <button
      type="button"
      lang="en"
      aria-label="English"
      :aria-pressed="locale === 'en'"
      @click="setLocale('en')"
    >
      EN
    </button>
  </div>
</template>

<style scoped>
.language-switch {
  display: inline-flex;
  align-items: center;
  color: #65717c;
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.08em;
}

button {
  display: grid;
  place-items: center;
  min-width: 2.75rem;
  min-height: 2.75rem;
  padding: 0;
  border: 0;
  color: #7f8b97;
  background: transparent;
  font: inherit;
  transition:
    color 180ms ease,
    background 180ms ease;
}

button:hover,
button:focus-visible {
  color: var(--ink);
}

button[aria-pressed='true'] {
  color: var(--accent);
}

@media (max-width: 760px) {
  .language-switch {
    border: 1px solid var(--line);
  }

  button[aria-pressed='true'] {
    color: var(--accent-contrast);
    background: var(--accent);
  }
}
</style>
