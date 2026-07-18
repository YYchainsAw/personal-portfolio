import { describe, expect, expectTypeOf, it } from 'vitest'

import { translationStatus } from './useTranslationStatus'

type CompletionSnapshot = Readonly<{
  complete: number
  total: number
}>

type TranslationStatus = Readonly<
  Record<'zh-CN' | 'en', CompletionSnapshot>
>

describe('translationStatus', () => {
  it('reports trimmed required strings independently for exactly zh-CN and en', () => {
    const translations = {
      'zh-CN': { title: '  标题  ', summary: '\t摘要\n', ignored: '' },
      en: { title: '  Title  ', summary: ' \t\n ', ignored: 'not required' },
      fr: { title: 'Titre', summary: 'Résumé', ignored: 'must not appear' },
    }

    const result = translationStatus(translations, ['title', 'summary'])

    expect(Object.keys(result).sort()).toEqual(['en', 'zh-CN'])
    expect(result['zh-CN']).toEqual({ complete: 2, total: 2 })
    expect(result.en).toEqual({ complete: 1, total: 2 })
    expect(result).not.toHaveProperty('fr')
  })

  it('counts zero and false as complete while null and undefined remain incomplete', () => {
    const translations = {
      'zh-CN': { order: 0, featured: false, nullable: null, omitted: undefined },
      en: { order: 0, featured: false, nullable: null, omitted: undefined },
    }

    const result = translationStatus(translations, [
      'order',
      'featured',
      'nullable',
      'omitted',
    ])

    expect(result['zh-CN']).toEqual({ complete: 2, total: 4 })
    expect(result.en).toEqual({ complete: 2, total: 4 })
  })

  it('uses distinct required keys and returns zero totals for an empty requirement', () => {
    const translations = {
      'zh-CN': { title: '标题', summary: '摘要' },
      en: { title: 'Title', summary: '' },
    }

    expect(translationStatus(translations, [])).toEqual({
      'zh-CN': { complete: 0, total: 0 },
      en: { complete: 0, total: 0 },
    })

    const duplicated = translationStatus(translations, [
      'title',
      'title',
      'summary',
      'summary',
    ])
    expect(duplicated['zh-CN']).toEqual({ complete: 2, total: 2 })
    expect(duplicated.en).toEqual({ complete: 1, total: 2 })
  })

  it('does not mutate caller data and returns a deeply frozen readonly snapshot', () => {
    const translations = Object.freeze({
      'zh-CN': Object.freeze({ title: '标题', summary: '摘要', untouched: ['一', '二'] }),
      en: Object.freeze({ title: 'Title', summary: 'Summary', untouched: ['one', 'two'] }),
    })
    const required = Object.freeze(['title', 'title', 'summary'] as const)
    const translationsBefore = structuredClone(translations)
    const requiredBefore = [...required]

    const result = translationStatus(translations, required)

    expect(translations).toEqual(translationsBefore)
    expect(required).toEqual(requiredBefore)
    expect(Object.isFrozen(result)).toBe(true)
    expect(Object.isFrozen(result['zh-CN'])).toBe(true)
    expect(Object.isFrozen(result.en)).toBe(true)
    expectTypeOf(result).toEqualTypeOf<TranslationStatus>()
  })
})
