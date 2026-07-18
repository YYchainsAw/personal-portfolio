import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const qr = vi.hoisted(() => ({
  toCanvas: vi.fn().mockResolvedValue(undefined),
}))

const route = vi.hoisted(() => ({
  leaveGuards: [] as Array<() => boolean>,
}))

vi.mock('qrcode', () => ({ default: { toCanvas: qr.toCanvas } }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    onBeforeRouteLeave: (guard: () => boolean) => route.leaveGuards.push(guard),
  }
})

import { ApiProblem } from '@/types/api'
import type { RecoveryCodesResponse, TotpEnrollmentResponse } from '@/types/settings'

import OneTimeRecoveryCodes from './OneTimeRecoveryCodes.vue'
import SecuritySettings from './SecuritySettings.vue'
import TotpEnrollmentPanel from './TotpEnrollmentPanel.vue'

const wrappers: VueWrapper[] = []
const originalGetContext = HTMLCanvasElement.prototype.getContext
const originalShowModal = HTMLDialogElement.prototype.showModal
const originalDialogClose = HTMLDialogElement.prototype.close
let canvasContexts = new WeakMap<HTMLCanvasElement, {
  clearRect: ReturnType<typeof vi.fn>
  drawImage: ReturnType<typeof vi.fn>
}>()

const strongPassword = 'Abcdefghijkl1!'
const provisioningUri =
  'otpauth://totp/Portfolio%3Aadmin?secret=JBSWY3DPEHPK3PXP&issuer=Portfolio'
const enrollment = (expiresAt = new Date(Date.now() + 600_000).toISOString()): TotpEnrollmentResponse => ({
  enrollmentId: '10000000-0000-4000-8000-000000000001',
  provisioningUri,
  expiresAt,
})
const recovery = (): RecoveryCodesResponse => ({
  recoveryCodes: Array.from({ length: 10 }, (_, index) => `CODE-${index + 1}-ABCD`),
})

interface Deferred<T> {
  readonly promise: Promise<T>
  readonly resolve: (value: T) => void
  readonly reject: (reason: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept
    reject = decline
  })
  return { promise, resolve, reject }
}

function problem(
  status: number,
  code: string,
  retryAfterSeconds?: number,
  fieldErrors?: Readonly<Record<string, string>>,
): ApiProblem {
  return new ApiProblem({
    type: 'about:blank',
    title: '安全请求未完成 / Security request failed',
    status,
    code,
    traceId: 'trace-security',
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
    ...(fieldErrors === undefined ? {} : { fieldErrors }),
  })
}

function mounted<T extends VueWrapper>(wrapper: T): T {
  wrappers.push(wrapper)
  return wrapper
}

async function fillReauthentication(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-field="current-password"]').setValue('Current-password-1!')
  await wrapper.get('[data-field="current-totp"]').setValue('123456')
}

async function beginTotp(wrapper: VueWrapper): Promise<void> {
  await fillReauthentication(wrapper)
  await wrapper.get('[data-action="begin-totp-enrollment"]').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  route.leaveGuards.length = 0
  qr.toCanvas.mockReset().mockResolvedValue(undefined)
  canvasContexts = new WeakMap()
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: vi.fn(function (this: HTMLCanvasElement) {
      let context = canvasContexts.get(this)
      if (context === undefined) {
        context = { clearRect: vi.fn(), drawImage: vi.fn() }
        canvasContexts.set(this, context)
      }
      return context
    }),
  })
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: originalGetContext,
  })
  if (originalShowModal === undefined) {
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  } else {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: originalShowModal,
    })
  }
  if (originalDialogClose === undefined) {
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
  } else {
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: originalDialogClose,
    })
  }
  document.body.innerHTML = ''
})

