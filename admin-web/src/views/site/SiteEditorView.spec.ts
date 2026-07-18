import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem, type VersionedDraft } from '@/types/api'
import type { MediaAssetSummaryDto, SaveWorkspaceRequest, SiteWorkspaceDto } from '@/types/content'
import { createSiteFixture } from '@/tests/fixtures/siteWorkspace'

const routeHooks = vi.hoisted(() => ({
  leaveGuards: [] as Array<() => boolean>,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    onBeforeRouteLeave: (guard: () => boolean) => routeHooks.leaveGuards.push(guard),
  }
})

import SiteEditorView from './SiteEditorView.vue'

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

const MediaPickerStub = defineComponent({
  name: 'MediaPickerDialog',
  props: {
    open: { type: Boolean, required: true },
    accept: { type: Array, required: true },
  },
  emits: ['select', 'close'],
  template: '<div v-if="open" data-media-picker-stub><button type="button" @click="$emit(\'close\')">关闭媒体选择</button></div>',
})

const mounted: VueWrapper[] = []

function successfulSave(request: SaveWorkspaceRequest<SiteWorkspaceDto>): VersionedDraft<SiteWorkspaceDto> {
  const version = request.expectedVersion + 1
  return {
    version,
    value: { ...request.workspace, version },
  }
}

