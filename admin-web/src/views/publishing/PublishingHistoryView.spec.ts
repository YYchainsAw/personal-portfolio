import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import {
  PROJECT_CATALOG_ID,
  SITE_ID,
  type RevisionSummaryDto,
} from '@/types/publishing'

import PublishingHistoryView from './PublishingHistoryView.vue'

const PROJECT_ID = '20000000-0000-4000-8000-000000000001'
const ACTOR_ID = '41000000-0000-4000-8000-000000000001'
const revisions: RevisionSummaryDto[] = [
  {
    id: '31000000-0000-4000-8000-000000000001',
    type: 'PROJECT',
    aggregateId: PROJECT_ID,
    version: 1,
    schemaVersion: 1,
    checksum: 'a'.repeat(64),
    publishedBy: ACTOR_ID,
    publishedAt: '2026-07-14T10:00:00Z',
  },
  {
    id: '31000000-0000-4000-8000-000000000003',
    type: 'PROJECT',
    aggregateId: PROJECT_ID,
    version: 3,
    schemaVersion: 1,
    checksum: 'c'.repeat(64),
    publishedBy: ACTOR_ID,
    publishedAt: '2026-07-16T10:00:00Z',
  },
  {
    id: '31000000-0000-4000-8000-000000000002',
    type: 'PROJECT',
    aggregateId: PROJECT_ID,
    version: 2,
    schemaVersion: 1,
    checksum: 'b'.repeat(64),
    publishedBy: ACTOR_ID,
    publishedAt: '2026-07-15T10:00:00Z',
  },
]

const mounted: VueWrapper[] = []

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

