import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('App', () => {
  it('provides the admin application landmark', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterView'] } })
    expect(wrapper.find('[data-admin-app]').exists()).toBe(true)
  })
})