describe('SecuritySettings', () => {
  it('validates the real local password policy and sends exactly three backend keys', async () => {
    const changePassword = vi.fn().mockResolvedValue(undefined)
    const refreshSessions = vi.fn().mockResolvedValue(undefined)
    const wrapper = mounted(mount(SecuritySettings, {
      attachTo: document.body,
      props: { changePassword, refreshSessions },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()

    expect(changePassword).toHaveBeenCalledOnce()
    expect(changePassword).toHaveBeenCalledWith({
      currentPassword: 'Current-password-1!',
      currentTotp: '123456',
      newPassword: strongPassword,
    })
    expect(Object.keys(changePassword.mock.calls[0]?.[0] ?? {})).toEqual([
      'currentPassword',
      'currentTotp',
      'newPassword',
    ])
    expect(wrapper.get<HTMLInputElement>('[data-field="confirm-password"]').element.value).toBe('')
    expect(wrapper.get<HTMLInputElement>('[data-field="current-password"]').element.value).toBe('')
    expect(wrapper.text()).toContain('All other sessions were revoked; this session remains active.')
    expect(wrapper.emitted('sessions-changed')).toHaveLength(1)
    expect(refreshSessions).toHaveBeenCalledOnce()
  })

  it('accepts Unicode Nd and punctuation but rejects mismatched confirmation locally', async () => {
    const changePassword = vi.fn().mockResolvedValue(undefined)
    const wrapper = mounted(mount(SecuritySettings, { props: { changePassword } }))
    const unicodePassword = 'Abcdefghijklm１！'

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(unicodePassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(`${unicodePassword}x`)
    expect(wrapper.get<HTMLButtonElement>('[data-action="change-password"]').element.disabled).toBe(false)
    expect(changePassword).not.toHaveBeenCalled()

    await wrapper.get('[data-field="confirm-password"]').setValue(unicodePassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()
    expect(changePassword).toHaveBeenCalledOnce()
  })

  it('renders enrollment only through local QRCode.toCanvas and confirms exact enrollment fields', async () => {
    const beginEnrollment = vi.fn().mockResolvedValue(enrollment())
    const confirmEnrollment = vi.fn().mockResolvedValue(recovery())
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const dataUrlSpy = vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL')
    const wrapper = mounted(mount(SecuritySettings, {
      attachTo: document.body,
      props: { beginEnrollment, confirmEnrollment },
    }))

    await beginTotp(wrapper)
    expect(beginEnrollment).toHaveBeenCalledWith({
      currentPassword: 'Current-password-1!',
      currentTotp: '123456',
    })
    expect(qr.toCanvas).toHaveBeenCalledOnce()
    expect(qr.toCanvas.mock.calls[0]?.[1]).toBe(provisioningUri)
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(dataUrlSpy).not.toHaveBeenCalled()

    await wrapper.get('[data-field="new-totp"]').setValue('654321')
    await wrapper.get('[data-action="confirm-totp"]').trigger('submit')
    await flushPromises()

    expect(confirmEnrollment).toHaveBeenCalledWith({
      enrollmentId: '10000000-0000-4000-8000-000000000001',
      newTotp: '654321',
    })
    expect(wrapper.find('[data-totp-enrollment]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(provisioningUri)
    expect(wrapper.findAll('[data-recovery-codes] li')).toHaveLength(10)
    expect(wrapper.emitted('sessions-changed')).toHaveLength(2)
  })

  it('requires strong regeneration confirmation and clears codes only after offline acknowledgement', async () => {
    const regenerate = vi.fn().mockResolvedValue(recovery())
    const wrapper = mounted(mount(SecuritySettings, {
      attachTo: document.body,
      props: { regenerate },
    }))

    await fillReauthentication(wrapper)
    expect(wrapper.get<HTMLButtonElement>('[data-action="regenerate-recovery"]').element.disabled).toBe(false)
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
    expect(regenerate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Type REGENERATE RECOVERY CODES')
    await wrapper.get('[data-field="recovery-confirmation"]').setValue('REGENERATE RECOVERY CODES')
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
    await flushPromises()

    expect(regenerate).toHaveBeenCalledWith({
      currentPassword: 'Current-password-1!',
      currentTotp: '123456',
    })
    expect(wrapper.get<HTMLButtonElement>('[data-action="dismiss-codes"]').element.disabled).toBe(true)
    const departure = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(departure)
    expect(departure.defaultPrevented).toBe(true)
    expect(route.leaveGuards.at(-1)?.()).toBe(false)

    await wrapper.get('[data-field="codes-saved-offline"]').setValue(true)
    expect(route.leaveGuards.at(-1)?.()).toBe(true)
    await wrapper.get('[data-action="dismiss-codes"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-recovery-codes]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('CODE-1-ABCD')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(document.activeElement).toBe(wrapper.get('[data-action="regenerate-recovery"]').element)
  })

  it('keeps local reauthentication 401 local and emits only global authentication-required errors', async () => {
    const localFailure = vi.fn().mockRejectedValue(problem(401, 'AUTHENTICATION_FAILED'))
    const wrapper = mounted(mount(SecuritySettings, { props: { changePassword: localFailure } }))
    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('trace-security')
    expect(wrapper.emitted('authentication-required')).toBeUndefined()

    const globalFailure = vi.fn().mockRejectedValue(problem(401, 'AUTHENTICATION_REQUIRED'))
    await wrapper.setProps({ changePassword: globalFailure })
    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('authentication-required')).toHaveLength(1)
  })

  it('clears enrollment on conflict and never automatically repeats confirmation', async () => {
    const beginEnrollment = vi.fn().mockResolvedValue(enrollment())
    const confirmEnrollment = vi.fn().mockRejectedValue(problem(409, 'TOTP_ENROLLMENT_EXPIRED'))
    const wrapper = mounted(mount(SecuritySettings, {
      attachTo: document.body,
      props: { beginEnrollment, confirmEnrollment },
    }))

    await beginTotp(wrapper)
    await wrapper.get('[data-field="new-totp"]').setValue('654321')
    await wrapper.get('[data-action="confirm-totp"]').trigger('submit')
    await flushPromises()

    expect(confirmEnrollment).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-totp-enrollment]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(provisioningUri)
    expect(wrapper.text()).toContain('Enrollment expired')
    expect(document.activeElement).toBe(
      wrapper.get('[data-action="begin-totp-enrollment"]').element,
    )
  })

  it('binds safe validation errors and bounds a 429 countdown to one hour', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-18T10:00:00Z'))
    const changePassword = vi
      .fn()
      .mockRejectedValueOnce(problem(422, 'VALIDATION_FAILED', undefined, {
        newPassword: '服务端拒绝该新密码。',
      }))
      .mockRejectedValueOnce(problem(429, 'RATE_LIMITED', 86_400))
    const wrapper = mounted(mount(SecuritySettings, { props: { changePassword } }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务端拒绝该新密码。')

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-action="change-password"]').text()).toContain('3600s')
    expect(wrapper.get<HTMLButtonElement>('[data-action="change-password"]').element.disabled).toBe(true)

    await vi.advanceTimersByTimeAsync(3_600_000)
    expect(wrapper.get('[data-action="change-password"]').text()).not.toContain('3600s')
    expect(changePassword).toHaveBeenCalledTimes(2)
  })

  it('expires and removes local enrollment material after at most ten minutes', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-18T10:00:00Z'))
    const beginEnrollment = vi.fn().mockResolvedValue(
      enrollment('2026-07-18T12:00:00.000Z'),
    )
    const wrapper = mounted(mount(SecuritySettings, { props: { beginEnrollment } }))

    await beginTotp(wrapper)
    expect(wrapper.find('[data-totp-enrollment]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()
    expect(wrapper.find('[data-totp-enrollment]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(provisioningUri)
    expect(wrapper.text()).toContain('cleared after 10 minutes')
  })

  it('blocks route and hard navigation while enrollment is pending, then tombstones a late response after unmount', async () => {
    const pending = deferred<TotpEnrollmentResponse>()
    const delivered = enrollment()
    const beginEnrollment = vi.fn().mockReturnValue(pending.promise)
    const sessionsChanged = vi.fn()
    const authenticationRequired = vi.fn()
    const wrapper = mounted(mount(SecuritySettings, {
      props: {
        beginEnrollment,
        onSessionsChanged: sessionsChanged,
        onAuthenticationRequired: authenticationRequired,
      },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-action="begin-totp-enrollment"]').trigger('click')
    expect(beginEnrollment).toHaveBeenCalledOnce()
    expect(route.leaveGuards[0]?.()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('A security action is still pending')

    const departure = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(departure)
    expect(departure.defaultPrevented).toBe(true)

    wrapper.unmount()
    pending.resolve(delivered)
    await flushPromises()
    expect(delivered).toEqual({ enrollmentId: '', provisioningUri: '', expiresAt: '' })
    expect(sessionsChanged).not.toHaveBeenCalled()
    expect(authenticationRequired).not.toHaveBeenCalled()

    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })

  it('drops a late authentication rejection after unmount without emitting a redirect', async () => {
    const pending = deferred<TotpEnrollmentResponse>()
    const sessionsChanged = vi.fn()
    const authenticationRequired = vi.fn()
    const wrapper = mounted(mount(SecuritySettings, {
      props: {
        beginEnrollment: vi.fn().mockReturnValue(pending.promise),
        onSessionsChanged: sessionsChanged,
        onAuthenticationRequired: authenticationRequired,
      },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-action="begin-totp-enrollment"]').trigger('click')
    wrapper.unmount()
    pending.reject(problem(401, 'AUTHENTICATION_REQUIRED'))
    await flushPromises()

    expect(authenticationRequired).not.toHaveBeenCalled()
    expect(sessionsChanged).not.toHaveBeenCalled()
  })

  it('allows the login redirect guard synchronously for AUTHENTICATION_REQUIRED', async () => {
    const pending = deferred<void>()
    let guardDuringAuthenticationEvent: boolean | undefined
    const wrapper = mounted(mount(SecuritySettings, {
      props: {
        changePassword: vi.fn().mockReturnValue(pending.promise),
        onAuthenticationRequired: () => {
          guardDuringAuthenticationEvent = route.leaveGuards[0]?.()
        },
      },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    expect(route.leaveGuards[0]?.()).toBe(false)
    pending.reject(problem(401, 'AUTHENTICATION_REQUIRED'))
    await flushPromises()

    expect(guardDuringAuthenticationEvent).toBe(true)
    expect(wrapper.emitted('authentication-required')).toHaveLength(1)
  })

  it('scrubs a late confirmation recovery response and emits nothing after unmount', async () => {
    const pending = deferred<RecoveryCodesResponse>()
    const delivered = recovery()
    const sessionsChanged = vi.fn()
    const wrapper = mounted(mount(SecuritySettings, {
      props: {
        beginEnrollment: vi.fn().mockResolvedValue(enrollment()),
        confirmEnrollment: vi.fn().mockReturnValue(pending.promise),
        onSessionsChanged: sessionsChanged,
      },
    }))

    await beginTotp(wrapper)
    expect(sessionsChanged).toHaveBeenCalledOnce()
    await wrapper.get('[data-field="new-totp"]').setValue('654321')
    await wrapper.get('[data-action="confirm-totp"]').trigger('submit')
    expect(route.leaveGuards[0]?.()).toBe(false)
    wrapper.unmount()
    pending.resolve(delivered)
    await flushPromises()

    expect(delivered.recoveryCodes.every((code) => code === '')).toBe(true)
    expect(sessionsChanged).toHaveBeenCalledOnce()
    expect(document.body.textContent).not.toContain('CODE-1-ABCD')
  })

  it('scrubs a late regeneration response and emits nothing after unmount', async () => {
    const pending = deferred<RecoveryCodesResponse>()
    const delivered = recovery()
    const regenerate = vi.fn().mockReturnValue(pending.promise)
    const sessionsChanged = vi.fn()
    const wrapper = mounted(mount(SecuritySettings, {
      props: { regenerate, onSessionsChanged: sessionsChanged },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="recovery-confirmation"]').setValue('REGENERATE RECOVERY CODES')
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
    expect(route.leaveGuards[0]?.()).toBe(false)
    wrapper.unmount()
    pending.resolve(delivered)
    await flushPromises()

    expect(delivered.recoveryCodes.every((code) => code === '')).toBe(true)
    expect(sessionsChanged).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('CODE-1-ABCD')
  })

  it('shares a bounded 429 cooldown across every security action', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-18T10:00:00Z'))
    const changePassword = vi.fn().mockRejectedValue(problem(429, 'RATE_LIMITED', 120))
    const beginEnrollment = vi.fn().mockResolvedValue(enrollment())
    const regenerate = vi.fn().mockResolvedValue(recovery())
    const wrapper = mounted(mount(SecuritySettings, {
      props: { changePassword, beginEnrollment, regenerate },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-action="change-password"]').text()).toContain('120s')
    expect(wrapper.get('[data-action="begin-totp-enrollment"]').text()).toContain('120s')
    expect(wrapper.get('[data-action="regenerate-recovery"]').text()).toContain('120s')
    expect(wrapper.get<HTMLButtonElement>('[data-action="begin-totp-enrollment"]').element.disabled).toBe(true)
    expect(wrapper.get<HTMLButtonElement>('[data-action="regenerate-recovery"]').element.disabled).toBe(true)

    await wrapper.get('form').trigger('submit')
    await wrapper.get('[data-action="begin-totp-enrollment"]').trigger('click')
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
    expect(changePassword).toHaveBeenCalledOnce()
    expect(beginEnrollment).not.toHaveBeenCalled()
    expect(regenerate).not.toHaveBeenCalled()
  })

  it('uses a native modal dialog and blocks all further mutations while codes are displayed', async () => {
    const showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
    })
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open')
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: showModal,
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: close,
    })
    const changePassword = vi.fn().mockResolvedValue(undefined)
    const beginEnrollment = vi.fn().mockResolvedValue(enrollment())
    const regenerate = vi.fn().mockResolvedValue(recovery())
    const wrapper = mounted(mount(SecuritySettings, {
      attachTo: document.body,
      props: { changePassword, beginEnrollment, regenerate },
    }))

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="recovery-confirmation"]').setValue('REGENERATE RECOVERY CODES')
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get<HTMLDialogElement>('dialog[data-recovery-codes]')
    expect(showModal).toHaveBeenCalledOnce()
    expect(dialog.element.open).toBe(true)
    expect(dialog.attributes('aria-modal')).toBe('true')
    const cancel = new Event('cancel', { cancelable: true })
    expect(dialog.element.dispatchEvent(cancel)).toBe(false)
    expect(dialog.element.open).toBe(true)

    await fillReauthentication(wrapper)
    await wrapper.get('[data-field="new-password"]').setValue(strongPassword)
    await wrapper.get('[data-field="confirm-password"]').setValue(strongPassword)
    await wrapper.get('[data-action="change-password"]').trigger('click')
    await wrapper.get('[data-action="begin-totp-enrollment"]').trigger('click')
    await wrapper.get('[data-field="recovery-confirmation"]').setValue('REGENERATE RECOVERY CODES')
    await wrapper.get('[data-action="regenerate-recovery"]').trigger('click')

    expect(changePassword).not.toHaveBeenCalled()
    expect(beginEnrollment).not.toHaveBeenCalled()
    expect(regenerate).toHaveBeenCalledOnce()
    expect(route.leaveGuards.at(-1)?.()).toBe(false)
    expect(wrapper.text()).toContain('CODE-1-ABCD')
  })
})

describe('TotpEnrollmentPanel QR generation isolation', () => {
  it.each(['resolve', 'reject'] as const)(
    'keeps a newer QR intact when an older detached render later %s(s)',
    async (settlement) => {
      const first = deferred<void>()
      const second = deferred<void>()
      qr.toCanvas
        .mockImplementationOnce(() => first.promise)
        .mockImplementationOnce(() => second.promise)
      const firstEnrollment = enrollment()
      const secondEnrollment: TotpEnrollmentResponse = {
        ...enrollment(),
        enrollmentId: '10000000-0000-4000-8000-000000000002',
        provisioningUri: `${provisioningUri}-second`,
      }
      const wrapper = mounted(mount(TotpEnrollmentPanel, {
        attachTo: document.body,
        props: { enrollment: firstEnrollment, modelValue: '' },
      }))

      await flushPromises()
      await wrapper.setProps({ enrollment: secondEnrollment })
      await flushPromises()
      expect(qr.toCanvas).toHaveBeenCalledTimes(2)

      const visible = wrapper.get<HTMLCanvasElement>('[data-totp-canvas]').element
      const firstScratch = qr.toCanvas.mock.calls[0]?.[0] as HTMLCanvasElement
      const secondScratch = qr.toCanvas.mock.calls[1]?.[0] as HTMLCanvasElement
      expect(firstScratch).not.toBe(visible)
      expect(secondScratch).not.toBe(visible)
      expect(firstScratch.isConnected).toBe(false)
      expect(secondScratch.isConnected).toBe(false)

      second.resolve(undefined)
      await flushPromises()
      const visibleContext = canvasContexts.get(visible)
      expect(visibleContext?.drawImage).toHaveBeenCalledOnce()
      expect(visibleContext?.drawImage).toHaveBeenCalledWith(secondScratch, 0, 0)
      const clearCount = visibleContext?.clearRect.mock.calls.length

      if (settlement === 'resolve') first.resolve(undefined)
      else first.reject(new Error('stale QR render failed'))
      await flushPromises()

      expect(visibleContext?.drawImage).toHaveBeenCalledOnce()
      expect(visibleContext?.clearRect).toHaveBeenCalledTimes(clearCount ?? 0)
      expect(wrapper.text()).not.toContain('QR rendering failed')
    },
  )
})

describe('OneTimeRecoveryCodes', () => {
  it('copies and prints only on explicit local actions and scrubs its rendered list on unmount', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const background = document.createElement('div')
    background.dataset.printBackground = ''
    document.body.append(background)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    const wrapper = mounted(mount(OneTimeRecoveryCodes, {
      attachTo: document.body,
      props: { recoveryCodes: recovery().recoveryCodes },
    }))

    await wrapper.get('[data-action="copy-codes"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(recovery().recoveryCodes.join('\n'))
    await wrapper.get('[data-action="print-codes"]').trigger('click')
    expect(print).toHaveBeenCalledOnce()
    expect(document.body.classList.contains('printing-recovery-codes')).toBe(false)
    expect(document.querySelector('.recovery-print-excluded')).toBeNull()

    window.dispatchEvent(new Event('beforeprint'))
    expect(document.body.classList.contains('printing-recovery-codes')).toBe(true)
    expect(background.classList.contains('recovery-print-excluded')).toBe(true)
    window.dispatchEvent(new Event('afterprint'))
    expect(document.body.classList.contains('printing-recovery-codes')).toBe(false)
    expect(background.classList.contains('recovery-print-excluded')).toBe(false)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)

    wrapper.unmount()
    expect(document.body.textContent).not.toContain('CODE-1-ABCD')
  })
})