async function mountView(
  props: Partial<InstanceType<typeof PublishingHistoryView>['$props']> = {},
) {
  const RouteStub = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory('/'),
    routes: [
      { path: '/admin/site', name: 'site', component: RouteStub },
      { path: '/admin/projects', name: 'projects', component: RouteStub },
      { path: '/admin/projects/:projectId', name: 'project-edit', component: RouteStub },
    ],
  })
  await router.push('/admin/projects')
  await router.isReady()
  const wrapper = mount(PublishingHistoryView, {
    attachTo: document.body,
    props: {
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_ID,
      loadHistory: vi.fn(async () => revisions),
      restoreRevision: vi.fn(async () => undefined),
      loadWorkspaceVersion: vi.fn(async () => 7),
      navigateAfterRestore: vi.fn(async () => undefined),
      ...props,
    },
    global: { plugins: [router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('PublishingHistoryView', () => {
  it('sorts a defensive copy by descending immutable version', async () => {
    const source = Object.freeze([...revisions])
    const loadHistory = vi.fn(async () => source as RevisionSummaryDto[])
    const { wrapper } = await mountView({ loadHistory })

    expect(wrapper.findAll('[data-revision-id]').map((row) => row.text())).toEqual([
      expect.stringContaining('版本 3'),
      expect.stringContaining('版本 2'),
      expect.stringContaining('版本 1'),
    ])
    expect(source.map((row) => row.version)).toEqual([1, 3, 2])
    expect(wrapper.text()).toContain(revisions[1]!.checksum)
    expect(wrapper.text()).toContain(ACTOR_ID)
  })

  it.each([
    ['UNKNOWN', PROJECT_ID],
    ['project', PROJECT_ID],
    ['PROJECT', 'not-a-uuid'],
    ['PROJECT', SITE_ID],
    ['PROJECT', PROJECT_CATALOG_ID],
    ['SITE', PROJECT_ID],
    ['PROJECT_CATALOG', PROJECT_ID],
  ])('rejects invalid route params without a history request: %s %s', async (aggregateType, aggregateId) => {
    const loadHistory = vi.fn(async () => revisions)
    const { wrapper } = await mountView({ aggregateType, aggregateId, loadHistory })

    expect(loadHistory).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('发布历史地址无效')
  })

  it('clears a pending valid load when route params become invalid', async () => {
    const pending = deferred<RevisionSummaryDto[]>()
    const loadHistory = vi.fn(() => pending.promise)
    const { wrapper } = await mountView({ loadHistory })

    expect(wrapper.get('[data-async-panel]').attributes('aria-busy')).toBe('true')
    await wrapper.setProps({ aggregateType: 'project' })
    await flushPromises()

    expect(wrapper.get('[data-async-panel]').attributes('aria-busy')).toBe('false')
    expect(wrapper.get('[role="alert"]').text()).toContain('发布历史地址无效')

    pending.resolve(revisions)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('发布历史地址无效')
  })

  it('keeps catalog history read-only', async () => {
    const catalogRows = revisions.map((row) => ({
      ...row,
      type: 'PROJECT_CATALOG' as const,
      aggregateId: PROJECT_CATALOG_ID,
    }))
    const { wrapper } = await mountView({
      aggregateType: 'PROJECT_CATALOG',
      aggregateId: PROJECT_CATALOG_ID,
      loadHistory: vi.fn(async () => catalogRows),
    })

    expect(wrapper.text()).toContain('项目目录发布历史')
    expect(wrapper.find('[data-action="choose-restore"]').exists()).toBe(false)
  })

  it('uses two-stage confirmation, current workspace version, 204 restore, verification, and navigation', async () => {
    const restoreRevision = vi.fn(async () => undefined)
    const loadWorkspaceVersion = vi.fn(async () => 7)
    const navigateAfterRestore = vi.fn(async () => undefined)
    const { wrapper } = await mountView({
      restoreRevision,
      loadWorkspaceVersion,
      navigateAfterRestore,
    })

    const choose = wrapper.findAll<HTMLButtonElement>('[data-action="choose-restore"]')[0]!
    await choose.trigger('click')
    await nextTick()
    expect(wrapper.get('[data-restore-confirmation]').text()).toContain('恢复版本 3')
    expect(document.activeElement).toBe(wrapper.get('[data-action="cancel-restore"]').element)
    expect(restoreRevision).not.toHaveBeenCalled()

    await wrapper.get('[data-action="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(loadWorkspaceVersion).toHaveBeenCalledTimes(2)
    expect(restoreRevision).toHaveBeenCalledOnce()
    expect(restoreRevision).toHaveBeenCalledWith(revisions[1]!.id, {
      expectedWorkspaceVersion: 7,
    })
    expect(navigateAfterRestore).toHaveBeenCalledWith('PROJECT', PROJECT_ID)
    expect(wrapper.findAll('[data-revision-id]')).toHaveLength(3)
  })

  it('restores focus when confirmation is canceled', async () => {
    const { wrapper } = await mountView()
    const choose = wrapper.findAll<HTMLButtonElement>('[data-action="choose-restore"]')[1]!
    await choose.trigger('click')
    await wrapper.get('[data-action="cancel-restore"]').trigger('click')
    await nextTick()

    expect(wrapper.find('[data-restore-confirmation]').exists()).toBe(false)
    expect(document.activeElement).toBe(choose.element)
  })

  it('never repeats a successful restore when post-restore reload first fails', async () => {
    const restoreRevision = vi.fn(async () => undefined)
    const loadWorkspaceVersion = vi
      .fn()
      .mockResolvedValueOnce(9)
      .mockRejectedValueOnce(new Error('private backend detail'))
      .mockResolvedValueOnce(10)
    const navigateAfterRestore = vi.fn(async () => undefined)
    const { wrapper } = await mountView({
      restoreRevision,
      loadWorkspaceVersion,
      navigateAfterRestore,
    })

    await wrapper.findAll('[data-action="choose-restore"]')[0]!.trigger('click')
    await wrapper.get('[data-action="confirm-restore"]').trigger('click')
    await flushPromises()

    const problem = wrapper.get('[data-restore-problem]')
    expect(problem.text()).toContain('已经恢复')
    expect(problem.text()).not.toContain('private backend detail')
    expect(restoreRevision).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(
      wrapper.get('[data-action="retry-restore-refresh"]').element,
    )

    await wrapper.get('[data-action="retry-restore-refresh"]').trigger('click')
    await flushPromises()

    expect(restoreRevision).toHaveBeenCalledOnce()
    expect(loadWorkspaceVersion).toHaveBeenCalledTimes(3)
    expect(navigateAfterRestore).toHaveBeenCalledOnce()
  })

  it('locks an uncertain restore outcome to GET-only reconciliation without repeating POST', async () => {
    const restoreRevision = vi.fn().mockRejectedValue(
      new ApiProblem({
        type: 'network_error',
        title: '无法连接服务器',
        status: 0,
        code: 'NETWORK_ERROR',
        traceId: 'client',
      }),
    )
    const loadWorkspaceVersion = vi.fn(async () => 10)
    const navigateAfterRestore = vi.fn(async () => undefined)
    const { wrapper } = await mountView({
      restoreRevision,
      loadWorkspaceVersion,
      navigateAfterRestore,
    })

    await wrapper.findAll('[data-action="choose-restore"]')[0]!.trigger('click')
    await wrapper.get('[data-action="confirm-restore"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-restore-problem]').text()).toContain('没有收到确定结果')
    expect(wrapper.find('[data-restore-confirmation]').exists()).toBe(false)
    expect(wrapper.findAll<HTMLButtonElement>('[data-action="choose-restore"]'))
      .toSatisfy((buttons: VueWrapper<HTMLButtonElement>[]) =>
        buttons.every((button) => button.element.disabled),
      )
    expect(restoreRevision).toHaveBeenCalledOnce()

    await wrapper.get('[data-action="retry-restore-refresh"]').trigger('click')
    await flushPromises()

    expect(restoreRevision).toHaveBeenCalledOnce()
    expect(loadWorkspaceVersion).toHaveBeenCalledTimes(2)
    expect(navigateAfterRestore).toHaveBeenCalledOnce()
  })

  it('discards a pending restore result synchronously when the route target changes', async () => {
    const restoreGate = deferred<void>()
    const restoreRevision = vi.fn(() => restoreGate.promise)
    const loadWorkspaceVersion = vi.fn(async () => 7)
    const navigateAfterRestore = vi.fn(async () => undefined)
    const { wrapper } = await mountView({
      restoreRevision,
      loadWorkspaceVersion,
      navigateAfterRestore,
    })

    await wrapper.findAll('[data-action="choose-restore"]')[0]!.trigger('click')
    wrapper.get('[data-action="confirm-restore"]').element.dispatchEvent(new MouseEvent('click'))
    await flushPromises()
    expect(restoreRevision).toHaveBeenCalledOnce()

    const nextProjectId = '20000000-0000-4000-8000-000000000002'
    await wrapper.setProps({ aggregateId: nextProjectId })
    restoreGate.resolve()
    await flushPromises()

    expect(navigateAfterRestore).not.toHaveBeenCalled()
    expect(wrapper.find('[data-action="retry-restore-refresh"]').exists()).toBe(false)
  })

  it('renders server restore field errors as text without injecting markup', async () => {
    const restoreRevision = vi.fn().mockRejectedValue(
      new ApiProblem({
        type: 'validation',
        title: '<img src=x onerror=alert(1)>无法恢复',
        status: 422,
        code: 'RESTORE_INVALID',
        traceId: 'restore-trace',
        fieldErrors: { expectedWorkspaceVersion: '<script>stale</script>' },
      }),
    )
    const { wrapper } = await mountView({ restoreRevision })

    await wrapper.findAll('[data-action="choose-restore"]')[0]!.trigger('click')
    await wrapper.get('[data-action="confirm-restore"]').trigger('click')
    await flushPromises()

    const problem = wrapper.get('[data-restore-problem]')
    expect(problem.text()).toContain('无法恢复')
    expect(problem.text()).toContain('expectedWorkspaceVersion')
    expect(problem.text()).toContain('<script>stale</script>')
    expect(problem.find('img').exists()).toBe(false)
    expect(problem.find('script').exists()).toBe(false)
    expect(wrapper.find('[data-restore-confirmation]').exists()).toBe(true)
    expect(wrapper.find('[data-action="retry-restore-refresh"]').exists()).toBe(false)
  })

  it('accepts the fixed SITE identity', async () => {
    const loadHistory = vi.fn(async () =>
      revisions.map((row) => ({ ...row, type: 'SITE' as const, aggregateId: SITE_ID })),
    )
    const { wrapper } = await mountView({
      aggregateType: 'SITE',
      aggregateId: SITE_ID,
      loadHistory,
    })

    expect(loadHistory).toHaveBeenCalledWith('SITE', SITE_ID)
    expect(wrapper.text()).toContain('站点发布历史')
  })

  it('reloads and clears target-local restore state when route props change', async () => {
    const loadHistory = vi.fn(async (type: 'SITE' | 'PROJECT' | 'PROJECT_CATALOG') =>
      revisions.map((row) => ({
        ...row,
        type,
        aggregateId: type === 'SITE' ? SITE_ID : PROJECT_ID,
      })),
    )
    const { wrapper } = await mountView({ loadHistory })
    await wrapper.findAll('[data-action="choose-restore"]')[0]!.trigger('click')
    expect(wrapper.find('[data-restore-confirmation]').exists()).toBe(true)

    await wrapper.setProps({ aggregateType: 'SITE', aggregateId: SITE_ID })
    await flushPromises()

    expect(loadHistory).toHaveBeenCalledTimes(2)
    expect(loadHistory).toHaveBeenLastCalledWith('SITE', SITE_ID)
    expect(wrapper.find('[data-restore-confirmation]').exists()).toBe(false)
    expect(wrapper.text()).toContain('站点发布历史')
  })
})
