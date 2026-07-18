import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createBlock } from '@/types/blocks'
import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { ProjectWorkspaceDto, SaveWorkspaceRequest } from '@/types/content'
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

async function mountEdit(options: {
  fixture?: VersionedDraft<ProjectWorkspaceDto>
  loadProject?: (projectId: string) => Promise<VersionedDraft<ProjectWorkspaceDto>>
  saveProject?: (
    projectId: string,
    request: SaveWorkspaceRequest<ProjectWorkspaceDto>,
  ) => Promise<VersionedDraft<ProjectWorkspaceDto>>
} = {}) {
  const taxonomy = createTaxonomyFixtures()
  const loadProject = vi.fn(
    options.loadProject ?? (async () => options.fixture ?? createProjectFixture()),
  )
  const saveProject = vi.fn(options.saveProject ?? (async (_projectId, request) => savedProject(request)))
  const wrapper = mount(ProjectEditorView, {
    attachTo: document.body,
    props: {
      mode: 'edit',
      projectId: PROJECT_IDS.first,
      loadProject,
      saveProject,
      loadTags: vi.fn(async () => taxonomy.tags),
      loadSkills: vi.fn(async () => taxonomy.skills),
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, loadProject, saveProject }
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
