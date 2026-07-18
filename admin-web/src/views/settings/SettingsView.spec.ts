import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

const session = vi.hoisted(() => ({ invalidate: vi.fn() }))

vi.mock('@/stores/sessionInstance', () => ({ sessionStore: session }))

import SettingsView from './SettingsView.vue'

const refreshSessions = vi.fn().mockResolvedValue(undefined)

const SecurityStub = defineComponent({
  emits: ['sessions-changed', 'authentication-required'],
  template: `
    <section data-stub="security">
      <button data-stub-action="sessions-changed" @click="$emit('sessions-changed')">changed</button>
    </section>
  `,
})

const SessionsStub = defineComponent({
  emits: ['current-revoked'],
  setup(_props, { expose }) {
    expose({ refresh: refreshSessions })
    return {}
  },
  template: `
    <section data-stub="sessions">
      <button data-stub-action="current-revoked" @click="$emit('current-revoked')">revoked</button>
    </section>
  `,
})

const AuditStub = defineComponent({ template: '<section data-stub="audit" />' })
const OperationsStub = defineComponent({ template: '<section data-stub="operations" />' })
const Empty = defineComponent({ template: '<div />' })

afterEach(() => {
  session.invalidate.mockClear()
  refreshSessions.mockClear()
})

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/settings', component: Empty },
      { path: '/admin/login', component: Empty },
      { path: '/admin/site', component: Empty },
    ],
  })
  await router.push('/admin/settings')
  await router.isReady()
  const wrapper = mount(SettingsView, {
    global: {
      plugins: [router],
      stubs: {
        SecuritySettings: SecurityStub,
        SessionTable: SessionsStub,
        AuditTable: AuditStub,
        OperationsStatus: OperationsStub,
      },
    },
  })
  return { wrapper, router }
}

describe('SettingsView', () => {
  it('composes all independent panels and links to SITE SEO and resume editors', async () => {
    const { wrapper } = await mountView()

    for (const name of ['security', 'sessions', 'audit', 'operations']) {
      expect(wrapper.find(`[data-stub="${name}"]`).exists()).toBe(true)
    }
    const links = wrapper.findAll('a').map((link) => link.attributes('href'))
    expect(links).toContain('/admin/site#seo')
    expect(links).toContain('/admin/site#resumes')

    await wrapper.get('[data-stub-action="sessions-changed"]').trigger('click')
    expect(refreshSessions).toHaveBeenCalledOnce()
  })

  it('invalidates locally and goes directly to login after current-session revocation', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.get('[data-stub-action="current-revoked"]').trigger('click')
    await flushPromises()

    expect(session.invalidate).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/admin/login')
    expect(refreshSessions).not.toHaveBeenCalled()
  })
})
