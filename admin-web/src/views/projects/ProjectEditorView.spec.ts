import { config, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import BlockEditor from '@/components/editor/BlockEditor.vue'
import PublishPanel from '@/components/publishing/PublishPanel.vue'
import { createBlock } from '@/types/blocks'
import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { ProjectWorkspaceDto, SaveWorkspaceRequest } from '@/types/content'
import {
  PROJECT_CATALOG_ID,
  type AggregateType,
  type ArchiveProjectCommand,
  type PublicationResultDto,
  type PublicationStateDto,
  type PublishTarget,
} from '@/types/publishing'
import {
  createProjectCatalogFixture,
  createProjectFixture,
  createTaxonomyFixtures,
  PROJECT_IDS,
} from '@/tests/fixtures/projectWorkspace'

const route = vi.hoisted(() => ({
  replace: vi.fn(),
  leaveGuards: [] as Array<() => boolean>,
  updateGuards: [] as Array<() => boolean>,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ replace: route.replace }),
    onBeforeRouteLeave: (guard: () => boolean) => route.leaveGuards.push(guard),
    onBeforeRouteUpdate: (guard: () => boolean) => route.updateGuards.push(guard),
  }
})

import ProjectEditorView from './ProjectEditorView.vue'

const mounted: VueWrapper[] = []

config.global.stubs = {
  ...config.global.stubs,
  RouterLink: {
    name: 'RouterLink',
    props: ['to'],
    template: '<a data-router-link><slot /></a>',
  },
}

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T | PromiseLike<T>) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T | PromiseLike<T>) => void
  const promise = new Promise<T>((accept) => {
    resolve = accept
  })
  return { promise, resolve }
}

function savedProject(
  request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
): VersionedDraft<ProjectWorkspaceDto> {
  const version = request.expectedVersion + 1
  return { version, value: { ...request.workspace, version, publicationDirty: true } }
}

function publicationState(
  aggregateType: AggregateType,
  aggregateId: string,
  version: number,
  revisionId?: string,
  status?: PublicationStateDto['status'],
): PublicationStateDto {
  return {
    aggregateType,
    aggregateId,
    status: status ?? (version === 0 ? 'UNPUBLISHED' : 'PUBLISHED'),
    version,
    currentRevisionId:
      version === 0
        ? null
        : revisionId ??
          (aggregateType === 'PROJECT'
            ? '31000000-0000-4000-8000-000000000001'
            : '31000000-0000-4000-8000-000000000002'),
    publishedAt: version === 0 ? null : '2026-07-18T08:00:00Z',
    projectIdsInOrder: [],
  }
}

function projectPublicationResult(
  aggregateVersion: number,
  catalogVersion: number,
): PublicationResultDto {
  return {
    revisionId: '32000000-0000-4000-8000-000000000001',
    aggregateVersion,
    catalogRevisionId: '32000000-0000-4000-8000-000000000002',
    catalogVersion,
    checksum: 'a'.repeat(64),
  }
}

