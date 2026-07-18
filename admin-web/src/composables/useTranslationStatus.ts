import { locales, type Locale, type TranslationStatus } from '@/types/content'

function isComplete(value: unknown): boolean {
  if (typeof value === 'string') return value.trim().length > 0
  return value !== null && value !== undefined
}

export function translationStatus<
  T extends Readonly<Record<Locale, Readonly<Record<string, unknown>>>>,
  K extends keyof T[Locale],
>(
  translations: T & Readonly<Record<string, unknown>>,
  required: readonly K[],
): TranslationStatus {
  const requiredKeys = [...new Set(required)]
  const result = {} as Record<(typeof locales)[number], Readonly<{ complete: number; total: number }>>

  for (const locale of locales) {
    const localized = translations[locale] as Readonly<Record<PropertyKey, unknown>>
    const complete = requiredKeys.reduce(
      (count, key) => count + (isComplete(localized[key]) ? 1 : 0),
      0,
    )
    result[locale] = Object.freeze({ complete, total: requiredKeys.length })
  }

  return Object.freeze(result)
}
