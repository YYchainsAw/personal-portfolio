import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import type { MetricsPayload } from '@/types/blocks'
import MetricsBlockEditor from './MetricsBlockEditor.vue'

const firstId = '11111111-1111-4111-8111-111111111111'
const secondId = '22222222-2222-4222-8222-222222222222'
const mounted: Array<ReturnType<typeof mount>> = []

const sourcePayload: MetricsPayload = {
  type: 'METRICS',
  metrics: [
    {
      id: firstId,
      sortOrder: 7,
      numericValue: '001.2300',
      copy: {
        'zh-CN': { label: '项目', value: '十', suffix: '个' },
        en: { label: 'Projects', value: 'Ten', suffix: 'items' },
      },
    },
    {
      id: secondId,
      sortOrder: 12,
      numericValue: null,
      copy: {
        'zh-CN': { label: '年份', value: '五', suffix: '年' },
        en: { label: 'Years', value: 'Five', suffix: 'years' },
      },
    },
  ],
}

function mountControlled(initial: MetricsPayload = sourcePayload) {
  const Host = defineComponent({
    components: { MetricsBlockEditor },
    setup() {
      return {
        locale: ref<'zh-CN' | 'en'>('zh-CN'),
        payload: ref(initial),
      }
    },
    template: `
      <MetricsBlockEditor v-model="payload" :locale="locale" />
    `,
  })
  const wrapper = mount(Host, { attachTo: document.body })
  mounted.push(wrapper)
  return wrapper
}

