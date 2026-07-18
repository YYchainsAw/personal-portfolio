import { afterEach, vi } from 'vitest'

class TestMediaQueryList extends EventTarget implements MediaQueryList {
  readonly matches = false

  readonly onchange = null

  constructor(readonly media: string) {
    super()
  }

  addListener() {}

  removeListener() {}

  dispatchEvent(event: Event): boolean {
    return super.dispatchEvent(event)
  }
}

Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: (query: string) => new TestMediaQueryList(query),
})

Object.defineProperty(window, 'scrollTo', {
  configurable: true,
  value: vi.fn(),
})

Object.defineProperty(window, 'requestAnimationFrame', {
  configurable: true,
  value: (callback: FrameRequestCallback) => window.setTimeout(() => callback(performance.now()), 0),
})

Object.defineProperty(window, 'cancelAnimationFrame', {
  configurable: true,
  value: (handle: number) => window.clearTimeout(handle),
})

afterEach(() => {
  document.head.innerHTML = ''
  document.body.innerHTML = ''
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})
