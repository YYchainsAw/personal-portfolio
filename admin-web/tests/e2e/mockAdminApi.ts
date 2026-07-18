import { Buffer } from 'node:buffer'

import type { Page, Request, Route } from '@playwright/test'

import type {
  MediaAssetView,
  MediaTranslationInput,
  ProjectWorkspaceDto,
  SiteWorkspaceDto,
  TaxonomyWorkspaceDto,
} from '../../src/types/content'
import type {
  AnalyticsBreakdownItemDto,
  AnalyticsPointDto,
  AnalyticsSummaryDto,
  MessageDetailDto,
  MessageStatus,
} from '../../src/types/operations'
import type {
  AggregateType,
  PublicationStateDto,
  RevisionSummaryDto,
} from '../../src/types/publishing'
import type {
  AdminAuditItem,
  OperationsStatus,
  SessionView,
  TotpEnrollmentResponse,
} from '../../src/types/settings'

export const MOCK_IDS = Object.freeze({
  admin: '10000000-0000-4000-8000-000000000001',
  site: '00000000-0000-0000-0000-000000000001',
  catalog: '00000000-0000-0000-0000-000000000002',
  project: '20000000-0000-4000-8000-000000000001',
  secondProject: '20000000-0000-4000-8000-000000000002',
  tag: '21000000-0000-4000-8000-000000000001',
  skill: '22000000-0000-4000-8000-000000000001',
  readyImage: '23000000-0000-4000-8000-000000000001',
  referencedImage: '23000000-0000-4000-8000-000000000002',
  readyPdf: '23000000-0000-4000-8000-000000000003',
  currentSession: '30000000-0000-4000-8000-000000000001',
  otherSession: '30000000-0000-4000-8000-000000000002',
  expiredSession: '30000000-0000-4000-8000-000000000003',
  enrollment: '40000000-0000-4000-8000-000000000001',
})

export const PREVIEW_TOKEN = `ticket-1.${'A'.repeat(43)}`

export type MockAuthPhase = 'anonymous' | 'second-factor' | 'authenticated'

export type AdminConflictTarget =
  | 'site-save'
  | 'project-save'
  | 'site-publish'
  | 'project-publish'
  | 'catalog-reorder'

export type SecurityAction =
  | 'password'
  | 'totp-enrollment'
  | 'totp-confirm'
  | 'recovery-regenerate'
  | 'session-revoke'

export interface ScriptedProblem {
  readonly status: 401 | 409 | 422 | 429 | 500 | 503
  readonly code: string
  readonly title?: string
  readonly fieldErrors?: Readonly<Record<string, string>>
  readonly retryAfterSeconds?: number
}

export interface AdminApiRequest {
  readonly method: string
  readonly url: string
  readonly path: string
  readonly query: Readonly<Record<string, string>>
  readonly headers: Readonly<Record<string, string>>
  readonly body: unknown
}

export interface AdminApiOptions {
  readonly initialAuth?: MockAuthPhase
  /** Convenience alias for older specs. `initialAuth` wins when both are supplied. */
  readonly authenticated?: boolean
  readonly conflictOnce?: AdminConflictTarget | readonly AdminConflictTarget[]
  readonly securityErrors?: Partial<
    Record<SecurityAction, ScriptedProblem | readonly ScriptedProblem[]>
  >
  readonly referencedMediaArchiveConflict?: boolean
}

interface PublicationVersions {
  site: number
  catalog: number
  projects: Record<string, number>
}

interface AuditCursorState {
  readonly fingerprint: string
  readonly start: number
}

export interface AdminApiState {
  authPhase: MockAuthPhase
  csrfToken: string
  requests: AdminApiRequest[]
  siteWorkspace: SiteWorkspaceDto
  projects: ProjectWorkspaceDto[]
  tags: TaxonomyWorkspaceDto[]
  skills: TaxonomyWorkspaceDto[]
  media: MediaAssetView[]
  messages: MessageDetailDto[]
  sessions: SessionView[]
  auditItems: AdminAuditItem[]
  auditCursors: Record<string, AuditCursorState>
  auditCursorSequence: number
  operations: OperationsStatus
  publicationVersions: PublicationVersions
  publicationStates: Record<string, PublicationStateDto>
  revisions: RevisionSummaryDto[]
  conflicts: AdminConflictTarget[]
  securityErrors: Record<SecurityAction, ScriptedProblem[]>
  totpEnrollment: TotpEnrollmentResponse | null
  deletedMessageIds: string[]
  referencedMediaArchiveConflict: boolean
  uploadSequence: number
}

export interface AdminApiHarness {
  readonly state: AdminApiState
  readonly requests: AdminApiRequest[]
  scriptConflict(target: AdminConflictTarget): void
  scriptSecurityError(action: SecurityAction, problem: ScriptedProblem): void
  dispose(): Promise<void>
}

const CSRF_TOKEN = 'synthetic-csrf-token'
const MOCK_ORIGIN = 'http://127.0.0.1:4174'
const ROUTE_PATTERN = '**/api/admin/**'
const SHA_A = 'a'.repeat(64)
const SHA_B = 'b'.repeat(64)
const SHA_C = 'c'.repeat(64)

const SECURITY_ACTIONS: readonly SecurityAction[] = [
  'password',
  'totp-enrollment',
  'totp-confirm',
  'recovery-regenerate',
  'session-revoke',
]

const RECOVERY_CODES = Object.freeze([
  'ABCD-EFGH-JKL2',
  'ABCD-EFGH-JKL3',
  'ABCD-EFGH-JKL4',
  'ABCD-EFGH-JKL5',
  'ABCD-EFGH-JKL6',
  'ABCD-EFGH-JKL7',
  'BCDE-FGHJ-KLM2',
  'BCDE-FGHJ-KLM3',
  'BCDE-FGHJ-KLM4',
  'BCDE-FGHJ-KLM5',
])

type JsonRecord = Record<string, unknown>

interface UploadedFile {
  readonly name: string
  readonly mimeType: 'image/jpeg' | 'image/png' | 'application/pdf'
  readonly byteSize: number
}