async function mountEdit(options: {
  fixture?: VersionedDraft<ProjectWorkspaceDto>
  loadProject?: (projectId: string) => Promise<VersionedDraft<ProjectWorkspaceDto>>
  saveProject?: (
    projectId: string,
    request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
  ) => Promise<VersionedDraft<ProjectWorkspaceDto>>
  loadTags?: () => Promise<ReturnType<typeof createTaxonomyFixtures>['tags']>
  loadSkills?: () => Promise<ReturnType<typeof createTaxonomyFixtures>['skills']>
  loadPublicationState?: (
    aggregateType: AggregateType,
    aggregateId: string,
  ) => Promise<PublicationStateDto>
  publishTarget?: (target: PublishTarget) => Promise<PublicationResultDto>
  archiveProject?: (command: ArchiveProjectCommand) => Promise<PublicationResultDto>
} = {}) {
  const taxonomy = createTaxonomyFixtures()
  const loadProject = vi.fn(
    options.loadProject ?? (async () => options.fixture ?? createProjectFixture()),
  )
  const saveProject = vi.fn(options.saveProject ?? (async (_projectId, request) => savedProject(request)))
  const loadTags = vi.fn(options.loadTags ?? (async () => taxonomy.tags))
  const loadSkills = vi.fn(options.loadSkills ?? (async () => taxonomy.skills))
  const loadPublicationState = vi.fn(
    options.loadPublicationState ??
      (async (aggregateType: AggregateType, aggregateId: string) =>
        publicationState(
          aggregateType,
          aggregateId,
          aggregateType === 'PROJECT' ? 1 : 3,
        )),
  )
  const publishTarget = vi.fn(
    options.publishTarget ?? (async () => projectPublicationResult(2, 4)),
  )
  const archiveProject = vi.fn(
    options.archiveProject ?? (async () => projectPublicationResult(2, 4)),
  )
  const wrapper = mount(ProjectEditorView, {
    attachTo: document.body,
    props: {
      mode: 'edit',
      projectId: PROJECT_IDS.first,
      loadProject,
      saveProject,
      loadTags,
      loadSkills,
      loadPublicationState,
      publishTarget,
      archiveProject,
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return {
    wrapper,
    loadProject,
    saveProject,
    loadTags,
    loadSkills,
    loadPublicationState,
    publishTarget,
    archiveProject,
  }
}

beforeEach(() => {
  route.replace.mockReset()
  route.leaveGuards.splice(0)
  route.updateGuards.splice(0)
  vi.restoreAllMocks()
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.useRealTimers()
})

describe('ProjectEditorView', () => {
  it('loads bilingual metadata and taxonomies, then saves without discarding existing blocks', async () => {
    const precise = '9007199254740993.12345678901234567890'
    const existingBlock = createBlock('MARKDOWN')
    existingBlock.sortOrder = 4
    existingBlock.payload.markdown['zh-CN'] = '已有正文'
    existingBlock.payload.markdown.en = 'Existing body'
    const metricBlock = createBlock('METRICS')
    metricBlock.id = '50000000-0000-4000-8000-000000000010'
    metricBlock.sortOrder = 5
    metricBlock.payload.metrics = [
      {
        id: '60000000-0000-4000-8000-000000000010',
        sortOrder: 0,
        numericValue: precise,
        copy: {
          'zh-CN': { label: '精确值', value: precise, suffix: '' },
          en: { label: 'Precise value', value: precise, suffix: '' },
        },
      },
    ]
    const fixture = createProjectFixture({ blocks: [existingBlock, metricBlock] })
    const { wrapper, saveProject } = await mountEdit({ fixture })

    expect(
      wrapper.get<HTMLInputElement>('[data-field="translations.zh-CN.title"]').element.value,
    ).toBe('回声计划')
    expect(wrapper.text()).toContain('玩法')
    expect(wrapper.text()).toContain('虚幻引擎')
    expect(wrapper.findAll('[data-block-id]')).toHaveLength(2)
    expect(wrapper.get('[data-project-block-editor]').text()).toContain('项目内容块')

    await wrapper.get<HTMLInputElement>('[data-field="translations.zh-CN.title"]').setValue('新的中文标题')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(saveProject).toHaveBeenCalledOnce()
    const [projectId, request] = saveProject.mock.calls[0]!
    expect(projectId).toBe(PROJECT_IDS.first)
    expect(request.expectedVersion).toBe(4)
    expect(request.workspace.translations['zh-CN'].title).toBe('新的中文标题')
    expect(request.workspace.blocks).toEqual([existingBlock, metricBlock])
    expect(
      request.workspace.blocks[1]?.payload.type === 'METRICS'
        ? request.workspace.blocks[1].payload.metrics[0]?.numericValue
        : null,
    ).toBe(precise)
  })

  it('keeps an incomplete block local, then saves the exact nested payload after repair', async () => {
    const { wrapper, saveProject } = await mountEdit()

    await wrapper.get('[data-add-block="LINK"]').trigger('click')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(saveProject).not.toHaveBeenCalled()
    const url = wrapper.get<HTMLInputElement>(
      '[data-block-type="LINK"] [data-field="url"]',
    )
    expect(url.attributes('aria-invalid')).toBe('true')
    expect(document.activeElement).toBe(url.element)

    await url.setValue('https://yychainsaw.xyz/projects/echoes')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(saveProject).toHaveBeenCalledOnce()
    const request = saveProject.mock.calls[0]![1]
    expect(request.workspace.blocks).toHaveLength(1)
    expect(request.workspace.blocks[0]).toMatchObject({
      sortOrder: 0,
      visible: true,
      width: 'STANDARD',
      alignment: 'LEFT',
      emphasis: 'NONE',
      columns: 1,
      payload: {
        type: 'LINK',
        url: 'https://yychainsaw.xyz/projects/echoes',
        openNewTab: true,
        copy: {
          'zh-CN': { label: '', description: '' },
          en: { label: '', description: '' },
        },
      },
    })
  })

  it('suspends invalid block autosave and resumes one interval after the draft is repaired', async () => {
    const { wrapper, saveProject } = await mountEdit()
    vi.useFakeTimers()

    await wrapper.get('[data-add-block="LINK"]').trigger('click')
    await vi.advanceTimersByTimeAsync(15_000)

    expect(saveProject).not.toHaveBeenCalled()

    await wrapper
      .get<HTMLInputElement>('[data-block-type="LINK"] [data-field="url"]')
      .setValue('https://yychainsaw.xyz/projects/autosave')
    await vi.advanceTimersByTimeAsync(14_999)
    expect(saveProject).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(saveProject).toHaveBeenCalledOnce()
    expect(saveProject.mock.calls[0]![1].workspace.blocks[0]?.payload).toMatchObject({
      type: 'LINK',
      url: 'https://yychainsaw.xyz/projects/autosave',
    })
  })

  it('switches locale tabs while retaining independent project copy edits', async () => {
    const { wrapper } = await mountEdit()

    await wrapper.get<HTMLInputElement>('[data-field="translations.zh-CN.title"]').setValue('中文草稿')
    await wrapper.findAll('button[role="tab"]')[1]!.trigger('click')
    const englishTitle = wrapper.get<HTMLInputElement>('[data-field="translations.en.title"]')
    expect(englishTitle.element.value).toBe('Project Echoes')
    await englishTitle.setValue('English draft')

    await wrapper.findAll('button[role="tab"]')[0]!.trigger('click')
    expect(
      wrapper.get<HTMLInputElement>('[data-field="translations.zh-CN.title"]').element.value,
    ).toBe('中文草稿')
  })

  it('keeps metadata and blocks in the controlled locale panel with explicit save semantics', async () => {
    const { wrapper } = await mountEdit()
    const tabs = wrapper.findAll('button[role="tab"]')

    expect(tabs[0]!.attributes('aria-label')).toContain('元数据翻译完成度')
    const blockEditor = wrapper.get('[data-project-block-editor]')
    const activePanel = blockEditor.element.closest('[role="tabpanel"]')
    expect(activePanel).not.toBeNull()
    expect(activePanel?.getAttribute('aria-labelledby')).toBe(tabs[0]!.attributes('id'))

    const saveStatus = wrapper.get('[data-save-state][role="status"]')
    expect(saveStatus.attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[data-project-editor-form]').attributes('aria-busy')).toBe('false')
    expect(wrapper.get('[data-action="save-bottom"]').attributes('aria-describedby')).toBe(
      saveStatus.attributes('id'),
    )
  })

  it('publishes one exact three-version target, freezes the editor, then reloads every changed source', async () => {
    const publishGate = deferred<PublicationResultDto>()
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(
        createProjectFixture({ version: 5, publicationDirty: false }),
      )
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          return publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads,
            projectStateLoads === 2
              ? '32000000-0000-4000-8000-000000000001'
              : undefined,
          )
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads + 2,
          catalogStateLoads === 2
            ? '32000000-0000-4000-8000-000000000002'
            : undefined,
        )
      },
    )
    const publishTarget = vi.fn().mockReturnValue(publishGate.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const { wrapper, loadTags, loadSkills } = await mountEdit({
      loadProject,
      loadPublicationState,
      publishTarget,
    })

    expect(loadPublicationState.mock.calls.slice(0, 2)).toEqual([
      ['PROJECT', PROJECT_IDS.first],
      ['PROJECT_CATALOG', PROJECT_CATALOG_ID],
    ])
    expect(wrapper.getComponent(PublishPanel).props('target')).toEqual({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_IDS.first,
      expectedWorkspaceVersion: 4,
      expectedProjectPublicationVersion: 1,
      expectedCatalogVersion: 3,
    })
    expect(wrapper.getComponent({ name: 'RouterLink' }).props('to')).toEqual({
      name: 'publishing-history',
      params: { aggregateType: 'PROJECT', aggregateId: PROJECT_IDS.first },
    })

    await wrapper.get('[data-action="publish"]').trigger('click')
    await Promise.resolve()

    expect(publishTarget).toHaveBeenCalledOnce()
    expect(publishTarget).toHaveBeenCalledWith({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_IDS.first,
      expectedWorkspaceVersion: 4,
      expectedProjectPublicationVersion: 1,
      expectedCatalogVersion: 3,
    })
    expect(wrapper.get('[data-project-editor-fields]').attributes('disabled')).toBeDefined()
    expect(wrapper.getComponent(BlockEditor).props('disabled')).toBe(true)
    expect(wrapper.get('[data-action="save"]').attributes('disabled')).toBeDefined()

    publishGate.resolve(projectPublicationResult(2, 4))
    await flushPromises()

    expect(loadProject).toHaveBeenCalledTimes(2)
    expect(loadTags).toHaveBeenCalledTimes(2)
    expect(loadSkills).toHaveBeenCalledTimes(2)
    expect(loadPublicationState).toHaveBeenCalledTimes(4)
    expect(wrapper.text()).toContain('已保存 · 版本 5')
    expect(wrapper.get('[data-publication-version]').text()).toContain('项目发布版本 2 · 目录版本 4')
    expect(wrapper.getComponent(PublishPanel).props('target')).toEqual({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_IDS.first,
      expectedWorkspaceVersion: 5,
      expectedProjectPublicationVersion: 2,
      expectedCatalogVersion: 4,
    })
  })

  it('discards a pending publication result synchronously when the route switches projects', async () => {
    const publishGate = deferred<PublicationResultDto>()
    const loadProject = vi.fn(async (projectId: string) =>
      createProjectFixture({ id: projectId }),
    )
    const publishTarget = vi.fn(() => publishGate.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({ loadProject, publishTarget })

    wrapper.get('[data-action="publish"]').element.dispatchEvent(new MouseEvent('click'))
    await flushPromises()
    expect(publishTarget).toHaveBeenCalledOnce()

    await wrapper.setProps({ projectId: PROJECT_IDS.second })
    await flushPromises()
    publishGate.resolve(projectPublicationResult(2, 4))
    await flushPromises()

    expect(loadProject).toHaveBeenCalledTimes(2)
    expect(loadProject).toHaveBeenLastCalledWith(PROJECT_IDS.second)
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
    expect(wrapper.getComponent(PublishPanel).props('target')).toMatchObject({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_IDS.second,
    })
  })

  it('archives one exact project and catalog target, freezes all mutations, then proves the refreshed successors', async () => {
    const archiveGate = deferred<PublicationResultDto>()
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(createProjectFixture())
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          return publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads,
            projectStateLoads === 2
              ? '32000000-0000-4000-8000-000000000001'
              : undefined,
            projectStateLoads === 2 ? 'ARCHIVED' : 'PUBLISHED',
          )
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads + 2,
          catalogStateLoads === 2
            ? '32000000-0000-4000-8000-000000000002'
            : undefined,
        )
      },
    )
    const archiveProject = vi.fn().mockReturnValue(archiveGate.promise)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper, loadTags, loadSkills } = await mountEdit({
      loadProject,
      loadPublicationState,
      archiveProject,
    })

    expect(wrapper.get('[data-project-archive]').text()).toContain('下线已发布项目')
    await wrapper.get('[data-action="archive-project"]').trigger('click')
    await Promise.resolve()

    expect(confirm).toHaveBeenCalledWith(
      '确定下线这个已发布项目吗？项目会从公开目录移除，但后台内容和发布历史会保留。',
    )
    expect(archiveProject).toHaveBeenCalledOnce()
    expect(archiveProject).toHaveBeenCalledWith({
      projectId: PROJECT_IDS.first,
      expectedProjectPublicationVersion: 1,
      expectedCatalogVersion: 3,
    })
    expect(wrapper.get('[data-project-editor-fields]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="archive-project"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="archive-project"]').trigger('click')
    expect(archiveProject).toHaveBeenCalledOnce()

    archiveGate.resolve(projectPublicationResult(2, 4))
    await flushPromises()

    expect(loadProject).toHaveBeenCalledTimes(2)
    expect(loadTags).toHaveBeenCalledTimes(2)
    expect(loadSkills).toHaveBeenCalledTimes(2)
    expect(loadPublicationState).toHaveBeenCalledTimes(4)
    expect(wrapper.get('[data-publication-version]').text()).toContain(
      '项目发布版本 2 · 目录版本 4',
    )
    expect(wrapper.find('[data-project-archive]').exists()).toBe(false)
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
  })

  it('requires an explicit archive confirmation before sending any mutation', async () => {
    const archiveProject = vi.fn(async () => projectPublicationResult(2, 4))
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper } = await mountEdit({ archiveProject })

    await wrapper.get('[data-action="archive-project"]').trigger('click')

    expect(archiveProject).not.toHaveBeenCalled()
    expect(wrapper.get('[data-project-editor-fields]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeUndefined()
  })

  it.each(['409', 'network'] as const)(
    'keeps an uncertain archive %s in a GET-only lock, including after a refresh failure',
    async (failureKind) => {
      const archiveFailure =
        failureKind === '409'
          ? new ApiProblem({
              type: 'conflict',
              title: '发布版本已经变化',
              status: 409,
              code: 'PUBLICATION_VERSION_CONFLICT',
              traceId: 'trace-archive-conflict',
            })
          : new Error('socket closed')
      const refreshFailure = new ApiProblem({
        type: 'unavailable',
        title: '最新项目工作区暂时不可用',
        status: 503,
        code: 'PROJECT_REFRESH_UNAVAILABLE',
        traceId: 'trace-archive-refresh',
      })
      const loadProject = vi
        .fn()
        .mockResolvedValueOnce(createProjectFixture())
        .mockRejectedValueOnce(refreshFailure)
        .mockResolvedValueOnce(createProjectFixture())
      const archiveProject = vi.fn().mockRejectedValue(archiveFailure)
      vi.spyOn(window, 'confirm').mockReturnValue(true)
      const { wrapper } = await mountEdit({ loadProject, archiveProject })
      const editorForm = wrapper.get('[data-project-editor-form]').element

      await wrapper.get('[data-action="archive-project"]').trigger('click')
      await flushPromises()

      expect(archiveProject).toHaveBeenCalledOnce()
      expect(wrapper.get('[data-publication-refresh-problem]').text()).toContain(
        '不会重复提交归档请求',
      )
      expect(wrapper.get('[data-project-editor-form]').element).toBe(editorForm)
      expect(document.activeElement).toBe(
        wrapper.get('[data-action="retry-publication-refresh"]').element,
      )
      expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-action="archive-project"]').attributes('disabled')).toBeDefined()

      await wrapper.get('[data-action="retry-publication-refresh"]').trigger('click')
      await flushPromises()

      expect(archiveProject).toHaveBeenCalledOnce()
      expect(loadProject).toHaveBeenCalledTimes(3)
      expect(wrapper.get('[data-publication-refresh-problem]').text()).toContain(
        '不会重复提交归档请求',
      )
    },
  )

  it('accepts higher legal project and catalog states after an archive result', async () => {
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(createProjectFixture({ version: 5 }))
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          return publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads === 1 ? 1 : 3,
          )
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads === 1 ? 3 : 5,
        )
      },
    )
    const archiveProject = vi.fn(async () => projectPublicationResult(2, 4))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({
      loadProject,
      loadPublicationState,
      archiveProject,
    })

    await wrapper.get('[data-action="archive-project"]').trigger('click')
    await flushPromises()

    expect(archiveProject).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
    expect(wrapper.text()).toContain('刷新期间又有更新')
    expect(wrapper.get('[data-action="archive-project"]').attributes('disabled')).toBeUndefined()
  })

  it('does not treat higher still-published states as proof of an uncertain archive', async () => {
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '发布版本已经变化',
      status: 409,
      code: 'PUBLICATION_VERSION_CONFLICT',
      traceId: 'trace-archive-newer-published',
    })
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(createProjectFixture({ version: 5 }))
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          return publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads === 1 ? 1 : 5,
            undefined,
            'PUBLISHED',
          )
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads === 1 ? 3 : 11,
        )
      },
    )
    const archiveProject = vi.fn().mockRejectedValue(conflict)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({
      loadProject,
      loadPublicationState,
      archiveProject,
    })

    await wrapper.get('[data-action="archive-project"]').trigger('click')
    await flushPromises()

    expect(archiveProject).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-publication-refresh-problem]').text()).toContain(
      '不会重复提交归档请求',
    )
    expect(wrapper.get('[data-action="archive-project"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('本次项目归档已处理')
  })

  it('discards a late archive response synchronously when the route switches projects', async () => {
    const archiveGate = deferred<PublicationResultDto>()
    const loadProject = vi.fn(async (projectId: string) =>
      createProjectFixture({ id: projectId }),
    )
    const archiveProject = vi.fn(() => archiveGate.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({ loadProject, archiveProject })

    wrapper
      .get('[data-action="archive-project"]')
      .element.dispatchEvent(new MouseEvent('click'))
    await flushPromises()
    expect(archiveProject).toHaveBeenCalledOnce()

    await wrapper.setProps({ projectId: PROJECT_IDS.second })
    await flushPromises()
    archiveGate.resolve(projectPublicationResult(2, 4))
    await flushPromises()

    expect(loadProject).toHaveBeenCalledTimes(2)
    expect(loadProject).toHaveBeenLastCalledWith(PROJECT_IDS.second)
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
    expect(wrapper.get('[data-action="archive-project"]').attributes('disabled')).toBeUndefined()
  })

  it('locks a successful publication after refresh failure and retries GETs without another POST', async () => {
    const refreshFailure = new ApiProblem({
      type: 'unavailable',
      title: '最新项目工作区暂时不可用',
      status: 503,
      code: 'PROJECT_REFRESH_UNAVAILABLE',
      traceId: 'trace-project-refresh',
    })
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockRejectedValueOnce(refreshFailure)
      .mockResolvedValueOnce(
        createProjectFixture({ version: 5, publicationDirty: false }),
      )
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          return publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads === 1 ? 1 : 2,
            projectStateLoads === 1
              ? undefined
              : '32000000-0000-4000-8000-000000000001',
          )
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads === 1 ? 3 : 4,
          catalogStateLoads === 1
            ? undefined
            : '32000000-0000-4000-8000-000000000002',
        )
      },
    )
    const publishTarget = vi.fn(async () => projectPublicationResult(2, 4))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({
      loadProject,
      loadPublicationState,
      publishTarget,
    })
    const editorForm = wrapper.get('[data-project-editor-form]').element

    await wrapper.get('[data-action="publish"]').trigger('click')
    await flushPromises()

    expect(publishTarget).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-publication-refresh-problem]').text()).toContain(
      '不会重复提交发布请求',
    )
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-project-editor-form]').element).toBe(editorForm)
    expect(document.activeElement).toBe(
      wrapper.get('[data-action="retry-publication-refresh"]').element,
    )

    await wrapper.get('[data-action="retry-publication-refresh"]').trigger('click')
    await flushPromises()

    expect(publishTarget).toHaveBeenCalledOnce()
    expect(loadProject).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已保存 · 版本 5')
  })

  it('accepts monotonic concurrent successors after its publish instead of entering a permanent lock', async () => {
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(createProjectFixture({ version: 6, publicationDirty: true }))
    let projectStateLoads = 0
    let catalogStateLoads = 0
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT') {
          projectStateLoads += 1
          const state = publicationState(
            aggregateType,
            aggregateId,
            projectStateLoads === 1 ? 1 : 3,
          )
          return projectStateLoads === 1 ? state : { ...state, status: 'ARCHIVED' as const }
        }
        catalogStateLoads += 1
        return publicationState(
          aggregateType,
          aggregateId,
          catalogStateLoads === 1 ? 3 : 5,
        )
      },
    )
    const publishTarget = vi.fn(async () => projectPublicationResult(2, 4))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({ loadProject, loadPublicationState, publishTarget })

    await wrapper.get('[data-action="publish"]').trigger('click')
    await flushPromises()

    expect(publishTarget).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
    expect(wrapper.text()).toContain('刷新期间又有更新')
    expect(wrapper.text()).toContain('已保存 · 版本 6')
    expect(wrapper.getComponent(PublishPanel).props('target')).toEqual({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_IDS.first,
      expectedWorkspaceVersion: 6,
      expectedProjectPublicationVersion: 3,
      expectedCatalogVersion: 5,
    })
  })

  it.each([
    {
      label: 'project revision',
      workspaceVersion: 5,
      publicationDirty: false,
      projectVersion: 2,
      projectRevision: '33000000-0000-4000-8000-000000000001',
      catalogVersion: 4,
      catalogRevision: '32000000-0000-4000-8000-000000000002',
    },
    {
      label: 'catalog revision',
      workspaceVersion: 5,
      publicationDirty: false,
      projectVersion: 2,
      projectRevision: '32000000-0000-4000-8000-000000000001',
      catalogVersion: 4,
      catalogRevision: '33000000-0000-4000-8000-000000000002',
    },
    {
      label: 'workspace publication-dirty flag',
      workspaceVersion: 5,
      publicationDirty: true,
      projectVersion: 2,
      projectRevision: '32000000-0000-4000-8000-000000000001',
      catalogVersion: 4,
      catalogRevision: '32000000-0000-4000-8000-000000000002',
    },
  ])(
    'keeps the GET-only lock when refreshed same-version $label does not prove the publication outcome',
    async (mismatch) => {
      const loadProject = vi
        .fn()
        .mockResolvedValueOnce(createProjectFixture())
        .mockResolvedValueOnce(
          createProjectFixture({
            version: mismatch.workspaceVersion,
            publicationDirty: mismatch.publicationDirty,
          }),
        )
        .mockResolvedValueOnce(
          createProjectFixture({ version: 5, publicationDirty: false }),
        )
      let projectStateLoads = 0
      let catalogStateLoads = 0
      const loadPublicationState = vi.fn(
        async (aggregateType: AggregateType, aggregateId: string) => {
          if (aggregateType === 'PROJECT') {
            projectStateLoads += 1
            if (projectStateLoads === 1) {
              return publicationState(aggregateType, aggregateId, 1)
            }
            if (projectStateLoads === 2) {
              return publicationState(
                aggregateType,
                aggregateId,
                mismatch.projectVersion,
                mismatch.projectRevision,
              )
            }
            return publicationState(
              aggregateType,
              aggregateId,
              2,
              '32000000-0000-4000-8000-000000000001',
            )
          }
          catalogStateLoads += 1
          if (catalogStateLoads === 1) {
            return publicationState(aggregateType, aggregateId, 3)
          }
          if (catalogStateLoads === 2) {
            return publicationState(
              aggregateType,
              aggregateId,
              mismatch.catalogVersion,
              mismatch.catalogRevision,
            )
          }
          return publicationState(
            aggregateType,
            aggregateId,
            4,
            '32000000-0000-4000-8000-000000000002',
          )
        },
      )
      const publishTarget = vi.fn(async () => projectPublicationResult(2, 4))
      vi.spyOn(window, 'confirm').mockReturnValue(true)
      const { wrapper } = await mountEdit({
        loadProject,
        loadPublicationState,
        publishTarget,
      })

      await wrapper.get('[data-action="publish"]').trigger('click')
      await flushPromises()

      expect(wrapper.get('[data-publication-refresh-problem]').text()).toContain(
        '不会重复提交发布请求',
      )
      expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
      expect(publishTarget).toHaveBeenCalledOnce()

      await wrapper.get('[data-action="retry-publication-refresh"]').trigger('click')
      await flushPromises()

      expect(publishTarget).toHaveBeenCalledOnce()
      expect(loadProject).toHaveBeenCalledTimes(3)
      expect(wrapper.find('[data-publication-refresh-problem]').exists()).toBe(false)
      expect(wrapper.text()).toContain('已保存 · 版本 5')
    },
  )

  it('keeps preview and publish disabled when either publication state is unavailable', async () => {
    const unavailable = new ApiProblem({
      type: 'unavailable',
      title: '发布状态服务暂时不可用',
      status: 503,
      code: 'PUBLICATION_STATE_UNAVAILABLE',
      traceId: 'trace-publication-state',
    })
    const publishTarget = vi.fn(async () => projectPublicationResult(2, 4))
    const { wrapper } = await mountEdit({
      loadPublicationState: vi.fn().mockRejectedValue(unavailable),
      publishTarget,
    })

    expect(wrapper.get('[data-publication-state-error]').text()).toContain(
      '发布状态服务暂时不可用',
    )
    expect(wrapper.get('[data-action="preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(publishTarget).not.toHaveBeenCalled()
  })

  it('uses visible block copy in publication completion instead of metadata alone', async () => {
    const incomplete = createBlock('MARKDOWN')
    const { wrapper } = await mountEdit({
      fixture: createProjectFixture({ blocks: [incomplete] }),
    })

    const completion = wrapper.getComponent(PublishPanel).props('completion') as {
      'zh-CN': { complete: number; total: number }
      en: { complete: number; total: number }
    }
    expect(completion['zh-CN'].complete).toBe(completion['zh-CN'].total - 1)
    expect(completion.en.complete).toBe(completion.en.total - 1)
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="preview"]').attributes('disabled')).toBeUndefined()
  })

  it('never requests publication state or renders publishing controls in create mode', async () => {
    const loadPublicationState = vi.fn()
    const publishTarget = vi.fn()
    const archiveProject = vi.fn()
    const wrapper = mount(ProjectEditorView, {
      props: { mode: 'create', loadPublicationState, publishTarget, archiveProject },
    })
    mounted.push(wrapper)
    await flushPromises()

    expect(loadPublicationState).not.toHaveBeenCalled()
    expect(publishTarget).not.toHaveBeenCalled()
    expect(archiveProject).not.toHaveBeenCalled()
    expect(wrapper.findComponent(PublishPanel).exists()).toBe(false)
    expect(wrapper.find('[data-project-archive]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目发布历史')
  })

  it('does not render the archive action for an unpublished project', async () => {
    const archiveProject = vi.fn()
    const { wrapper } = await mountEdit({
      loadPublicationState: vi.fn(async (aggregateType, aggregateId) =>
        publicationState(
          aggregateType,
          aggregateId,
          aggregateType === 'PROJECT' ? 0 : 3,
        ),
      ),
      archiveProject,
    })

    expect(wrapper.find('[data-project-archive]').exists()).toBe(false)
    expect(archiveProject).not.toHaveBeenCalled()
  })

  it('discards late publication state from the previous project route', async () => {
    const projectAState = deferred<PublicationStateDto>()
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockResolvedValueOnce(
        createProjectFixture({
          id: PROJECT_IDS.second,
          externalKey: 'project-rift',
          slug: 'project-rift',
          version: 8,
        }),
      )
    const loadPublicationState = vi.fn(
      async (aggregateType: AggregateType, aggregateId: string) => {
        if (aggregateType === 'PROJECT' && aggregateId === PROJECT_IDS.first) {
          return projectAState.promise
        }
        return publicationState(
          aggregateType,
          aggregateId,
          aggregateType === 'PROJECT' ? 7 : 4,
        )
      },
    )
    const { wrapper } = await mountEdit({ loadProject, loadPublicationState })

    await wrapper.setProps({ projectId: PROJECT_IDS.second })
    await flushPromises()
    expect(wrapper.getComponent(PublishPanel).props('target')).toMatchObject({
      aggregateId: PROJECT_IDS.second,
      expectedWorkspaceVersion: 8,
      expectedProjectPublicationVersion: 7,
      expectedCatalogVersion: 4,
    })

    projectAState.resolve(publicationState('PROJECT', PROJECT_IDS.first, 1))
    await flushPromises()
    expect(wrapper.getComponent(PublishPanel).props('target')).toMatchObject({
      aggregateId: PROJECT_IDS.second,
      expectedProjectPublicationVersion: 7,
    })
  })

  it('validates a new slug and number before one explicit POST, then navigates by the server UUID', async () => {
    const taxonomy = createTaxonomyFixtures()
    const listProjects = vi.fn(async () => createProjectCatalogFixture())
    const createProject = vi.fn(async (workspace: ProjectWorkspaceDto) => ({
      version: 0,
      value: {
        ...workspace,
        id: '20000000-0000-4000-8000-000000000099',
        version: 0,
        publicationDirty: true,
      },
    }))
    const wrapper = mount(ProjectEditorView, {
      props: {
        mode: 'create',
        listProjects,
        createProject,
        loadTags: vi.fn(async () => taxonomy.tags),
        loadSkills: vi.fn(async () => taxonomy.skills),
      },
    })
    mounted.push(wrapper)

    await wrapper.get<HTMLInputElement>('input[name="slug"]').setValue('中文-slug')
    await wrapper.get<HTMLInputElement>('input[name="number"]').setValue('04')
    await wrapper.get('form').trigger('submit')
    expect(createProject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('只能包含小写英文字母、数字和连字符')

    await wrapper.get<HTMLInputElement>('input[name="slug"]').setValue('project-nova')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(listProjects).toHaveBeenCalledOnce()
    expect(createProject).toHaveBeenCalledOnce()
    const workspace = createProject.mock.calls[0]![0]
    expect(workspace).toMatchObject({
      externalKey: 'project-nova',
      slug: 'project-nova',
      number: '04',
      sortOrder: 3,
      version: 0,
      visible: false,
      blocks: [],
    })
    expect(workspace.id).toMatch(/^[0-9a-f-]{36}$/)
    expect(route.replace).toHaveBeenCalledWith({
      name: 'project-edit',
      params: { projectId: '20000000-0000-4000-8000-000000000099' },
    })
  })

  it('locks the create form so repeated activation cannot create duplicate projects', async () => {
    const gate = deferred<VersionedDraft<ProjectWorkspaceDto>>()
    const createProject = vi.fn().mockReturnValue(gate.promise)
    const wrapper = mount(ProjectEditorView, {
      props: {
        mode: 'create',
        listProjects: vi.fn(async () => []),
        createProject,
      },
    })
    mounted.push(wrapper)
    await wrapper.get<HTMLInputElement>('input[name="slug"]').setValue('project-once')
    await wrapper.get<HTMLInputElement>('input[name="number"]').setValue('01')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(createProject).toHaveBeenCalledOnce()
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()

    await wrapper.get('form').trigger('submit')
    expect(createProject).toHaveBeenCalledOnce()

    const pendingWorkspace = createProject.mock.calls[0]![0] as ProjectWorkspaceDto
    gate.resolve({
      version: 0,
      value: {
        ...pendingWorkspace,
        id: '20000000-0000-4000-8000-000000000098',
      },
    })
    await flushPromises()
    expect(route.replace).toHaveBeenCalledOnce()
  })

  it('protects an unpersisted create identity from browser refresh', async () => {
    const wrapper = mount(ProjectEditorView, { props: { mode: 'create' } })
    mounted.push(wrapper)

    const pristine = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(pristine)
    expect(pristine.defaultPrevented).toBe(false)

    await wrapper.get<HTMLInputElement>('input[name="slug"]').setValue('project-refresh')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
  })

  it.each(['aborted', 'rejected'] as const)(
    'retries a %s post-create navigation without issuing a second POST',
    async (failureMode) => {
      if (failureMode === 'aborted') {
        route.replace.mockResolvedValueOnce(new Error('navigation aborted'))
      } else {
        route.replace.mockRejectedValueOnce(new Error('navigation failed'))
      }
      route.replace.mockResolvedValueOnce(undefined)
      const createProject = vi.fn(async (workspace: ProjectWorkspaceDto) => ({
        version: 0,
        value: {
          ...workspace,
          id: '20000000-0000-4000-8000-000000000097',
        },
      }))
      const wrapper = mount(ProjectEditorView, {
        props: {
          mode: 'create',
          listProjects: vi.fn(async () => []),
          createProject,
        },
      })
      mounted.push(wrapper)
      await wrapper.get<HTMLInputElement>('input[name="slug"]').setValue('project-created')
      await wrapper.get<HTMLInputElement>('input[name="number"]').setValue('01')

      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(createProject).toHaveBeenCalledOnce()
      expect(wrapper.text()).toContain('项目已经创建，不会再次提交创建请求')
      expect(wrapper.text()).toContain('请重试')
      expect(wrapper.get<HTMLInputElement>('input[name="slug"]').attributes('disabled')).toBeDefined()

      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(createProject).toHaveBeenCalledOnce()
      expect(route.replace).toHaveBeenCalledTimes(2)
    },
  )

  it('keeps a slug 409 manually repairable instead of showing a version-conflict reload', async () => {
    const slugConflict = new ApiProblem({
      type: 'conflict',
      title: 'Slug 已被其他项目使用',
      status: 409,
      code: 'CONTENT_SLUG_CONFLICT',
      traceId: 'trace-project-slug',
      fieldErrors: { slug: '请换一个 Slug' },
    })
    const { wrapper } = await mountEdit({
      saveProject: vi.fn().mockRejectedValue(slugConflict),
    })

    await wrapper.get<HTMLInputElement>('[data-field="slug"]').setValue('project-taken')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Slug 已被其他项目使用')
    expect(wrapper.text()).not.toContain('VERSION CONFLICT')
    expect(wrapper.get<HTMLInputElement>('[data-field="slug"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[data-action="save"]').attributes('disabled')).toBeUndefined()
  })

  it('clears project A before a failed route update can render project B', async () => {
    const unavailable = new ApiProblem({
      type: 'not_found',
      title: '目标项目不存在',
      status: 404,
      code: 'PROJECT_NOT_FOUND',
      traceId: 'trace-project-b',
    })
    const loadProject = vi
      .fn()
      .mockResolvedValueOnce(createProjectFixture())
      .mockRejectedValueOnce(unavailable)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper } = await mountEdit({ loadProject })
    await wrapper
      .get<HTMLInputElement>('[data-field="translations.zh-CN.title"]')
      .setValue('项目 A 未保存草稿')

    expect(route.updateGuards[0]!()).toBe(true)
    expect(confirm).toHaveBeenCalledOnce()
    await wrapper.setProps({ projectId: PROJECT_IDS.second })
    await flushPromises()

    expect(loadProject).toHaveBeenLastCalledWith(PROJECT_IDS.second)
    expect(wrapper.find('[data-field="translations.zh-CN.title"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('目标项目不存在')
    expect(wrapper.text()).not.toContain('项目 A 未保存草稿')
  })

  it('stops saving after a CAS conflict and protects dirty route departure', async () => {
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '项目已被其他会话修改',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'trace-project-conflict',
    })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper, saveProject } = await mountEdit({
      saveProject: vi.fn().mockRejectedValue(conflict),
    })

    await wrapper.get<HTMLInputElement>('[data-field="translations.zh-CN.title"]').setValue('冲突草稿')
    expect(route.leaveGuards).toHaveLength(1)
    expect(route.updateGuards).toHaveLength(1)
    expect(route.leaveGuards[0]!()).toBe(false)
    expect(route.updateGuards[0]!()).toBe(false)
    expect(confirm).toHaveBeenCalledTimes(2)

    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(saveProject).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="alert"]').text()).toContain('项目已被其他会话修改')
    expect(wrapper.get('[data-action="save"]').attributes('disabled')).toBeDefined()
  })
})
