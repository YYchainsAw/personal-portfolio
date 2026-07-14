import { readonly, ref } from 'vue'

export const locales = ['zh-CN', 'en'] as const
export type Locale = (typeof locales)[number]
export type Localized<T> = Record<Locale, T>

const STORAGE_KEY = 'portfolio.locale'
const locale = ref<Locale>('zh-CN')
let initialized = false

const isLocale = (value: unknown): value is Locale =>
  typeof value === 'string' && locales.includes(value as Locale)

const syncLocale = (nextLocale: Locale) => {
  if (typeof document !== 'undefined') document.documentElement.lang = nextLocale

  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(STORAGE_KEY, nextLocale)
    } catch {
      // The site still works when storage is unavailable.
    }
  }
}

export const initializeLocale = () => {
  if (initialized) return

  if (typeof window !== 'undefined') {
    try {
      const savedLocale = window.localStorage.getItem(STORAGE_KEY)
      if (isLocale(savedLocale)) locale.value = savedLocale
    } catch {
      locale.value = 'zh-CN'
    }
  }

  initialized = true
  syncLocale(locale.value)
}

export const useLocale = () => {
  const setLocale = (nextLocale: Locale) => {
    if (locale.value === nextLocale) return
    locale.value = nextLocale
    syncLocale(nextLocale)
  }

  return {
    locale: readonly(locale),
    setLocale,
  }
}
