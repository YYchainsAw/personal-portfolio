import { isLocale, type Locale, type PageBootstrap } from '@/types/public'

export type PageDescriptor =
  | { kind: 'home' | 'privacy'; locale: Locale }
  | { kind: 'project'; locale: Locale; slug: string }

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

export function readInitialPayload(doc: Document = document): PageBootstrap | null {
  const node = doc.querySelector<HTMLTemplateElement>('#__PORTFOLIO_DATA__')
  if (!node) return null
  node.remove()

  try {
    const value: unknown = JSON.parse(node.content.textContent?.trim() ?? '')
    if (!isObject(value) || !isLocale(value.locale) || !['home', 'project', 'privacy'].includes(String(value.kind)) || !isObject(value.site)) return null
    if ((value.kind === 'home' || value.kind === 'project') && !Array.isArray(value.catalog)) return null
    if (value.kind === 'project' && (!isObject(value.project) || typeof value.project.slug !== 'string')) return null
    return value as PageBootstrap
  } catch {
    return null
  }
}

export function matchesInitialRoute(payload: PageBootstrap, route: PageDescriptor): boolean {
  if (payload.kind !== route.kind || payload.locale !== route.locale) return false
  return payload.kind !== 'project' || (route.kind === 'project' && payload.project.slug === route.slug)
}