function currentPayload(wrapper: ReturnType<typeof mountControlled>): MetricsPayload {
  return (wrapper.vm as unknown as { payload: MetricsPayload }).payload
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

describe('MetricsBlockEditor', () => {
  it('preserves decimal input losslessly and updates only active-locale copy', async () => {
    const wrapper = mountControlled()

    const decimal = wrapper.get(`[data-metric-id="${firstId}"] [data-field="numericValue"]`)
    expect(decimal.attributes('type')).toBe('text')
    await decimal.setValue('-001.2300e+04')
    expect(currentPayload(wrapper).metrics[0]?.numericValue).toBe('-001.2300e+04')
    expect(typeof currentPayload(wrapper).metrics[0]?.numericValue).toBe('string')

    await decimal.setValue('12..3')
    expect(currentPayload(wrapper).metrics[0]?.numericValue).toBe('12..3')
    expect(wrapper.get(`[data-metric-id="${firstId}"] [data-decimal-error]`).text()).toContain('decimal')

    await decimal.setValue('')
    expect(currentPayload(wrapper).metrics[0]?.numericValue).toBeNull()

    await wrapper
      .get(`[data-metric-id="${firstId}"] [data-field="copy.zh-CN.label"]`)
      .setValue('新项目')
    const next = currentPayload(wrapper)
    expect(next.metrics[0]?.copy).toEqual({
      'zh-CN': { label: '新项目', value: '十', suffix: '个' },
      en: { label: 'Projects', value: 'Ten', suffix: 'items' },
    })
    expect(next.metrics[0]?.copy.en).not.toBe(sourcePayload.metrics[0]?.copy.en)
    expect(sourcePayload.metrics[0]?.numericValue).toBe('001.2300')
    expect(sourcePayload.metrics[0]?.copy['zh-CN'].label).toBe('项目')
  })

  it('adds, reorders, and deletes with stable ids and contiguous sort orders', async () => {
    const wrapper = mountControlled()

    await wrapper.get('[data-action="add-metric"]').trigger('click')
    let next = currentPayload(wrapper)
    expect(next.metrics).toHaveLength(3)
    const addedId = next.metrics[2]?.id
    expect(addedId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(next.metrics.map((metric) => metric.sortOrder)).toEqual([0, 1, 2])
    expect(next.metrics[2]).toMatchObject({
      id: addedId,
      numericValue: null,
      copy: {
        'zh-CN': { label: '', value: '', suffix: '' },
        en: { label: '', value: '', suffix: '' },
      },
    })
    expect(document.activeElement).toBe(
      wrapper.get(`[data-metric-id="${addedId}"] [data-field="numericValue"]`).element,
    )

    const moveUp = wrapper.get<HTMLButtonElement>(
      `[data-metric-id="${addedId}"] [data-direction="up"]`,
    )
    moveUp.element.focus()
    await moveUp.trigger('click')
    next = currentPayload(wrapper)
    expect(next.metrics.map((metric) => metric.id)).toEqual([firstId, addedId, secondId])
    expect(next.metrics.map((metric) => metric.sortOrder)).toEqual([0, 1, 2])
    expect(next.metrics[1]?.id).toBe(addedId)
    expect(next.metrics.every((metric) => !sourcePayload.metrics.includes(metric))).toBe(true)
    expect(document.activeElement).toBe(
      wrapper.get(`[data-metric-id="${addedId}"] [data-direction="up"]`).element,
    )

    await wrapper
      .get(`[data-metric-id="${firstId}"] [data-action="delete-metric"]`)
      .trigger('click')
    next = currentPayload(wrapper)
    expect(next.metrics.map((metric) => metric.id)).toEqual([addedId, secondId])
    expect(next.metrics.map((metric) => metric.sortOrder)).toEqual([0, 1])
    expect(sourcePayload.metrics.map((metric) => [metric.id, metric.sortOrder])).toEqual([
      [firstId, 7],
      [secondId, 12],
    ])
    expect(document.activeElement).toBe(
      wrapper.get(`[data-metric-id="${addedId}"] [data-metric-heading]`).element,
    )
    expect(wrapper.get('[role="status"]').text()).toContain('指标已删除')
  })

  it('switches locale without losing either language', async () => {
    const wrapper = mountControlled()
    ;(wrapper.vm as unknown as { locale: 'zh-CN' | 'en' }).locale = 'en'
    await nextTick()

    const englishLabel = wrapper.get(
      `[data-metric-id="${firstId}"] [data-field="copy.en.label"]`,
    )
    expect((englishLabel.element as HTMLInputElement).value).toBe('Projects')
    await englishLabel.setValue('Selected projects')

    expect(currentPayload(wrapper).metrics[0]?.copy).toEqual({
      'zh-CN': { label: '项目', value: '十', suffix: '个' },
      en: { label: 'Selected projects', value: 'Ten', suffix: 'items' },
    })
  })

  it('renders nested field errors and blocks changes while disabled', async () => {
    const wrapper = mount(MetricsBlockEditor, {
      props: {
        modelValue: sourcePayload,
        locale: 'en',
        disabled: true,
        fieldErrors: { 'payload.metrics[0].copy.en.value': 'Value is required' },
      },
    })

    const value = wrapper.get(`[data-metric-id="${firstId}"] [data-field="copy.en.value"]`)
    expect(value.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[role="alert"]').text()).toBe('Value is required')
    for (const control of wrapper.findAll('button, input')) {
      expect(control.attributes('disabled')).toBeDefined()
    }
    await wrapper.get('[data-action="add-metric"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('renders focusable structural metric errors and names the operation group', () => {
    const wrapper = mount(MetricsBlockEditor, {
      props: {
        modelValue: sourcePayload,
        locale: 'zh-CN',
        fieldErrors: {
          'payload.metrics[0].id': '指标编号无效',
          'payload.metrics[0].sortOrder': '指标排序无效',
        },
      },
    })

    const first = wrapper.get(`[data-metric-id="${firstId}"]`)
    const summary = first.get('[data-metric-error-summary]')
    expect(summary.attributes('tabindex')).toBe('-1')
    expect(summary.text()).toContain('指标编号无效')
    expect(first.attributes('aria-describedby')).toBe(summary.attributes('id'))
    expect(first.get('[role="group"]').attributes('aria-label')).toBe('指标 1 操作')
  })
})
