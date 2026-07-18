import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { describe, expect, it } from 'vitest'

import OrderedLocalizedList from './OrderedLocalizedList.vue'

interface Item {
  id: string
  sortOrder: number
  labels: Record<'zh-CN' | 'en', string>
}

const sourceItems: readonly Item[] = Object.freeze([
  Object.freeze({ id: 'alpha', sortOrder: 8, labels: { 'zh-CN': '甲', en: 'Alpha' } }),
  Object.freeze({ id: 'beta', sortOrder: 3, labels: { 'zh-CN': '乙', en: 'Beta' } }),
  Object.freeze({ id: 'gamma', sortOrder: 42, labels: { 'zh-CN': '丙', en: 'Gamma' } }),
])

const itemLabel = (
  item: { readonly id: string; readonly sortOrder: number },
  locale: 'zh-CN' | 'en',
) => (item as Item).labels[locale]
const specificItemLabel = (item: Item, locale: 'zh-CN' | 'en') => item.labels[locale]

describe('OrderedLocalizedList', () => {
  it('moves items immutably, preserves ids, and rewrites contiguous sortOrder values', async () => {
    const wrapper = mount(OrderedLocalizedList, {
      props: {
        items: sourceItems,
        locale: 'zh-CN',
        listLabel: '导航',
        itemLabel,
      },
    })

    const list = wrapper.get('ol')
    const initialRows = wrapper.findAll('[data-item-id]')
    const upButtons = wrapper.findAll<HTMLButtonElement>('[data-direction="up"]')
    const downButtons = wrapper.findAll<HTMLButtonElement>('[data-direction="down"]')

    expect(list.attributes('aria-label')).toBe('导航')
    expect(initialRows.map((row) => row.attributes('data-item-id'))).toEqual([
      'alpha',
      'beta',
      'gamma',
    ])
    expect(upButtons[0]!.attributes('disabled')).toBeDefined()
    expect(downButtons[2]!.attributes('disabled')).toBeDefined()
    expect(downButtons[0]!.attributes('data-reorder')).toBe('导航')
    expect(downButtons[0]!.attributes('data-index')).toBe('0')
    expect(downButtons[0]!.attributes('aria-label')).toContain('甲')

    await downButtons[0]!.trigger('click')

    const emitted = wrapper.emitted('update:items')
    expect(emitted).toHaveLength(1)
    const reordered = emitted![0]![0] as Item[]
    expect(reordered.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'beta', sortOrder: 0 },
      { id: 'alpha', sortOrder: 1 },
      { id: 'gamma', sortOrder: 2 },
    ])
    expect(reordered).not.toBe(sourceItems)
    expect(reordered.every((item) => !sourceItems.includes(item))).toBe(true)
    expect(sourceItems.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'alpha', sortOrder: 8 },
      { id: 'beta', sortOrder: 3 },
      { id: 'gamma', sortOrder: 42 },
    ])
  })

  it('keeps stable keyed rows and focus while a controlled host moves both directions', async () => {
    const Host = defineComponent({
      components: { OrderedLocalizedList },
      setup() {
        return {
          items: ref(sourceItems.map((item) => ({ ...item }))),
          itemLabel: specificItemLabel,
        }
      },
      template: `
        <OrderedLocalizedList
          v-model:items="items"
          locale="en"
          list-label="路线图"
          reorder-key="roadmap"
          :item-label="itemLabel"
        >
          <template #default="{ item, index, locale }">
            <span data-slot-item>{{ item.id }}-{{ index }}-{{ locale }}</span>
          </template>
        </OrderedLocalizedList>
      `,
    })
    const wrapper = mount(Host, { attachTo: document.body })

    try {
      let alpha = wrapper.get('[data-item-id="alpha"]')
      const alphaDown = alpha.get<HTMLButtonElement>('[data-direction="down"]')
      expect(alphaDown.attributes('data-reorder')).toBe('roadmap')
      alphaDown.element.focus()
      await alphaDown.trigger('click')

      expect(wrapper.findAll('[data-item-id]').map((row) => row.attributes('data-item-id'))).toEqual([
        'beta',
        'alpha',
        'gamma',
      ])
      alpha = wrapper.get('[data-item-id="alpha"]')
      expect(document.activeElement).toBe(alpha.get('[data-direction="down"]').element)
      expect(alpha.get('[data-slot-item]').text()).toBe('alpha-1-en')

      const alphaUp = alpha.get<HTMLButtonElement>('[data-direction="up"]')
      alphaUp.element.focus()
      await alphaUp.trigger('click')
      expect(wrapper.findAll('[data-item-id]').map((row) => row.attributes('data-item-id'))).toEqual([
        'alpha',
        'beta',
        'gamma',
      ])
      alpha = wrapper.get('[data-item-id="alpha"]')
      expect(alpha.get('[data-direction="up"]').attributes('disabled')).toBeDefined()
      expect(document.activeElement).toBe(alpha.get('[data-direction="down"]').element)
    } finally {
      wrapper.unmount()
    }
  })

  it('does not emit when an enabled list receives a boundary move', async () => {
    const wrapper = mount(OrderedLocalizedList, {
      props: {
        items: sourceItems,
        locale: 'en',
        listLabel: 'Navigation',
        itemLabel,
      },
    })

    await wrapper.findAll('[data-direction="up"]')[0]!.trigger('click')
    await wrapper.findAll('[data-direction="down"]')[2]!.trigger('click')

    expect(wrapper.emitted('update:items')).toBeUndefined()
  })

  it('does not emit while disabled', async () => {
    const wrapper = mount(OrderedLocalizedList, {
      props: {
        items: sourceItems,
        locale: 'en',
        listLabel: 'Navigation',
        itemLabel,
        disabled: true,
      },
    })

    await wrapper.findAll('[data-direction="down"]')[1]!.trigger('click')

    expect(wrapper.emitted('update:items')).toBeUndefined()
    for (const button of wrapper.findAll('button')) {
      expect(button.attributes('disabled')).toBeDefined()
    }
  })
})
