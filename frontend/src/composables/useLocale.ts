import { computed, watch } from 'vue'
import {
  useRoute,
  useRouter,
  type RouteLocationNormalizedLoaded,
  type RouteLocationRaw,
} from 'vue-router'
import { isLocale, locales, type Locale, type Localized } from '@/types/public'

export { locales, type Locale, type Localized }

export function localeRouteLocation(
  route: Pick<RouteLocationNormalizedLoaded, 'name' | 'params'>,
  locale: Locale,
): RouteLocationRaw {
  if (route.name === 'project' && typeof route.params.slug === 'string') {
    return { name: 'project', params: { locale, slug: route.params.slug } }
  }
  if (route.name === 'privacy') return { name: 'privacy', params: { locale } }
  return { name: 'home', params: { locale } }
}

export function useLocale() {
  const route = useRoute()
  const router = useRouter()
  const locale = computed<Locale>(() => {
    if (isLocale(route.params.locale)) return route.params.locale
    const first = route.path.split('/').filter(Boolean)[0]
    return isLocale(first) ? first : 'zh-CN'
  })

  watch(locale, (value) => {
    document.documentElement.lang = value
  }, { immediate: true })

  return {
    locale,
    setLocale: (value: Locale) => router.push(localeRouteLocation(route, value)),
  }
}
