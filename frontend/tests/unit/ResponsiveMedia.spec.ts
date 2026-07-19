import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ResponsiveMedia from '@/components/media/ResponsiveMedia.vue'
import { media } from '../fixtures/publicSnapshots'

it('renders published intrinsic and responsive media fields', () => {
  const wrapper = mount(ResponsiveMedia, { props: { media: media('1'), sizes: '50vw' } })
  const image = wrapper.get('img')
  expect(image.attributes()).toMatchObject({ width: '1200', height: '800', sizes: '50vw', alt: 'Media 1' })
  expect(image.attributes('srcset')).toContain('640w')
})
