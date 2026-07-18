import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ContentBlockRenderer from '@/components/project/ContentBlockRenderer.vue'
import { blocks } from '../fixtures/publicSnapshots'
import type { PublicBlock } from '@/types/public'

it('renders all nine public blocks in server order and code only as text', () => {
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [...blocks].reverse() } })
  expect(wrapper.findAll('[data-content-block]').map((node) => Number(node.attributes('data-order')))).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
  expect(wrapper.get('pre code').text()).toContain('<Actor>')
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('Project image gallery')
  expect(wrapper.findAll('.code-lines > span').map((line) => line.attributes('data-line'))).toEqual(['1'])
})

it('renders projected download metadata without a metadata request', () => {
  const fetcher = vi.spyOn(globalThis, 'fetch')
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [blocks[7]!] } })
  expect(wrapper.get('[data-download-metadata]').text()).toBe('ZIP · 1.5 MiB')
  expect(fetcher).not.toHaveBeenCalled()
})

it('links attribution only to HTTPS and uses safe external attributes', () => {
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: blocks.slice(1, 4) } })
  expect(wrapper.findAll('[data-media-source]').length).toBeGreaterThan(0)
  expect(wrapper.findAll('[data-media-source]').every((node) => node.attributes('rel') === 'noopener noreferrer')).toBe(true)
})

it('frames only the three canonical video embed shapes emitted by the server', () => {
  const video = blocks.find((block) => block.payload.type === 'VIDEO')!
  if (video.payload.type !== 'VIDEO') throw new Error('fixture video missing')
  const render = (embedUrl: string) => mount(ContentBlockRenderer, {
    props: { locale: 'en', blocks: [{ ...video, payload: { ...video.payload, embedUrl } }] },
  })

  for (const allowed of [
    'https://www.youtube.com/embed/AbC_123-xyZ',
    'https://player.vimeo.com/video/987654321',
    'https://player.bilibili.com/player.html?bvid=BV1xx411c7mD',
  ]) expect(render(allowed).find('iframe').exists()).toBe(true)

  for (const rejected of [
    'https://video.example/embed/AbC_123-xyZ',
    'https://www.youtube.com/embed/AbC_123-xyZ?autoplay=1',
    'https://player.vimeo.com:444/video/987654321',
    'https://player.bilibili.com/player.html?bvid=BV1xx411c7mD&x=1',
    'https://www.youtube.com/embed/AbC_123-xyZ#fragment',
  ]) expect(render(rejected).find('iframe').exists()).toBe(false)
})

it.each(['NONE', 'SOFT', 'STRONG'] as const)('applies the published %s emphasis', (emphasis) => {
  const block = { ...blocks[0]!, emphasis }
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [block] } })
  expect(wrapper.get('[data-content-block]').classes()).toContain(`content-block--${emphasis.toLowerCase()}`)
})

it.each([
  ['NARROW', 'LEFT'], ['STANDARD', 'CENTER'], ['WIDE', 'RIGHT'], ['FULL', 'LEFT'],
] as const)('applies the published %s width and %s alignment', (width, alignment) => {
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [{ ...blocks[0]!, width, alignment }] } })
  const element = wrapper.get('[data-content-block]')
  expect(element.classes()).toContain(`content-block--${width.toLowerCase()}`)
  expect(element.classes()).toContain(`content-block--${alignment.toLowerCase()}`)
})

it('labels a gallery in Chinese and rejects a mismatched discriminator during development', () => {
  const gallery = blocks.find((block) => block.payload.type === 'GALLERY')!
  const wrapper = mount(ContentBlockRenderer, { props: { locale: 'zh-CN', blocks: [gallery] } })
  expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('项目图片集')

  const mismatch = { ...blocks[0]!, type: 'IMAGE' } as unknown as PublicBlock
  expect(() => mount(ContentBlockRenderer, { props: { locale: 'en', blocks: [mismatch] } })).toThrow('Published block type mismatch')
})