interface ParsedRequestBody {
  readonly body: unknown
  readonly uploadedFile: UploadedFile | null
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function localized<T>(chinese: T, english: T): { 'zh-CN': T; en: T } {
  return { 'zh-CN': chinese, en: english }
}

function createSiteWorkspace(): SiteWorkspaceDto {
  return {
    siteId: MOCK_IDS.site,
    version: 4,
    monogram: 'DEMO',
    email: 'portfolio-owner@example.test',
    identity: localized(
      { displayName: '示例开发者', secondaryName: 'Demo Dev' },
      { displayName: 'Demo Developer', secondaryName: 'Demo Dev' },
    ),
    seo: localized(
      { title: '示例游戏开发作品集', description: '完全合成的端到端测试内容。' },
      { title: 'Demo Game Portfolio', description: 'Fully synthetic end-to-end fixture.' },
    ),
    accessibility: localized(
      {
        skip: '跳到内容',
        primaryNav: '主导航',
        mobileNav: '移动导航',
        openMenu: '打开菜单',
        closeMenu: '关闭菜单',
        language: '切换语言',
        backToTop: '返回顶部',
        projectTags: '项目标签',
      },
      {
        skip: 'Skip to content',
        primaryNav: 'Primary navigation',
        mobileNav: 'Mobile navigation',
        openMenu: 'Open menu',
        closeMenu: 'Close menu',
        language: 'Change language',
        backToTop: 'Back to top',
        projectTags: 'Project tags',
      },
    ),
    navigation: [
      {
        id: '11000000-0000-4000-8000-000000000001',
        target: 'work',
        sortOrder: 0,
        visible: true,
        labels: localized('作品', 'Work'),
      },
    ],
    hero: {
      id: '12000000-0000-4000-8000-000000000001',
      version: 1,
      mediaAssetId: MOCK_IDS.readyImage,
      objectPosition: '50% 50%',
      credit: 'Synthetic Studio',
      sourceUrl: 'https://example.test/assets/demo',
      copy: localized(
        {
          eyebrow: '游戏开发',
          displayName: '示例开发者',
          secondaryName: 'Demo Dev',
          role: '游戏开发学习者',
          headline: '构建清晰、可玩的系统。',
          introduction: '用于端到端测试的合成个人简介。',
          availability: '开放合作',
          primaryCta: '查看作品',
          secondaryCta: '成长路线',
          visualLabel: '示例视觉',
          stageLabel: '当前阶段',
        },
        {
          eyebrow: 'Game development',
          displayName: 'Demo Developer',
          secondaryName: 'Demo Dev',
          role: 'Game development learner',
          headline: 'Building clear, playable systems.',
          introduction: 'A synthetic profile used only for end-to-end tests.',
          availability: 'Open to collaboration',
          primaryCta: 'View work',
          secondaryCta: 'Roadmap',
          visualLabel: 'Demo visual',
          stageLabel: 'Current stage',
        },
      ),
    },
    about: localized(
      {
        label: '关于',
        title: '示例开发者',
        statement: '这是一段合成介绍。',
        focusLabel: '当前专注',
        focusTitle: '玩法系统',
        focusIntro: '练习工程与交互。',
      },
      {
        label: 'About',
        title: 'Demo developer',
        statement: 'This is synthetic introduction copy.',
        focusLabel: 'Current focus',
        focusTitle: 'Gameplay systems',
        focusIntro: 'Practising engineering and interaction.',
      },
    ),
    facts: [
      {
        id: '13000000-0000-4000-8000-000000000001',
        externalKey: 'education',
        sortOrder: 0,
        copy: localized(
          { label: '教育', value: '示例大学' },
          { label: 'Education', value: 'Example University' },
        ),
      },
    ],
    profileSkills: [
      {
        id: '14000000-0000-4000-8000-000000000001',
        externalKey: 'game-development',
        sortOrder: 0,
        copy: localized(
          { name: '游戏开发', status: '练习中' },
          { name: 'Game development', status: 'Practising' },
        ),
      },
    ],
    work: localized(
      {
        label: '作品',
        title: '合成项目',
        introduction: '仅用于自动化测试。',
        imageNotice: '媒体均为合成数据。',
        openSlotLabel: '扩展位',
        openSlotTitle: '下一项作品',
        openSlotText: '预留的合成项目位。',
        openSlotMeta: '持续更新',
      },
      {
        label: 'Work',
        title: 'Synthetic projects',
        introduction: 'Used only by automated tests.',
        imageNotice: 'All media records are synthetic.',
        openSlotLabel: 'Open slot',
        openSlotTitle: 'Next project',
        openSlotText: 'Reserved synthetic project space.',
        openSlotMeta: 'Continuously updated',
      },
    ),
    roadmap: {
      header: localized(
        { label: '路线图', title: '合成路线', introduction: '测试阶段数据。' },
        { label: 'Roadmap', title: 'Synthetic roadmap', introduction: 'Test stage data.' },
      ),
      stages: [
        {
          id: '15000000-0000-4000-8000-000000000001',
          externalKey: 'now',
          number: '01',
          sortOrder: 0,
          visible: true,
          copy: localized(
            { period: '现在', title: '玩法原型', summary: '完成一个示例。' },
            { period: 'Now', title: 'Gameplay prototype', summary: 'Complete one demo.' },
          ),
          outcomes: [
            {
              id: '16000000-0000-4000-8000-000000000001',
              sortOrder: 0,
              text: localized('完成测试原型', 'Finish the test prototype'),
            },
          ],
        },
      ],
    },
    contact: localized(
      {
        label: '联系',
        title: '联系示例',
        introduction: '不对应真实联系人。',
        emailLabel: '邮箱',
        workCta: '查看作品',
        roadmapCta: '查看路线',
        footerNote: '合成测试页。',
      },
      {
        label: 'Contact',
        title: 'Contact demo',
        introduction: 'This does not represent a real person.',
        emailLabel: 'Email',
        workCta: 'View work',
        roadmapCta: 'View roadmap',
        footerNote: 'Synthetic test page.',
      },
    ),
    privacy: localized(
      { title: '隐私', bodyMarkdown: '这是合成测试说明。' },
      { title: 'Privacy', bodyMarkdown: 'This is a synthetic test notice.' },
    ),
    socialLinks: [
      {
        id: '17000000-0000-4000-8000-000000000001',
        platform: 'Example',
        url: 'https://example.test/profile',
        sortOrder: 0,
        visible: true,
      },
    ],
    resumes: [
      {
        id: '18000000-0000-4000-8000-000000000001',
        locale: 'zh-CN',
        mediaAssetId: MOCK_IDS.readyPdf,
        versionLabel: 'synthetic-v1',
        current: true,
        documentDate: '2026-07-01',
      },
      {
        id: '18000000-0000-4000-8000-000000000002',
        locale: 'en',
        mediaAssetId: MOCK_IDS.readyPdf,
        versionLabel: 'synthetic-v1',
        current: true,
        documentDate: '2026-07-01',
      },
    ],
  }
}

function projectCopy(title: string, englishTitle: string) {
  return localized(
    {
      status: '开发中',
      eyebrow: '合成项目',
      title,
      summary: '用于浏览器自动化测试的合成玩法原型。',
      seoTitle: `${title}｜合成作品集`,
      seoDescription: '合成项目 SEO 描述。',
    },
    {
      status: 'In development',
      eyebrow: 'Synthetic project',
      title: englishTitle,
      summary: 'A synthetic gameplay prototype for browser automation.',
      seoTitle: `${englishTitle} | Synthetic portfolio`,
      seoDescription: 'Synthetic project SEO description.',
    },
  )
}

function createProject(
  id: string,
  externalKey: string,
  slug: string,
  sortOrder: number,
  title: string,
  englishTitle: string,
): ProjectWorkspaceDto {
  return {
    id,
    externalKey,
    slug,
    number: String(sortOrder + 1).padStart(2, '0'),
    sortOrder,
    featured: sortOrder === 0,
    visible: true,
    publicationDirty: true,
    version: sortOrder === 0 ? 4 : 2,
    translations: projectCopy(title, englishTitle),
    tags: [
      {
        id: MOCK_IDS.tag,
        normalizedKey: 'gameplay',
        sortOrder: 0,
        names: localized('玩法', 'Gameplay'),
      },
    ],
    skills: [
      {
        id: MOCK_IDS.skill,
        normalizedKey: 'engine',
        sortOrder: 0,
        names: localized('示例引擎', 'Example Engine'),
      },
    ],
    media: [
      {
        assetId: MOCK_IDS.referencedImage,
        usage: 'COVER',
        sortOrder: 0,
        layout: 'wide',
        objectPosition: '50% 50%',
        credit: 'Synthetic Studio',
        sourceUrl: 'https://example.test/assets/project',
      },
    ],
    blocks: [],
  }
}

function mediaAsset(
  id: string,
  filename: string,
  mimeType: 'image/jpeg' | 'image/png' | 'application/pdf',
  checksum: string,
): MediaAssetView {
  const image = mimeType !== 'application/pdf'
  return {
    id,
    originalFilename: filename,
    mimeType,
    byteSize: image ? 4096 : 8192,
    width: image ? 1280 : null,
    height: image ? 720 : null,
    sha256: checksum,
    status: 'READY',
    version: 1,
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-01T08:05:00Z',
    translations: [
      {
        locale: 'zh-CN',
        altText: '合成媒体',
        caption: '合成媒体说明',
        credit: 'Synthetic Studio',
        sourceUrl: 'https://example.test/assets/source',
      },
      {
        locale: 'en',
        altText: 'Synthetic media',
        caption: 'Synthetic media caption',
        credit: 'Synthetic Studio',
        sourceUrl: 'https://example.test/assets/source',
      },
    ],
    variants: image
      ? [{ name: 'w640', width: 640, height: 360, status: 'READY' }]
      : [{ name: 'document', width: null, height: null, status: 'READY' }],
  }
}

function messageId(index: number): string {
  return `50000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`
}

function createMessages(): MessageDetailDto[] {
  return Array.from({ length: 32 }, (_, index) => {
    const created = new Date(Date.UTC(2026, 5, 30, 12, 0) - index * 60_000)
    const privacy = new Date(created.getTime() - 30_000)
    const sent = new Date(created.getTime() + 30_000)
    const updated = new Date(created.getTime() + 60_000)
    return {
      id: messageId(index),
      visitorName: `Synthetic Visitor ${String(index + 1).padStart(2, '0')}`,
      visitorEmail: `visitor${String(index + 1).padStart(2, '0')}@example.test`,
      subject: `Synthetic portfolio inquiry ${String(index + 1).padStart(2, '0')}`,
      body:
        index === 0
          ? '<img src=x onerror="window.__E2E_XSS__=1"> This must render as plain text.'
          : `Synthetic message body ${index + 1}.`,
      status: index % 3 === 0 ? 'UNREAD' : 'READ',
      email: {
        status: index === 0 ? 'FAILED' : 'SENT',
        attempts: index === 0 ? 2 : 1,
        nextAttemptAt: sent.toISOString(),
        sentAt: index === 0 ? null : sent.toISOString(),
        updatedAt: updated.toISOString(),
        errorCategory: index === 0 ? 'SMTP_DELIVERY_FAILED' : null,
      },
      privacyAcceptedAt: privacy.toISOString(),
      createdAt: created.toISOString(),
      updatedAt: updated.toISOString(),
      version: 0,
    }
  })
}

function auditId(index: number): string {
  return `60000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`
}

function createAuditItems(): AdminAuditItem[] {
  return Array.from({ length: 55 }, (_, index) => {
    const metadata: Readonly<Record<string, string>> =
      index % 2 === 0 ? { stage: 'SECOND_FACTOR' } : { channel: 'ADMIN_WEB' }
    return {
      id: auditId(index),
      actorAdminId: index % 11 === 0 ? null : MOCK_IDS.admin,
      action: index % 2 === 0 ? 'ADMIN_LOGIN_SUCCEEDED' : 'WORKSPACE_UPDATED',
      targetType: index % 2 === 0 ? 'ADMIN_SESSION' : 'SITE_WORKSPACE',
      targetId: index % 2 === 0 ? MOCK_IDS.currentSession : MOCK_IDS.site,
      outcome: index % 7 === 0 ? 'FAILURE' : 'SUCCESS',
      traceId: `synthetic-trace-${String(index + 1).padStart(2, '0')}`,
      metadata,
      timestamp: new Date(Date.UTC(2026, 6, 18, 10, 0) - index * 60_000)
        .toISOString()
        .replace('.000Z', 'Z'),
    }
  })
}

function createSessions(): SessionView[] {
  return [
    {
      id: MOCK_IDS.currentSession,
      status: 'ACTIVE',
      createdAt: '2026-07-18T09:00:00Z',
      endedAt: null,
      lastAccessMillis: Date.parse('2026-07-18T10:00:00Z'),
      clientSummary: 'Synthetic Chromium · current browser',
      reason: null,
      current: true,
    },
    {
      id: MOCK_IDS.otherSession,
      status: 'ACTIVE',
      createdAt: '2026-07-17T09:00:00Z',
      endedAt: null,
      lastAccessMillis: Date.parse('2026-07-17T10:00:00Z'),
      clientSummary: 'Synthetic Firefox · other browser',
      reason: null,
      current: false,
    },
    {
      id: MOCK_IDS.expiredSession,
      status: 'EXPIRED',
      createdAt: '2026-07-16T09:00:00Z',
      endedAt: '2026-07-16T10:00:00Z',
      lastAccessMillis: Date.parse('2026-07-16T09:30:00Z'),
      clientSummary: 'Synthetic Safari · expired browser',
      reason: 'IDLE_TIMEOUT',
      current: false,
    },
  ]
}

function maintenance(
  type: NonNullable<OperationsStatus[keyof Omit<OperationsStatus, 'serverTime'>]>['type'],
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED',
  errorCategory: NonNullable<
    OperationsStatus[keyof Omit<OperationsStatus, 'serverTime'>]
  >['errorCategory'] = null,
) {
  return {
    type,
    status,
    startedAt: '2026-07-18T06:00:00Z',
    finishedAt: status === 'RUNNING' ? null : '2026-07-18T06:05:00Z',
    artifactChecksum: status === 'SUCCEEDED' ? SHA_C : null,
    errorCategory,
  } as const
}

function createOperations(): OperationsStatus {
  return {
    databaseBackup: maintenance('DATABASE_BACKUP', 'SUCCEEDED'),
    mediaBackup: null,
    analyticsAggregation: maintenance(
      'ANALYTICS_AGGREGATE',
      'FAILED',
      'ANALYTICS_AGGREGATION_FAILED',
    ),
    contactRetention: maintenance('CONTACT_RETENTION', 'RUNNING'),
    mediaCleanup: maintenance('MEDIA_CLEANUP_SCAN', 'SUCCEEDED'),
    deployment: maintenance('DEPLOYMENT', 'FAILED', 'DEPLOYMENT_FAILED'),
    restoreDrill: null,
    serverTime: '2026-07-18T10:00:00Z',
  }
}

function revision(
  id: string,
  type: AggregateType,
  aggregateId: string,
  version: number,
  publishedAt: string,
): RevisionSummaryDto {
  return {
    id,
    type,
    aggregateId,
    version,
    schemaVersion: 1,
    checksum: version % 2 === 0 ? SHA_A : SHA_B,
    publishedBy: MOCK_IDS.admin,
    publishedAt,
  }
}

function publicationKey(type: AggregateType, id: string): string {
  return `${type}:${id.toLowerCase()}`
}

function createPublicationState(): {
  versions: PublicationVersions
  states: Record<string, PublicationStateDto>
  revisions: RevisionSummaryDto[]
} {
  const siteRevision = revision(
    '70000000-0000-4000-8000-000000000001',
    'SITE',
    MOCK_IDS.site,
    1,
    '2026-07-10T08:00:00Z',
  )
  const projectRevision = revision(
    '70000000-0000-4000-8000-000000000002',
    'PROJECT',
    MOCK_IDS.project,
    2,
    '2026-07-11T08:00:00Z',
  )
  const catalogRevision = revision(
    '70000000-0000-4000-8000-000000000003',
    'PROJECT_CATALOG',
    MOCK_IDS.catalog,
    3,
    '2026-07-11T08:00:00Z',
  )
  const secondRevision = revision(
    '70000000-0000-4000-8000-000000000004',
    'PROJECT',
    MOCK_IDS.secondProject,
    1,
    '2026-07-09T08:00:00Z',
  )
  const states: Record<string, PublicationStateDto> = {}
  states[publicationKey('SITE', MOCK_IDS.site)] = {
    aggregateType: 'SITE',
    aggregateId: MOCK_IDS.site,
    status: 'PUBLISHED',
    version: 1,
    currentRevisionId: siteRevision.id,
    publishedAt: siteRevision.publishedAt,
    projectIdsInOrder: [],
  }
  states[publicationKey('PROJECT', MOCK_IDS.project)] = {
    aggregateType: 'PROJECT',
    aggregateId: MOCK_IDS.project,
    status: 'PUBLISHED',
    version: 2,
    currentRevisionId: projectRevision.id,
    publishedAt: projectRevision.publishedAt,
    projectIdsInOrder: [],
  }
  states[publicationKey('PROJECT', MOCK_IDS.secondProject)] = {
    aggregateType: 'PROJECT',
    aggregateId: MOCK_IDS.secondProject,
    status: 'PUBLISHED',
    version: 1,
    currentRevisionId: secondRevision.id,
    publishedAt: secondRevision.publishedAt,
    projectIdsInOrder: [],
  }
  states[publicationKey('PROJECT_CATALOG', MOCK_IDS.catalog)] = {
    aggregateType: 'PROJECT_CATALOG',
    aggregateId: MOCK_IDS.catalog,
    status: 'PUBLISHED',
    version: 3,
    currentRevisionId: catalogRevision.id,
    publishedAt: catalogRevision.publishedAt,
    projectIdsInOrder: [MOCK_IDS.project, MOCK_IDS.secondProject],
  }
  return {
    versions: {
      site: 1,
      catalog: 3,
      projects: { [MOCK_IDS.project]: 2, [MOCK_IDS.secondProject]: 1 },
    },
    states,
    revisions: [siteRevision, projectRevision, catalogRevision, secondRevision],
  }
}

function normalizeSecurityErrors(
  options: AdminApiOptions['securityErrors'],
): Record<SecurityAction, ScriptedProblem[]> {
  const result = Object.fromEntries(
    SECURITY_ACTIONS.map((action) => [action, [] as ScriptedProblem[]]),
  ) as Record<SecurityAction, ScriptedProblem[]>
  if (options === undefined) return result
  for (const action of SECURITY_ACTIONS) {
    const configured = options[action]
    if (configured === undefined) continue
    result[action].push(...(Array.isArray(configured) ? configured : [configured]))
  }
  return result
}

export function createAdminApiState(options: AdminApiOptions = {}): AdminApiState {
  const publication = createPublicationState()
  const conflicts = options.conflictOnce === undefined
    ? []
    : Array.isArray(options.conflictOnce)
      ? [...options.conflictOnce]
      : [options.conflictOnce]
  const authPhase =
    options.initialAuth ?? (options.authenticated === true ? 'authenticated' : 'anonymous')
  return {
    authPhase,
    csrfToken: CSRF_TOKEN,
    requests: [],
    siteWorkspace: createSiteWorkspace(),
    projects: [
      createProject(
        MOCK_IDS.project,
        'synthetic-echo',
        'synthetic-echo',
        0,
        '合成回声',
        'Synthetic Echo',
      ),
      createProject(
        MOCK_IDS.secondProject,
        'synthetic-rift',
        'synthetic-rift',
        1,
        '合成裂隙',
        'Synthetic Rift',
      ),
    ],
    tags: [
      {
        id: MOCK_IDS.tag,
        normalizedKey: 'gameplay',
        version: 1,
        names: localized('玩法', 'Gameplay'),
      },
    ],
    skills: [
      {
        id: MOCK_IDS.skill,
        normalizedKey: 'engine',
        version: 1,
        names: localized('示例引擎', 'Example Engine'),
      },
    ],
    media: [
      mediaAsset(MOCK_IDS.readyImage, 'synthetic-hero.png', 'image/png', SHA_A),
      mediaAsset(
        MOCK_IDS.referencedImage,
        'synthetic-project.jpg',
        'image/jpeg',
        SHA_B,
      ),
      mediaAsset(MOCK_IDS.readyPdf, 'synthetic-resume.pdf', 'application/pdf', SHA_C),
    ],
    messages: createMessages(),
    sessions: createSessions(),
    auditItems: createAuditItems(),
    auditCursors: {},
    auditCursorSequence: 0,
    operations: createOperations(),
    publicationVersions: publication.versions,
    publicationStates: publication.states,
    revisions: publication.revisions,
    conflicts,
    securityErrors: normalizeSecurityErrors(options.securityErrors),
    totpEnrollment: null,
    deletedMessageIds: [],
    referencedMediaArchiveConflict: options.referencedMediaArchiveConflict ?? true,
    uploadSequence: 0,
  }
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasExactKeys(value: unknown, expected: readonly string[]): value is JsonRecord {
  if (!isRecord(value)) return false
  const keys = Object.keys(value)
  return keys.length === expected.length && expected.every((key) => key in value)
}

function safeHeaders(headers: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(headers).filter(
      ([name]) => name.toLowerCase() !== 'cookie' && name.toLowerCase() !== 'authorization',
    ),
  )
}

function parseMultipart(request: Request, data: Buffer): UploadedFile | null {
  const contentType = request.headers()['content-type'] ?? ''
  const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType)
  const boundary = boundaryMatch?.[1] ?? boundaryMatch?.[2]
  if (boundary === undefined) return null

