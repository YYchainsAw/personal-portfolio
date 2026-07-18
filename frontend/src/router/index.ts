import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import { isLocale } from '@/types/public'

export function createPublicRouter(history: RouterHistory) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/zh-CN' },
      { path: '/:locale', name: 'home', component: () => import('@/views/HomePageView.vue') },
      { path: '/:locale/projects/:slug', name: 'project', component: () => import('@/views/ProjectPageView.vue') },
      { path: '/:locale/privacy', name: 'privacy', component: () => import('@/views/PrivacyView.vue') },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
    ],
    scrollBehavior(to) {
      if (to.hash) return matchMedia('(prefers-reduced-motion: reduce)').matches
        ? { el: to.hash, top: 24 }
        : { el: to.hash, behavior: 'smooth', top: 24 }
      return { top: 0 }
    },
  })

  router.beforeEach((to) => {
    if ((to.name === 'home' || to.name === 'project' || to.name === 'privacy') && !isLocale(to.params.locale)) {
      return { name: 'not-found', params: { pathMatch: to.path.split('/').filter(Boolean) } }
    }
    if (to.name === 'project' && (typeof to.params.slug !== 'string' || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(to.params.slug))) {
      return { name: 'not-found', params: { pathMatch: to.path.split('/').filter(Boolean) } }
    }
    return true
  })

  return router
}

export default createPublicRouter(createWebHistory(import.meta.env.BASE_URL))
