import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'

import { ApiProblem } from '@/types/api'
import ConflictBanner from './ConflictBanner.vue'

function conflictProblem(title = '<img src=x onerror=alert(1)>服务器版本已更新'): ApiProblem {
  const problem = new ApiProblem({
    type: 'https://yychainsaw.xyz/problems/version-conflict',
    title,
    status: 409,
    code: 'VERSION_CONFLICT',
    traceId: 'trace-conflict-409',
  })
  problem.stack = 'INTERNAL_STACK SQL SELECT secret FROM administrator'
  return problem
}

function buttonNamed(wrapper: VueWrapper, name: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === name)
  expect(button, `button named "${name}"`).toBeDefined()
  return button!
}

describe('ConflictBanner', () => {
  it('renders only the escaped safe title and server trace id with no overwrite action', () => {
    const wrapper = mount(ConflictBanner, {
      props: { problem: conflictProblem(), reloading: false },
    })

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('<img src=x onerror=alert(1)>服务器版本已更新')
    expect(alert.text()).toContain('trace-conflict-409')
    expect(alert.find('img').exists()).toBe(false)
    expect(alert.text()).not.toContain('VERSION_CONFLICT')
    expect(alert.text()).not.toContain('INTERNAL_STACK')
    expect(alert.text()).not.toContain('SELECT secret')
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual([
      '保留当前页面',
      '重新载入服务器版本',
    ])
    expect(wrapper.text()).not.toContain('强制覆盖')
    expect(wrapper.text()).not.toContain('覆盖服务器版本')
  })

  it('keeps the current page and conflict state without clearing or reloading anything', async () => {
    const problem = conflictProblem()
    const wrapper = mount(ConflictBanner, { props: { problem, reloading: false } })

    await buttonNamed(wrapper, '保留当前页面').trigger('click')

    expect(wrapper.get('[role="alert"]').text()).toContain(problem.body.title)
    expect(wrapper.get('[role="alert"]').text()).toContain(problem.body.traceId)
    expect(wrapper.emitted('reload')).toBeUndefined()
    expect(wrapper.emitted('dismiss')).toBeUndefined()
    expect(wrapper.emitted('clear')).toBeUndefined()
    expect(wrapper.emitted('update:problem')).toBeUndefined()
  })

  it('emits reload only after an explicit confirmation', async () => {
    const wrapper = mount(ConflictBanner, {
      attachTo: document.body,
      props: { problem: conflictProblem(), reloading: false },
    })

    try {
      const reload = buttonNamed(wrapper, '重新载入服务器版本')
      reload.element.focus()
      await reload.trigger('click')

      expect(wrapper.emitted('reload')).toBeUndefined()
      const confirmation = wrapper.get('[role="group"]')
      expect(confirmation.attributes('aria-label')).toBeTruthy()
      expect(confirmation.text()).toContain('未保存')
      expect(confirmation.text()).toContain('服务器版本')
      expect(document.activeElement).toBe(buttonNamed(wrapper, '取消').element)

      await buttonNamed(wrapper, '确认重新载入').trigger('click')

      expect(wrapper.emitted('reload')).toEqual([[]])
      expect(document.activeElement).toBe(confirmation.element)
      expect(confirmation.attributes('aria-busy')).toBe('true')
    } finally {
      wrapper.unmount()
    }
  })

  it('cancels reload confirmation without emitting or clearing the conflict', async () => {
    const problem = conflictProblem()
    const wrapper = mount(ConflictBanner, {
      attachTo: document.body,
      props: { problem, reloading: false },
    })

    try {
      await buttonNamed(wrapper, '重新载入服务器版本').trigger('click')
      await buttonNamed(wrapper, '取消').trigger('click')

      expect(wrapper.emitted('reload')).toBeUndefined()
      expect(wrapper.find('[role="group"]').exists()).toBe(false)
      expect(wrapper.get('[role="alert"]').text()).toContain(problem.body.title)
      expect(wrapper.get('[role="alert"]').text()).toContain(problem.body.traceId)
      expect(document.activeElement).toBe(buttonNamed(wrapper, '重新载入服务器版本').element)
    } finally {
      wrapper.unmount()
    }
  })

  it('uses unique title ids and labels each banner with its own heading', () => {
    const first = conflictProblem()
    const second = conflictProblem('第二个版本冲突')
    const wrapper = mount({
      components: { ConflictBanner },
      setup: () => ({ first, second }),
      template: '<ConflictBanner :problem="first" :reloading="false" /><ConflictBanner :problem="second" :reloading="false" />',
    })

    const alerts = wrapper.findAll('[role="alert"]')
    const headings = wrapper.findAll('h2')
    expect(alerts).toHaveLength(2)
    expect(headings).toHaveLength(2)
    expect(headings[0]!.attributes('id')).not.toBe(headings[1]!.attributes('id'))
    expect(alerts[0]!.attributes('aria-labelledby')).toBe(headings[0]!.attributes('id'))
    expect(alerts[1]!.attributes('aria-labelledby')).toBe(headings[1]!.attributes('id'))
  })

  it('emits at most one reload until the controlled reload attempt finishes', async () => {
    const wrapper = mount(ConflictBanner, {
      attachTo: document.body,
      props: { problem: conflictProblem(), reloading: false },
    })

    try {
      await buttonNamed(wrapper, '重新载入服务器版本').trigger('click')
      const confirm = buttonNamed(wrapper, '确认重新载入')
      await confirm.trigger('click')
      await confirm.trigger('click')
      expect(wrapper.emitted('reload')).toEqual([[]])
      expect(document.activeElement).toBe(wrapper.get('[role="group"]').element)

      await wrapper.setProps({ reloading: true })
      await wrapper.setProps({ reloading: false })
      await nextTick()
      expect(wrapper.find('[role="group"]').exists()).toBe(false)
      expect(document.activeElement).toBe(
        buttonNamed(wrapper, '重新载入服务器版本').element,
      )

      await buttonNamed(wrapper, '重新载入服务器版本').trigger('click')
      await buttonNamed(wrapper, '确认重新载入').trigger('click')
      expect(wrapper.emitted('reload')).toEqual([[], []])
    } finally {
      wrapper.unmount()
    }
  })

  it('preserves focus context when a new conflict replaces one being reloaded', async () => {
    const wrapper = mount(ConflictBanner, {
      attachTo: document.body,
      props: { problem: conflictProblem(), reloading: false },
    })

    try {
      await buttonNamed(wrapper, '重新载入服务器版本').trigger('click')
      await buttonNamed(wrapper, '确认重新载入').trigger('click')
      await wrapper.setProps({ reloading: true })
      await wrapper.setProps({ problem: conflictProblem('新的服务器版本冲突') })
      await nextTick()

      expect(document.activeElement).toBe(wrapper.get('[role="alert"]').element)

      await wrapper.setProps({ reloading: false })
      await nextTick()
      expect(document.activeElement).toBe(
        buttonNamed(wrapper, '重新载入服务器版本').element,
      )
    } finally {
      wrapper.unmount()
    }
  })

  it('does not steal focus back when the user leaves during a reload', async () => {
    const outside = document.createElement('button')
    outside.type = 'button'
    outside.textContent = '组件外操作'
    document.body.append(outside)
    const wrapper = mount(ConflictBanner, {
      attachTo: document.body,
      props: { problem: conflictProblem(), reloading: false },
    })

    try {
      await buttonNamed(wrapper, '重新载入服务器版本').trigger('click')
      await buttonNamed(wrapper, '确认重新载入').trigger('click')
      await wrapper.setProps({ reloading: true })
      outside.focus()

      await wrapper.setProps({ reloading: false })
      await nextTick()

      expect(document.activeElement).toBe(outside)
      expect(wrapper.find('[role="group"]').exists()).toBe(false)
    } finally {
      wrapper.unmount()
      outside.remove()
    }
  })
})