  const headerEnd = data.indexOf(Buffer.from('\r\n\r\n'))
  if (headerEnd < 0) return null
  const headerText = data.subarray(0, headerEnd).toString('utf8')
  const disposition = /content-disposition:\s*form-data;[^\r\n]*name="file"[^\r\n]*filename="([^"]+)"/i.exec(
    headerText,
  )
  const mime = /content-type:\s*(image\/jpeg|image\/png|application\/pdf)/i.exec(headerText)
  if (disposition?.[1] === undefined || mime?.[1] === undefined) return null

  const bodyStart = headerEnd + 4
  const bodyEnd = data.indexOf(Buffer.from(`\r\n--${boundary}`), bodyStart)
  if (bodyEnd < bodyStart) return null
  return {
    name: disposition[1],
    mimeType: mime[1].toLowerCase() as UploadedFile['mimeType'],
    byteSize: bodyEnd - bodyStart,
  }
}

async function parseRequestBody(request: Request): Promise<ParsedRequestBody> {
  if (request.method() === 'GET' || request.method() === 'HEAD') {
    return { body: null, uploadedFile: null }
  }
  const contentType = request.headers()['content-type'] ?? ''
  if (contentType.includes('multipart/form-data')) {
    const data = request.postDataBuffer()
    const uploadedFile = data === null ? null : parseMultipart(request, data)
    return {
      body: uploadedFile === null ? { multipart: true } : { file: uploadedFile },
      uploadedFile,
    }
  }
  const raw = request.postData()
  if (raw === null || raw === '') return { body: null, uploadedFile: null }
  if (contentType.includes('application/json')) {
    try {
      return { body: JSON.parse(raw) as unknown, uploadedFile: null }
    } catch {
      return { body: raw, uploadedFile: null }
    }
  }
  return { body: raw, uploadedFile: null }
}

