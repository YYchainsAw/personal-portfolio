import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import type {
  EmailDeliveryStatus,
  MessageDetailDto,
  MessageListOptions,
  MessagePageDto,
  MessageStatus,
  MessageSummaryDto,
  UpdateMessageStatusRequest,
} from '@/types/operations'

const routeHooks = vi.hoisted(() => ({ leaveGuards: [] as Array<() => boolean> }))

vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (guard: () => boolean) => routeHooks.leaveGuards.push(guard),
}))

import MessagesView from './MessagesView.vue'

const uuid = (value: number): string =>
  `20000000-0000-0000-0000-${value.toString().padStart(12, '0')}`

function summary(
  id: string,
  overrides: Partial<MessageSummaryDto> = {},
): MessageSummaryDto {
  return {
    id,
    visitorName: '访客姓名',
    visitorEmail: 'visitor@example.com',
    subject: '作品合作咨询',
    status: 'UNREAD',
    emailStatus: 'FAILED',
    createdAt: '2026-07-14T00:00:00Z',
    version: 3,
    ...overrides,
  }
}

function detail(
  id: string,
  overrides: Partial<MessageDetailDto> = {},
): MessageDetailDto {
  const item = summary(id, overrides)
  return {
    id,
    visitorName: item.visitorName,
    visitorEmail: item.visitorEmail,
    subject: item.subject,
    body: '希望讨论游戏开发合作。',
    status: item.status,
    email: {
      status: item.emailStatus,
      attempts: 2,
      nextAttemptAt: '2026-07-14T00:05:00Z',
      sentAt: null,
      updatedAt: '2026-07-14T00:02:00Z',
      errorCategory: 'SMTP_CONNECTION_FAILED',
    },
    privacyAcceptedAt: '2026-07-13T23:59:00Z',
    createdAt: item.createdAt,
    updatedAt: '2026-07-14T00:02:00Z',
    version: item.version,
    ...overrides,
  }
}

function withEmail(
  message: MessageDetailDto,
  status: EmailDeliveryStatus,
  overrides: Partial<MessageDetailDto['email']> = {},
): MessageDetailDto {
  return {
    ...message,
    email: {
      ...message.email,
      status,
      sentAt: status === 'SENT' ? '2026-07-14T00:03:00Z' : null,
      ...overrides,
    },
  }
}

function page(
  items: MessageSummaryDto[] = [],
  nextCursor: string | null = null,
): MessagePageDto {
  return { items, nextCursor }
}