async function mountEditor(options: {
  load?: () => Promise<VersionedDraft<SiteWorkspaceDto>>
  save?: (request: SaveWorkspaceRequest<SiteWorkspaceDto>) => Promise<VersionedDraft<SiteWorkspaceDto>>
  fixture?: VersionedDraft<SiteWorkspaceDto>
} = {}): Promise<{
  wrapper: VueWrapper
  load: ReturnType<typeof vi.fn>
  save: ReturnType<typeof vi.fn>
}> {
  const load = vi.fn(options.load ?? (async () => options.fixture ?? createSiteFixture()))
  const save = vi.fn(options.save ?? (async (request) => successfulSave(request)))
  const wrapper = mount(SiteEditorView, {
    props: {
      load,
      save,
    },
    global: {
      stubs: {
        MediaPickerDialog: MediaPickerStub,
        PublishPanel: true,
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, load, save }
}

function input(wrapper: VueWrapper, field: string) {
  if (field === 'monogram' || field === 'email') {
    return wrapper.get<HTMLInputElement>(`input[name="${field}"]`)
  }
  if (field === 'hero.copy.headline') {
    return wrapper.get<HTMLInputElement>('[data-section="hero-copy"] [data-field="headline"]')
  }
  return wrapper.get<HTMLInputElement | HTMLTextAreaElement>(`[data-field="${field}"]`)
}

describe('SiteEditorView', () => {
  beforeEach(() => {
    routeHooks.leaveGuards.splice(0)
    vi.restoreAllMocks()
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
    vi.useRealTimers()
  })

  it('exposes every SITE content group as explicit controls rather than JSON', async () => {
    const { wrapper } = await mountEditor()

    for (const heading of [
      '身份',
      'SEO',
      '无障碍',
      '导航',
      'Hero',
      '关于',
      '作品区',
      '路线图',
      '联系',
      '隐私',
      '社交链接',
      '双语简历',
    ]) {
      expect(wrapper.text()).toContain(heading)
    }

    expect(wrapper.find('textarea[data-json]').exists()).toBe(false)
    expect(input(wrapper, 'monogram').element.value).toBe('YJX')
    expect(input(wrapper, 'email').element.value).toBe('hello@yychainsaw.xyz')
    expect(input(wrapper, 'hero.objectPosition').element.value).toBe('50% 50%')
    expect(input(wrapper, 'hero.credit').element.value).toBe('易嘉轩')
    expect(input(wrapper, 'hero.sourceUrl').element.value).toBe('https://yychainsaw.xyz')
  })

  it('switches one typed form between locales while retaining both completion counts and edits', async () => {
    const { wrapper } = await mountEditor()
    const tabs = wrapper.findAll('button[role="tab"]')

    expect(tabs).toHaveLength(2)
    expect(tabs[0]!.text()).toMatch(/\d+\/\d+/)
    expect(tabs[1]!.text()).toMatch(/\d+\/\d+/)
    expect(input(wrapper, 'hero.copy.headline').element.value).toBe(
      '把系统做扎实，把体验做明亮。',
    )
    expect(input(wrapper, 'navigation.0.label').attributes('lang')).toBe('zh-CN')
    expect(input(wrapper, 'facts.0.label').attributes('lang')).toBe('zh-CN')

    await tabs[1]!.trigger('click')
    expect(input(wrapper, 'hero.copy.headline').element.value).toBe(
      'Solid systems, bright experiences.',
    )
    for (const field of [
      'navigation.0.label',
      'facts.0.label',
      'profileSkills.0.name',
      'roadmap.stages.0.period',
      'roadmap.stages.0.title',
      'roadmap.stages.0.outcomes.0',
    ]) {
      expect(input(wrapper, field).attributes('lang')).toBe('en')
    }
    expect(wrapper.find('[data-item-id] p[lang="en"]').exists()).toBe(true)
    await input(wrapper, 'hero.copy.headline').setValue('Build playful, reliable worlds.')

    await wrapper.findAll('button[role="tab"]')[0]!.trigger('click')
    expect(input(wrapper, 'hero.copy.headline').element.value).toBe(
      '把系统做扎实，把体验做明亮。',
    )
    await wrapper.findAll('button[role="tab"]')[1]!.trigger('click')
    expect(input(wrapper, 'hero.copy.headline').element.value).toBe(
      'Build playful, reliable worlds.',
    )
  })

  it('blocks locally invalid email and HTTP URLs, then renders authoritative 422 fields safely', async () => {
    const serverProblem = new ApiProblem({
      type: 'validation',
      title: '站点内容校验失败',
      status: 422,
      code: 'SITE_WORKSPACE_INVALID',
      traceId: 'trace-site-422',
      fieldErrors: {
        email: '<b>服务器邮箱错误</b>',
        'hero.sourceUrl': '服务器要求 HTTPS URL',
        'facts[0].copy.zh-CN.label': '服务器资料卡标签错误',
        'socialLinks[0].url': '服务器社交链接错误',
      },
    })
    const { wrapper, save } = await mountEditor({
      save: vi.fn().mockRejectedValue(serverProblem),
    })

    await input(wrapper, 'email').setValue('not-an-email')
    await input(wrapper, 'hero.sourceUrl').setValue('http://unsafe.example')
    await wrapper.get('[data-action="save"]').trigger('click')

    expect(save).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入有效的邮箱地址')
    expect(wrapper.text()).toContain('必须填写 HTTPS 来源链接')

    await input(wrapper, 'email').setValue('valid@example.com')
    await input(wrapper, 'hero.sourceUrl').setValue('https://safe.example')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(save).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('<b>服务器邮箱错误</b>')
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.text()).toContain('服务器要求 HTTPS URL')
    expect(wrapper.text()).toContain('trace-site-422')

    for (const field of ['facts.0.label', 'socialLinks.0.url']) {
      const control = input(wrapper, field)
      const descriptionId = control.attributes('aria-describedby')
      expect(control.attributes('aria-invalid')).toBe('true')
      expect(descriptionId).toBeTruthy()
      expect(wrapper.get(`[id="${descriptionId}"]`).text()).toContain('服务器')
    }

    await wrapper
      .get('[data-reorder="socialLinks"][data-index="0"][data-direction="down"]')
      .trigger('click')
    expect(input(wrapper, 'socialLinks.0.url').attributes('aria-invalid')).toBeUndefined()
    expect(wrapper.text()).not.toContain('服务器社交链接错误')
  })

  it('applies the same client validation to the 15-second autosave path', async () => {
    const { wrapper, save } = await mountEditor()
    vi.useFakeTimers()

    await input(wrapper, 'email').setValue('invalid-autosave-email')
    await vi.advanceTimersByTimeAsync(15_000)

    expect(save).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入有效的邮箱地址')
    expect(wrapper.get('[data-save-state]').text()).toContain('有未保存修改')

    await input(wrapper, 'email').setValue('recovered@example.com')
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(save).toHaveBeenCalledOnce()
  })

  it('does not announce an unsaved version as saved while the first load is pending', async () => {
    const pending = deferred<VersionedDraft<SiteWorkspaceDto>>()
    const { wrapper } = await mountEditor({ load: () => pending.promise })

    expect(wrapper.get('[data-save-state]').text()).toContain('正在加载')
    expect(wrapper.get('[data-save-state]').text()).not.toContain('已保存')

    pending.resolve(createSiteFixture())
    await flushPromises()
    expect(wrapper.get('[data-save-state]').text()).toContain('已保存')
  })

  it('reports loading honestly and disables the entire stale form during a conflict reload', async () => {
    const initial = createSiteFixture()
    const reloadedFixture = createSiteFixture()
    const reloaded: VersionedDraft<SiteWorkspaceDto> = {
      version: 12,
      value: { ...reloadedFixture.value, version: 12, monogram: 'SERVER' },
    }
    const pendingReload = deferred<VersionedDraft<SiteWorkspaceDto>>()
    const load = vi
      .fn()
      .mockResolvedValueOnce(initial)
      .mockReturnValueOnce(pendingReload.promise)
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '服务器版本已更新',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'trace-reload-lock',
    })
    const { wrapper } = await mountEditor({ load, save: vi.fn().mockRejectedValue(conflict) })

    await input(wrapper, 'monogram').setValue('STALE')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const reloadButton = wrapper.findAll('button').find((button) =>
      button.text().includes('重新载入服务器版本'),
    )
    expect(reloadButton).toBeDefined()
    await reloadButton!.trigger('click')
    const confirmButton = wrapper.findAll('button').find((button) =>
      button.text().includes('确认重新载入'),
    )
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(load).toHaveBeenCalledTimes(2)
    expect(wrapper.get('form fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.get('form').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[data-save-state]').text()).toContain('正在重新载入')

    pendingReload.resolve(reloaded)
    await flushPromises()
    expect(input(wrapper, 'monogram').element.value).toBe('SERVER')
    expect(wrapper.get('form fieldset').attributes('disabled')).toBeUndefined()
  })

  it('keeps a failed conflict reload visible with its own trace id', async () => {
    const conflict = new ApiProblem({
      type: 'conflict', title: '服务器版本已更新', status: 409,
      code: 'CONTENT_VERSION_CONFLICT', traceId: 'trace-conflict',
    })
    const loadFailure = new ApiProblem({
      type: 'upstream', title: '重新载入失败', status: 503,
      code: 'SITE_RELOAD_FAILED', traceId: 'trace-reload-failed',
    })
    const load = vi
      .fn()
      .mockResolvedValueOnce(createSiteFixture())
      .mockRejectedValueOnce(loadFailure)
    const { wrapper } = await mountEditor({ load, save: vi.fn().mockRejectedValue(conflict) })

    await input(wrapper, 'monogram').setValue('DIRTY')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) =>
      button.text().includes('重新载入服务器版本'),
    )!.trigger('click')
    await wrapper.findAll('button').find((button) =>
      button.text().includes('确认重新载入'),
    )!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('服务器版本已更新')
    expect(wrapper.text()).toContain('重新载入失败')
    expect(wrapper.text()).toContain('trace-reload-failed')
  })

  it('shows dirty and saving state, then enters a conflict-safe stopped state on 409', async () => {
    const pending = deferred<VersionedDraft<SiteWorkspaceDto>>()
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '服务器版本已更新',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'trace-site-409',
    })
    const save = vi
      .fn()
      .mockReturnValueOnce(pending.promise)
      .mockRejectedValueOnce(conflict)
    const { wrapper } = await mountEditor({ save })

    await input(wrapper, 'monogram').setValue('YI')
    expect(wrapper.get('[data-save-state]').text()).toContain('有未保存修改')

    await wrapper.get('[data-action="save"]').trigger('click')
    expect(wrapper.get('[data-save-state]').text()).toContain('正在保存')
    const firstRequest = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    pending.resolve(successfulSave(firstRequest))
    await flushPromises()
    expect(wrapper.get('[data-save-state]').text()).toContain('已保存')

    await input(wrapper, 'monogram').setValue('YJX2')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('服务器版本已更新')
    expect(wrapper.get('[role="alert"]').text()).toContain('trace-site-409')
    expect(wrapper.get('[data-save-state]').text()).toContain('版本冲突')
  })

  it('registers a route-leave confirmation only while the workspace is dirty', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper } = await mountEditor()

    expect(routeHooks.leaveGuards).toHaveLength(1)
    expect(routeHooks.leaveGuards[0]!()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()

    await input(wrapper, 'monogram').setValue('DIRTY')
    expect(routeHooks.leaveGuards[0]!()).toBe(false)
    expect(confirm).toHaveBeenCalledOnce()

    confirm.mockReturnValue(true)
    expect(routeHooks.leaveGuards[0]!()).toBe(true)
  })

  it('writes contiguous sortOrder values for every ordered SITE list before saving', async () => {
    const { wrapper, save } = await mountEditor()

    for (const list of [
      'navigation',
      'facts',
      'profileSkills',
      'roadmapStages',
      'socialLinks',
    ]) {
      await wrapper
        .get(`[data-reorder="${list}"][data-index="0"][data-direction="down"]`)
        .trigger('click')
    }
    const outcomeDown = wrapper
      .findAll('[data-reorder="roadmapOutcomes"][data-index="0"][data-direction="down"]')
      .find((button) => button.attributes('disabled') === undefined)
    expect(outcomeDown).toBeDefined()
    await outcomeDown!.trigger('click')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    const request = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect(request.workspace.navigation.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.navigation.map((item) => item.target)).toEqual(['roadmap', 'work'])
    expect(request.workspace.facts.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.profileSkills.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.socialLinks.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.socialLinks.map((item) => item.platform)).toEqual([
      'Bilibili',
      'GitHub',
    ])
    expect(request.workspace.roadmap.stages.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.roadmap.stages[1]!.outcomes.map((item) => item.sortOrder)).toEqual([0, 1])
    expect(request.workspace.roadmap.stages[1]!.outcomes.map((item) => item.id)).toEqual([
      '70000000-0000-0000-0000-000000000002',
      '70000000-0000-0000-0000-000000000001',
    ])
  })

  it('selects only compatible READY media for the hero and current locale resume', async () => {
    const { wrapper, save } = await mountEditor()
    const image: MediaAssetSummaryDto = {
      id: 'b0000000-0000-0000-0000-000000000001', kind: 'IMAGE', originalFilename: 'hero-new.jpg', mimeType: 'image/jpeg', status: 'READY', previewUrl: '/api/admin/media/b0000000-0000-0000-0000-000000000001/preview/thumbnail', width: 1920, height: 1080,
    }
    const pdf: MediaAssetSummaryDto = {
      id: 'b0000000-0000-0000-0000-000000000002', kind: 'PDF', originalFilename: 'resume-en.pdf', mimeType: 'application/pdf', status: 'READY', previewUrl: '/api/admin/media/b0000000-0000-0000-0000-000000000002/preview/original', width: null, height: null,
    }

    await wrapper.get('[data-media-target="hero"]').trigger('click')
    wrapper.getComponent(MediaPickerStub).vm.$emit('select', image)
    await flushPromises()
    await input(wrapper, 'hero.credit').setValue('易嘉轩')
    await input(wrapper, 'hero.sourceUrl').setValue('https://safe.example/hero-new')

    await wrapper.findAll('button[role="tab"]')[1]!.trigger('click')
    await wrapper.get('[data-media-target="resume"]').trigger('click')
    wrapper.getComponent(MediaPickerStub).vm.$emit('select', pdf)
    await flushPromises()

    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const request = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect(request.workspace.hero.mediaAssetId).toBe(image.id)
    expect(request.workspace.resumes.find((resume) => resume.locale === 'en')?.mediaAssetId).toBe(pdf.id)
  })

  it('rejects forged media events at the final write boundary', async () => {
    const fixture = createSiteFixture()
    const originalHeroId = fixture.value.hero.mediaAssetId
    const originalResumeId = fixture.value.resumes.find((resume) => resume.locale === 'zh-CN')!.mediaAssetId
    const { wrapper, save } = await mountEditor({ fixture })
    const forgedAssets: MediaAssetSummaryDto[] = [
      {
        id: '..', kind: 'IMAGE', originalFilename: 'escape.jpg', mimeType: 'image/jpeg', status: 'READY', previewUrl: null, width: 1, height: 1,
      },
      {
        id: 'b0000000-0000-0000-0000-000000000010', kind: 'IMAGE', originalFilename: 'fake.jpg', mimeType: 'application/pdf', status: 'READY', previewUrl: null, width: 1, height: 1,
      },
      {
        id: 'b0000000-0000-0000-0000-000000000011', kind: 'IMAGE', originalFilename: 'pending.jpg', mimeType: 'image/jpeg', status: 'PROCESSING', previewUrl: null, width: 1, height: 1,
      },
    ]

    for (const forged of forgedAssets) {
      await wrapper.get('[data-media-target="hero"]').trigger('click')
      wrapper.getComponent(MediaPickerStub).vm.$emit('select', forged)
      await flushPromises()
      wrapper.getComponent(MediaPickerStub).vm.$emit('close')
    }

    await wrapper.get('[data-media-target="resume"]').trigger('click')
    wrapper.getComponent(MediaPickerStub).vm.$emit('select', {
      id: 'b0000000-0000-0000-0000-000000000012',
      kind: 'PDF',
      originalFilename: 'fake.pdf',
      mimeType: 'image/png',
      status: 'READY',
      previewUrl: null,
      width: null,
      height: null,
    } satisfies MediaAssetSummaryDto)
    await flushPromises()
    wrapper.getComponent(MediaPickerStub).vm.$emit('close')

    await input(wrapper, 'monogram').setValue('SAFE')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const request = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect(request.workspace.hero.mediaAssetId).toBe(originalHeroId)
    expect(request.workspace.resumes.find((resume) => resume.locale === 'zh-CN')?.mediaAssetId).toBe(originalResumeId)
  })

  it('clears stale attribution whenever a different Hero asset replaces the current image', async () => {
    const { wrapper, save } = await mountEditor()
    const replacement: MediaAssetSummaryDto = {
      id: 'b0000000-0000-0000-0000-000000000013', kind: 'IMAGE', originalFilename: 'replacement.png', mimeType: 'image/png', status: 'READY', previewUrl: null, width: 1920, height: 1080,
    }

    expect(input(wrapper, 'hero.credit').element.value).not.toBe('')
    expect(input(wrapper, 'hero.sourceUrl').element.value).not.toBe('')
    await wrapper.get('[data-media-target="hero"]').trigger('click')
    wrapper.getComponent(MediaPickerStub).vm.$emit('select', replacement)
    await flushPromises()

    expect(input(wrapper, 'hero.objectPosition').element.value).toBe('50% 50%')
    expect(input(wrapper, 'hero.credit').element.value).toBe('')
    expect(input(wrapper, 'hero.sourceUrl').element.value).toBe('')
    await wrapper.get('[data-action="save"]').trigger('click')
    expect(save).not.toHaveBeenCalled()
  })

  it('keeps a media selection made while an autosave is settling as a new draft revision', async () => {
    vi.useFakeTimers()
    const firstSave = deferred<VersionedDraft<SiteWorkspaceDto>>()
    const save = vi
      .fn()
      .mockReturnValueOnce(firstSave.promise)
      .mockImplementationOnce(async (request) => successfulSave(request))
    const { wrapper } = await mountEditor({ save })
    const replacement: MediaAssetSummaryDto = {
      id: 'b0000000-0000-0000-0000-000000000014', kind: 'IMAGE', originalFilename: 'during-save.jpg', mimeType: 'image/jpeg', status: 'READY', previewUrl: null, width: 1600, height: 900,
    }

    await input(wrapper, 'monogram').setValue('AUTOSAVE')
    await wrapper.get('[data-media-target="hero"]').trigger('click')
    await vi.advanceTimersByTimeAsync(15_000)
    expect(save).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-save-state]').text()).toContain('正在保存')

    wrapper.getComponent(MediaPickerStub).vm.$emit('select', replacement)
    await flushPromises()
    const firstRequest = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    firstSave.resolve(successfulSave(firstRequest))
    await flushPromises()

    expect(wrapper.text()).toContain(replacement.id)
    expect(wrapper.get('[data-save-state]').text()).toContain('有未保存修改')
    await input(wrapper, 'hero.credit').setValue('新署名')
    await input(wrapper, 'hero.sourceUrl').setValue('https://safe.example/during-save')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const secondRequest = save.mock.calls[1]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect(secondRequest.workspace.hero.mediaAssetId).toBe(replacement.id)
    expect(secondRequest.expectedVersion).toBe(firstRequest.expectedVersion + 1)
  })

  it('keeps the backend hero-media tuple atomic when selecting or removing an image', async () => {
    const fixture = createSiteFixture()
    fixture.value.hero.mediaAssetId = null
    fixture.value.hero.objectPosition = null
    fixture.value.hero.credit = null
    fixture.value.hero.sourceUrl = null
    const { wrapper, save } = await mountEditor({ fixture })
    const image: MediaAssetSummaryDto = {
      id: 'b0000000-0000-0000-0000-000000000003', kind: 'IMAGE', originalFilename: 'atomic.jpg', mimeType: 'image/jpeg', status: 'READY', previewUrl: '/api/admin/media/b0000000-0000-0000-0000-000000000003/preview/w640', width: 1600, height: 900,
    }

    await wrapper.get('[data-media-target="hero"]').trigger('click')
    wrapper.getComponent(MediaPickerStub).vm.$emit('select', image)
    await flushPromises()
    expect(input(wrapper, 'hero.objectPosition').element.value).toBe('50% 50%')

    await wrapper.get('[data-action="save"]').trigger('click')
    expect(save).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('选择图片后必须填写图片署名')
    expect(wrapper.text()).toContain('选择图片后必须填写 HTTPS 来源链接')

    await input(wrapper, 'hero.credit').setValue('易嘉轩')
    await input(wrapper, 'hero.sourceUrl').setValue('https://safe.example/hero')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const request = save.mock.calls[0]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect([
      request.workspace.hero.mediaAssetId,
      request.workspace.hero.objectPosition,
      request.workspace.hero.credit,
      request.workspace.hero.sourceUrl,
    ]).not.toContain(null)

    await wrapper.get('[data-action="clear-hero-media"]').trigger('click')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()
    const cleared = save.mock.calls[1]![0] as SaveWorkspaceRequest<SiteWorkspaceDto>
    expect([
      cleared.workspace.hero.mediaAssetId,
      cleared.workspace.hero.objectPosition,
      cleared.workspace.hero.credit,
      cleared.workspace.hero.sourceUrl,
    ]).toEqual([null, null, null, null])
  })
})
