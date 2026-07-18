import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref, type PropType } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import { BLOCK_TYPES, createBlock, type ContentBlockDto } from '@/types/blocks'
import type { Locale, MediaAssetView } from '@/types/content'

vi.mock('./BlockCard.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name: 'BlockCard',
      template: '<div />',
    }),
  }
})

import BlockEditor from './BlockEditor.vue'

const BlockCardStub = defineComponent({
  name: 'BlockCard',
  props: {
    block: { type: Object as PropType<ContentBlockDto>, required: true },
    locale: { type: String as PropType<Locale>, required: true },
    disabled: Boolean,
    fieldErrors: Object as PropType<Readonly<Record<string, string>>>,
    loadMedia: Function as PropType<MediaPickerLoad>,
    resolveMedia: Function as PropType<(id: string) => Promise<MediaAssetView>>,
    labelledby: String,
  },
  emits: { 'update:block': (_block: ContentBlockDto) => true },
  template: '<div data-block-card-stub :data-card-id="block.id">{{ block.payload.type }}</div>',
})

const mounted: Array<ReturnType<typeof mount>> = []

function block(type: ContentBlockDto['payload']['type'], id: string, sortOrder: number) {
  return { ...createBlock(type), id, sortOrder }
}

function mountControlled(initial: readonly ContentBlockDto[], disabled = false) {
  const Host = defineComponent({
    components: { BlockEditor },
    setup() {
      return {
        blocks: ref(initial.map((item) => ({ ...item }))),
        disabled: ref(disabled),
      }
    },
    template: '<BlockEditor v-model:blocks="blocks" locale="zh-CN" :disabled="disabled" />',
  })
  const wrapper = mount(Host, {
    attachTo: document.body,
    global: { stubs: { BlockCard: BlockCardStub } },
  })
  mounted.push(wrapper)
  return wrapper
}

