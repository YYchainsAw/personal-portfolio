import { expect, it } from 'vitest'
import { mapHomeViewModel } from '@/mappers/homeMapper'
import { card, site } from '../fixtures/publicSnapshots'

it('maps every published card, its exact cover, and sort order', () => {
  const publishedSite = site('zh-CN')
  const later = { ...card('zh-CN'), projectId: '00000000-0000-0000-0000-000000000002', slug: 'later', sortOrder: 2 }
  const first = { ...card('zh-CN'), sortOrder: 1 }
  const model = mapHomeViewModel('zh-CN', publishedSite, [later, first])
  expect(model.projects.map((project) => project.slug)).toEqual(['ue-study', 'later'])
  expect(model.projects[0]!.cover).toBe(first.cover)
  expect(model.heroAsset).toBe(publishedSite.hero.media)
})

it('keeps a legitimate null hero media value without inventing an asset', () => {
  const withoutHeroMedia = site('en')
  withoutHeroMedia.hero.media = null

  const model = mapHomeViewModel('en', withoutHeroMedia, [card('en')])

  expect(model.heroAsset).toBeNull()
  expect(model.projects).toHaveLength(1)
})