async function fulfillJson(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({ status, json: body, headers })
}

async function fulfillNoContent(route: Route): Promise<void> {
  await route.fulfill({ status: 204, body: '' })
}

async function fulfillProblem(
  route: Route,
  status: number,
  code: string,
  title: string,
  additions: {
    readonly fieldErrors?: Readonly<Record<string, string>>
    readonly retryAfterSeconds?: number
  } = {},
): Promise<void> {
  await fulfillJson(
    route,
    {
      type: `https://example.test/problems/${code.toLowerCase()}`,
      title,
      status,
      code,
      traceId: `synthetic-${code.toLowerCase()}`,
      ...(additions.fieldErrors === undefined
        ? {}
        : { fieldErrors: additions.fieldErrors }),
      ...(additions.retryAfterSeconds === undefined
        ? {}
        : { retryAfterSeconds: additions.retryAfterSeconds }),
    },
    status,
    additions.retryAfterSeconds === undefined
      ? {}
      : { 'Retry-After': String(additions.retryAfterSeconds) },
  )
}

function consumeConflict(state: AdminApiState, target: AdminConflictTarget): boolean {
  const index = state.conflicts.indexOf(target)
  if (index < 0) return false
  state.conflicts.splice(index, 1)
  return true
}

async function respondConflict(route: Route): Promise<void> {
  await fulfillProblem(
    route,
    409,
    'VERSION_CONFLICT',
    '工作区已由另一个会话更新 / Workspace changed in another session.',
  )
}

async function respondContentVersionConflict(route: Route): Promise<void> {
  await fulfillProblem(
    route,
    409,
    'CONTENT_VERSION_CONFLICT',
    '工作区已由另一个会话更新 / Workspace changed in another session.',
  )
}

function applyWinningSiteEdit(state: AdminApiState): void {
  const winningWorkspace = clone(state.siteWorkspace)
  winningWorkspace.version += 1
  winningWorkspace.hero.version += 1
  winningWorkspace.hero.copy['zh-CN'].headline =
    `${winningWorkspace.hero.copy['zh-CN'].headline} · server-winning edit`
  state.siteWorkspace = winningWorkspace
}

function consumeSecurityError(
  state: AdminApiState,
  action: SecurityAction,
): ScriptedProblem | null {
  return state.securityErrors[action].shift() ?? null
}

async function respondScriptedProblem(
  route: Route,
  state: AdminApiState,
  problem: ScriptedProblem,
): Promise<void> {
  if (problem.status === 401 && problem.code === 'AUTHENTICATION_REQUIRED') {
    state.authPhase = 'anonymous'
  }
  if (problem.status === 409 && problem.code === 'TOTP_ENROLLMENT_EXPIRED') {
    state.totpEnrollment = null
  }
  await fulfillProblem(
    route,
    problem.status,
    problem.code,
    problem.title ?? `Synthetic ${problem.code}`,
    {
      ...(problem.fieldErrors === undefined ? {} : { fieldErrors: problem.fieldErrors }),
      ...(problem.retryAfterSeconds === undefined
        ? {}
        : { retryAfterSeconds: problem.retryAfterSeconds }),
    },
  )
}

function revokeOtherSessions(state: AdminApiState): void {
  state.sessions = state.sessions.map((session) =>
    session.status === 'ACTIVE' && !session.current
      ? {
          ...session,
          status: 'REVOKED',
          endedAt: '2026-07-18T10:01:00Z',
          reason: 'SECURITY_CREDENTIAL_CHANGED',
        }
      : session,
  )
}

function projectById(state: AdminApiState, id: string): ProjectWorkspaceDto | undefined {
  return state.projects.find((project) => project.id.toLowerCase() === id.toLowerCase())
}

function mediaById(state: AdminApiState, id: string): MediaAssetView | undefined {
  return state.media.find((asset) => asset.id.toLowerCase() === id.toLowerCase())
}

function messageById(state: AdminApiState, id: string): MessageDetailDto | undefined {
  return state.messages.find((message) => message.id.toLowerCase() === id.toLowerCase())
}

function stateFor(
  state: AdminApiState,
  type: AggregateType,
  id: string,
): PublicationStateDto | undefined {
  return state.publicationStates[publicationKey(type, id)]
}

function setPublicationState(state: AdminApiState, value: PublicationStateDto): void {
  state.publicationStates[publicationKey(value.aggregateType, value.aggregateId)] = value
}

function nextUuid(prefix: string, version: number): string {
  const cleanPrefix = prefix.slice(0, 8).padEnd(8, '8')
  return `${cleanPrefix}-0000-4000-8000-${String(version).padStart(12, '0')}`
}

function queryObject(url: URL): Record<string, string> {
  return Object.fromEntries(url.searchParams.entries())
}

function dateRange(from: string, to: string): string[] {
  const values: string[] = []
  const start = Date.parse(`${from}T00:00:00Z`)
  const finish = Date.parse(`${to}T00:00:00Z`)
  for (let value = start; value <= finish; value += 86_400_000) {
    values.push(new Date(value).toISOString().slice(0, 10))
  }
  return values
}

function analyticsSummary(locale: string | null): AnalyticsSummaryDto {
  const english = locale === 'en'
  return {
    pageViews: 128,
    dailyUniqueVisitors: 37,
    projectViews: 42,
    resumeDownloads: 5,
    demoDownloads: 3,
    outboundClicks: 9,
    dataCompleteThrough: '2026-07-18T08:00:00Z',
    zone: 'Asia/Hong_Kong',
    definitions: {
      PV: english ? 'Synthetic page-view count.' : '合成页面浏览量。',
      DAILY_UV: english ? 'Synthetic daily unique visitors.' : '合成日独立访客数。',
      EVENT_COUNT: english ? 'Synthetic event total.' : '合成事件总数。',
    },
  }
}

