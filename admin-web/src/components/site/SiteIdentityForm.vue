<script setup lang="ts">
import { useId } from 'vue'

const props = withDefaults(
  defineProps<{
    monogram: string
    email: string
    disabled?: boolean
    fieldErrors?: Readonly<Partial<Record<'monogram' | 'email', string>>>
  }>(),
  {
    disabled: false,
    fieldErrors: undefined,
  },
)

const emit = defineEmits<{
  'update:monogram': [value: string]
  'update:email': [value: string]
}>()

const instanceId = useId()
const legendId = `${instanceId}-site-identity-legend`
const monogramId = `${instanceId}-site-monogram`
const emailId = `${instanceId}-site-email`
const monogramErrorId = `${monogramId}-error`
const emailErrorId = `${emailId}-error`

function inputValue(event: Event): string {
  return (event.target as HTMLInputElement).value
}
</script>

<template>
  <fieldset
    class="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6"
    :aria-labelledby="legendId"
  >
    <legend :id="legendId" class="px-1 text-lg font-semibold text-slate-950">
      站点标识
    </legend>
    <p class="mt-1 text-sm leading-6 text-slate-600">
      设置后台与公开页面共用的字母标识和联系邮箱。
    </p>

    <div class="mt-5 grid gap-5 md:grid-cols-2">
      <div>
        <label :for="monogramId" class="block text-sm font-medium text-slate-800">
          字母标识
        </label>
        <input
          :id="monogramId"
          class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500"
          name="monogram"
          type="text"
          :value="monogram"
          :disabled="disabled"
          required
          :aria-invalid="props.fieldErrors?.monogram ? 'true' : undefined"
          :aria-describedby="props.fieldErrors?.monogram ? monogramErrorId : undefined"
          @input="emit('update:monogram', inputValue($event))"
        />
        <p
          v-if="props.fieldErrors?.monogram"
          :id="monogramErrorId"
          class="mt-2 text-sm text-red-700"
          role="alert"
        >
          {{ props.fieldErrors.monogram }}
        </p>
      </div>

      <div>
        <label :for="emailId" class="block text-sm font-medium text-slate-800">
          联系邮箱
        </label>
        <input
          :id="emailId"
          class="mt-2 w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-950 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-500"
          name="email"
          type="email"
          autocomplete="email"
          inputmode="email"
          spellcheck="false"
          :value="email"
          :disabled="disabled"
          required
          :aria-invalid="props.fieldErrors?.email ? 'true' : undefined"
          :aria-describedby="props.fieldErrors?.email ? emailErrorId : undefined"
          @input="emit('update:email', inputValue($event))"
        />
        <p
          v-if="props.fieldErrors?.email"
          :id="emailErrorId"
          class="mt-2 text-sm text-red-700"
          role="alert"
        >
          {{ props.fieldErrors.email }}
        </p>
      </div>
    </div>
  </fieldset>
</template>
