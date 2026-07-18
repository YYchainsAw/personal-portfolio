import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import type { TranslationStatus } from '@/types/content'
import type {
  PreviewTokenRequest,
  PreviewTokenResponse,
  PublicationResultDto,
  PublishTarget,
} from '@/types/publishing'
import PublishPanel from './PublishPanel.vue'

const SITE_ID = '00000000-0000-0000-0000-000000000001'
const PROJECT_ID = '00000000-0000-0000-0000-000000000111'

const SITE_TARGET: PublishTarget = Object.freeze({
  aggregateType: 'SITE',
  aggregateId: SITE_ID,
  expectedWorkspaceVersion: 4,
  expectedPublicationVersion: 2,
})

const PROJECT_TARGET: PublishTarget = Object.freeze({
  aggregateType: 'PROJECT',
  aggregateId: PROJECT_ID,
  expectedWorkspaceVersion: 7,
  expectedProjectPublicationVersion: 3,
  expectedCatalogVersion: 9,
})

const COMPLETE: TranslationStatus = Object.freeze({
  'zh-CN': Object.freeze({ complete: 5, total: 5 }),
  en: Object.freeze({ complete: 5, total: 5 }),
})

const INCOMPLETE: TranslationStatus = Object.freeze({
  'zh-CN': Object.freeze({ complete: 5, total: 5 }),
  en: Object.freeze({ complete: 4, total: 5 }),
})

const VALID_PREVIEW: PreviewTokenResponse = Object.freeze({
  token: 'header.payload-signature',
  expiresAt: '2099-07-18T12:00:00Z',
})

const PUBLICATION_RESULT: PublicationResultDto = Object.freeze({
  revisionId: '10000000-0000-4000-8000-000000000001',
  aggregateVersion: 3,
  catalogRevisionId: null,
  catalogVersion: null,
  checksum: 'a'.repeat(64),
})

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T | PromiseLike<T>) => void
  readonly reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

function apiProblem(
  status: number,
  code: string,
  fieldErrors?: Readonly<Record<string, string>>,
): ApiProblem {
  const problem = new ApiProblem({
    type: `https://yychainsaw.xyz/problems/${code.toLowerCase()}`,
    title: '<img src=x onerror=alert(1)>发布校验失败',
    status,
    code,
    traceId: `trace-${status}`,
    ...(fieldErrors === undefined ? {} : { fieldErrors }),
  })
  problem.stack = 'INTERNAL_STACK SQL SELECT administrator_secret'
  return problem
}

function mountPanel(
  overrides: Partial<{
    target: PublishTarget
    locale: 'zh-CN' | 'en'
    completion: TranslationStatus
    disabled: boolean
    createPreview: (request: PreviewTokenRequest) => Promise<PreviewTokenResponse>
    preflightPreview: (token: string) => Promise<void>
    previewUrl: (token: string) => string
    publishTarget: (target: PublishTarget) => Promise<PublicationResultDto>
  }> = {},
): VueWrapper {
  return mount(PublishPanel, {
    props: {
      target: overrides.target ?? SITE_TARGET,
      locale: overrides.locale ?? 'zh-CN',
      completion: overrides.completion ?? COMPLETE,
      disabled: overrides.disabled ?? false,
      createPreview:
        overrides.createPreview ?? vi.fn(async (_request: PreviewTokenRequest) => VALID_PREVIEW),
      preflightPreview: overrides.preflightPreview ?? vi.fn(async () => undefined),
      previewUrl:
        overrides.previewUrl ??
        vi.fn((token: string) => `/api/admin/publishing/previews/${token}`),
      publishTarget:
        overrides.publishTarget ?? vi.fn(async () => PUBLICATION_RESULT),
    },
  })
}

