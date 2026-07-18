import { describe, expect, it } from 'vitest'

import { createBlock } from '@/types/blocks'
import { createProjectFixture } from '@/tests/fixtures/projectWorkspace'

import { projectPublicationCompletion } from './publicationCompletion'

describe('projectPublicationCompletion', () => {
  it('counts project, taxonomy, visible block, and metric copy per locale', () => {
    const workspace = createProjectFixture().value
    const markdown = createBlock('MARKDOWN')
    markdown.payload.markdown = { 'zh-CN': '中文正文', en: 'English body' }
    const metrics = createBlock('METRICS')
    metrics.payload.metrics = [
      {
        id: '24000000-0000-4000-8000-000000000001',
        sortOrder: 0,
        numericValue: '60',
        copy: {
          'zh-CN': { label: '帧率', value: '六十', suffix: '帧' },
          en: { label: 'Frame rate', value: '', suffix: 'FPS' },
        },
      },
    ]
    workspace.blocks = [markdown, metrics]

    const result = projectPublicationCompletion(workspace)

    expect(result['zh-CN'].total).toBe(result.en.total)
    expect(result['zh-CN'].complete).toBe(result['zh-CN'].total)
    expect(result.en.complete).toBe(result.en.total - 1)
  })

  it('does not block publication on copy belonging only to a hidden block', () => {
    const workspace = createProjectFixture().value
    const hidden = createBlock('LINK')
    hidden.visible = false
    workspace.blocks = [hidden]

    const result = projectPublicationCompletion(workspace)

    expect(result['zh-CN'].complete).toBe(result['zh-CN'].total)
    expect(result.en.complete).toBe(result.en.total)
  })

  it('counts media-backed blocks through server validation rather than inventing media copy', () => {
    const workspace = createProjectFixture().value
    const image = createBlock('IMAGE')
    image.payload.mediaAssetId = '23000000-0000-4000-8000-000000000001'
    workspace.blocks = [image]

    const withoutImage = { ...workspace, blocks: [] }
    expect(projectPublicationCompletion(workspace)).toEqual(
      projectPublicationCompletion(withoutImage),
    )
  })
})
