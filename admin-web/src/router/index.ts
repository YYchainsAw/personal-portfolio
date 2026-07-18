import { watch, type WatchStopHandle } from 'vue'
import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalizedLoaded,
  type Router,
  type RouterHistory,
} from 'vue-router'

import { sessionStore } from '@/stores/sessionInstance'
import type { SessionPhase } from '@/stores/session'
import { sanitizeAdminRedirect } from './redirect'

const routerBindings = new WeakMap<Router, WatchStopHandle>()

export { sanitizeAdminRedirect } from './redirect'

export interface SessionGuardPort {
  readonly state: {
    readonly phase: SessionPhase
    readonly secondFactorExpiresAt: string | null
  }
  bootstrap(): Promise<SessionPhase>
  invalidate(): void
}

const adminShell = () => import('@/components/layout/AdminShell.vue')
const dashboard = () => import('@/views/DashboardView.vue')
const siteEditor = () => import('@/views/site/SiteEditorView.vue')
const projectList = () => import('@/views/projects/ProjectListView.vue')
const projectEditor = () => import('@/views/projects/ProjectEditorView.vue')
const mediaLibrary = () => import('@/views/media/MediaLibraryView.vue')
const publishingHistory = () => import('@/views/publishing/PublishingHistoryView.vue')
const messages = () => import('@/views/messages/MessagesView.vue')
const analytics = () => import('@/views/analytics/AnalyticsView.vue')
const feature = () => import('@/views/FeatureShellView.vue')

function singleParam(value: string | string[] | undefined): string {
  return typeof value === 'string' ? value : ''
}

function nestedRedirect(route: RouteLocationNormalizedLoaded): string {
  return route.name === 'login' || route.name === 'totp'
    ? sanitizeAdminRedirect(route.query.redirect)
    : sanitizeAdminRedirect(route.fullPath)
}

function hasActiveSecondFactor(session: SessionGuardPort): boolean {
  if (session.state.phase !== 'TOTP_REQUIRED') return false
  const expiry = session.state.secondFactorExpiresAt
  if (typeof expiry !== 'string') return false
  const expiresAt = Date.parse(expiry)
  return Number.isFinite(expiresAt) && expiresAt > Date.now()
}

export function disposeAdminRouter(router: Router): void {
  routerBindings.get(router)?.()
  routerBindings.delete(router)
}

export function createAdminRouter(session: SessionGuardPort, history: RouterHistory): Router {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/admin/login',
        name: 'login',
        component: () => import('@/views/auth/LoginView.vue'),
        meta: { public: true },
      },
      {
        path: '/admin/totp',
        name: 'totp',
        component: () => import('@/views/auth/TotpView.vue'),
        meta: { public: true },
      },
      {
        path: '/admin',
        component: adminShell,
        children: [
          { path: '', redirect: { name: 'dashboard' } },
          {
            path: 'dashboard',
            name: 'dashboard',
            component: dashboard,
          },
          {
            path: 'site',
            name: 'site',
            component: siteEditor,
          },
          {
            path: 'projects',
            name: 'projects',
            component: projectList,
          },
          {
            path: 'projects/new',
            name: 'project-new',
            component: projectEditor,
            props: { mode: 'create' },
          },
          {
            path: 'projects/:projectId',
            name: 'project-edit',
            component: projectEditor,
            props: (route) => ({
              mode: 'edit',
              projectId: singleParam(route.params.projectId),
            }),
          },
          {
            path: 'media',
            name: 'media',
            component: mediaLibrary,
          },
          {
            path: 'publishing/:aggregateType/:aggregateId/history',
            name: 'publishing-history',
            component: publishingHistory,
            props: (route) => ({
              aggregateType: singleParam(route.params.aggregateType),
              aggregateId: singleParam(route.params.aggregateId),
            }),
          },
          {
            path: 'messages',
            name: 'messages',
            component: messages,
          },
          {
            path: 'analytics',
            name: 'analytics',
            component: analytics,
          },
          {
            path: 'settings',
            name: 'settings',
            component: feature,
            props: { title: '设置' },
          },
        ],
      },
      {
        path: '/admin/:pathMatch(.*)*',
        name: 'admin-not-found',
        component: () => import('@/views/NotFoundView.vue'),
      },
    ],
  })

  router.beforeEach(async (to) => {
    if (session.state.phase === 'UNKNOWN') {
      try {
        await session.bootstrap()
      } catch {
        if (to.name === 'login') return true
        return { name: 'login', query: { redirect: nestedRedirect(to) }, replace: true }
      }
    }

    if (session.state.phase === 'AUTHENTICATED') {
      return to.name === 'login' || to.name === 'totp' ? { name: 'dashboard' } : true
    }

    if (session.state.phase === 'TOTP_REQUIRED') {
      const redirect = nestedRedirect(to)
      if (!hasActiveSecondFactor(session)) {
        session.invalidate()
        return { name: 'login', query: { redirect }, replace: true }
      }
      if (to.name === 'totp') return true
      return { name: 'totp', query: { redirect }, replace: true }
    }

    if (to.name === 'login') return true
    if (to.name === 'totp') {
      return {
        name: 'login',
        query: { redirect: sanitizeAdminRedirect(to.query.redirect) },
        replace: true,
      }
    }
    if (to.meta.public === true) return true
    return { name: 'login', query: { redirect: nestedRedirect(to) }, replace: true }
  })

  const stop = watch(
    () => session.state.phase,
    (phase) => {
      if (phase !== 'ANONYMOUS') return
      const current = router.currentRoute.value
      if (current.matched.length === 0 || current.name === 'login') return
      if (current.meta.public === true && current.name !== 'totp') return
      const redirect = nestedRedirect(current)
      void router.replace({ name: 'login', query: { redirect } }).catch(() => undefined)
    },
    { flush: 'sync' },
  )
  routerBindings.set(router, stop)

  return router
}

const router = createAdminRouter(sessionStore, createWebHistory('/'))

if (import.meta.hot) {
  import.meta.hot.dispose(() => disposeAdminRouter(router))
}

export default router
