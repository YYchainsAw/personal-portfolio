import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AdminAuditItem, AdminAuditPage } from '@/types/settings'

import AuditTable from './AuditTable.vue'

const auditId = (value: number): string =>
  `62000000-0000-4000-8000-${value.toString().padStart(12, '0')}`

function item(value: number, overrides: Partial<AdminAuditItem> = {}): AdminAuditItem {
  return {
    id: auditId(value),
    actorAdminId: null,
    action: 'AUTH_LOGIN_SUCCEEDED',
    targetType: 'ADMIN',
    targetId: null,
    outcome: 'SUCCESS',
    traceId: `trace-${value}`,
    metadata: { method: 'TOTP' },
    timestamp: `2026-07-18T01:0${value}:00Z`,
    ...overrides,
  }
}

function page(items: AdminAuditItem[], nextCursor: string | null = null): AdminAuditPage {
  return { items, nextCursor }
}

const wrappers: VueWrapper[] = []

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('AuditTable', () => {
  it('appends an opaque cursor without duplicates, then resets it when filters change', async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce(page([item(1)], 'opaque_cursor_1'))
      .mockResolvedValueOnce(page([item(1), item(2)]))
      .mockResolvedValueOnce(page([item(3, { action: 'ADMIN_PASSWORD_CHANGED' })]))
    const wrapper = mount(AuditTable, { props: { load } })
    wrappers.push(wrapper)
    await flushPromises()

    expect(load).toHaveBeenNthCalledWith(1, { limit: 50 })
    await wrapper.get('[data-action="load-more-audit"]').trigger('click')
    await flushPromises()
    expect(load).toHaveBeenNthCalledWith(2, { limit: 50, cursor: 'opaque_cursor_1' })
    expect(wrapper.findAll('[data-audit-id]')).toHaveLength(2)

    await wrapper.get('[data-filter="action"]').setValue(' ADMIN_PASSWORD_CHANGED ')
    expect(wrapper.find('[data-action="load-more-audit"]').exists()).toBe(false)
    await wrapper.get('[data-action="apply-audit-filters"]').trigger('submit')
    await flushPromises()

    expect(load).toHaveBeenNthCalledWith(3, {
      action: 'ADMIN_PASSWORD_CHANGED',
      limit: 50,
    })
    expect(wrapper.findAll('[data-audit-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('ADMIN_PASSWORD_CHANGED')
  })

  it('canonicalizes whole-second time filters and exposes no mutation control', async () => {
    const load = vi.fn().mockResolvedValue(page([item(4)]))
    const wrapper = mount(AuditTable, { props: { load } })
    wrappers.push(wrapper)
    await flushPromises()
    load.mockClear()

    await wrapper.get('[data-filter="from"]').setValue('2026-07-18T01:00:00')
    await wrapper.get('[data-filter="to"]').setValue('2026-07-18T02:00:00')
    await wrapper.get('[data-action="apply-audit-filters"]').trigger('submit')
    await flushPromises()

    const query = load.mock.calls[0]?.[0] as { from: string; to: string }
    expect(query.from).toMatch(/Z$/)
    expect(query.to).toMatch(/Z$/)
    expect(query.from).not.toContain('.000Z')
    expect(query.to).not.toContain('.000Z')
    expect(wrapper.find('[data-action*="revoke"]').exists()).toBe(false)
    expect(wrapper.find('[data-action*="delete"]').exists()).toBe(false)
  })

  it('rejects an invalid action filter locally without issuing or retrying a request', async () => {
    const load = vi.fn().mockResolvedValue(page([item(5)]))
    const wrapper = mount(AuditTable, { props: { load } })
    wrappers.push(wrapper)
    await flushPromises()
    load.mockClear()

    await wrapper.get('[data-filter="action"]').setValue('lowercase-action')
    await wrapper.get('[data-action="apply-audit-filters"]').trigger('submit')
    await flushPromises()

    expect(load).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('uppercase letters')
    expect(wrapper.text()).not.toContain('Unable to load audit history')
  })
})
