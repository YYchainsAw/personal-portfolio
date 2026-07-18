export const SITE_ID = '00000000-0000-0000-0000-000000000001' as const
export const PROJECT_CATALOG_ID = '00000000-0000-0000-0000-000000000002' as const

export type AggregateType = 'SITE' | 'PROJECT' | 'PROJECT_CATALOG'
export type PreviewAggregateType = Exclude<AggregateType, 'PROJECT_CATALOG'>
export type PublicationStatus = 'UNPUBLISHED' | 'PUBLISHED' | 'ARCHIVED'

export interface PreviewTokenRequest {
  readonly aggregateType: PreviewAggregateType
  readonly aggregateId: string
  readonly workspaceVersion: number
}

export interface PreviewTokenResponse {
  readonly token: string
  readonly expiresAt: string
}

export interface PublishSiteCommand {
  readonly expectedWorkspaceVersion: number
  readonly expectedPublicationVersion: number
}

export interface PublishProjectCommand {
  readonly projectId: string
  readonly expectedWorkspaceVersion: number
  readonly expectedProjectPublicationVersion: number
  readonly expectedCatalogVersion: number
}

export interface ArchiveProjectCommand {
  readonly projectId: string
  readonly expectedProjectPublicationVersion: number
  readonly expectedCatalogVersion: number
}

export interface ReorderCatalogCommand {
  readonly expectedCatalogVersion: number
  readonly projectIdsInOrder: readonly string[]
}

export interface PublicationResultDto {
  readonly revisionId: string
  readonly aggregateVersion: number
  readonly catalogRevisionId: string | null
  readonly catalogVersion: number | null
  readonly checksum: string
}

export interface RevisionSummaryDto {
  readonly id: string
  readonly type: AggregateType
  readonly aggregateId: string
  readonly version: number
  readonly schemaVersion: number
  readonly checksum: string
  readonly publishedBy: string
  readonly publishedAt: string
}

export interface RestoreRevisionRequest {
  readonly expectedWorkspaceVersion: number
}

export interface PublicationStateDto {
  readonly aggregateType: AggregateType
  readonly aggregateId: string
  readonly status: PublicationStatus
  readonly version: number
  readonly currentRevisionId: string | null
  readonly publishedAt: string | null
  readonly projectIdsInOrder: readonly string[]
}

export type SitePublishTarget = {
  readonly aggregateType: 'SITE'
  readonly aggregateId: typeof SITE_ID
} & PublishSiteCommand

export type ProjectPublishTarget = {
  readonly aggregateType: 'PROJECT'
  readonly aggregateId: string
} & Omit<PublishProjectCommand, 'projectId'>

export type PublishTarget = SitePublishTarget | ProjectPublishTarget
