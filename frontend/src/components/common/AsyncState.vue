<script setup lang="ts">
import type { Locale } from '@/types/public'
import { uiCopy } from '@/data/uiCopy'

defineProps<{ locale: Locale; state: 'loading' | 'ready' | 'error'; traceId?: string | null }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <main v-if="state !== 'ready'" id="main-content" class="async-state" tabindex="-1">
    <p v-if="state === 'loading'" role="status">{{ uiCopy[locale].loading }}</p>
    <div v-else role="alert">
      <h1>{{ uiCopy[locale].error }}</h1>
      <p v-if="traceId && traceId !== 'client'">Trace ID: <code>{{ traceId }}</code></p>
      <button type="button" @click="$emit('retry')">{{ uiCopy[locale].retry }}</button>
    </div>
  </main>
  <slot v-else />
</template>

<style scoped>
.async-state { min-height: 70vh; display: grid; place-items: center; padding: 8rem 1.5rem 4rem; text-align: center; }
button { margin-top: 1rem; padding: .8rem 1.2rem; border: 0; border-radius: 999px; color: #fff; background: var(--accent); cursor: pointer; }
</style>
