export const locales = Object.freeze(['zh-CN', 'en'] as const)

export type Locale = (typeof locales)[number]

export type Localized<T> = Record<Locale, T>

export interface SaveWorkspaceRequest<T> {
  readonly expectedVersion: number
  readonly workspace: T
}

export interface TranslationCompletion {
  readonly complete: number
  readonly total: number
}

export type TranslationStatus = Readonly<Record<Locale, Readonly<TranslationCompletion>>>
