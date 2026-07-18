import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import { createProjectCatalogFixture, PROJECT_IDS } from '@/tests/fixtures/projectWorkspace'

import ProjectListView from './ProjectListView.vue'

const mounted: VueWrapper[] = []

async function mountList(
  load = vi.fn(async () => createProjectCatalogFixture()),
): Promise<{ wrapper: VueWrapper; load: typeof load }> {
  const wrapper = mount(ProjectListView, {
    props: { load },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, load }
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
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