function currentBlocks(wrapper: ReturnType<typeof mountControlled>): ContentBlockDto[] {
  return (wrapper.vm as unknown as { blocks: ContentBlockDto[] }).blocks
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

describe('BlockEditor', () => {
  it('adds every approved block type and emits one contiguous immutable order', async () => {
    const wrapper = mountControlled([])

    for (const type of BLOCK_TYPES) {
      await wrapper.get(`[data-add-block="${type}"]`).trigger('click')
    }

    const blocks = currentBlocks(wrapper)
    expect(blocks.map((item) => item.payload.type)).toEqual(BLOCK_TYPES)
    expect(blocks.map((item) => item.sortOrder)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8])
    expect(new Set(blocks.map((item) => item.id))).toHaveProperty('size', BLOCK_TYPES.length)
    expect(wrapper.get('[data-block-announcement]').text()).toContain('链接 已添加')
    expect(document.activeElement).toBe(wrapper.findAll('[data-block-heading]').at(-1)?.element)
  })

  it('renumbers the complete array when adding to a non-contiguous catalog', async () => {
    const source = [block('MARKDOWN', 'a', 7), block('QUOTE', 'b', 12)]
    const wrapper = mountControlled(source)

    await wrapper.get('[data-add-block="LINK"]').trigger('click')

    const blocks = currentBlocks(wrapper)
    expect(blocks.map((item) => [item.id, item.sortOrder])).toEqual([
      ['a', 0],
      ['b', 1],
      [expect.any(String), 2],
    ])
    expect(blocks[0]).not.toBe(source[0])
    expect(blocks[1]).not.toBe(source[1])
    expect(source.map((item) => item.sortOrder)).toEqual([7, 12])
  })

  it('moves with keyboard controls, preserves keyed focus, and announces positions', async () => {
    const wrapper = mountControlled([
      block('MARKDOWN', 'a', 0),
      block('QUOTE', 'b', 1),
      block('CODE', 'c', 2),
    ])

    let row = wrapper.get('[data-block-id="a"]')
    let down = row.get<HTMLButtonElement>('[data-direction="down"]')
    down.element.focus()
    await down.trigger('click')

    expect(currentBlocks(wrapper).map((item) => item.id)).toEqual(['b', 'a', 'c'])
    row = wrapper.get('[data-block-id="a"]')
    down = row.get<HTMLButtonElement>('[data-direction="down"]')
    expect(document.activeElement).toBe(down.element)
    expect(wrapper.get('[data-block-announcement]').text()).toContain('2')

    await down.trigger('click')
    expect(currentBlocks(wrapper).map((item) => item.id)).toEqual(['b', 'c', 'a'])
    row = wrapper.get('[data-block-id="a"]')
    expect(row.get('[data-direction="down"]').attributes('disabled')).toBeDefined()
    expect(document.activeElement).toBe(row.get('[data-direction="up"]').element)
    expect(wrapper.get('[data-block-announcement]').text()).toContain('3')
  })

  it('uses a pointer-only drag handle, exposes a drop target, and focuses the moved heading', async () => {
    const wrapper = mountControlled([
      block('MARKDOWN', 'a', 0),
      block('QUOTE', 'b', 1),
      block('CODE', 'c', 2),
    ])
    const firstRow = wrapper.get('[data-block-id="a"]')
    expect(firstRow.attributes('draggable')).toBeUndefined()
    const handle = firstRow.get<HTMLElement>('[data-drag-handle]')
    expect(handle.element.tagName).toBe('SPAN')
    expect(handle.attributes('draggable')).toBe('true')
    expect(handle.attributes('aria-hidden')).toBe('true')
    expect(handle.attributes('aria-pressed')).toBeUndefined()

    const setData = vi.fn()
    await handle.trigger('dragstart', {
      dataTransfer: { effectAllowed: '', dropEffect: '', setData },
    })
    expect(setData).toHaveBeenCalledWith('text/plain', 'a')

    const target = wrapper.get('[data-block-id="c"]')
    await target.trigger('dragover', {
      dataTransfer: { dropEffect: '', effectAllowed: '', setData: vi.fn() },
    })
    expect(target.attributes('data-drop-target')).toBe('true')
    await target.trigger('drop', {
      dataTransfer: { dropEffect: '', effectAllowed: '', setData: vi.fn() },
    })
    await nextTick()

    expect(currentBlocks(wrapper).map((item) => item.id)).toEqual(['b', 'c', 'a'])
    const movedHandle = wrapper.get('[data-block-id="a"] [data-drag-handle]')
    expect(movedHandle.attributes('aria-pressed')).toBeUndefined()
    expect(wrapper.find('[data-drop-target="true"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('[data-block-id="a"] [data-block-heading]').element)

    await movedHandle.trigger('dragend')
    expect(movedHandle.attributes('aria-pressed')).toBeUndefined()
  })

  it('updates and removes by stable id without trusting a replacement identity', async () => {
    const wrapper = mountControlled([
      block('MARKDOWN', 'a', 5),
      block('QUOTE', 'b', 9),
    ])
    const cards = wrapper.findAllComponents(BlockCardStub)
    const current = currentBlocks(wrapper)[1]!
    cards[1]!.vm.$emit('update:block', {
      ...current,
      id: 'forged',
      sortOrder: 999,
      visible: false,
    })
    await nextTick()

    expect(currentBlocks(wrapper)[1]).toMatchObject({ id: 'b', sortOrder: 9, visible: false })
    await wrapper.get('[data-block-id="a"] [data-action="delete-block"]').trigger('click')
    expect(currentBlocks(wrapper).map((item) => [item.id, item.sortOrder])).toEqual([['b', 0]])
    expect(document.activeElement).toBe(wrapper.get('[data-block-id="b"] [data-block-heading]').element)
    expect(wrapper.get('[data-block-announcement]').text()).toContain('Markdown 已删除')
  })

  it('scopes field errors and forwards media dependencies to the matching card', () => {
    const loadMedia = vi.fn<MediaPickerLoad>()
    const resolveMedia = vi.fn<(id: string) => Promise<MediaAssetView>>()
    const wrapper = mount(BlockEditor, {
      props: {
        blocks: [
          block('IMAGE', 'a', 0),
          block('VIDEO', 'b', 1),
          block('DOWNLOAD', 'c', 2),
        ],
        locale: 'en',
        fieldErrors: {
          'blocks[0].mediaAssetId': 'Select an image',
          'blocks.1.payload.url': 'HTTPS required',
          'blocks[2]': 'Choose exactly one download target',
          'payload.copy.en.title': 'Unscoped title',
        },
        loadMedia,
        resolveMedia,
      },
      global: { stubs: { BlockCard: BlockCardStub } },
    })
    mounted.push(wrapper)

    const cards = wrapper.findAllComponents(BlockCardStub)
    expect(cards[0]!.props()).toMatchObject({ locale: 'en', loadMedia, resolveMedia })
    expect(cards[0]!.props('fieldErrors')).toMatchObject({
      'blocks[0].mediaAssetId': 'Select an image',
      mediaAssetId: 'Select an image',
      'payload.copy.en.title': 'Unscoped title',
    })
    expect(cards[0]!.props('fieldErrors')).not.toHaveProperty('blocks.1.payload.url')
    expect(cards[1]!.props('fieldErrors')).toMatchObject({
      'blocks.1.payload.url': 'HTTPS required',
      'payload.url': 'HTTPS required',
    })
    expect(cards[2]!.props('fieldErrors')).toMatchObject({
      'blocks[2]': 'Choose exactly one download target',
      target: 'Choose exactly one download target',
      'payload.target': 'Choose exactly one download target',
    })
    expect(cards[0]!.props('labelledby')).toBeTruthy()
    expect(wrapper.get('[data-block-id="c"] [data-block-error-summary]').text()).toContain(
      'Choose exactly one download target',
    )
  })

  it('renders structural row errors and gives repeated block actions unique positional names', () => {
    const wrapper = mount(BlockEditor, {
      props: {
        blocks: [block('IMAGE', 'a', 0), block('IMAGE', 'b', 1)],
        locale: 'zh-CN',
        fieldErrors: {
          'blocks[0].id': '内容块编号无效',
          'blocks[0].sortOrder': '内容块排序无效',
        },
      },
      global: { stubs: { BlockCard: BlockCardStub } },
    })
    mounted.push(wrapper)

    const first = wrapper.get('[data-block-id="a"]')
    const second = wrapper.get('[data-block-id="b"]')
    expect(first.get('[data-block-error-summary]').text()).toContain('内容块编号无效')
    expect(first.attributes('aria-describedby')).toBe(first.get('[data-block-error-summary]').attributes('id'))
    expect(first.get('[data-action="delete-block"]').attributes('aria-label')).toContain('第 1 项 图片')
    expect(second.get('[data-action="delete-block"]').attributes('aria-label')).toContain('第 2 项 图片')
    expect(first.attributes('aria-labelledby')).not.toBe(second.attributes('aria-labelledby'))
  })

  it('blocks every mutation and clears an active drag when disabled', async () => {
    const wrapper = mountControlled([block('MARKDOWN', 'a', 0), block('QUOTE', 'b', 1)])
    const handle = wrapper.get('[data-block-id="a"] [data-drag-handle]')
    await handle.trigger('dragstart', {
      dataTransfer: { effectAllowed: '', dropEffect: '', setData: vi.fn() },
    })
    ;(wrapper.vm as unknown as { disabled: boolean }).disabled = true
    await nextTick()

    expect(wrapper.get('[data-block-id="a"] [data-drag-handle]').attributes('aria-pressed')).toBeUndefined()
    expect(wrapper.get('[data-block-id="a"] [data-drag-handle]').attributes('draggable')).toBe('false')
    expect(wrapper.get('[data-block-id="a"] [data-drag-handle]').attributes('data-disabled')).toBe('true')
    for (const button of wrapper.findAll('button')) expect(button.attributes('disabled')).toBeDefined()

    await wrapper.get('[data-add-block="LINK"]').trigger('click')
    await wrapper.get('[data-block-id="a"] [data-action="delete-block"]').trigger('click')
    wrapper.findAllComponents(BlockCardStub)[0]!.vm.$emit('update:block', {
      ...currentBlocks(wrapper)[0]!,
      visible: false,
    })
    await flushPromises()
    expect(currentBlocks(wrapper).map((item) => item.id)).toEqual(['a', 'b'])
    expect(currentBlocks(wrapper)[0]?.visible).toBe(true)
  })
})
