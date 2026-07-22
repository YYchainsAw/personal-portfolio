import { mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { expect, it } from 'vitest'
import { createPublicRouter } from '@/router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { card, project, site } from '../fixtures/publicSnapshots'

it('renders the first case study inside the public shell with the UE presentation cover and all published content', async () => {
  const locale = 'en' as const
  const publishedSite = site(locale)
  const publishedCard = card(locale)
  const publishedProject = project(locale)
  const router = createPublicRouter(createMemoryHistory())
  await router.push(`/en/projects/${publishedProject.slug}`)
  await router.isReady()

  const wrapper = mount(ProjectDetailView, {
    props: {
      locale,
      site: publishedSite,
      project: publishedProject,
      catalog: [publishedCard],
    },
    global: { plugins: [router] },
  })

  expect(wrapper.findAll('header.public-site-header')).toHaveLength(1)
  expect(wrapper.get('.public-site-header .brand__name').text()).toBe(
    publishedSite.identity.displayName,
  )
  expect(wrapper.findAll('main#main-content.project-detail')).toHaveLength(1)
  expect(wrapper.findAll('h1')).toHaveLength(1)
  expect(wrapper.get('h1').text()).toBe(publishedProject.title)
  expect(wrapper.findAll('footer.public-site-footer')).toHaveLength(1)

  const heroImage = wrapper.get('.project-hero__visual img')
  expect(heroImage.attributes('src')).toMatch(/ue-scene-interaction-study.*\.webp/u)
  expect(heroImage.attributes('src')).not.toBe(publishedCard.cover?.src)
  expect(heroImage.attributes('srcset')).toMatch(/ue-scene-interaction-study.*1672w/u)
  expect(heroImage.attributes('width')).toBe('1672')
  expect(heroImage.attributes('height')).toBe('941')
  expect(heroImage.attributes('alt')).not.toBe('')

  expect(wrapper.get('.case-taxonomy').text()).toContain(publishedProject.tags[0])
  expect(wrapper.get('.case-taxonomy').text()).toContain(publishedProject.skills[0])
  expect(wrapper.findAll('[data-content-block]')).toHaveLength(publishedProject.blocks.length)
  expect(wrapper.findAll('[data-content-block]')).toHaveLength(9)

  wrapper.unmount()
})
