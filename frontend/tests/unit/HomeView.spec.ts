import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { expect, it } from 'vitest'
import HomeView from '@/views/HomeView.vue'
import { mapHomeViewModel } from '@/mappers/homeMapper'
import { createPublicRouter } from '@/router'
import { card, site } from '../fixtures/publicSnapshots'

async function mountHome(contactEmail = 'portfolio@yychainsaw.xyz') {
  const publishedSite = site('en')
  publishedSite.hero.media = null
  publishedSite.contact.email = contactEmail
  const router = createPublicRouter(createMemoryHistory())
  await router.push('/en')
  await router.isReady()
  const wrapper = mount(HomeView, {
    props: { model: mapHomeViewModel('en', publishedSite, [card('en')]) },
    global: { plugins: [router], directives: { reveal: {} } },
  })
  await flushPromises()
  return wrapper
}

it('keeps a published home navigable and semantic when hero media is null', async () => {
  const wrapper = await mountHome()

  expect(wrapper.find('section.premiere-work').exists()).toBe(true)
  expect(wrapper.find('figure.project-stage__visual').exists()).toBe(true)
  expect(wrapper.findAll('main')).toHaveLength(1)
  expect(wrapper.findAll('h1')).toHaveLength(1)
  expect(wrapper.get('a.contact-email').attributes('href')).toContain(
    'mailto:portfolio@yychainsaw.xyz',
  )
  expect(wrapper.get('a[href="/en/projects/ue-study"]')).toBeTruthy()
  wrapper.unmount()
})

it('never creates a mailto link from an unsafe published contact value', async () => {
  const wrapper = await mountHome('owner@example.com?subject=spoof')

  expect(wrapper.find('a.contact-email').exists()).toBe(false)
  expect(wrapper.get('.contact-email--disabled').text()).toContain(
    'owner@example.com?subject=spoof',
  )
  expect(wrapper.get('.retention-copy a').attributes('href')).toBe('mailto:hi@yychainsaw.xyz')
  wrapper.unmount()
})