beforeEach(() => {
  vi.spyOn(window, 'open').mockReturnValue({} as Window)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('PublishPanel', () => {
  it('shows both completion counts, blocks incomplete publication, and keeps structural preview available', async () => {
    const publishTarget = vi.fn(async () => PUBLICATION_RESULT)
    const createPreview = vi.fn(async () => VALID_PREVIEW)
    const wrapper = mountPanel({
      locale: 'en',
      completion: INCOMPLETE,
      createPreview,
      publishTarget,
    })

    expect(wrapper.get('[data-locale-completion="zh-CN"]').text()).toContain('5/5')
    const english = wrapper.get('[data-locale-completion="en"]')
    expect(english.text()).toContain('4/5')
    expect(english.attributes('aria-current')).toBe('true')
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="preview"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('结构预览仍然可用')

    await wrapper.get('[data-action="publish"]').trigger('click')
    expect(window.confirm).not.toHaveBeenCalled()
    expect(publishTarget).not.toHaveBeenCalled()

    await wrapper.get('[data-action="preview"]').trigger('click')
    await flushPromises()
    expect(createPreview).toHaveBeenCalledOnce()
  })

  it('creates an exact three-field token request, preflights it, and opens only the same URL safely', async () => {
    const calls: string[] = []
    const createPreview = vi.fn(async (_request: PreviewTokenRequest) => {
      calls.push('create')
      return VALID_PREVIEW
    })
    const preflightPreview = vi.fn(async (token: string) => {
      calls.push(`preflight:${token}`)
    })
    const previewUrl = vi.fn((token: string) => {
      calls.push(`url:${token}`)
      return `/api/admin/publishing/previews/${token}`
    })
    const wrapper = mountPanel({
      target: PROJECT_TARGET,
      locale: 'en',
      completion: INCOMPLETE,
      createPreview,
      preflightPreview,
      previewUrl,
    })

    wrapper.get('[data-action="preview"]').element.dispatchEvent(new MouseEvent('click'))
    expect(wrapper.emitted('busy-change')).toEqual([[true]])
    await flushPromises()

    expect(createPreview).toHaveBeenCalledWith({
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_ID,
      workspaceVersion: 7,
    })
    expect(Object.keys(createPreview.mock.calls[0]![0])).toEqual([
      'aggregateType',
      'aggregateId',
      'workspaceVersion',
    ])
    expect(createPreview.mock.calls[0]![0]).not.toHaveProperty('locale')
    expect(preflightPreview).toHaveBeenCalledWith(VALID_PREVIEW.token)
    expect(previewUrl).toHaveBeenCalledWith(VALID_PREVIEW.token)
    expect(calls).toEqual([
      'create',
      `preflight:${VALID_PREVIEW.token}`,
      `url:${VALID_PREVIEW.token}`,
    ])
    expect(window.open).toHaveBeenCalledWith(
      `/api/admin/publishing/previews/${VALID_PREVIEW.token}`,
      '_blank',
      'noopener,noreferrer',
    )
    expect(wrapper.emitted('busy-change')).toEqual([[true], [false]])
    expect(wrapper.get('[role="status"]').text()).toContain('已请求打开预览')
  })

  it('rejects an already-expired token before preflight and one that expires during preflight', async () => {
    const preflightExpired = vi.fn(async () => undefined)
    const alreadyExpired = mountPanel({
      createPreview: vi.fn(async () => ({
        token: 'expired-token',
        expiresAt: '2000-01-01T00:00:00Z',
      })),
      preflightPreview: preflightExpired,
    })

    await alreadyExpired.get('[data-action="preview"]').trigger('click')
    await flushPromises()
    expect(preflightExpired).not.toHaveBeenCalled()
    expect(window.open).not.toHaveBeenCalled()
    expect(alreadyExpired.get('[data-preview-error]').text()).toContain('已过期')

    vi.mocked(window.open).mockClear()
    const expiresAt = '2030-01-01T00:00:02.000Z'
    const firstCheck = Date.parse('2030-01-01T00:00:01.000Z')
    const secondCheck = Date.parse('2030-01-01T00:00:03.000Z')
    let currentTime = firstCheck
    vi.spyOn(Date, 'now').mockImplementation(() => currentTime)
    const preflight = vi.fn(async () => {
      currentTime = secondCheck
    })
    const expiresDuringPreflight = mountPanel({
      createPreview: vi.fn(async () => ({ token: 'short-token', expiresAt })),
      preflightPreview: preflight,
    })

    await expiresDuringPreflight.get('[data-action="preview"]').trigger('click')
    await flushPromises()
    expect(preflight).toHaveBeenCalledOnce()
    expect(window.open).not.toHaveBeenCalled()
    expect(expiresDuringPreflight.get('[data-preview-error]').text()).toContain('已过期')
  })

  it('keeps an authoritative preflight 422 in the panel and renders every escaped field path', async () => {
    const problem = apiProblem(422, 'PUBLICATION_VALIDATION_FAILED', {
      'translations.en.title': '<b>English title required</b>',
      'blocks[2].payload.url': '必须是 HTTPS 地址',
      '<script>unsafe.path</script>': '<img src=x onerror=alert(2)>',
    })
    const wrapper = mountPanel({
      preflightPreview: vi.fn().mockRejectedValue(problem),
    })

    await wrapper.get('[data-action="preview"]').trigger('click')
    await flushPromises()

    const alert = wrapper.get('[data-publication-error]')
    expect(alert.text()).toContain(problem.body.title)
    expect(alert.text()).toContain(problem.body.traceId)
    for (const [path, message] of Object.entries(problem.body.fieldErrors!)) {
      expect(alert.text()).toContain(path)
      expect(alert.text()).toContain(message)
    }
    expect(alert.find('img').exists()).toBe(false)
    expect(alert.find('script').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('INTERNAL_STACK')
    expect(wrapper.text()).not.toContain('administrator_secret')
    expect(window.open).not.toHaveBeenCalled()
  })

  it('publishes only after confirmation, dispatches the exact target once, and emits the result', async () => {
    const publishTarget = vi.fn(async () => PUBLICATION_RESULT)
    const wrapper = mountPanel({ target: PROJECT_TARGET, publishTarget })

    await wrapper.get('[data-action="publish"]').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(publishTarget).toHaveBeenCalledOnce()
    expect(publishTarget).toHaveBeenCalledWith(PROJECT_TARGET)
    expect(wrapper.emitted('published')).toEqual([[PUBLICATION_RESULT]])
    expect(wrapper.emitted('busy-change')).toEqual([[true], [false]])
    expect(wrapper.get('[role="status"]').text()).toContain('发布成功')
  })

  it('does nothing when publication confirmation is cancelled', async () => {
    vi.mocked(window.confirm).mockReturnValue(false)
    const publishTarget = vi.fn(async () => PUBLICATION_RESULT)
    const wrapper = mountPanel({ publishTarget })

    await wrapper.get('[data-action="publish"]').trigger('click')

    expect(publishTarget).not.toHaveBeenCalled()
    expect(wrapper.emitted('busy-change')).toBeUndefined()
    expect(wrapper.emitted('published')).toBeUndefined()
  })

  it('renders every authoritative publish 422 path without clearing or retrying the action', async () => {
    const problem = apiProblem(422, 'PUBLICATION_VALIDATION_FAILED', {
      'hero.copy.zh-CN.headline': '中文标题不能为空',
      'hero.copy.en.headline': 'English headline is required',
      'media[0].translations.en.altText': 'English alt text is required',
    })
    const publishTarget = vi.fn().mockRejectedValue(problem)
    const wrapper = mountPanel({ publishTarget })

    await wrapper.get('[data-action="publish"]').trigger('click')
    await flushPromises()

    expect(publishTarget).toHaveBeenCalledOnce()
    const alert = wrapper.get('[data-publication-error]')
    expect(alert.text()).toContain(problem.body.title)
    expect(alert.text()).toContain('trace-422')
    expect(alert.findAll('li')).toHaveLength(3)
    for (const path of Object.keys(problem.body.fieldErrors!)) {
      expect(alert.text()).toContain(path)
    }
    expect(wrapper.emitted('published')).toBeUndefined()
    expect(wrapper.emitted('busy-change')).toEqual([[true], [false]])
  })

  it('keeps publication 409 separate, safe, and free of any overwrite action', async () => {
    const problem = apiProblem(409, 'PUBLICATION_VERSION_CONFLICT')
    const publishTarget = vi.fn().mockRejectedValue(problem)
    const wrapper = mountPanel({ publishTarget })

    await wrapper.get('[data-action="publish"]').trigger('click')
    await flushPromises()

    const conflict = wrapper.get('[data-publication-conflict]')
    expect(conflict.text()).toContain(problem.body.title)
    expect(conflict.text()).toContain(problem.body.traceId)
    expect(conflict.find('img').exists()).toBe(false)
    expect(conflict.text()).not.toContain('INTERNAL_STACK')
    expect(wrapper.find('[data-publication-error]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('强制覆盖')
    expect(wrapper.text()).not.toContain('覆盖服务器版本')
    expect(publishTarget).toHaveBeenCalledOnce()

    expect(wrapper.get('[data-action="preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-action="publish"]').trigger('click')
    await wrapper.get('[data-action="preview"]').trigger('click')
    expect(publishTarget).toHaveBeenCalledOnce()

    const reload = wrapper.get<HTMLButtonElement>('[data-action="reload-publication-conflict"]')
    await reload.trigger('click')
    await reload.trigger('click')
    expect(wrapper.emitted('reload-requested')).toEqual([[]])
    expect(reload.element.disabled).toBe(true)
  })

  it('locks both actions immediately so repeated preview and publish activation make one request', async () => {
    const previewGate = deferred<PreviewTokenResponse>()
    const createPreview = vi.fn(() => previewGate.promise)
    const previewWrapper = mountPanel({ createPreview })
    const previewButton = previewWrapper.get('[data-action="preview"]')

    previewButton.element.dispatchEvent(new MouseEvent('click'))
    previewButton.element.dispatchEvent(new MouseEvent('click'))
    await nextTick()

    expect(createPreview).toHaveBeenCalledOnce()
    expect(previewWrapper.attributes('aria-busy')).toBe('true')
    expect(previewWrapper.get('[data-action="preview"]').attributes('disabled')).toBeDefined()
    expect(previewWrapper.get('[data-action="publish"]').attributes('disabled')).toBeDefined()

    previewGate.resolve(VALID_PREVIEW)
    await flushPromises()

    const publishGate = deferred<PublicationResultDto>()
    const publishTarget = vi.fn(() => publishGate.promise)
    const publishWrapper = mountPanel({ publishTarget })
    const publishButton = publishWrapper.get('[data-action="publish"]')
    publishButton.element.dispatchEvent(new MouseEvent('click'))
    publishButton.element.dispatchEvent(new MouseEvent('click'))
    await nextTick()

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(publishTarget).toHaveBeenCalledOnce()
    expect(publishWrapper.attributes('aria-busy')).toBe('true')

    publishGate.resolve(PUBLICATION_RESULT)
    await flushPromises()
    expect(publishWrapper.emitted('published')).toEqual([[PUBLICATION_RESULT]])
  })

  it('does not open or emit a late result after target replacement', async () => {
    const previewGate = deferred<PreviewTokenResponse>()
    const preflightPreview = vi.fn(async () => undefined)
    const previewWrapper = mountPanel({
      createPreview: vi.fn(() => previewGate.promise),
      preflightPreview,
    })

    previewWrapper.get('[data-action="preview"]').element.dispatchEvent(new MouseEvent('click'))
    await previewWrapper.setProps({ target: PROJECT_TARGET })
    expect(previewWrapper.emitted('busy-change')).toEqual([[true], [false]])

    previewGate.resolve(VALID_PREVIEW)
    await flushPromises()
    expect(preflightPreview).not.toHaveBeenCalled()
    expect(window.open).not.toHaveBeenCalled()
    expect(previewWrapper.emitted('busy-change')).toEqual([[true], [false]])

    const publishGate = deferred<PublicationResultDto>()
    const publishWrapper = mountPanel({
      target: SITE_TARGET,
      publishTarget: vi.fn(() => publishGate.promise),
    })
    publishWrapper.get('[data-action="publish"]').element.dispatchEvent(new MouseEvent('click'))
    await publishWrapper.setProps({ target: PROJECT_TARGET })
    publishGate.resolve(PUBLICATION_RESULT)
    await flushPromises()

    expect(publishWrapper.emitted('published')).toBeUndefined()
    expect(publishWrapper.emitted('busy-change')).toEqual([[true], [false]])
  })

  it('does not open a late preflight after unmount and never emits from its settlement', async () => {
    const preflightGate = deferred<void>()
    const preflightPreview = vi.fn(() => preflightGate.promise)
    const wrapper = mountPanel({ preflightPreview })

    wrapper.get('[data-action="preview"]').element.dispatchEvent(new MouseEvent('click'))
    await flushPromises()
    expect(preflightPreview).toHaveBeenCalledOnce()
    expect(wrapper.emitted('busy-change')).toEqual([[true]])
    const busyEvents = wrapper.emitted('busy-change')!

    wrapper.unmount()
    preflightGate.resolve(undefined)
    await flushPromises()

    expect(window.open).not.toHaveBeenCalled()
    expect(busyEvents).toEqual([[true]])
    expect(wrapper.emitted('published')).toBeUndefined()
  })

  it('treats the null noopener return as neutral without exposing the token and refuses a non-same-origin URL', async () => {
    vi.mocked(window.open).mockReturnValue(null)
    const blocked = mountPanel()

    await blocked.get('[data-action="preview"]').trigger('click')
    await flushPromises()

    expect(window.open).toHaveBeenCalledWith(
      '/api/admin/publishing/previews/header.payload-signature',
      '_blank',
      'noopener,noreferrer',
    )
    expect(blocked.find('[data-preview-error]').exists()).toBe(false)
    expect(blocked.get('[data-publish-status]').text()).toContain('已请求打开预览')
    expect(blocked.text()).not.toContain(VALID_PREVIEW.token)

    vi.mocked(window.open).mockClear()
    const unsafe = mountPanel({
      previewUrl: vi.fn(() => 'https://attacker.example/steal-preview'),
    })
    await unsafe.get('[data-action="preview"]').trigger('click')
    await flushPromises()

    expect(window.open).not.toHaveBeenCalled()
    expect(unsafe.get('[data-preview-error]').text()).toContain('已阻止不安全的预览地址')
  })

  it('exposes labelled, live, independently disabled controls with unique panel ids', () => {
    const wrapper = mount({
      components: { PublishPanel },
      setup: () => ({
        target: SITE_TARGET,
        completion: COMPLETE,
        createPreview: vi.fn(async () => VALID_PREVIEW),
        preflightPreview: vi.fn(async () => undefined),
        previewUrl: vi.fn(() => '/api/admin/publishing/previews/token'),
        publishTarget: vi.fn(async () => PUBLICATION_RESULT),
      }),
      template: `
        <PublishPanel
          :target="target"
          locale="zh-CN"
          :completion="completion"
          disabled
          :create-preview="createPreview"
          :preflight-preview="preflightPreview"
          :preview-url="previewUrl"
          :publish-target="publishTarget"
        />
        <PublishPanel
          :target="target"
          locale="en"
          :completion="completion"
          :create-preview="createPreview"
          :preflight-preview="preflightPreview"
          :preview-url="previewUrl"
          :publish-target="publishTarget"
        />
      `,
    })

    const panels = wrapper.findAll('[data-publish-panel]')
    const headings = wrapper.findAll('h2')
    expect(panels).toHaveLength(2)
    expect(headings).toHaveLength(2)
    expect(headings[0]!.attributes('id')).not.toBe(headings[1]!.attributes('id'))
    expect(panels[0]!.attributes('aria-labelledby')).toBe(headings[0]!.attributes('id'))
    expect(panels[1]!.attributes('aria-labelledby')).toBe(headings[1]!.attributes('id'))
    expect(panels[0]!.get('[role="status"]').attributes('aria-live')).toBe('polite')
    expect(panels[0]!.get('legend').text()).toContain('预览与发布操作')
    expect(panels[0]!.get('[data-action="preview"]').attributes('disabled')).toBeDefined()
    expect(panels[0]!.get('[data-action="publish"]').attributes('disabled')).toBeDefined()
    expect(panels[0]!.get('[data-action="preview"]').attributes('aria-describedby')).toBeTruthy()
  })
})
