import type { AxiosInstance } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import {
  PROJECT_CATALOG_ID,
  SITE_ID,
  type PreviewTokenRequest,
  type PublicationResultDto,
} from '@/types/publishing'

import { createPublishingApi } from './publishingApi'

const PROJECT_ID = '10000000-0000-4000-8000-000000000001'
const SECOND_PROJECT_ID = '10000000-0000-4000-8000-000000000002'
const REVISION_ID = '20000000-0000-4000-8000-000000000001'
const CATALOG_REVISION_ID = '20000000-0000-4000-8000-000000000002'
const ADMIN_ID = '30000000-0000-4000-8000-000000000001'
const TOKEN = `eyJhZ2dyZWdhdGVUeXBlIjoiU0lURSJ9.${'a'.repeat(43)}`
const CHECKSUM = 'b'.repeat(64)

function response(data: unknown, status = 200) {
  return { data, status }
}

function siteResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    revisionId: REVISION_ID,
    aggregateVersion: 3,
    catalogRevisionId: null,
    catalogVersion: null,
    checksum: CHECKSUM,
    ...overrides,
  }
}

function projectResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    revisionId: REVISION_ID,
    aggregateVersion: 4,
    catalogRevisionId: CATALOG_REVISION_ID,
    catalogVersion: 7,
    checksum: CHECKSUM,
    ...overrides,
  }
}

function revision(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: REVISION_ID,
    type: 'PROJECT',
    aggregateId: PROJECT_ID,
    version: 3,
    schemaVersion: 1,
    checksum: CHECKSUM,
    publishedBy: ADMIN_ID,
    publishedAt: '2026-07-18T01:02:03.123456789Z',
    ...overrides,
  }
}

function createClient() {
  const get = vi.fn()
  const post = vi.fn()
  const put = vi.fn()
  const client = { get, post, put } as unknown as AxiosInstance
  return { client, get, post, put, api: createPublishingApi(client) }
}

describe('publishingApi preview boundary', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  it('posts the exact three-field preview request without a locale and normalizes the response', async () => {
    const { api, post } = createClient()
    const wire = { token: TOKEN, expiresAt: '2999-07-18T01:02:03Z' }
    post.mockResolvedValueOnce(response(wire))
    const request: PreviewTokenRequest = {
      aggregateType: 'SITE',
      aggregateId: SITE_ID,
      workspaceVersion: 8,
    }

    const result = await api.createPreview(request)

    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith('/api/admin/publishing/preview-tokens', {
      aggregateType: 'SITE',
      aggregateId: SITE_ID,
      workspaceVersion: 8,
    })
    expect(Object.keys(post.mock.calls[0]?.[1] as object)).not.toContain('locale')
    expect(result).toEqual(wire)
    expect(result).not.toBe(wire)
  })

  it('rejects extra request keys, catalog previews, and mismatched fixed identities before I/O', async () => {
    const { api, post } = createClient()

    await expect(
      api.createPreview({
        aggregateType: 'SITE',
        aggregateId: SITE_ID,
        workspaceVersion: 1,
        locale: 'en',
      } as unknown as PreviewTokenRequest),
    ).rejects.toThrow(TypeError)
    await expect(
      api.createPreview({
        aggregateType: 'PROJECT_CATALOG',
        aggregateId: PROJECT_CATALOG_ID,
        workspaceVersion: 1,
      } as unknown as PreviewTokenRequest),
    ).rejects.toThrow(TypeError)
    await expect(
      api.createPreview({ aggregateType: 'SITE', aggregateId: PROJECT_ID, workspaceVersion: 1 }),
    ).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()
  })

  it.each([
    ['', 'empty token'],
    ['a', 'one token segment'],
    [`a.b.${'c'.repeat(43)}`, 'three token segments'],
    [`a.${'a'.repeat(42)}`, 'short signature'],
    [`a.${'a'.repeat(44)}`, 'long signature'],
    [`a=.${'a'.repeat(43)}`, 'non-base64url payload'],
    [`a.${'a'.repeat(43)}`, 'non-canonical payload length'],
  ])('rejects a malformed response token: %s (%s)', async (token) => {
    const { api, post } = createClient()
    post.mockResolvedValueOnce(response({ token, expiresAt: '2999-07-18T01:02:03Z' }))

    await expect(
      api.createPreview({ aggregateType: 'PROJECT', aggregateId: PROJECT_ID, workspaceVersion: 1 }),
    ).rejects.toMatchObject({ body: { code: 'INVALID_SERVER_RESPONSE' } })
  })

  it('requires an exact response and a valid future UTC expiry', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-18T01:02:03Z'))
    const { api, post } = createClient()
    const request: PreviewTokenRequest = {
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_ID,
      workspaceVersion: 1,
    }
    post
      .mockResolvedValueOnce(response({ token: TOKEN, expiresAt: '2026-07-18T01:02:03Z' }))
      .mockResolvedValueOnce(response({ token: TOKEN, expiresAt: '2026-02-30T01:02:03Z' }))
      .mockResolvedValueOnce(
        response({ token: TOKEN, expiresAt: '2999-07-18T01:02:03Z', locale: 'en' }),
      )

    await expect(api.createPreview(request)).rejects.toBeInstanceOf(ApiProblem)
    await expect(api.createPreview(request)).rejects.toBeInstanceOf(ApiProblem)
    await expect(api.createPreview(request)).rejects.toBeInstanceOf(ApiProblem)
  })

  it('constructs only a relative encoded URL and preflights that same endpoint once', async () => {
    const { api, get } = createClient()
    get.mockResolvedValueOnce(response({ preview: true }))

    expect(api.previewUrl(TOKEN)).toBe(
      `/api/admin/publishing/previews/${encodeURIComponent(TOKEN)}`,
    )
    await expect(api.preflightPreview(TOKEN)).resolves.toBeUndefined()
    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith(api.previewUrl(TOKEN))
  })

  it('does not retry a rejected preview preflight', async () => {
    const { api, get } = createClient()
    const failure = new Error('offline')
    get.mockRejectedValue(failure)

    await expect(api.preflightPreview(TOKEN)).rejects.toBe(failure)
    expect(get).toHaveBeenCalledOnce()
  })
})

