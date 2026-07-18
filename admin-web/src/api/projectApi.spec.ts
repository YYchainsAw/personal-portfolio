import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import type {
  ProjectWorkspaceDto,
  TaxonomyWorkspaceDto,
  UpdateTaxonomyRequest,
} from '@/types/content'

import { http } from './http'
import { projectApi } from './projectApi'

const PROJECT_ID = '20000000-0000-4000-8000-000000000001'
const CREATED_PROJECT_ID = '20000000-0000-4000-8000-000000000002'
const TAG_ID = '30000000-0000-4000-8000-000000000001'
const SKILL_ID = '30000000-0000-4000-8000-000000000002'
const MEDIA_ID = '40000000-0000-4000-8000-000000000001'

const projectResponse = {
  id: PROJECT_ID,
  externalKey: 'project-test',
  slug: 'project-test',
  number: '01',
  sortOrder: 4,
  featured: false,
  visible: true,
  publicationDirty: true,
  version: 7,
  translations: {
    'zh-CN': {
      status: '开发中',
      eyebrow: '游戏开发',
      title: '项目测试',
      summary: '一个双语项目。',
      seoTitle: '项目测试',
      seoDescription: '项目搜索描述。',
    },
    en: {
      status: 'In progress',
      eyebrow: 'Game development',
      title: 'Project Test',
      summary: 'A bilingual project.',
      seoTitle: 'Project Test',
      seoDescription: 'Project search description.',
    },
  },
  tags: [
    {
      id: TAG_ID,
      normalizedKey: 'gameplay',
      sortOrder: 0,
      names: { 'zh-CN': '玩法', en: 'Gameplay' },
    },
  ],
  skills: [
    {
      id: SKILL_ID,
      normalizedKey: 'unreal-engine',
      sortOrder: 0,
      names: { 'zh-CN': '虚幻引擎', en: 'Unreal Engine' },
    },
  ],
  media: [
    {
      assetId: MEDIA_ID,
      usage: 'COVER',
      sortOrder: 0,
      layout: 'wide',
      objectPosition: '50% 50%',
      credit: 'Yi Jiaxuan',
      sourceUrl: 'https://example.test/project-cover',
    },
  ],
  blocks: [],
} satisfies ProjectWorkspaceDto

const tagResponse = {
  id: TAG_ID,
  normalizedKey: 'gameplay',
  version: 3,
  names: { 'zh-CN': '玩法', en: 'Gameplay' },
} satisfies TaxonomyWorkspaceDto

const skillResponse = {
  id: SKILL_ID,
  normalizedKey: 'unreal-engine',
  version: 5,
  names: { 'zh-CN': '虚幻引擎', en: 'Unreal Engine' },
} satisfies TaxonomyWorkspaceDto

function clonedProject(): ProjectWorkspaceDto {
  return structuredClone(projectResponse)
}

function invalidResponse(): object {
  return {
    body: {
      status: 0,
      code: 'INVALID_SERVER_RESPONSE',
      traceId: 'client',
    },
  }
}

