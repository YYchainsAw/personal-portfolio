import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { createPublicRouter } from '@/router'
import { PublicApiProblem } from '@/services/portfolioApi'
import { currentProjectId, publicContentStore, type ProjectContent } from '@/stores/publicContent'
import ProjectPageView from '@/views/ProjectPageView.vue'
import { card, project, site } from '../fixtures/publicSnapshots'

afterEach(() => {
  vi.restoreAllMocks()
  publicContentStore.clear()
  currentProjectId.value = null
  document.head.innerHTML = ''
})

function projectContent(slug: string, title: string): ProjectContent {
  const value = project('en')
  value.slug = slug
  value.title = title
  value.seoTitle = `${title} · Portfolio`
  const catalogCard = card('en')
  catalogCard.slug = slug
  catalogCard.title = title
  return { site: site('en'), catalog: [catalogCard], project: value }
}

it('clears project A metadata while project B fails, then rebuilds it only after an explicit retry succeeds', async () => {
  const first = projectContent('project-a', 'Project A')
  const second = projectContent('project-b', 'Project B')
  let attemptsForB = 0
  vi.spyOn(publicContentStore, 'loadProject').mockImplementation(async (_locale, slug) => {
    if (slug === 'project-a') return first
    attemptsForB += 1
    if (attemptsForB === 1) throw new PublicApiProblem({
      type: 'synthetic', title: 'Synthetic failure', status: 500, code: 'SYNTHETIC_FAILURE', traceId: 'safe-trace',
    })
    return second
  })

  const router = createPublicRouter(createMemoryHistory())
  await router.push('/en/projects/project-a')
  await router.isReady()
  const wrapper = mount(ProjectPageView, {
    global: {
      plugins: [router],
      stubs: { ProjectDetailView: { props: ['project'], template: '<main><h1>{{ project.title }}</h1></main>' } },
    },
  })
  await flushPromises()
  expect(document.title).toBe('Project A · Portfolio')
  expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toContain('/en/projects/project-a')

  await router.push('/en/projects/project-b')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('safe-trace')
  expect(document.title).toBe('Project unavailable · Yi Jiaxuan')
  expect(document.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex,follow')
  expect(document.querySelector('meta[name="description"], meta[property^="og:"]')).toBeNull()
  expect(document.querySelector('link[rel="canonical"], link[rel="alternate"]')).toBeNull()
  expect(document.querySelector('script[data-portfolio-seo]')).toBeNull()
  expect(currentProjectId.value).toBeNull()

  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('h1').text()).toBe('Project B')
  expect(document.title).toBe('Project B · Portfolio')
  expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toContain('/en/projects/project-b')
  expect(document.querySelector('meta[name="robots"][data-route-noindex]')).toBeNull()
  wrapper.unmount()
})
