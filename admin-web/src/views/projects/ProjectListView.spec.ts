import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import {
  PROJECT_CATALOG_ID,
  type PublicationResultDto,
  type PublicationStateDto,
  type ReorderCatalogCommand,
} from '@/types/publishing'
import { createProjectCatalogFixture, PROJECT_IDS } from '@/tests/fixtures/projectWorkspace'

import ProjectListView from './ProjectListView.vue'

const mounted: VueWrapper[] = []

function catalogState(
  overrides: Partial<PublicationStateDto> = {},
): PublicationStateDto {
  return {
    aggregateType: 'PROJECT_CATALOG',
    aggregateId: PROJECT_CATALOG_ID,
    status: 'PUBLISHED',
    version: 9,
    currentRevisionId: '30000000-0000-4000-8000-000000000009',
    publishedAt: '2026-07-18T00:00:00Z',
    projectIdsInOrder: [PROJECT_IDS.first, PROJECT_IDS.second],
    ...overrides,
  }
}

async function mountList(
  load = vi.fn(async () => createProjectCatalogFixture()),
  options: {
    loadCatalogState?: () => Promise<PublicationStateDto>
    reorderCatalog?: (command: ReorderCatalogCommand) => Promise<PublicationResultDto>
  } = {},
) {
  const loadCatalogState = vi.fn(
    options.loadCatalogState ?? (async () => catalogState()),
  )
  const reorderCatalog = vi.fn(
    options.reorderCatalog ??
      (async () => ({
        revisionId: '30000000-0000-4000-8000-000000000010',
        aggregateVersion: 10,
        catalogRevisionId: null,
        catalogVersion: null,
        checksum: 'a'.repeat(64),
      })),
  )
  const wrapper = mount(ProjectListView, {
    props: { load, loadCatalogState, reorderCatalog },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, load, loadCatalogState, reorderCatalog }
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('ProjectListView', () => {
  it('sorts a full workspace catalog deterministically and edits by stable UUID', async () => {
    const { wrapper } = await mountList()

    const rows = wrapper.findAll('[data-project-id]')
    expect(rows.map((row) => row.attributes('data-project-id'))).toEqual([
      PROJECT_IDS.first,
      PROJECT_IDS.second,
      PROJECT_IDS.third,
    ])
    expect(rows.map((row) => row.text())).toEqual([
      expect.stringContaining('回声计划'),
      expect.stringContaining('裂隙原型'),
      expect.stringContaining('地图集计划'),
    ])

    const edit = rows[0]!.getComponent(RouterLinkStub)
    expect(edit.props('to')).toEqual({
      name: 'project-edit',
      params: { projectId: PROJECT_IDS.first },
    })
    expect(JSON.stringify(edit.props('to'))).not.toContain('project-echoes')
  })

  it('filters locally across both languages, status, slug, number, and external key', async () => {
    const { wrapper, load } = await mountList()

    await wrapper.get<HTMLInputElement>('[data-filter="search"]').setValue('Rift Prototype')
    expect(wrapper.findAll('[data-project-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('裂隙原型')

    await wrapper.get<HTMLInputElement>('[data-filter="search"]').setValue('')
    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('已完成')
    expect(wrapper.findAll('[data-project-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('project-rift')

    await wrapper.get<HTMLSelectElement>('[data-filter="status"]').setValue('')
    await wrapper.get<HTMLInputElement>('[data-filter="search"]').setValue('03')
    expect(wrapper.findAll('[data-project-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('地图集计划')
    expect(load).toHaveBeenCalledOnce()
  })

  it('renders an accessible filtered empty state without fetching again', async () => {
    const { wrapper, load } = await mountList()

    await wrapper.get<HTMLInputElement>('[data-filter="search"]').setValue('does-not-exist')

    const empty = wrapper.get('[data-project-empty]')
    expect(empty.attributes('role')).toBe('status')
    expect(empty.text()).toContain('没有匹配的项目')
    expect(load).toHaveBeenCalledOnce()
  })

  it('reorders only the authoritative published project set and reloads after one CAS mutation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const load = vi.fn(async () => createProjectCatalogFixture())
    const loadCatalogState = vi
      .fn()
      .mockResolvedValueOnce(catalogState())
      .mockResolvedValueOnce(
        catalogState({
          version: 10,
          currentRevisionId: '30000000-0000-4000-8000-000000000010',
          projectIdsInOrder: [PROJECT_IDS.second, PROJECT_IDS.first],
        }),
      )
    const reorderCatalog = vi.fn(async () => ({
      revisionId: '30000000-0000-4000-8000-000000000010',
      aggregateVersion: 10,
      catalogRevisionId: null,
      catalogVersion: null,
      checksum: 'a'.repeat(64),
    }))
    const { wrapper } = await mountList(load, { loadCatalogState, reorderCatalog })

    expect(wrapper.findAll('[data-published-order-id]').map((row) => row.attributes('data-published-order-id'))).toEqual([
      PROJECT_IDS.first,
      PROJECT_IDS.second,
    ])
    expect(wrapper.find(`[data-published-order-id="${PROJECT_IDS.third}"]`).exists()).toBe(false)

    await wrapper.findAll('[data-action="move-published-down"]')[0]!.trigger('click')
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(reorderCatalog).toHaveBeenCalledWith({
      expectedCatalogVersion: 9,
      projectIdsInOrder: [PROJECT_IDS.second, PROJECT_IDS.first],
    })
    expect(load).toHaveBeenCalledTimes(2)
    expect(loadCatalogState).toHaveBeenCalledTimes(2)
  })

  it('locks complete-catalog ordering while any local filter is active', async () => {
    const { wrapper } = await mountList()

    await wrapper.get<HTMLInputElement>('[data-filter="search"]').setValue('echoes')

    expect(
      wrapper.findAll<HTMLButtonElement>('[data-action="move-published-down"]')[0]!
        .attributes('disabled'),
    ).toBeDefined()
    expect(wrapper.get('[data-action="publish-catalog-order"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('清空筛选后才能调整完整发布顺序')
  })

  it('reloads current projects and catalog state after a 409 without retrying the mutation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '目录版本已经变化',
      status: 409,
      code: 'PUBLICATION_VERSION_CONFLICT',
      traceId: 'catalog-conflict',
    })
    const load = vi.fn(async () => createProjectCatalogFixture())
    const loadCatalogState = vi.fn(async () => catalogState())
    const reorderCatalog = vi.fn().mockRejectedValue(conflict)
    const { wrapper } = await mountList(load, { loadCatalogState, reorderCatalog })

    await wrapper.findAll('[data-action="move-published-down"]')[0]!.trigger('click')
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(load).toHaveBeenCalledTimes(2)
    expect(loadCatalogState).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-reorder-problem]').text()).toContain('目录版本已经变化')
    expect(wrapper.get('[data-action="publish-catalog-order"]').attributes('disabled')).toBeDefined()
  })

  it('keeps stale catalog controls locked when the 409 refresh fails until a GET-only retry succeeds', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '目录版本已经变化',
      status: 409,
      code: 'PUBLICATION_VERSION_CONFLICT',
      traceId: 'catalog-conflict-refresh',
    })
    const load = vi
      .fn()
      .mockResolvedValueOnce(createProjectCatalogFixture())
      .mockRejectedValueOnce(new Error('private refresh detail'))
      .mockResolvedValueOnce(createProjectCatalogFixture())
    const loadCatalogState = vi.fn(async () => catalogState())
    const reorderCatalog = vi.fn().mockRejectedValue(conflict)
    const { wrapper } = await mountList(load, { loadCatalogState, reorderCatalog })

    await wrapper.findAll('[data-action="move-published-down"]')[0]!.trigger('click')
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(wrapper.findAll('[data-action="move-published-down"]')[0]!.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="publish-catalog-order"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('private refresh detail')

    await wrapper.get('[data-action="retry-project-catalog-load"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(load).toHaveBeenCalledTimes(3)
    expect(wrapper.findAll('[data-action="move-published-down"]')[0]!.attributes('disabled')).toBeUndefined()
  })

  it('never repeats a successful reorder when its refresh fails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const load = vi
      .fn()
      .mockResolvedValueOnce(createProjectCatalogFixture())
      .mockRejectedValueOnce(new Error('private refresh detail'))
      .mockResolvedValueOnce(createProjectCatalogFixture())
    const loadCatalogState = vi
      .fn()
      .mockResolvedValueOnce(catalogState())
      .mockResolvedValueOnce(catalogState())
      .mockResolvedValueOnce(
        catalogState({
          version: 10,
          currentRevisionId: '30000000-0000-4000-8000-000000000010',
          projectIdsInOrder: [PROJECT_IDS.second, PROJECT_IDS.first],
        }),
      )
    const reorderCatalog = vi.fn(async () => ({
      revisionId: '30000000-0000-4000-8000-000000000010',
      aggregateVersion: 10,
      catalogRevisionId: null,
      catalogVersion: null,
      checksum: 'a'.repeat(64),
    }))
    const { wrapper } = await mountList(load, { loadCatalogState, reorderCatalog })

    await wrapper.findAll('[data-action="move-published-down"]')[0]!.trigger('click')
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-reorder-problem]').text()).toContain('排序已经发布')
    expect(wrapper.text()).not.toContain('private refresh detail')

    await wrapper.get('[data-action="retry-reorder-refresh"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(load).toHaveBeenCalledTimes(3)
  })

  it('keeps the mutation latch when a successful refresh is stale until GET proves the successor', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const load = vi.fn(async () => createProjectCatalogFixture())
    const loadCatalogState = vi
      .fn()
      .mockResolvedValueOnce(catalogState())
      .mockResolvedValueOnce(catalogState())
      .mockResolvedValueOnce(
        catalogState({
          version: 10,
          currentRevisionId: '30000000-0000-4000-8000-000000000010',
          projectIdsInOrder: [PROJECT_IDS.second, PROJECT_IDS.first],
        }),
      )
    const reorderCatalog = vi.fn(async () => ({
      revisionId: '30000000-0000-4000-8000-000000000010',
      aggregateVersion: 10,
      catalogRevisionId: null,
      catalogVersion: null,
      checksum: 'a'.repeat(64),
    }))
    const { wrapper } = await mountList(load, { loadCatalogState, reorderCatalog })

    await wrapper.findAll('[data-action="move-published-down"]')[0]!.trigger('click')
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-reorder-problem]').text()).toContain('尚未证明最新版本')
    expect(wrapper.get('[data-action="publish-catalog-order"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="publish-catalog-order"]').trigger('click')
    expect(reorderCatalog).toHaveBeenCalledOnce()

    await wrapper.get('[data-action="retry-reorder-refresh"]').trigger('click')
    await flushPromises()

    expect(reorderCatalog).toHaveBeenCalledOnce()
    expect(loadCatalogState).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-reorder-problem]').exists()).toBe(false)
    expect(wrapper.findAll('[data-published-order-id]').map((row) =>
      row.attributes('data-published-order-id'),
    )).toEqual([PROJECT_IDS.second, PROJECT_IDS.first])
  })

  it('shows a safe load failure and retries through the same explicit boundary', async () => {
    const problem = new ApiProblem({
      type: 'server_error',
      title: '暂时无法读取项目',
      status: 503,
      code: 'PROJECTS_UNAVAILABLE',
      traceId: 'trace-project-list',
    })
    const load = vi
      .fn()
      .mockRejectedValueOnce(problem)
      .mockResolvedValueOnce(createProjectCatalogFixture())
    const { wrapper } = await mountList(load)

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法读取项目')
    expect(wrapper.text()).toContain('trace-project-list')

    await wrapper.get('[data-async-panel] button').trigger('click')
    await flushPromises()

    expect(load).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-project-id]')).toHaveLength(3)
  })
})