describe('publishingApi mutation contracts', () => {
  it('publishes SITE and PROJECT targets through exact wire commands', async () => {
    const { api, post } = createClient()
    post
      .mockResolvedValueOnce(response(siteResult()))
      .mockResolvedValueOnce(response(projectResult()))

    await expect(
      api.publishTarget({
        aggregateType: 'SITE',
        aggregateId: SITE_ID,
        expectedWorkspaceVersion: 8,
        expectedPublicationVersion: 2,
      }),
    ).resolves.toEqual(siteResult())
    await expect(
      api.publishTarget({
        aggregateType: 'PROJECT',
        aggregateId: PROJECT_ID,
        expectedWorkspaceVersion: 9,
        expectedProjectPublicationVersion: 3,
        expectedCatalogVersion: 6,
      }),
    ).resolves.toEqual(projectResult())

    expect(post).toHaveBeenNthCalledWith(1, '/api/admin/publishing/site', {
      expectedWorkspaceVersion: 8,
      expectedPublicationVersion: 2,
    })
    expect(post).toHaveBeenNthCalledWith(2, `/api/admin/publishing/projects/${PROJECT_ID}`, {
      projectId: PROJECT_ID,
      expectedWorkspaceVersion: 9,
      expectedProjectPublicationVersion: 3,
      expectedCatalogVersion: 6,
    })
  })

  it('normalizes both omitted and explicit nullable catalog result fields', async () => {
    const { api, post } = createClient()
    const omitted = {
      revisionId: REVISION_ID,
      aggregateVersion: 3,
      checksum: CHECKSUM,
    }
    post
      .mockResolvedValueOnce(response(omitted))
      .mockResolvedValueOnce(response(siteResult()))

    const first = await api.publishSite({
      expectedWorkspaceVersion: 4,
      expectedPublicationVersion: 2,
    })
    const second = await api.publishSite({
      expectedWorkspaceVersion: 4,
      expectedPublicationVersion: 2,
    })

    expect(first).toEqual({ ...omitted, catalogRevisionId: null, catalogVersion: null })
    expect(second.catalogRevisionId).toBeNull()
    expect(second.catalogVersion).toBeNull()
  })

  it('uses aggregateVersion—not catalogVersion—for catalog reorder CAS', async () => {
    const { api, put } = createClient()
    put.mockResolvedValueOnce(response(siteResult({ aggregateVersion: 10 })))
    const command = {
      expectedCatalogVersion: 9,
      projectIdsInOrder: [SECOND_PROJECT_ID, PROJECT_ID],
    }

    await expect(api.reorderCatalog(command)).resolves.toMatchObject({ aggregateVersion: 10 })
    expect(put).toHaveBeenCalledOnce()
    expect(put).toHaveBeenCalledWith('/api/admin/publishing/catalog/order', command)
    expect(put.mock.calls[0]?.[1]).not.toBe(command)
    expect((put.mock.calls[0]?.[1] as { projectIdsInOrder: string[] }).projectIdsInOrder).not.toBe(
      command.projectIdsInOrder,
    )
  })

  it('archives with the exact project command and validates both pointer successors', async () => {
    const { api, post } = createClient()
    post.mockResolvedValueOnce(
      response(projectResult({ aggregateVersion: 4, catalogVersion: 7 })),
    )
    const command = {
      projectId: PROJECT_ID,
      expectedProjectPublicationVersion: 3,
      expectedCatalogVersion: 6,
    }

    await expect(api.archiveProject(command)).resolves.toMatchObject({ aggregateVersion: 4 })
    expect(post).toHaveBeenCalledWith(
      `/api/admin/publishing/projects/${PROJECT_ID}/archive`,
      command,
    )
  })

  it('rejects an archive response with a mismatched project pointer successor', async () => {
    const { api, post } = createClient()
    post.mockResolvedValueOnce(
      response(projectResult({ aggregateVersion: 99, catalogVersion: 7 })),
    )

    await expect(
      api.archiveProject({
        projectId: PROJECT_ID,
        expectedProjectPublicationVersion: 3,
        expectedCatalogVersion: 6,
      }),
    ).rejects.toMatchObject({ body: { code: 'INVALID_SERVER_RESPONSE' } })
  })

  it.each([
    ['SITE aggregate version', 'site', siteResult({ aggregateVersion: 4 })],
    ['SITE catalog fields', 'site', siteResult({ catalogRevisionId: CATALOG_REVISION_ID, catalogVersion: 4 })],
    ['PROJECT aggregate version', 'project', projectResult({ aggregateVersion: 5 })],
    ['PROJECT catalog version', 'project', projectResult({ catalogVersion: 8 })],
    ['REORDER aggregate version', 'reorder', siteResult({ aggregateVersion: 9 })],
  ])('rejects a response with a mismatched %s', async (_name, method, wire) => {
    const { api, post, put } = createClient()
    post.mockResolvedValue(response(wire))
    put.mockResolvedValue(response(wire))

    const operation =
      method === 'site'
        ? api.publishSite({ expectedWorkspaceVersion: 1, expectedPublicationVersion: 2 })
        : method === 'project'
          ? api.publishProject({
              projectId: PROJECT_ID,
              expectedWorkspaceVersion: 1,
              expectedProjectPublicationVersion: 3,
              expectedCatalogVersion: 6,
            })
          : api.reorderCatalog({
              expectedCatalogVersion: 9,
              projectIdsInOrder: [PROJECT_ID],
            })

    await expect(operation).rejects.toMatchObject({
      body: { code: 'INVALID_SERVER_RESPONSE' },
    })
  })

  it.each([
    siteResult({ checksum: 'A'.repeat(64) }),
    siteResult({ aggregateVersion: 0 }),
    siteResult({ extra: true }),
    siteResult({ catalogRevisionId: null, catalogVersion: 4 }),
    siteResult({ catalogRevisionId: CATALOG_REVISION_ID, catalogVersion: null }),
    { revisionId: REVISION_ID, aggregateVersion: 3, catalogRevisionId: null, checksum: CHECKSUM },
  ])('rejects a malformed publication result %#', async (wire) => {
    const { api, post } = createClient()
    post.mockResolvedValueOnce(response(wire))

    await expect(
      api.publishSite({ expectedWorkspaceVersion: 1, expectedPublicationVersion: 2 }),
    ).rejects.toBeInstanceOf(ApiProblem)
  })

  it('rejects duplicate/incomplete reorder IDs and extra target keys before I/O', async () => {
    const { api, post, put } = createClient()

    await expect(
      api.reorderCatalog({
        expectedCatalogVersion: 1,
        projectIdsInOrder: [PROJECT_ID, PROJECT_ID.toUpperCase()],
      }),
    ).rejects.toThrow(TypeError)
    await expect(
      api.reorderCatalog({
        expectedCatalogVersion: 1,
        projectIdsInOrder: [PROJECT_ID, 'not-a-uuid'],
      }),
    ).rejects.toThrow(TypeError)
    await expect(
      api.publishTarget({
        aggregateType: 'SITE',
        aggregateId: SITE_ID,
        expectedWorkspaceVersion: 1,
        expectedPublicationVersion: 1,
        projectId: PROJECT_ID,
      } as never),
    ).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()
    expect(put).not.toHaveBeenCalled()
  })

  it.each(['createPreview', 'publishSite', 'publishProject', 'archiveProject', 'reorderCatalog'])(
    'does not retry a rejected %s mutation',
    async (method) => {
      const { api, post, put } = createClient()
      const failure = new Error('network failed')
      post.mockRejectedValue(failure)
      put.mockRejectedValue(failure)

      const operation =
        method === 'createPreview'
          ? api.createPreview({
              aggregateType: 'PROJECT',
              aggregateId: PROJECT_ID,
              workspaceVersion: 1,
            })
          : method === 'publishSite'
            ? api.publishSite({ expectedWorkspaceVersion: 1, expectedPublicationVersion: 1 })
            : method === 'publishProject'
              ? api.publishProject({
                  projectId: PROJECT_ID,
                  expectedWorkspaceVersion: 1,
                  expectedProjectPublicationVersion: 1,
                  expectedCatalogVersion: 1,
                })
              : method === 'archiveProject'
                ? api.archiveProject({
                    projectId: PROJECT_ID,
                    expectedProjectPublicationVersion: 1,
                    expectedCatalogVersion: 1,
                  })
                : api.reorderCatalog({
                    expectedCatalogVersion: 1,
                    projectIdsInOrder: [PROJECT_ID],
                  })

      await expect(operation).rejects.toBe(failure)
      expect(method === 'reorderCatalog' ? put : post).toHaveBeenCalledOnce()
    },
  )
})