function filterAudit(state: AdminApiState, url: URL): AdminAuditItem[] {
  const action = url.searchParams.get('action')
  const outcome = url.searchParams.get('outcome')
  const from = url.searchParams.get('from')
  const to = url.searchParams.get('to')
  return state.auditItems.filter(
    (item) =>
      (action === null || item.action === action) &&
      (outcome === null || item.outcome === outcome) &&
      (from === null || Date.parse(item.timestamp) >= Date.parse(from)) &&
      (to === null || Date.parse(item.timestamp) < Date.parse(to)),
  )
}

function auditCursorFingerprint(url: URL): string {
  const entries = [...url.searchParams.entries()]
    .filter(([key]) => key !== 'cursor')
    .sort(([leftKey, leftValue], [rightKey, rightValue]) => {
      const keyOrder = leftKey.localeCompare(rightKey)
      return keyOrder === 0 ? leftValue.localeCompare(rightValue) : keyOrder
    })
  return JSON.stringify(entries)
}

function issueAuditCursor(
  state: AdminApiState,
  fingerprint: string,
  start: number,
): string {
  state.auditCursorSequence += 1
  const token = Buffer.from(
    `synthetic-audit-cursor-${state.auditCursorSequence}`,
  ).toString('base64url')
  state.auditCursors[token] = Object.freeze({ fingerprint, start })
  return token
}

function listMessageSummaries(messages: readonly MessageDetailDto[]) {
  return messages.map((message) => ({
    id: message.id,
    visitorName: message.visitorName,
    visitorEmail: message.visitorEmail,
    subject: message.subject,
    status: message.status,
    emailStatus: message.email.status,
    createdAt: message.createdAt,
    version: message.version,
  }))
}

async function requireAuthenticated(route: Route, state: AdminApiState): Promise<boolean> {
  if (state.authPhase === 'authenticated') return true
  await fulfillProblem(
    route,
    401,
    'AUTHENTICATION_REQUIRED',
    '请重新登录 / Authentication is required.',
  )
  return false
}

