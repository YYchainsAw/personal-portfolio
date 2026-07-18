import { expect, it, vi } from 'vitest'
import { mountWhenRouterReady } from '@/bootstrap'

it('keeps the SSR shell until the initial lazy route is ready', async () => {
  document.body.innerHTML = '<div id="app"><main id="main-content"><h1>published marker</h1></main></div>'
  let release!: () => void
  const ready = new Promise<void>((resolve) => { release = resolve })
  const app = { mount: vi.fn(() => { document.querySelector('#app')!.innerHTML = '<main id="main-content"><h1>Vue marker</h1></main>' }) }
  const pending = mountWhenRouterReady(app, { isReady: () => ready })
  expect(document.querySelector('h1')?.textContent).toBe('published marker')
  expect(app.mount).not.toHaveBeenCalled()
  release(); await pending
  expect(document.querySelectorAll('main')).toHaveLength(1)
  expect(document.querySelectorAll('h1')).toHaveLength(1)
  expect(document.querySelector('h1')?.textContent).toBe('Vue marker')
})

it('preserves SSR when the router cannot initialize', async () => {
  document.body.innerHTML = '<div id="app"><main><h1>published marker</h1></main></div>'
  const app = { mount: vi.fn() }
  await expect(mountWhenRouterReady(app, { isReady: () => Promise.reject(new Error('synthetic')) })).resolves.toBe(false)
  expect(app.mount).not.toHaveBeenCalled()
  expect(document.querySelector('h1')?.textContent).toBe('published marker')
})
