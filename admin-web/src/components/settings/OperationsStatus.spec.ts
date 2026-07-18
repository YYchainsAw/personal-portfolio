import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { MaintenanceView, OperationsStatus as OperationsStatusDto } from '@/types/settings'

import OperationsStatus from './OperationsStatus.vue'

function maintenance(overrides: Partial<MaintenanceView> = {}): MaintenanceView {
  return {
    type: 'DATABASE_BACKUP',
    status: 'SUCCEEDED',
    startedAt: '2026-07-18T01:00:00Z',
    finishedAt: '2026-07-18T01:05:00Z',
    artifactChecksum: 'a'.repeat(64),
    errorCategory: null,
    ...overrides,
  }
}

describe('OperationsStatus', () => {
  it('renders exactly seven redacted cards and never exposes extra configuration', async () => {
    const response: OperationsStatusDto & { rawSecret: string; objectKey: string } = {
      databaseBackup: maintenance(),
      mediaBackup: null,
      analyticsAggregation: null,
      contactRetention: null,
      mediaCleanup: null,
      deployment: null,
      restoreDrill: null,
      serverTime: '2026-07-18T01:06:00Z',
      rawSecret: 'must-not-render',
      objectKey: 'private/backup/path',
    }
    const wrapper = mount(OperationsStatus, {
      props: { load: vi.fn().mockResolvedValue(response) },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-operation-key]')).toHaveLength(7)
    expect(wrapper.findAll('[data-operation-empty]')).toHaveLength(6)
    expect(wrapper.get('[data-operation-key="databaseBackup"]').text()).toContain(
      'DATABASE_BACKUP',
    )
    expect(wrapper.text()).not.toContain('must-not-render')
    expect(wrapper.text()).not.toContain('private/backup/path')
    expect(wrapper.find('[data-action*="deploy"]').exists()).toBe(false)
    expect(wrapper.find('[data-action*="backup"]').exists()).toBe(false)
    expect(wrapper.find('[data-action*="restore"]').exists()).toBe(false)
  })
})