async function handleRequest(
  route: Route,
  state: AdminApiState,
  parsed: ParsedRequestBody,
): Promise<void> {
  const request = route.request()
  const method = request.method()
  const url = new URL(request.url())
  const path = url.pathname
  const body = parsed.body

  if (method !== 'GET') {
    const provided = request.headers()['x-xsrf-token']
    if (provided !== state.csrfToken) {
      await fulfillProblem(
        route,
        403,
        'CSRF_REJECTED',
        'CSRF 校验失败 / CSRF validation failed.',
      )
      return
    }
  }

  if (method === 'GET' && path === '/api/admin/auth/csrf') {
    await fulfillJson(
      route,
      { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: state.csrfToken },
      200,
      {
        'Cache-Control': 'no-store',
        'Set-Cookie': `XSRF-TOKEN=${state.csrfToken}; Path=/; SameSite=Strict`,
      },
    )
    return
  }

  if (method === 'GET' && path === '/api/admin/auth/me') {
    if (state.authPhase !== 'authenticated') {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_REQUIRED',
        '请登录 / Authentication is required.',
      )
      return
    }
    await fulfillJson(route, { id: MOCK_IDS.admin, username: 'admin' })
    return
  }

  if (method === 'POST' && path === '/api/admin/auth/password') {
    if (
      !hasExactKeys(body, ['username', 'password']) ||
      body.username !== 'admin' ||
      body.password !== 'correct horse battery staple'
    ) {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_FAILED',
        '用户名或密码错误 / Invalid username or password.',
      )
      return
    }
    state.authPhase = 'second-factor'
    await fulfillJson(route, {
      next: 'SECOND_FACTOR',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    })
    return
  }

  if (method === 'POST' && path === '/api/admin/auth/second-factor') {
    const validCode =
      isRecord(body) &&
      ((body.method === 'TOTP' && body.code === '123456') ||
        (body.method === 'RECOVERY_CODE' && RECOVERY_CODES.includes(String(body.code))))
    if (state.authPhase !== 'second-factor' || !validCode) {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_FAILED',
        '验证码无效 / Invalid second factor.',
      )
      return
    }
    state.authPhase = 'authenticated'
    await fulfillJson(route, { id: MOCK_IDS.admin, username: 'admin' })
    return
  }

  if (method === 'POST' && path === '/api/admin/auth/logout') {
    state.authPhase = 'anonymous'
    await fulfillNoContent(route)
    return
  }

  if (!(await requireAuthenticated(route, state))) return

  if (method === 'GET' && path === '/api/admin/site/workspace') {
    await fulfillJson(route, clone(state.siteWorkspace))
    return
  }

  if (method === 'PUT' && path === '/api/admin/site/workspace') {
    if (consumeConflict(state, 'site-save')) {
      applyWinningSiteEdit(state)
      await respondConflict(route)
      return
    }
    if (
      !hasExactKeys(body, ['expectedVersion', 'workspace']) ||
      body.expectedVersion !== state.siteWorkspace.version ||
      !isRecord(body.workspace)
    ) {
      await respondConflict(route)
      return
    }
    const saved = clone(body.workspace as unknown as SiteWorkspaceDto)
    saved.version = state.siteWorkspace.version + 1
    state.siteWorkspace = saved
    await fulfillJson(route, clone(saved))
    return
  }

  if (method === 'GET' && path === '/api/admin/projects') {
    await fulfillJson(route, clone(state.projects))
    return
  }

  if (method === 'POST' && path === '/api/admin/projects') {
    if (!hasExactKeys(body, ['workspace']) || !isRecord(body.workspace)) {
      await fulfillProblem(route, 422, 'VALIDATION_FAILED', '项目数据无效。')
      return
    }
    const created = clone(body.workspace as unknown as ProjectWorkspaceDto)
    created.version = 0
    created.publicationDirty = true
    state.projects.push(created)
    state.publicationVersions.projects[created.id] = 0
    setPublicationState(state, {
      aggregateType: 'PROJECT',
      aggregateId: created.id,
      status: 'UNPUBLISHED',
      version: 0,
      currentRevisionId: null,
      publishedAt: null,
      projectIdsInOrder: [],
    })
    await fulfillJson(route, clone(created))
    return
  }

  const projectWorkspaceMatch = /^\/api\/admin\/projects\/([^/]+)\/workspace$/.exec(path)
  if (projectWorkspaceMatch !== null) {
    const id = decodeURIComponent(projectWorkspaceMatch[1] ?? '')
    const project = projectById(state, id)
    if (project === undefined) {
      await fulfillProblem(route, 404, 'PROJECT_NOT_FOUND', '项目不存在。')
      return
    }
    if (method === 'GET') {
      await fulfillJson(route, clone(project))
      return
    }
    if (method === 'PUT') {
      if (consumeConflict(state, 'project-save')) {
        await respondContentVersionConflict(route)
        return
      }
      if (
        !hasExactKeys(body, ['expectedVersion', 'workspace']) ||
        body.expectedVersion !== project.version ||
        !isRecord(body.workspace)
      ) {
        await respondContentVersionConflict(route)
        return
      }
      const saved = clone(body.workspace as unknown as ProjectWorkspaceDto)
      saved.version = project.version + 1
      saved.publicationDirty = true
      state.projects = state.projects.map((candidate) =>
        candidate.id.toLowerCase() === id.toLowerCase() ? saved : candidate,
      )
      await fulfillJson(route, clone(saved))
      return
    }
  }

  if (method === 'GET' && path === '/api/admin/tags') {
    await fulfillJson(route, clone(state.tags))
    return
  }

  if (method === 'GET' && path === '/api/admin/skills') {
    await fulfillJson(route, clone(state.skills))
    return
  }

  const taxonomyMatch = /^\/api\/admin\/(tags|skills)\/([^/]+)$/.exec(path)
  if (method === 'PUT' && taxonomyMatch !== null) {
    const kind = taxonomyMatch[1] as 'tags' | 'skills'
    const id = decodeURIComponent(taxonomyMatch[2] ?? '')
    const collection = state[kind]
    const current = collection.find((item) => item.id.toLowerCase() === id.toLowerCase())
    if (
      current === undefined ||
      !hasExactKeys(body, ['expectedVersion', 'names']) ||
      body.expectedVersion !== current.version ||
      !isRecord(body.names)
    ) {
      await respondConflict(route)
      return
    }
    const updated: TaxonomyWorkspaceDto = {
      ...current,
      version: current.version + 1,
      names: clone(body.names as unknown as TaxonomyWorkspaceDto['names']),
    }
    state[kind] = collection.map((item) => (item.id === current.id ? updated : item))
    await fulfillJson(route, clone(updated))
    return
  }

  if (method === 'GET' && path === '/api/admin/media') {
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '24')
    const status = url.searchParams.get('status')
    const filtered = status === null
      ? state.media
      : state.media.filter((asset) => asset.status === status)
    const start = page * size
    await fulfillJson(route, {
      items: clone(filtered.slice(start, start + size)),
      page,
      size,
      totalItems: filtered.length,
      totalPages: filtered.length === 0 ? 0 : Math.ceil(filtered.length / size),
    })
    return
  }

  if (method === 'POST' && path === '/api/admin/media') {
    const upload = parsed.uploadedFile
    if (upload === null || upload.byteSize < 1) {
      await fulfillProblem(route, 422, 'VALIDATION_FAILED', '上传文件无效。')
      return
    }
    state.uploadSequence += 1
    const image = upload.mimeType !== 'application/pdf'
    const asset: MediaAssetView = {
      id: `24000000-0000-4000-8000-${String(state.uploadSequence).padStart(12, '0')}`,
      originalFilename: upload.name,
      mimeType: upload.mimeType,
      byteSize: upload.byteSize,
      width: image ? 1 : null,
      height: image ? 1 : null,
      sha256: SHA_A,
      status: 'PROCESSING',
      version: 0,
      createdAt: '2026-07-18T10:02:00Z',
      updatedAt: '2026-07-18T10:02:00Z',
      translations: [],
      variants: [],
    }
    state.media.unshift(asset)
    await fulfillJson(route, clone(asset))
    return
  }

  const mediaPreviewMatch = /^\/api\/admin\/media\/([^/]+)\/preview\/([^/]+)$/.exec(path)
  if (method === 'GET' && mediaPreviewMatch !== null) {
    const id = decodeURIComponent(mediaPreviewMatch[1] ?? '')
    const asset = mediaById(state, id)
    if (asset === undefined) {
      await fulfillProblem(route, 404, 'MEDIA_NOT_FOUND', '媒体不存在。')
      return
    }
    if (asset.mimeType === 'application/pdf') {
      await route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        body: Buffer.from('%PDF-1.4\n% synthetic preview\n'),
      })
    } else {
      await route.fulfill({
        status: 200,
        contentType: asset.mimeType,
        body: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
          'base64',
        ),
      })
    }
    return
  }

  const mediaTranslationsMatch = /^\/api\/admin\/media\/([^/]+)\/translations$/.exec(path)
  if (method === 'PUT' && mediaTranslationsMatch !== null) {
    const id = decodeURIComponent(mediaTranslationsMatch[1] ?? '')
    const asset = mediaById(state, id)
    if (
      asset === undefined ||
      !hasExactKeys(body, ['expectedVersion', 'translations']) ||
      body.expectedVersion !== asset.version ||
      !Array.isArray(body.translations)
    ) {
      await respondConflict(route)
      return
    }
    const updated: MediaAssetView = {
      ...asset,
      version: asset.version + 1,
      updatedAt: '2026-07-18T10:03:00Z',
      translations: clone(body.translations as MediaTranslationInput[]),
    }
    state.media = state.media.map((item) => (item.id === asset.id ? updated : item))
    await fulfillJson(route, clone(updated))
    return
  }

  const mediaMatch = /^\/api\/admin\/media\/([^/]+)$/.exec(path)
  if (mediaMatch !== null) {
    const id = decodeURIComponent(mediaMatch[1] ?? '')
    const asset = mediaById(state, id)
    if (asset === undefined || asset.status === 'PENDING_DELETE') {
      await fulfillProblem(route, 404, 'MEDIA_NOT_FOUND', '媒体不存在。')
      return
    }
    if (method === 'GET') {
      await fulfillJson(route, clone(asset))
      return
    }
    if (method === 'DELETE') {
      if (
        state.referencedMediaArchiveConflict &&
        id.toLowerCase() === MOCK_IDS.referencedImage.toLowerCase()
      ) {
        await fulfillProblem(
          route,
          409,
          'MEDIA_STILL_REFERENCED',
          '媒体仍被项目引用 / Media is still referenced.',
        )
        return
      }
      state.media = state.media.map((item) =>
        item.id === asset.id ? { ...item, status: 'ARCHIVED' } : item,
      )
      await fulfillNoContent(route)
      return
    }
  }

  if (method === 'POST' && path === '/api/admin/publishing/preview-tokens') {
    if (
      !hasExactKeys(body, ['aggregateType', 'aggregateId', 'workspaceVersion']) ||
      (body.aggregateType !== 'SITE' && body.aggregateType !== 'PROJECT')
    ) {
      await fulfillProblem(route, 422, 'VALIDATION_FAILED', '预览请求无效。')
      return
    }
    await fulfillJson(route, {
      token: PREVIEW_TOKEN,
      expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    })
    return
  }

  const previewMatch = /^\/api\/admin\/publishing\/previews\/([^/]+)$/.exec(path)
  if (method === 'GET' && previewMatch !== null) {
    const token = decodeURIComponent(previewMatch[1] ?? '')
    if (token !== PREVIEW_TOKEN) {
      await fulfillProblem(route, 404, 'PREVIEW_NOT_FOUND', '预览已过期。')
      return
    }
    await fulfillJson(
      route,
      {
        kind: 'SYNTHETIC_ADMIN_PREVIEW',
        token: PREVIEW_TOKEN,
        site: clone(state.siteWorkspace),
        projects: clone(state.projects),
      },
      200,
      { 'Cache-Control': 'no-store' },
    )
    return
  }

  if (method === 'POST' && path === '/api/admin/publishing/site') {
    if (consumeConflict(state, 'site-publish')) {
      await respondConflict(route)
      return
    }
    if (
      !hasExactKeys(body, ['expectedWorkspaceVersion', 'expectedPublicationVersion']) ||
      body.expectedWorkspaceVersion !== state.siteWorkspace.version ||
      body.expectedPublicationVersion !== state.publicationVersions.site
    ) {
      await respondConflict(route)
      return
    }
    state.publicationVersions.site += 1
    const revisionId = nextUuid('71000000', state.publicationVersions.site)
    const publishedAt = '2026-07-18T10:04:00Z'
    const item = revision(
      revisionId,
      'SITE',
      MOCK_IDS.site,
      state.publicationVersions.site,
      publishedAt,
    )
    state.revisions.unshift(item)
    setPublicationState(state, {
      aggregateType: 'SITE',
      aggregateId: MOCK_IDS.site,
      status: 'PUBLISHED',
      version: state.publicationVersions.site,
      currentRevisionId: revisionId,
      publishedAt,
      projectIdsInOrder: [],
    })
    await fulfillJson(route, {
      revisionId,
      aggregateVersion: state.publicationVersions.site,
      checksum: SHA_A,
    })
    return
  }

  const archiveMatch = /^\/api\/admin\/publishing\/projects\/([^/]+)\/archive$/.exec(path)
  if (method === 'POST' && archiveMatch !== null) {
    const id = decodeURIComponent(archiveMatch[1] ?? '')
    const projectVersion = state.publicationVersions.projects[id]
    if (
      projectVersion === undefined ||
      !hasExactKeys(body, [
        'projectId',
        'expectedProjectPublicationVersion',
        'expectedCatalogVersion',
      ]) ||
      body.projectId !== id ||
      body.expectedProjectPublicationVersion !== projectVersion ||
      body.expectedCatalogVersion !== state.publicationVersions.catalog
    ) {
      await respondConflict(route)
      return
    }
    const nextProjectVersion = projectVersion + 1
    const nextCatalogVersion = state.publicationVersions.catalog + 1
    state.publicationVersions.projects[id] = nextProjectVersion
    state.publicationVersions.catalog = nextCatalogVersion
    const revisionId = nextUuid('72000000', nextProjectVersion)
    const catalogRevisionId = nextUuid('73000000', nextCatalogVersion)
    const publishedAt = '2026-07-18T10:05:00Z'
    const catalogState = stateFor(state, 'PROJECT_CATALOG', MOCK_IDS.catalog)
    state.revisions.unshift(
      revision(revisionId, 'PROJECT', id, nextProjectVersion, publishedAt),
      revision(
        catalogRevisionId,
        'PROJECT_CATALOG',
        MOCK_IDS.catalog,
        nextCatalogVersion,
        publishedAt,
      ),
    )
    setPublicationState(state, {
      aggregateType: 'PROJECT',
      aggregateId: id,
      status: 'ARCHIVED',
      version: nextProjectVersion,
      currentRevisionId: revisionId,
      publishedAt,
      projectIdsInOrder: [],
    })
    setPublicationState(state, {
      aggregateType: 'PROJECT_CATALOG',
      aggregateId: MOCK_IDS.catalog,
      status: 'PUBLISHED',
      version: nextCatalogVersion,
      currentRevisionId: catalogRevisionId,
      publishedAt,
      projectIdsInOrder: (catalogState?.projectIdsInOrder ?? []).filter(
        (projectId) => projectId.toLowerCase() !== id.toLowerCase(),
      ),
    })
    await fulfillJson(route, {
      revisionId,
      aggregateVersion: nextProjectVersion,
      catalogRevisionId,
      catalogVersion: nextCatalogVersion,
      checksum: SHA_B,
    })
    return
  }

  const projectPublishMatch = /^\/api\/admin\/publishing\/projects\/([^/]+)$/.exec(path)
  if (method === 'POST' && projectPublishMatch !== null) {
    const id = decodeURIComponent(projectPublishMatch[1] ?? '')
    if (consumeConflict(state, 'project-publish')) {
      await respondConflict(route)
      return
    }
    const project = projectById(state, id)
    const projectVersion = state.publicationVersions.projects[id]
    if (
      project === undefined ||
      projectVersion === undefined ||
      !hasExactKeys(body, [
        'projectId',
        'expectedWorkspaceVersion',
        'expectedProjectPublicationVersion',
        'expectedCatalogVersion',
      ]) ||
      body.projectId !== id ||
      body.expectedWorkspaceVersion !== project.version ||
      body.expectedProjectPublicationVersion !== projectVersion ||
      body.expectedCatalogVersion !== state.publicationVersions.catalog
    ) {
      await respondConflict(route)
      return
    }
    const nextProjectVersion = projectVersion + 1
    const nextCatalogVersion = state.publicationVersions.catalog + 1
    state.publicationVersions.projects[id] = nextProjectVersion
    state.publicationVersions.catalog = nextCatalogVersion
    const revisionId = nextUuid('74000000', nextProjectVersion)
    const catalogRevisionId = nextUuid('75000000', nextCatalogVersion)
    const publishedAt = '2026-07-18T10:06:00Z'
    const publishedWorkspace: ProjectWorkspaceDto = {
      ...project,
      version: project.version + 1,
      publicationDirty: false,
    }
    state.projects = state.projects.map((candidate) =>
      candidate.id.toLowerCase() === id.toLowerCase() ? publishedWorkspace : candidate,
    )
    const catalogState = stateFor(state, 'PROJECT_CATALOG', MOCK_IDS.catalog)
    const catalogOrder = [...(catalogState?.projectIdsInOrder ?? [])]
    if (!catalogOrder.some((projectId) => projectId.toLowerCase() === id.toLowerCase())) {
      catalogOrder.push(id)
    }
    state.revisions.unshift(
      revision(revisionId, 'PROJECT', id, nextProjectVersion, publishedAt),
      revision(
        catalogRevisionId,
        'PROJECT_CATALOG',
        MOCK_IDS.catalog,
        nextCatalogVersion,
        publishedAt,
      ),
    )
    setPublicationState(state, {
      aggregateType: 'PROJECT',
      aggregateId: id,
      status: 'PUBLISHED',
      version: nextProjectVersion,
      currentRevisionId: revisionId,
      publishedAt,
      projectIdsInOrder: [],
    })
    setPublicationState(state, {
      aggregateType: 'PROJECT_CATALOG',
      aggregateId: MOCK_IDS.catalog,
      status: 'PUBLISHED',
      version: nextCatalogVersion,
      currentRevisionId: catalogRevisionId,
      publishedAt,
      projectIdsInOrder: catalogOrder,
    })
    await fulfillJson(route, {
      revisionId,
      aggregateVersion: nextProjectVersion,
      catalogRevisionId,
      catalogVersion: nextCatalogVersion,
      checksum: SHA_B,
    })
    return
  }

  if (method === 'PUT' && path === '/api/admin/publishing/catalog/order') {
    if (consumeConflict(state, 'catalog-reorder')) {
      await respondConflict(route)
      return
    }
    if (
      !hasExactKeys(body, ['expectedCatalogVersion', 'projectIdsInOrder']) ||
      body.expectedCatalogVersion !== state.publicationVersions.catalog ||
      !Array.isArray(body.projectIdsInOrder)
    ) {
      await respondConflict(route)
      return
    }
    state.publicationVersions.catalog += 1
    const revisionId = nextUuid('76000000', state.publicationVersions.catalog)
    const publishedAt = '2026-07-18T10:07:00Z'
    state.revisions.unshift(
      revision(
        revisionId,
        'PROJECT_CATALOG',
        MOCK_IDS.catalog,
        state.publicationVersions.catalog,
        publishedAt,
      ),
    )
    setPublicationState(state, {
      aggregateType: 'PROJECT_CATALOG',
      aggregateId: MOCK_IDS.catalog,
      status: 'PUBLISHED',
      version: state.publicationVersions.catalog,
      currentRevisionId: revisionId,
      publishedAt,
      projectIdsInOrder: clone(body.projectIdsInOrder as string[]),
    })
    await fulfillJson(route, {
      revisionId,
      aggregateVersion: state.publicationVersions.catalog,
      checksum: SHA_C,
    })
    return
  }

  const historyMatch = /^\/api\/admin\/publishing\/(SITE|PROJECT|PROJECT_CATALOG)\/([^/]+)\/history$/.exec(
    path,
  )
  if (method === 'GET' && historyMatch !== null) {
    const type = historyMatch[1] as AggregateType
    const id = decodeURIComponent(historyMatch[2] ?? '')
    await fulfillJson(
      route,
      clone(
        state.revisions.filter(
          (item) => item.type === type && item.aggregateId.toLowerCase() === id.toLowerCase(),
        ),
      ),
    )
    return
  }

  const publicationStateMatch = /^\/api\/admin\/publishing\/(SITE|PROJECT|PROJECT_CATALOG)\/([^/]+)\/state$/.exec(
    path,
  )
  if (method === 'GET' && publicationStateMatch !== null) {
    const type = publicationStateMatch[1] as AggregateType
    const id = decodeURIComponent(publicationStateMatch[2] ?? '')
    const value = stateFor(state, type, id)
    if (value === undefined) {
      await fulfillProblem(route, 404, 'PUBLICATION_NOT_FOUND', '发布状态不存在。')
      return
    }
    await fulfillJson(route, clone(value))
    return
  }

  const restoreMatch = /^\/api\/admin\/publishing\/revisions\/([^/]+)\/restore$/.exec(path)
  if (method === 'POST' && restoreMatch !== null) {
    const id = decodeURIComponent(restoreMatch[1] ?? '')
    if (
      !hasExactKeys(body, ['expectedWorkspaceVersion']) ||
      !state.revisions.some((item) => item.id.toLowerCase() === id.toLowerCase())
    ) {
      await fulfillProblem(route, 404, 'REVISION_NOT_FOUND', '修订不存在。')
      return
    }
    await fulfillNoContent(route)
    return
  }

  if (method === 'GET' && path === '/api/admin/messages') {
    const status = url.searchParams.get('status') as MessageStatus | null
    const cursor = url.searchParams.get('cursor')
    const limit = Number(url.searchParams.get('limit') ?? '30')
    const filtered = status === null
      ? state.messages
      : state.messages.filter((message) => message.status === status)
    const start = cursor === null ? 0 : Number(cursor.replace('messages_', ''))
    const items = filtered.slice(start, start + limit)
    await fulfillJson(route, {
      items: listMessageSummaries(items),
      nextCursor: start + limit < filtered.length ? `messages_${start + limit}` : null,
    })
    return
  }

  const messageStatusMatch = /^\/api\/admin\/messages\/([^/]+)\/status$/.exec(path)
  if (method === 'PATCH' && messageStatusMatch !== null) {
    const id = decodeURIComponent(messageStatusMatch[1] ?? '')
    const message = messageById(state, id)
    if (
      message === undefined ||
      !hasExactKeys(body, ['status', 'version']) ||
      body.version !== message.version ||
      !['UNREAD', 'READ', 'ARCHIVED', 'SPAM'].includes(String(body.status))
    ) {
      await respondConflict(route)
      return
    }
    const updated: MessageDetailDto = {
      ...message,
      status: body.status as MessageStatus,
      version: message.version + 1,
      updatedAt: '2026-07-18T10:08:00Z',
    }
    state.messages = state.messages.map((item) => (item.id === message.id ? updated : item))
    await fulfillJson(route, clone(updated))
    return
  }

  const messageRetryMatch = /^\/api\/admin\/messages\/([^/]+)\/email\/retry$/.exec(path)
  if (method === 'POST' && messageRetryMatch !== null) {
    const id = decodeURIComponent(messageRetryMatch[1] ?? '')
    const message = messageById(state, id)
    if (message === undefined) {
      await fulfillProblem(route, 404, 'MESSAGE_NOT_FOUND', '消息不存在。')
      return
    }
    const retried: MessageDetailDto = {
      ...message,
      email: {
        status: 'PENDING',
        attempts: message.email.attempts + 1,
        nextAttemptAt: '2026-07-18T10:15:00Z',
        sentAt: null,
        updatedAt: '2026-07-18T10:10:00Z',
        errorCategory: null,
      },
      updatedAt: '2026-07-18T10:10:00Z',
    }
    state.messages = state.messages.map((item) =>
      item.id === message.id ? retried : item,
    )
    await fulfillNoContent(route)
    return
  }

  const messageMatch = /^\/api\/admin\/messages\/([^/]+)$/.exec(path)
  if (messageMatch !== null) {
    const id = decodeURIComponent(messageMatch[1] ?? '')
    const message = messageById(state, id)
    if (message === undefined) {
      await fulfillProblem(route, 404, 'MESSAGE_NOT_FOUND', '消息不存在。')
      return
    }
    if (method === 'GET') {
      await fulfillJson(route, clone(message))
      return
    }
    if (method === 'DELETE') {
      state.messages = state.messages.filter((item) => item.id !== message.id)
      state.deletedMessageIds.push(message.id)
      await fulfillNoContent(route)
      return
    }
  }

  if (method === 'GET' && path === '/api/admin/analytics/summary') {
    await fulfillJson(route, analyticsSummary(url.searchParams.get('locale')))
    return
  }

  if (method === 'GET' && path === '/api/admin/analytics/timeseries') {
    const from = url.searchParams.get('from') ?? '2026-07-01'
    const to = url.searchParams.get('to') ?? from
    const points: AnalyticsPointDto[] = dateRange(from, to).map((date, index) => ({
      date,
      value: index % 3 === 1 ? 0 : index + 2,
    }))
    await fulfillJson(route, points)
    return
  }

  if (method === 'GET' && path === '/api/admin/analytics/breakdown') {
    const dimension = url.searchParams.get('dimension') ?? 'PAGE'
    const limit = Number(url.searchParams.get('limit') ?? '10')
    const items: AnalyticsBreakdownItemDto[] = [
      { dimensionValue: `Synthetic ${dimension} A`, value: 12 },
      { dimensionValue: `Synthetic ${dimension} B`, value: 7 },
      { dimensionValue: `Synthetic ${dimension} C`, value: 0 },
    ]
    await fulfillJson(route, items.slice(0, limit))
    return
  }

  if (method === 'POST' && path === '/api/admin/security/password') {
    const scripted = consumeSecurityError(state, 'password')
    if (scripted !== null) {
      await respondScriptedProblem(route, state, scripted)
      return
    }
    if (
      !hasExactKeys(body, ['currentPassword', 'currentTotp', 'newPassword']) ||
      body.currentPassword !== 'correct horse battery staple' ||
      body.currentTotp !== '123456' ||
      typeof body.newPassword !== 'string'
    ) {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_FAILED',
        '当前凭据无效 / Current credentials are invalid.',
      )
      return
    }
    revokeOtherSessions(state)
    await fulfillNoContent(route)
    return
  }

  if (method === 'POST' && path === '/api/admin/security/totp/enrollment') {
    const scripted = consumeSecurityError(state, 'totp-enrollment')
    if (scripted !== null) {
      await respondScriptedProblem(route, state, scripted)
      return
    }
    if (
      !hasExactKeys(body, ['currentPassword', 'currentTotp']) ||
      body.currentPassword !== 'correct horse battery staple' ||
      body.currentTotp !== '123456'
    ) {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_FAILED',
        '当前凭据无效 / Current credentials are invalid.',
      )
      return
    }
    state.totpEnrollment = {
      enrollmentId: MOCK_IDS.enrollment,
      provisioningUri:
        'otpauth://totp/Synthetic%20Portfolio:admin?secret=JBSWY3DPEHPK3PXP&issuer=Synthetic%20Portfolio&algorithm=SHA1&digits=6&period=30',
      expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    }
    revokeOtherSessions(state)
    await fulfillJson(route, clone(state.totpEnrollment), 200, { 'Cache-Control': 'no-store' })
    return
  }

  if (method === 'POST' && path === '/api/admin/security/totp/confirm') {
    const scripted = consumeSecurityError(state, 'totp-confirm')
    if (scripted !== null) {
      await respondScriptedProblem(route, state, scripted)
      return
    }
    if (
      state.totpEnrollment === null ||
      !hasExactKeys(body, ['enrollmentId', 'newTotp']) ||
      body.enrollmentId !== state.totpEnrollment.enrollmentId ||
      typeof body.newTotp !== 'string' ||
      !/^\d{6}$/.test(body.newTotp)
    ) {
      state.totpEnrollment = null
      await fulfillProblem(
        route,
        409,
        'TOTP_ENROLLMENT_EXPIRED',
        '绑定已过期 / Enrollment expired.',
      )
      return
    }
    state.totpEnrollment = null
    revokeOtherSessions(state)
    await fulfillJson(
      route,
      { recoveryCodes: [...RECOVERY_CODES] },
      200,
      { 'Cache-Control': 'no-store' },
    )
    return
  }

  if (method === 'POST' && path === '/api/admin/security/recovery-codes/regenerate') {
    const scripted = consumeSecurityError(state, 'recovery-regenerate')
    if (scripted !== null) {
      await respondScriptedProblem(route, state, scripted)
      return
    }
    if (
      !hasExactKeys(body, ['currentPassword', 'currentTotp']) ||
      body.currentPassword !== 'correct horse battery staple' ||
      body.currentTotp !== '123456'
    ) {
      await fulfillProblem(
        route,
        401,
        'AUTHENTICATION_FAILED',
        '当前凭据无效 / Current credentials are invalid.',
      )
      return
    }
    revokeOtherSessions(state)
    await fulfillJson(
      route,
      { recoveryCodes: [...RECOVERY_CODES].reverse() },
      200,
      { 'Cache-Control': 'no-store' },
    )
    return
  }

  if (method === 'GET' && path === '/api/admin/security/sessions') {
    await fulfillJson(route, clone(state.sessions), 200, { 'Cache-Control': 'no-store' })
    return
  }

  const sessionRevokeMatch = /^\/api\/admin\/security\/sessions\/([^/]+)\/revoke$/.exec(path)
  if (method === 'POST' && sessionRevokeMatch !== null) {
    const scripted = consumeSecurityError(state, 'session-revoke')
    if (scripted !== null) {
      await respondScriptedProblem(route, state, scripted)
      return
    }
    const id = decodeURIComponent(sessionRevokeMatch[1] ?? '')
    const session = state.sessions.find((candidate) => candidate.id === id)
    if (session === undefined || session.status !== 'ACTIVE') {
      await fulfillProblem(route, 409, 'SESSION_NOT_ACTIVE', '会话不再活动。')
      return
    }
    state.sessions = state.sessions.map((candidate) =>
      candidate.id === id
        ? {
            ...candidate,
            status: 'REVOKED',
            endedAt: '2026-07-18T10:09:00Z',
            reason: 'ADMIN_REVOKED',
            current: false,
          }
        : candidate,
    )
    if (session.current) state.authPhase = 'anonymous'
    await fulfillNoContent(route)
    return
  }

  if (method === 'GET' && path === '/api/admin/audit') {
    const filtered = filterAudit(state, url)
    const limit = Number(url.searchParams.get('limit') ?? '50')
    const cursor = url.searchParams.get('cursor')
    const fingerprint = auditCursorFingerprint(url)
    let start = 0
    if (cursor !== null) {
      const cursorState = state.auditCursors[cursor]
      if (cursorState === undefined || cursorState.fingerprint !== fingerprint) {
        await fulfillProblem(
          route,
          400,
          'AUDIT_CURSOR_INVALID',
          '审计游标无效或不属于当前筛选条件。',
        )
        return
      }
      start = cursorState.start
    }
    const items = filtered.slice(start, start + limit)
    const nextStart = start + limit
    await fulfillJson(route, {
      items: clone(items),
      nextCursor:
        nextStart < filtered.length
          ? issueAuditCursor(state, fingerprint, nextStart)
          : null,
    })
    return
  }

  if (method === 'GET' && path === '/api/admin/system/operations') {
    await fulfillJson(route, clone(state.operations), 200, { 'Cache-Control': 'no-store' })
    return
  }

  await fulfillProblem(
    route,
    404,
    'MOCK_ENDPOINT_NOT_FOUND',
    `Synthetic mock has no route for ${method} ${path}.`,
  )
}

