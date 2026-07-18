import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { SessionView } from '@/types/settings'

const route = vi.hoisted(() => ({
  leaveGuards: [] as Array<() => boolean>,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    onBeforeRouteLeave: (guard: () => boolean) => route.leaveGuards.push(guard),
  }
})

import SessionTable from './SessionTable.vue'

const currentId = '61000000-0000-4000-8000-000000000001'
const otherId = '61000000-0000-4000-8000-000000000002'
const terminalId = '61000000-0000-4000-8000-000000000003'

function session(overrides: Partial<SessionView> = {}): SessionView {
  return {
    id: otherId,
    status: 'ACTIVE',
    createdAt: '2026-07-18T01:00:00Z',
    endedAt: null,
    lastAccessMillis: Date.parse('2026-07-18T01:05:00Z'),
    clientSummary: 'Chrome · Windows · 203.0.113.0/24',
    reason: null,
    current: false,
    ...overrides,
  }
}

const wrappers: VueWrapper[] = []

function mountTable(props: Record<string, unknown>): VueWrapper {
  const wrapper = mount(SessionTable, { attachTo: document.body, props })
  wrappers.push(wrapper)
  return wrapper
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
  route.leaveGuards.length = 0
  document.body.innerHTML = ''
})

describe('SessionTable', () => {
  it('marks the current session and leaves terminal history read-only', async () => {
    const load = vi.fn().mockResolvedValue([
      session({ id: currentId, current: true }),
      session({
        id: terminalId,
        status: 'REVOKED',
        endedAt: '2026-07-18T01:10:00Z',
        reason: 'ADMIN_REVOKED',
      }),
    ])
    const wrapper = mountTable({ load, revoke: vi.fn() })
    await flushPromises()

    expect(load).toHaveBeenCalledOnce()
    expect(wrapper.get(`[data-session-id="${currentId}"] [data-current-session]`).text()).toContain(
      'Current',
    )
    expect(
      wrapper.find(`[data-session-id="${terminalId}"] [data-action="revoke-session"]`).exists(),
    ).toBe(false)
    expect(wrapper.get(`[data-session-id="${terminalId}"]`).text()).toContain('ADMIN_REVOKED')
  })

  it('confirms another active session, posts its exact id, and reloads history', async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce([session()])
      .mockResolvedValueOnce([
        session({ status: 'REVOKED', endedAt: '2026-07-18T01:10:00Z', reason: 'ADMIN_REVOKED' }),
      ])
    const revoke = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountTable({ load, revoke })
    await flushPromises()

    await wrapper.get('[data-action="revoke-session"][data-current="false"]').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith(otherId)
    expect(load).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-action="revoke-session"]').exists()).toBe(false)
  })

  it('requires the exact strong phrase for current revocation and emits without reloading', async () => {
    const load = vi.fn().mockResolvedValue([session({ id: currentId, current: true })])
    const revoke = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountTable({ load, revoke })
    await flushPromises()

    await wrapper.get('[data-action="revoke-session"][data-current="true"]').trigger('click')
    const confirm = wrapper.get('[data-action="confirm-current-revoke"]')
    expect(confirm.attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-field="current-revoke-confirmation"]').setValue('REVOKE CURRENT SESSION')
    await confirm.trigger('click')
    await flushPromises()

    expect(revoke).toHaveBeenCalledWith(currentId)
    expect(wrapper.emitted('current-revoked')).toHaveLength(1)
    expect(load).toHaveBeenCalledOnce()
  })

  it('blocks leaving while current revocation is pending and completes global logout after unmount', async () => {
    const pending = deferred<void>()
    const load = vi.fn().mockResolvedValue([session({ id: currentId, current: true })])
    const revoke = vi.fn().mockReturnValue(pending.promise)
    const handleCurrentRevoked = vi.fn()
    const wrapper = mountTable({ load, revoke, handleCurrentRevoked })
    await flushPromises()

    await wrapper.get('[data-action="revoke-session"][data-current="true"]').trigger('click')
    await wrapper.get('[data-field="current-revoke-confirmation"]').setValue('REVOKE CURRENT SESSION')
    await wrapper.get('[data-action="confirm-current-revoke"]').trigger('click')
    await flushPromises()

    expect(route.leaveGuards.at(-1)?.()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-current-revoke-pending-warning]').exists()).toBe(true)
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)

    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    pending.resolve()
    await flushPromises()

    expect(handleCurrentRevoked).toHaveBeenCalledOnce()
    expect(load).toHaveBeenCalledOnce()
  })
})
