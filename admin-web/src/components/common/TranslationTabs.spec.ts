import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { describe, expect, it } from 'vitest'

import TranslationTabs from './TranslationTabs.vue'

const status = {
  'zh-CN': { complete: 2, total: 2 },
  en: { complete: 1, total: 2 },
} as const

describe('TranslationTabs', () => {
  it('renders exactly two accessible locale tabs with visible completion counts', () => {
    const wrapper = mount(TranslationTabs, {
      props: {
        modelValue: 'zh-CN',
        status,
      },
    })

    const tablist = wrapper.get('[role="tablist"]')
    const tabs = tablist.findAll('button[role="tab"]')

    expect(tablist.attributes('aria-label')).toBeTruthy()
    expect(tabs).toHaveLength(2)
    expect(tabs[0]?.attributes('type')).toBe('button')
    expect(tabs[0]?.text()).toContain('中文')
    expect(tabs[0]?.text().replace(/\s/g, '')).toContain('2/2')
    expect(tabs[0]?.attributes('aria-selected')).toBe('true')
    expect(tabs[0]?.attributes('aria-label')).toContain('翻译完成度 2/2')
    expect(tabs[0]?.attributes('tabindex')).toBe('0')
    expect(tabs[1]?.attributes('type')).toBe('button')
    expect(tabs[1]?.text()).toContain('English')
    expect(tabs[1]?.text().replace(/\s/g, '')).toContain('1/2')
    expect(tabs[1]?.attributes('aria-selected')).toBe('false')
    expect(tabs[1]?.attributes('tabindex')).toBe('-1')

    const panels = wrapper.findAll('[role="tabpanel"]')
    expect(panels).toHaveLength(2)
    expect(tabs[0]?.attributes('aria-controls')).toBe(panels[0]?.attributes('id'))
    expect(tabs[1]?.attributes('aria-controls')).toBe(panels[1]?.attributes('id'))
    expect(panels[0]?.attributes('aria-labelledby')).toBe(tabs[0]?.attributes('id'))
    expect(panels[1]?.attributes('aria-labelledby')).toBe(tabs[1]?.attributes('id'))
    expect(panels[0]?.attributes('hidden')).toBeUndefined()
    expect(panels[1]?.attributes('hidden')).toBe('')
  })

  it('emits the locale through the v-model contract and follows the controlled value', async () => {
    const wrapper = mount(TranslationTabs, {
      props: {
        modelValue: 'zh-CN',
        status,
      },
    })

    let tabs = wrapper.findAll('button[role="tab"]')
    expect(tabs).toHaveLength(2)
    await tabs[1]!.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['en']])
    expect(tabs[0]!.attributes('aria-selected')).toBe('true')

    await wrapper.setProps({ modelValue: 'en' })

    tabs = wrapper.findAll('button[role="tab"]')
    expect(tabs[0]!.attributes('aria-selected')).toBe('false')
    expect(tabs[1]!.attributes('aria-selected')).toBe('true')
  })

  it('switches and wraps the v-model locale with left and right arrow keys', async () => {
    const Host = defineComponent({
      components: { TranslationTabs },
      setup() {
        return {
          locale: ref<'zh-CN' | 'en'>('zh-CN'),
          status,
        }
      },
      template: '<TranslationTabs v-model="locale" :status="status" />',
    })
    const wrapper = mount(Host, { attachTo: document.body })

    try {
      let tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs).toHaveLength(2)
      tabs[0]!.element.focus()
      await tabs[0]!.trigger('keydown', { key: 'ArrowRight' })

      tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs[1]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(tabs[1]!.element)

      await tabs[1]!.trigger('keydown', { key: 'ArrowRight' })
      tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs[0]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(tabs[0]!.element)

      await tabs[0]!.trigger('keydown', { key: 'ArrowLeft' })
      tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs[1]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(tabs[1]!.element)
    } finally {
      wrapper.unmount()
    }
  })

  it('jumps to the first and last locale with Home and End', async () => {
    const Host = defineComponent({
      components: { TranslationTabs },
      setup() {
        return { locale: ref<'zh-CN' | 'en'>('en'), status }
      },
      template: '<TranslationTabs v-model="locale" :status="status" />',
    })
    const wrapper = mount(Host, { attachTo: document.body })

    try {
      let tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      await tabs[1]!.trigger('keydown', { key: 'Home' })
      tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs[0]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(tabs[0]!.element)

      await tabs[0]!.trigger('keydown', { key: 'End' })
      tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      expect(tabs[1]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(tabs[1]!.element)
    } finally {
      wrapper.unmount()
    }
  })

  it('does not emit or move focus while disabled', async () => {
    const Host = defineComponent({
      components: { TranslationTabs },
      setup: () => ({ status }),
      template: `
        <button data-focus-sentinel type="button">before tabs</button>
        <TranslationTabs model-value="zh-CN" :status="status" disabled />
      `,
    })
    const wrapper = mount(Host, { attachTo: document.body })

    try {
      const tabs = wrapper.findAll<HTMLButtonElement>('button[role="tab"]')
      const sentinel = wrapper.get<HTMLButtonElement>('[data-focus-sentinel]')
      const tabsComponent = wrapper.getComponent(TranslationTabs)
      sentinel.element.focus()
      await tabs[1]!.trigger('click')
      await tabs[0]!.trigger('keydown', { key: 'ArrowRight' })
      expect(document.activeElement).toBe(sentinel.element)
      expect(tabs[0]!.attributes('aria-selected')).toBe('true')
      expect(tabsComponent.emitted('update:modelValue')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('renders the scoped slot only in the active locale panel', async () => {
    const Host = defineComponent({
      components: { TranslationTabs },
      setup() {
        return { locale: ref<'zh-CN' | 'en'>('zh-CN'), status }
      },
      template: `
        <TranslationTabs v-model="locale" :status="status">
          <template #default="{ locale: panelLocale }">
            <span data-active-locale>{{ panelLocale }}</span>
          </template>
        </TranslationTabs>
      `,
    })
    const wrapper = mount(Host)

    let panels = wrapper.findAll('[role="tabpanel"]')
    expect(wrapper.findAll('[data-active-locale]')).toHaveLength(1)
    expect(panels[0]!.get('[data-active-locale]').text()).toBe('zh-CN')
    expect(panels[1]!.find('[data-active-locale]').exists()).toBe(false)

    await wrapper.findAll('button[role="tab"]')[1]!.trigger('click')

    panels = wrapper.findAll('[role="tabpanel"]')
    expect(wrapper.findAll('[data-active-locale]')).toHaveLength(1)
    expect(panels[0]!.find('[data-active-locale]').exists()).toBe(false)
    expect(panels[1]!.get('[data-active-locale]').text()).toBe('en')
  })

  it('keeps ids, panels, focus, and events isolated between instances', async () => {
    const Host = defineComponent({
      components: { TranslationTabs },
      setup() {
        return {
          first: ref<'zh-CN' | 'en'>('zh-CN'),
          second: ref<'zh-CN' | 'en'>('zh-CN'),
          status,
        }
      },
      template: `
        <TranslationTabs v-model="first" :status="status" />
        <TranslationTabs v-model="second" :status="status" />
      `,
    })
    const wrapper = mount(Host, { attachTo: document.body })

    try {
      const tablists = wrapper.findAll('[role="tablist"]')
      const panels = wrapper.findAll('[role="tabpanel"]')
      const firstTabs = tablists[0]!.findAll<HTMLButtonElement>('[role="tab"]')
      const secondTabs = tablists[1]!.findAll<HTMLButtonElement>('[role="tab"]')
      const ids = [
        ...firstTabs.map((tab) => tab.attributes('id')),
        ...secondTabs.map((tab) => tab.attributes('id')),
        ...panels.map((panel) => panel.attributes('id')),
      ]

      expect(panels).toHaveLength(4)
      expect(new Set(ids).size).toBe(ids.length)
      expect(firstTabs[0]!.attributes('aria-controls')).toBe(panels[0]!.attributes('id'))
      expect(firstTabs[1]!.attributes('aria-controls')).toBe(panels[1]!.attributes('id'))
      expect(secondTabs[0]!.attributes('aria-controls')).toBe(panels[2]!.attributes('id'))
      expect(secondTabs[1]!.attributes('aria-controls')).toBe(panels[3]!.attributes('id'))

      firstTabs[0]!.element.focus()
      await firstTabs[0]!.trigger('keydown', { key: 'ArrowRight' })
      expect(firstTabs[1]!.attributes('aria-selected')).toBe('true')
      expect(secondTabs[0]!.attributes('aria-selected')).toBe('true')
      expect(document.activeElement).toBe(firstTabs[1]!.element)
    } finally {
      wrapper.unmount()
    }
  })
})