export async function installAdminApi(
  page: Page,
  options: AdminApiOptions = {},
): Promise<AdminApiHarness> {
  const state = createAdminApiState(options)
  const handler = async (route: Route): Promise<void> => {
    const request = route.request()
    const parsed = await parseRequestBody(request)
    const url = new URL(request.url())
    const headers = safeHeaders(await request.allHeaders())
    state.requests.push(
      Object.freeze({
        method: request.method(),
        url: request.url(),
        path: url.pathname,
        query: Object.freeze(queryObject(url)),
        headers: Object.freeze(headers),
        body: clone(parsed.body),
      }),
    )
    await handleRequest(route, state, parsed)
  }

  await page.context().route(ROUTE_PATTERN, handler)

  if (state.authPhase !== 'anonymous') {
    const csrfResponse = await page.goto(`${MOCK_ORIGIN}/api/admin/auth/csrf`)
    if (csrfResponse?.status() !== 200) {
      throw new Error('Synthetic CSRF bootstrap failed.')
    }
  }

  return Object.freeze({
    state,
    requests: state.requests,
    scriptConflict(target: AdminConflictTarget) {
      state.conflicts.push(target)
    },
    scriptSecurityError(action: SecurityAction, problem: ScriptedProblem) {
      state.securityErrors[action].push(problem)
    },
    async dispose() {
      await page.context().unroute(ROUTE_PATTERN, handler)
    },
  })
}
