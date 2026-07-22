<script setup lang="ts">
import {
  PhArrowClockwise as ArrowClockwise,
  PhCircleNotch as CircleNotch,
  PhWarningCircle as WarningCircle,
} from '@phosphor-icons/vue'
import type { Locale } from '@/types/public'
import { uiCopy } from '@/data/uiCopy'

defineProps<{
  locale: Locale
  state: 'loading' | 'ready' | 'error'
  traceId?: string | null
}>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <main v-if="state !== 'ready'" id="main-content" class="async-state" tabindex="-1">
    <div v-if="state === 'loading'" class="state-panel state-panel--loading" role="status">
      <CircleNotch class="state-icon state-icon--loading" :size="34" weight="regular" aria-hidden="true" />
      <p class="state-kicker">PORTFOLIO / SYNC</p>
      <p class="state-title">{{ uiCopy[locale].loading }}</p>
      <span class="state-progress" aria-hidden="true"><span></span></span>
    </div>

    <div v-else class="state-panel state-panel--error" role="alert">
      <WarningCircle class="state-icon" :size="38" weight="regular" aria-hidden="true" />
      <p class="state-kicker">PORTFOLIO / CONNECTION</p>
      <h1>{{ uiCopy[locale].error }}</h1>
      <p v-if="traceId && traceId !== 'client'" class="trace-id">
        Trace ID: <code>{{ traceId }}</code>
      </p>
      <button type="button" @click="$emit('retry')">
        <span>{{ uiCopy[locale].retry }}</span>
        <ArrowClockwise :size="18" weight="bold" aria-hidden="true" />
      </button>
    </div>
  </main>
  <slot v-else />
</template>

<style scoped>
.async-state {
  display: grid;
  place-items: center;
  min-height: 100svh;
  padding: 2rem var(--page-gutter);
  color: #f2f1ed;
  background: #0b0d0f;
  font-family: Manrope, 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

.state-panel {
  display: grid;
  justify-items: start;
  width: min(100%, 38rem);
  padding: clamp(2rem, 6vw, 4.2rem);
  border: 1px solid rgb(255 255 255 / 15%);
  background: #111417;
}

.state-icon {
  margin-bottom: 2.2rem;
  color: #35d9e7;
}

.state-icon--loading {
  animation: state-spin 1.1s linear infinite;
}

.state-kicker {
  margin: 0 0 0.8rem;
  color: #35d9e7;
  font-size: 0.62rem;
  font-weight: 600;
  letter-spacing: 0.14em;
}

.state-title,
.state-panel h1 {
  margin: 0;
  color: #f2f1ed;
  font-size: clamp(1.6rem, 4vw, 3rem);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.05em;
}

.trace-id {
  margin: 1.5rem 0 0;
  color: #8f989e;
  font-size: 0.7rem;
}

.trace-id code {
  color: #cbd1d4;
  overflow-wrap: anywhere;
}

.state-progress {
  position: relative;
  display: block;
  width: 100%;
  height: 1px;
  margin-top: 2rem;
  overflow: hidden;
  background: rgb(255 255 255 / 13%);
}

.state-progress span {
  position: absolute;
  inset: 0 auto 0 0;
  width: 38%;
  background: #35d9e7;
  animation: state-progress 1.4s ease-in-out infinite;
}

.state-panel button {
  display: inline-flex;
  align-items: center;
  gap: 0.85rem;
  min-height: 3rem;
  margin-top: 2rem;
  padding: 0.7rem 1.05rem;
  border: 1px solid #35d9e7;
  color: #071012;
  background: #35d9e7;
  font: inherit;
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;
  transition: color 180ms ease, background 180ms ease;
}

.state-panel button:hover,
.state-panel button:focus-visible {
  color: #35d9e7;
  background: transparent;
}

@keyframes state-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes state-progress {
  0% {
    transform: translateX(-100%);
  }
  55%,
  100% {
    transform: translateX(265%);
  }
}

@media (max-width: 520px) {
  .async-state {
    padding: 1rem;
  }

  .state-panel {
    min-height: 22rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .state-icon--loading,
  .state-progress span {
    animation: none;
  }

  .state-progress span {
    width: 100%;
    opacity: 0.45;
  }

  .state-panel button {
    transition: none;
  }
}
</style>