describe('projectApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads the full workspace array and accepts a unique but unordered catalog', async () => {
    const first = clonedProject()
    const second = {
      ...clonedProject(),
      id: CREATED_PROJECT_ID,
      externalKey: 'earlier-project',
      slug: 'earlier-project',
      sortOrder: 0,
      tags: [],
      skills: [],
      media: [],
    }
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: [first, second] } as never)

    await expect(projectApi.list()).resolves.toEqual([first, second])
    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('/api/admin/projects')
  })

  it('creates through the real CreateProjectWorkspaceRequest envelope', async () => {
    const request = clonedProject()
    const created = {
      ...clonedProject(),
      id: CREATED_PROJECT_ID,
      version: 0,
      publicationDirty: true,
    }
    const post = vi.spyOn(http, 'post').mockResolvedValueOnce({ data: created } as never)

    await expect(projectApi.create(request)).resolves.toEqual({
      version: 0,
      value: created,
    })
    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith('/api/admin/projects', { workspace: request })
  })

  it('loads a workspace by its stable UUID and rejects a mismatched response identity', async () => {
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValueOnce({ data: projectResponse } as never)
      .mockResolvedValueOnce({ data: { ...projectResponse, id: CREATED_PROJECT_ID } } as never)

    await expect(projectApi.get(PROJECT_ID)).resolves.toEqual({
      version: 7,
      value: projectResponse,
    })
    await expect(projectApi.get(PROJECT_ID)).rejects.toMatchObject(invalidResponse())
    expect(get).toHaveBeenNthCalledWith(
      1,
      `/api/admin/projects/${PROJECT_ID}/workspace`,
    )
  })

  it('round-trips a high-precision metric as an unchanged decimal string', async () => {
    const precise = '9007199254740993.12345678901234567890'
    const workspace = {
      ...clonedProject(),
      blocks: [
        {
          id: '50000000-0000-4000-8000-000000000009',
          sortOrder: 0,
          visible: true,
          width: 'STANDARD',
          alignment: 'LEFT',
          emphasis: 'NONE',
          columns: 1,
          payload: {
            type: 'METRICS',
            metrics: [
              {
                id: '60000000-0000-4000-8000-000000000009',
                sortOrder: 0,
                numericValue: precise,
                copy: {
                  'zh-CN': { label: '精确值', value: precise, suffix: '' },
                  en: { label: 'Precise value', value: precise, suffix: '' },
                },
              },
            ],
          },
        },
      ],
    }
    const saved = { ...structuredClone(workspace), version: 8 }
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: workspace } as never)
    const put = vi.spyOn(http, 'put').mockResolvedValueOnce({ data: saved } as never)

    const loaded = await projectApi.get(PROJECT_ID)
    const metric = loaded.value.blocks[0]?.payload
    expect(metric).toMatchObject({
      type: 'METRICS',
      metrics: [{ numericValue: precise }],
    })

    await projectApi.save(PROJECT_ID, {
      expectedVersion: loaded.version,
      workspace: loaded.value,
    })
    expect(put).toHaveBeenCalledWith(
      `/api/admin/projects/${PROJECT_ID}/workspace`,
      {
        expectedVersion: 7,
        workspace: expect.objectContaining({
          blocks: [
            expect.objectContaining({
              payload: expect.objectContaining({
                metrics: [expect.objectContaining({ numericValue: precise })],
              }),
            }),
          ],
        }),
      },
    )
    expect(get).toHaveBeenCalledOnce()
  })

  it('saves only the exact CAS envelope and requires the fresh server version', async () => {
    const workspace = clonedProject()
    const saved = { ...clonedProject(), version: 8, publicationDirty: true }
    const put = vi.spyOn(http, 'put').mockResolvedValueOnce({ data: saved } as never)

    await expect(
      projectApi.save(PROJECT_ID, { expectedVersion: 7, workspace }),
    ).resolves.toEqual({ version: 8, value: saved })
    expect(put).toHaveBeenCalledOnce()
    expect(put).toHaveBeenCalledWith(
      `/api/admin/projects/${PROJECT_ID}/workspace`,
      { expectedVersion: 7, workspace },
    )
  })

  it('normalizes approved Jackson non-null omissions through the block normalizer', async () => {
    const response = {
      ...clonedProject(),
      blocks: [
        {
          id: '50000000-0000-4000-8000-000000000001',
          sortOrder: 0,
          visible: true,
          width: 'STANDARD',
          alignment: 'LEFT',
          emphasis: 'NONE',
          columns: 1,
          payload: {
            type: 'VIDEO',
            provider: 'YOUTUBE',
            url: 'https://example.test/video',
            copy: {
              'zh-CN': {},
              en: { title: 'Demo' },
            },
          },
        },
        {
          id: '50000000-0000-4000-8000-000000000002',
          sortOrder: 1,
          visible: true,
          width: 'STANDARD',
          alignment: 'LEFT',
          emphasis: 'NONE',
          columns: 2,
          payload: {
            type: 'METRICS',
            metrics: [
              {
                id: '60000000-0000-4000-8000-000000000001',
                sortOrder: 0,
                copy: {
                  'zh-CN': { label: '帧率', value: '60', suffix: 'FPS' },
                  en: { label: 'Frame rate', value: '60', suffix: 'FPS' },
                },
              },
            ],
          },
        },
        {
          id: '50000000-0000-4000-8000-000000000003',
          sortOrder: 2,
          visible: true,
          width: 'STANDARD',
          alignment: 'LEFT',
          emphasis: 'NONE',
          columns: 1,
          payload: {
            type: 'DOWNLOAD',
            mediaAssetId: MEDIA_ID,
            copy: { 'zh-CN': {}, en: { label: 'Download' } },
          },
        },
      ],
    }
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: response } as never)

    const result = await projectApi.get(PROJECT_ID)

    expect(result.value.blocks[0]?.payload).toEqual({
      type: 'VIDEO',
      provider: 'YOUTUBE',
      url: 'https://example.test/video',
      coverAssetId: null,
      copy: {
        'zh-CN': { title: '', description: '' },
        en: { title: 'Demo', description: '' },
      },
    })
    expect(result.value.blocks[1]?.payload).toMatchObject({
      type: 'METRICS',
      metrics: [{ numericValue: null }],
    })
    expect(result.value.blocks[2]?.payload).toEqual({
      type: 'DOWNLOAD',
      mediaAssetId: MEDIA_ID,
      externalUrl: null,
      copy: {
        'zh-CN': { label: '', description: '' },
        en: { label: 'Download', description: '' },
      },
    })
  })

  it.each([
    ['an extra root property', { ...projectResponse, unexpected: true }],
    [
      'a missing project locale',
      { ...projectResponse, translations: { en: projectResponse.translations.en } },
    ],
    ['an unsafe version', { ...projectResponse, version: Number.MAX_SAFE_INTEGER + 1 }],
    [
      'a duplicated taxonomy identity',
      { ...projectResponse, tags: [projectResponse.tags[0], projectResponse.tags[0]] },
    ],
    [
      'a duplicated taxonomy order',
      {
        ...projectResponse,
        tags: [
          projectResponse.tags[0],
          { ...projectResponse.tags[0], id: '30000000-0000-4000-8000-000000000099' },
        ],
      },
    ],
    [
      'a duplicated embedded taxonomy key',
      {
        ...projectResponse,
        tags: [
          projectResponse.tags[0],
          {
            ...projectResponse.tags[0],
            id: '30000000-0000-4000-8000-000000000099',
            sortOrder: 1,
          },
        ],
      },
    ],
    [
      'an invalid media enum',
      { ...projectResponse, media: [{ ...projectResponse.media[0], usage: 'cover' }] },
    ],
    [
      'an unsafe media source URL',
      { ...projectResponse, media: [{ ...projectResponse.media[0], sourceUrl: 'http://example.test' }] },
    ],
    [
      'a malformed block discriminator',
      {
        ...projectResponse,
        blocks: [
          {
            id: '50000000-0000-4000-8000-000000000010',
            sortOrder: 0,
            visible: true,
            width: 'STANDARD',
            alignment: 'LEFT',
            emphasis: 'NONE',
            columns: 1,
            payload: { type: 'UNKNOWN' },
          },
        ],
      },
    ],
  ])('rejects a successful project response containing %s', async (_case, malformed) => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: malformed } as never)

    await expect(projectApi.get(PROJECT_ID)).rejects.toMatchObject(invalidResponse())
  })

  it.each([
    [
      'duplicate project identities',
      [projectResponse, { ...projectResponse, sortOrder: 5 }],
    ],
    [
      'duplicate catalog sort orders',
      [
        projectResponse,
        {
          ...projectResponse,
          id: CREATED_PROJECT_ID,
          externalKey: 'another',
          slug: 'another',
        },
      ],
    ],
    [
      'duplicate external keys',
      [
        projectResponse,
        {
          ...projectResponse,
          id: CREATED_PROJECT_ID,
          slug: 'another',
          sortOrder: 5,
        },
      ],
    ],
    [
      'duplicate slugs',
      [
        projectResponse,
        {
          ...projectResponse,
          id: CREATED_PROJECT_ID,
          externalKey: 'another',
          sortOrder: 5,
        },
      ],
    ],
  ])('rejects a project list with %s', async (_case, malformed) => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: malformed } as never)

    await expect(projectApi.list()).rejects.toMatchObject(invalidResponse())
  })

  it('loads and updates global tag/skill workspaces with exact versions and locale names', async () => {
    const get = vi
      .spyOn(http, 'get')
      .mockResolvedValueOnce({ data: [tagResponse] } as never)
      .mockResolvedValueOnce({ data: [skillResponse] } as never)
    const request: UpdateTaxonomyRequest = {
      expectedVersion: 3,
      names: { 'zh-CN': '核心玩法', en: 'Core gameplay' },
    }
    const updated = { ...tagResponse, version: 4, names: request.names }
    const put = vi.spyOn(http, 'put').mockResolvedValueOnce({ data: updated } as never)

    await expect(projectApi.tags()).resolves.toEqual([tagResponse])
    await expect(projectApi.skills()).resolves.toEqual([skillResponse])
    await expect(projectApi.updateTag(TAG_ID, request)).resolves.toEqual(updated)
    expect(get).toHaveBeenNthCalledWith(1, '/api/admin/tags')
    expect(get).toHaveBeenNthCalledWith(2, '/api/admin/skills')
    expect(put).toHaveBeenCalledWith(`/api/admin/tags/${TAG_ID}`, request)
  })

  it.each([
    ['missing taxonomy locale', [{ ...tagResponse, names: { en: 'Gameplay' } }]],
    ['an extra taxonomy property', [{ ...tagResponse, extra: true }]],
    ['duplicate taxonomy ids', [tagResponse, tagResponse]],
    [
      'duplicate normalized keys',
      [{ ...tagResponse }, { ...skillResponse, normalizedKey: tagResponse.normalizedKey }],
    ],
  ])('rejects taxonomy lists containing %s', async (_case, malformed) => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: malformed } as never)

    await expect(projectApi.tags()).rejects.toMatchObject(invalidResponse())
  })

  it('rejects malformed mutation results instead of trusting a 2xx response', async () => {
    const workspace = clonedProject()
    vi.spyOn(http, 'put').mockResolvedValueOnce({
      data: { ...projectResponse, version: 9 },
    } as never)

    await expect(
      projectApi.save(PROJECT_ID, { expectedVersion: 7, workspace }),
    ).rejects.toMatchObject(invalidResponse())
  })

  it('blocks invalid paths and inconsistent CAS inputs before making a request', async () => {
    const get = vi.spyOn(http, 'get')
    const put = vi.spyOn(http, 'put')
    const workspace = clonedProject()

    await expect(projectApi.get('..')).rejects.toThrow('project id must be a UUID')
    await expect(
      projectApi.save(CREATED_PROJECT_ID, { expectedVersion: 7, workspace }),
    ).rejects.toThrow('project path/body id mismatch')
    await expect(
      projectApi.save(PROJECT_ID, { expectedVersion: 6, workspace }),
    ).rejects.toThrow('expectedVersion must match workspace.version')
    await expect(
      projectApi.save(PROJECT_ID, {
        expectedVersion: Number.MAX_SAFE_INTEGER + 1,
        workspace,
      }),
    ).rejects.toThrow('expectedVersion must be a non-negative safe integer')
    await expect(
      projectApi.updateSkill('not-a-uuid', {
        expectedVersion: 5,
        names: skillResponse.names,
      }),
    ).rejects.toThrow('taxonomy id must be a UUID')

    expect(get).not.toHaveBeenCalled()
    expect(put).not.toHaveBeenCalled()
  })

  it('does not retry rejected project or taxonomy mutations', async () => {
    const conflict = new ApiProblem({
      type: 'conflict',
      title: 'Version conflict',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'project-conflict',
    })
    const post = vi.spyOn(http, 'post').mockRejectedValue(conflict)
    const put = vi.spyOn(http, 'put').mockRejectedValue(conflict)

    await expect(projectApi.create(clonedProject())).rejects.toBe(conflict)
    await expect(
      projectApi.save(PROJECT_ID, {
        expectedVersion: 7,
        workspace: clonedProject(),
      }),
    ).rejects.toBe(conflict)
    await expect(
      projectApi.updateTag(TAG_ID, {
        expectedVersion: 3,
        names: tagResponse.names,
      }),
    ).rejects.toBe(conflict)

    expect(post).toHaveBeenCalledOnce()
    expect(put).toHaveBeenCalledTimes(2)
  })
})