function problem(status: number, code: string, title = '请求失败'): ApiProblem {
  return new ApiProblem({
    type: status === 0 ? 'network_error' : 'message_error',
    title,
    status,
    code,
    traceId: 'trace-safe',
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

interface MountOverrides {
  list?: (options: Readonly<MessageListOptions>) => Promise<MessagePageDto>
  detail?: (id: string) => Promise<MessageDetailDto>
  updateStatus?: (
    id: string,
    request: Readonly<UpdateMessageStatusRequest>,
  ) => Promise<MessageDetailDto>
  retryEmail?: (id: string) => Promise<void>
  deleteMessage?: (id: string) => Promise<void>
}

function mountInbox(overrides: MountOverrides = {}): VueWrapper {
  return mount(MessagesView, {
    attachTo: document.body,
    props: {
      list: overrides.list ?? vi.fn().mockResolvedValue(page()),
      detail: overrides.detail ?? vi.fn().mockResolvedValue(detail(uuid(1))),
      updateStatus: overrides.updateStatus ?? vi.fn(),
      retryEmail: overrides.retryEmail ?? vi.fn(),
      deleteMessage: overrides.deleteMessage ?? vi.fn(),
    },
  })
}

async function openMessage(wrapper: VueWrapper, id: string): Promise<void> {
  await wrapper.get(`[data-message-id="${id}"]`).trigger('click')
  await flushPromises()
}

describe('MessagesView', () => {
  beforeEach(() => {
    routeHooks.leaveGuards.length = 0
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('renders loading, retryable error, and empty states without exposing raw failures', async () => {
    const gate = deferred<MessagePageDto>()
    const list = vi
      .fn()
      .mockReturnValueOnce(gate.promise)
      .mockRejectedValueOnce(problem(503, 'MESSAGE_LIST_UNAVAILABLE', '暂时无法加载留言'))
      .mockResolvedValueOnce(page())
    const wrapper = mountInbox({ list })
    await Promise.resolve()

    expect(wrapper.find('[data-state="messages-loading"]').exists()).toBe(true)
    gate.resolve(page())
    await flushPromises()
    expect(wrapper.find('[data-state="messages-empty"]').exists()).toBe(true)

    await wrapper.get('[data-filter="status"]').setValue('READ')
    await flushPromises()
    expect(wrapper.get('[data-state="messages-error"]').text()).toContain('暂时无法加载留言')
    expect(wrapper.text()).not.toContain('MESSAGE_LIST_UNAVAILABLE')

    await wrapper.get('[data-state="messages-error"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-state="messages-empty"]').exists()).toBe(true)
    expect(list).toHaveBeenLastCalledWith({ status: 'READ' })
  })

  it('appends cursor pages with UUID deduplication and monotonic versions, then resets on status change', async () => {
    const firstId = uuid(2)
    const secondId = uuid(3)
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([summary(firstId, { version: 5 })], 'cursor_1'))
      .mockResolvedValueOnce(
        page([
          summary(firstId, { subject: '迟到旧版本', version: 4 }),
          summary(secondId, { subject: '第二条留言', version: 1 }),
        ]),
      )
      .mockResolvedValueOnce(
        page([summary(secondId, { status: 'READ', subject: '已读留言', version: 2 })]),
      )
    const wrapper = mountInbox({ list })
    await flushPromises()

    await wrapper.get('[data-action="load-more"]').trigger('click')
    await flushPromises()
    expect(list).toHaveBeenNthCalledWith(2, { cursor: 'cursor_1' })
    expect(wrapper.findAll('[data-message-id]')).toHaveLength(2)
    expect(wrapper.get(`[data-message-id="${firstId}"]`).text()).not.toContain('迟到旧版本')
    expect(wrapper.get(`[data-message-id="${firstId}"]`).attributes('data-version')).toBe('5')

    await wrapper.get('[data-filter="status"]').setValue('READ')
    await flushPromises()
    expect(list).toHaveBeenLastCalledWith({ status: 'READ' })
    expect(wrapper.find(`[data-message-id="${firstId}"]`).exists()).toBe(false)
    expect(wrapper.get(`[data-message-id="${secondId}"]`).text()).toContain('已读留言')
  })

  it('lets a filter supersede a pending append without leaving the new cursor locked', async () => {
    const appendGate = deferred<MessagePageDto>()
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([summary(uuid(30))], 'cursor_old'))
      .mockReturnValueOnce(appendGate.promise)
      .mockResolvedValueOnce(
        page([summary(uuid(31), { status: 'READ' })], 'cursor_new'),
      )
      .mockResolvedValueOnce(page([summary(uuid(32), { status: 'READ' })]))
    const wrapper = mountInbox({ list })
    await flushPromises()

    await wrapper.get('[data-action="load-more"]').trigger('click')
    await wrapper.get('[data-filter="status"]').setValue('READ')
    await flushPromises()
    appendGate.resolve(page([summary(uuid(33), { subject: '迟到游标页' })]))
    await flushPromises()

    expect(wrapper.text()).not.toContain('迟到游标页')
    expect(wrapper.get('[data-action="load-more"]').attributes()).not.toHaveProperty('disabled')
    await wrapper.get('[data-action="load-more"]').trigger('click')
    await flushPromises()
    expect(list).toHaveBeenLastCalledWith({ status: 'READ', cursor: 'cursor_new' })
  })

  it('ignores late list and detail responses after filters and selections move on', async () => {
    const listGate = deferred<MessagePageDto>()
    const firstDetailGate = deferred<MessageDetailDto>()
    const firstId = uuid(4)
    const secondId = uuid(5)
    const list = vi
      .fn()
      .mockReturnValueOnce(listGate.promise)
      .mockResolvedValueOnce(page([summary(firstId, { status: 'READ' }), summary(secondId, { status: 'READ' })]))
    const getDetail = vi
      .fn()
      .mockReturnValueOnce(firstDetailGate.promise)
      .mockResolvedValueOnce(detail(secondId, { subject: '第二条详情', status: 'READ' }))
    const wrapper = mountInbox({ list, detail: getDetail })

    await wrapper.get('[data-filter="status"]').setValue('READ')
    await flushPromises()
    listGate.resolve(page([summary(uuid(99), { subject: '迟到列表' })]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('迟到列表')

    await wrapper.get(`[data-message-id="${firstId}"]`).trigger('click')
    await wrapper.get(`[data-message-id="${secondId}"]`).trigger('click')
    await flushPromises()
    firstDetailGate.resolve(detail(firstId, { subject: '迟到详情', status: 'READ' }))
    await flushPromises()
    expect(wrapper.get('[data-message-detail]').attributes('data-message-id')).toBe(secondId)
    expect(wrapper.text()).toContain('第二条详情')
    expect(wrapper.text()).not.toContain('迟到详情')
  })

  it('renders subject and body only as text, exposes all six safe email fields, and restores focus', async () => {
    const id = uuid(6)
    const unsafe = detail(id, {
      subject: '<b>不要解释为 HTML</b>',
      body: '<script>window.__pii = true</script>正文',
    })
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id, { subject: unsafe.subject })])),
      detail: vi.fn().mockResolvedValue(unsafe),
    })
    await flushPromises()
    const opener = wrapper.get(`[data-message-id="${id}"]`)
    ;(opener.element as HTMLElement).focus()
    await openMessage(wrapper, id)

    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>window.__pii = true</script>正文')
    expect(document.activeElement).toBe(wrapper.get('[data-detail-heading]').element)
    for (const field of [
      'status',
      'attempts',
      'nextAttemptAt',
      'sentAt',
      'updatedAt',
      'errorCategory',
    ]) {
      expect(wrapper.find(`[data-email-field="${field}"]`).exists()).toBe(true)
    }
    expect(wrapper.text()).toContain('SMTP_CONNECTION_FAILED')
    expect(wrapper.get('[data-email-field="sentAt"]').text()).toContain('暂无')

    await wrapper.get('[data-action="close-detail"]').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(opener.element)
  })

  it('uses the loaded detail version for status PATCH and updates both detail and list', async () => {
    const id = uuid(7)
    const loaded = detail(id, { version: 7 })
    const updated = detail(id, { status: 'READ', version: 8 })
    const updateStatus = vi.fn().mockResolvedValue(updated)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id, { version: 6 })])),
      detail: vi.fn().mockResolvedValue(loaded),
      updateStatus,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-status="READ"]').trigger('click')
    await flushPromises()
    expect(updateStatus).toHaveBeenCalledWith(id, { status: 'READ', version: 7 })
    expect(wrapper.get('[data-message-detail]').attributes('data-version')).toBe('8')
    expect(wrapper.get(`[data-message-id="${id}"]`).attributes('data-status')).toBe('READ')
    expect(document.activeElement).toBe(wrapper.get('[data-detail-heading]').element)
  })

  it.each([
    ['409 conflict', problem(409, 'MESSAGE_VERSION_CONFLICT', '版本冲突')],
    ['network uncertainty', problem(0, 'NETWORK_ERROR', '网络中断')],
  ])('reconciles a %s with GET only and never repeats PATCH', async (_name, failure) => {
    const id = uuid(8)
    const loaded = detail(id, { version: 3 })
    const latest = detail(id, { status: 'READ', version: 4 })
    const getDetail = vi.fn().mockResolvedValueOnce(loaded).mockResolvedValueOnce(latest)
    const updateStatus = vi.fn().mockRejectedValue(failure)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      updateStatus,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-status="READ"]').trigger('click')
    await flushPromises()
    expect(updateStatus).toHaveBeenCalledTimes(1)
    expect(document.activeElement).toBe(wrapper.get('[data-action="reconcile-status"]').element)
    await wrapper.get('[data-action="reconcile-status"]').trigger('click')
    await flushPromises()
    expect(updateStatus).toHaveBeenCalledTimes(1)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-message-detail]').attributes('data-version')).toBe('4')
  })

  it('treats a concurrent deletion as terminal during status GET-only reconciliation', async () => {
    const id = uuid(81)
    const getDetail = vi
      .fn()
      .mockResolvedValueOnce(detail(id))
      .mockRejectedValueOnce(problem(404, 'MESSAGE_NOT_FOUND', '留言不存在'))
    const updateStatus = vi
      .fn()
      .mockRejectedValue(problem(409, 'MESSAGE_VERSION_CONFLICT', '版本冲突'))
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      updateStatus,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-status="READ"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="reconcile-status"]').trigger('click')
    await flushPromises()

    expect(updateStatus).toHaveBeenCalledTimes(1)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.find(`[data-message-id="${id}"]`).exists()).toBe(false)
    expect(wrapper.find('[data-message-detail]').exists()).toBe(false)
    expect(wrapper.get('[data-announcement]').text()).toContain('留言已不存在')
    expect(routeHooks.leaveGuards[0]?.()).toBe(true)
  })

  it('offers retry only for FAILED/DEAD, confirms, posts once, and reloads all delivery fields', async () => {
    const id = uuid(9)
    const failed = detail(id)
    const pending = withEmail(failed, 'PENDING', {
      nextAttemptAt: '2026-07-14T00:10:00Z',
      updatedAt: '2026-07-14T00:06:00Z',
      errorCategory: null,
    })
    const getDetail = vi.fn().mockResolvedValueOnce(failed).mockResolvedValueOnce(pending)
    const retryEmail = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      retryEmail,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-action="retry-email"]').trigger('click')
    await flushPromises()
    expect(retryEmail).toHaveBeenCalledOnce()
    expect(retryEmail).toHaveBeenCalledWith(id)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-email-field="status"]').text()).toContain('PENDING')
    expect(wrapper.find('[data-action="retry-email"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('[data-detail-heading]').element)
  })

  it('latches an unknown retry and reconciles with GET without a second POST', async () => {
    const id = uuid(10)
    const failed = detail(id)
    const pending = withEmail(failed, 'PENDING', { updatedAt: '2026-07-14T00:07:00Z' })
    const getDetail = vi.fn().mockResolvedValueOnce(failed).mockResolvedValueOnce(pending)
    const retryEmail = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '结果未知'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      retryEmail,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-action="retry-email"]').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[data-action="reconcile-retry"]').element)
    await wrapper.get('[data-action="reconcile-retry"]').trigger('click')
    await flushPromises()
    expect(retryEmail).toHaveBeenCalledTimes(1)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-retry-uncertain]').exists()).toBe(false)
  })

  it('treats a concurrent deletion as terminal during retry GET-only reconciliation', async () => {
    const id = uuid(101)
    const getDetail = vi
      .fn()
      .mockResolvedValueOnce(detail(id))
      .mockRejectedValueOnce(problem(404, 'MESSAGE_NOT_FOUND', '留言不存在'))
    const retryEmail = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '结果未知'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      retryEmail,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-action="retry-email"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="reconcile-retry"]').trigger('click')
    await flushPromises()

    expect(retryEmail).toHaveBeenCalledTimes(1)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.find(`[data-message-id="${id}"]`).exists()).toBe(false)
    expect(wrapper.find('[data-message-detail]').exists()).toBe(false)
    expect(wrapper.get('[data-announcement]').text()).toContain('留言已不存在')
    expect(routeHooks.leaveGuards[0]?.()).toBe(true)
  })

  it('requires exact DELETE plus confirmation and removes the row without announcing PII', async () => {
    const id = uuid(11)
    const deleteMessage = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id, { visitorName: 'PII-NAME' })])),
      detail: vi.fn().mockResolvedValue(detail(id, { visitorName: 'PII-NAME' })),
      deleteMessage,
    })
    await flushPromises()
    await openMessage(wrapper, id)

    const remove = wrapper.get('[data-action="delete-message"]')
    expect(remove.attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-delete-confirmation]').setValue('DELETE ')
    expect(remove.attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-delete-confirmation]').setValue('DELETE')
    await remove.trigger('click')
    await flushPromises()

    expect(deleteMessage).toHaveBeenCalledWith(id)
    expect(wrapper.find(`[data-message-id="${id}"]`).exists()).toBe(false)
    expect(wrapper.find('[data-message-detail]').exists()).toBe(false)
    expect(wrapper.get('[data-announcement]').text()).not.toContain('PII-NAME')
  })

  it('proves an unknown DELETE through a 404 GET and never sends DELETE twice', async () => {
    const id = uuid(12)
    const getDetail = vi
      .fn()
      .mockResolvedValueOnce(detail(id))
      .mockRejectedValueOnce(problem(404, 'MESSAGE_NOT_FOUND', '留言不存在'))
    const deleteMessage = vi.fn().mockRejectedValue(problem(0, 'NETWORK_ERROR', '结果未知'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: getDetail,
      deleteMessage,
    })
    await flushPromises()
    await openMessage(wrapper, id)
    await wrapper.get('[data-delete-confirmation]').setValue('DELETE')
    await wrapper.get('[data-action="delete-message"]').trigger('click')
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[data-action="reconcile-delete"]').element)
    await wrapper.get('[data-action="reconcile-delete"]').trigger('click')
    await flushPromises()
    expect(deleteMessage).toHaveBeenCalledTimes(1)
    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.find(`[data-message-id="${id}"]`).exists()).toBe(false)
  })

  it('does not resurrect a deleted row from a cursor request captured before deletion', async () => {
    const id = uuid(34)
    const appendGate = deferred<MessagePageDto>()
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([summary(id)], 'cursor_before_delete'))
      .mockReturnValueOnce(appendGate.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountInbox({
      list,
      detail: vi.fn().mockResolvedValue(detail(id)),
      deleteMessage: vi.fn().mockResolvedValue(undefined),
    })
    await flushPromises()
    await openMessage(wrapper, id)

    await wrapper.get('[data-action="load-more"]').trigger('click')
    await wrapper.get('[data-delete-confirmation]').setValue('DELETE')
    await wrapper.get('[data-action="delete-message"]').trigger('click')
    await flushPromises()
    appendGate.resolve(page([summary(id, { subject: '不应复活' })]))
    await flushPromises()

    expect(wrapper.find(`[data-message-id="${id}"]`).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('不应复活')
  })

  it('protects SPA and browser exits while a mutation is busy or uncertain and ignores unmounted work', async () => {
    const id = uuid(13)
    const patchGate = deferred<MessageDetailDto>()
    const updateStatus = vi.fn().mockReturnValue(patchGate.promise)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mountInbox({
      list: vi.fn().mockResolvedValue(page([summary(id)])),
      detail: vi.fn().mockResolvedValue(detail(id)),
      updateStatus,
    })
    await flushPromises()
    await openMessage(wrapper, id)
    await wrapper.get('[data-status="READ"]').trigger('click')

    expect(routeHooks.leaveGuards).toHaveLength(1)
    expect(routeHooks.leaveGuards[0]?.()).toBe(false)
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    expect(confirm).toHaveBeenCalled()

    wrapper.unmount()
    patchGate.resolve(detail(id, { status: 'READ', version: 4 }))
    await flushPromises()
  })
})