describe('publishingApi history, restore, and state', () => {
  it('validates history ownership and returns a defensive copy', async () => {
    const { api, get } = createClient()
    const wire = [revision()]
    get.mockResolvedValueOnce(response(wire))

    const result = await api.history('PROJECT', PROJECT_ID)

    expect(get).toHaveBeenCalledWith(
      `/api/admin/publishing/PROJECT/${PROJECT_ID}/history`,
    )
    expect(result).toEqual(wire)
    expect(result).not.toBe(wire)
    expect(result[0]).not.toBe(wire[0])
  })

  it.each([
    { wire: [revision({ type: 'SITE' })] },
    { wire: [revision({ aggregateId: SECOND_PROJECT_ID })] },
    { wire: [revision(), revision({ id: REVISION_ID.toUpperCase(), version: 4 })] },
    { wire: [revision(), revision({ id: CATALOG_REVISION_ID, version: 3 })] },
    { wire: [revision({ schemaVersion: 0 })] },
    { wire: [revision({ publishedAt: '2026-02-30T01:02:03Z' })] },
    { wire: [revision({ checksum: CHECKSUM.toUpperCase() })] },
    { wire: [revision({ extra: true })] },
  ])('rejects malformed or cross-aggregate history %#', async ({ wire }) => {
    const { api, get } = createClient()
    get.mockResolvedValueOnce(response(wire))

    await expect(api.history('PROJECT', PROJECT_ID)).rejects.toBeInstanceOf(ApiProblem)
  })

  it('requires fixed aggregate IDs before history or state I/O', async () => {
    const { api, get } = createClient()

    await expect(api.history('SITE', PROJECT_ID)).rejects.toThrow(TypeError)
    await expect(api.state('PROJECT_CATALOG', PROJECT_ID)).rejects.toThrow(TypeError)
    await expect(api.state('PROJECT', SITE_ID)).rejects.toThrow(TypeError)
    expect(get).not.toHaveBeenCalled()
  })

  it('accepts exact UNPUBLISHED, PUBLISHED, and ARCHIVED state invariants', async () => {
    const { api, get } = createClient()
    const unpublished = {
      aggregateType: 'SITE',
      aggregateId: SITE_ID,
      status: 'UNPUBLISHED',
      version: 0,
      currentRevisionId: null,
      publishedAt: null,
      projectIdsInOrder: [],
    }
    const publishedCatalog = {
      aggregateType: 'PROJECT_CATALOG',
      aggregateId: PROJECT_CATALOG_ID,
      status: 'PUBLISHED',
      version: 7,
      currentRevisionId: CATALOG_REVISION_ID,
      publishedAt: '2026-07-18T01:02:03Z',
      projectIdsInOrder: [SECOND_PROJECT_ID, PROJECT_ID],
    }
    const archivedProject = {
      aggregateType: 'PROJECT',
      aggregateId: PROJECT_ID,
      status: 'ARCHIVED',
      version: 4,
      currentRevisionId: REVISION_ID,
      publishedAt: '2026-07-18T01:02:03Z',
      projectIdsInOrder: [],
    }
    get
      .mockResolvedValueOnce(response(unpublished))
      .mockResolvedValueOnce(response(publishedCatalog))
      .mockResolvedValueOnce(response(archivedProject))

    await expect(api.state('SITE', SITE_ID)).resolves.toEqual(unpublished)
    const catalog = await api.state('PROJECT_CATALOG', PROJECT_CATALOG_ID)
    await expect(api.state('PROJECT', PROJECT_ID)).resolves.toEqual(archivedProject)
    expect(catalog).toEqual(publishedCatalog)
    expect(catalog.projectIdsInOrder).not.toBe(publishedCatalog.projectIdsInOrder)
  })

  it.each([
    {
      aggregateType: 'SITE', aggregateId: SITE_ID, status: 'UNPUBLISHED', version: 1,
      currentRevisionId: null, publishedAt: null, projectIdsInOrder: [],
    },
    {
      aggregateType: 'SITE', aggregateId: SITE_ID, status: 'PUBLISHED', version: 1,
      currentRevisionId: null, publishedAt: '2026-07-18T01:02:03Z', projectIdsInOrder: [],
    },
    {
      aggregateType: 'SITE', aggregateId: SITE_ID, status: 'ARCHIVED', version: 0,
      currentRevisionId: null, publishedAt: null, projectIdsInOrder: [],
    },
    {
      aggregateType: 'SITE', aggregateId: SITE_ID, status: 'PUBLISHED', version: 1,
      currentRevisionId: REVISION_ID, publishedAt: '2026-07-18T01:02:03Z',
      projectIdsInOrder: [PROJECT_ID],
    },
    {
      aggregateType: 'PROJECT_CATALOG', aggregateId: PROJECT_CATALOG_ID, status: 'PUBLISHED', version: 1,
      currentRevisionId: CATALOG_REVISION_ID, publishedAt: '2026-07-18T01:02:03Z',
      projectIdsInOrder: [PROJECT_ID, PROJECT_ID.toUpperCase()],
    },
  ])('rejects an inconsistent publication state %#', async (wire) => {
    const { api, get } = createClient()
    get.mockResolvedValueOnce(response(wire))
    const type = wire.aggregateType as 'SITE' | 'PROJECT_CATALOG'
    const id = type === 'SITE' ? SITE_ID : PROJECT_CATALOG_ID

    await expect(api.state(type, id)).rejects.toBeInstanceOf(ApiProblem)
  })

  it('posts an exact restore request and requires HTTP 204', async () => {
    const { api, post } = createClient()
    post.mockResolvedValueOnce(response(undefined, 204)).mockResolvedValueOnce(response(undefined, 200))

    await expect(api.restore(REVISION_ID, { expectedWorkspaceVersion: 8 })).resolves.toBeUndefined()
    expect(post).toHaveBeenNthCalledWith(
      1,
      `/api/admin/publishing/revisions/${REVISION_ID}/restore`,
      { expectedWorkspaceVersion: 8 },
    )
    await expect(api.restore(REVISION_ID, { expectedWorkspaceVersion: 8 })).rejects.toBeInstanceOf(
      ApiProblem,
    )
  })

  it('rejects malformed restore inputs before I/O and never retries a failure', async () => {
    const { api, post } = createClient()

    await expect(api.restore('not-a-uuid', { expectedWorkspaceVersion: 1 })).rejects.toThrow(
      TypeError,
    )
    await expect(
      api.restore(REVISION_ID, { expectedWorkspaceVersion: 1, extra: true } as never),
    ).rejects.toThrow(TypeError)
    expect(post).not.toHaveBeenCalled()

    const failure = new Error('offline')
    post.mockRejectedValueOnce(failure)
    await expect(api.restore(REVISION_ID, { expectedWorkspaceVersion: 1 })).rejects.toBe(failure)
    expect(post).toHaveBeenCalledOnce()
  })
})
