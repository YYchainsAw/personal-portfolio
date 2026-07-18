import type { AxiosInstance } from 'axios'

import { ApiProblem } from '@/types/api'
import type {
  AdminAuditItem,
  AdminAuditOutcome,
  AdminAuditPage,
  AdminAuditQuery,
  AdminSessionStatus,
  MaintenanceErrorCategory,
  MaintenanceStatus,
  MaintenanceType,
  MaintenanceView,
  OperationsStatus,
  PasswordChangeRequest,
  ReauthenticationRequest,
  RecoveryCodesResponse,
  SessionView,
  TotpConfirmRequest,
  TotpEnrollmentResponse,
} from '@/types/settings'

import { http } from './http'

const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/
const CURSOR = /^[A-Za-z0-9_-]{1,86}$/
const ACTION = /^[A-Z0-9_]{1,96}$/
const TARGET_TYPE = /^[A-Z0-9_]{1,64}$/
const SESSION_REASON = /^[A-Z0-9_]{1,64}$/
const SHA_256 = /^[0-9a-f]{64}$/
const RECOVERY_CODE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}(?:-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}){2}$/
const TOTP = /^[0-9]{6}$/
const MAX_CANONICAL_EPOCH_MILLIS = 253_402_300_799_999
const INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}|\d{6}|\d{9}))?Z$/

const SESSION_STATUSES = new Set<AdminSessionStatus>(['ACTIVE', 'REVOKED', 'EXPIRED'])
const AUDIT_OUTCOMES = new Set<AdminAuditOutcome>(['SUCCESS', 'FAILURE'])
const MAINTENANCE_STATUSES = new Set<MaintenanceStatus>([
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
])
const AUDIT_METADATA_KEYS = new Set([
  'stage',
  'next',
  'method',
  'reason',
  'channel',
  'backupSha256',
  'recoveryCodeCount',
  'revokedOtherSessions',
  'staleActor',
  'fromKeyVersion',
  'toKeyVersion',
  'previousStatus',
  'newStatus',
  'previousEmailStatus',
  'newEmailStatus',
  'createdDate',
])

const OPERATIONS_KEYS = [
  'databaseBackup',
  'mediaBackup',
  'analyticsAggregation',
  'contactRetention',
  'mediaCleanup',
  'deployment',
  'restoreDrill',
] as const

type OperationKey = (typeof OPERATIONS_KEYS)[number]
type RecordValue = Record<string, unknown>

const OPERATION_TYPES: Readonly<Record<OperationKey, MaintenanceType>> = {
  databaseBackup: 'DATABASE_BACKUP',
  mediaBackup: 'MEDIA_BACKUP',
  analyticsAggregation: 'ANALYTICS_AGGREGATE',
  contactRetention: 'CONTACT_RETENTION',
  mediaCleanup: 'MEDIA_CLEANUP_SCAN',
  deployment: 'DEPLOYMENT',
  restoreDrill: 'RESTORE_DRILL',
}

const FAILURE_CATEGORIES: Readonly<Record<MaintenanceType, MaintenanceErrorCategory>> = {
  DATABASE_BACKUP: 'DATABASE_BACKUP_FAILED',
  MEDIA_BACKUP: 'MEDIA_BACKUP_FAILED',
  ANALYTICS_AGGREGATE: 'ANALYTICS_AGGREGATION_FAILED',
  CONTACT_RETENTION: 'CONTACT_RETENTION_FAILED',
  MEDIA_CLEANUP_SCAN: 'MEDIA_CLEANUP_FAILED',
  DEPLOYMENT: 'DEPLOYMENT_FAILED',
  RESTORE_DRILL: 'RESTORE_DRILL_FAILED',
}

export interface SettingsApi {
  changePassword(body: Readonly<PasswordChangeRequest>): Promise<void>
  beginTotpEnrollment(
    body: Readonly<ReauthenticationRequest>,
  ): Promise<TotpEnrollmentResponse>
  confirmTotp(body: Readonly<TotpConfirmRequest>): Promise<RecoveryCodesResponse>
  regenerateRecoveryCodes(
    body: Readonly<ReauthenticationRequest>,
  ): Promise<RecoveryCodesResponse>
  listSessions(): Promise<readonly SessionView[]>
  revokeSession(metadataId: string): Promise<void>
  getAudit(query?: Readonly<AdminAuditQuery>): Promise<AdminAuditPage>
  getOperations(): Promise<OperationsStatus>
}

function invalidServerResponse(): ApiProblem {
  return new ApiProblem({
    type: 'invalid_server_response',
    title: '服务器返回了无效的设置数据 / Invalid settings response.',
    status: 0,
    code: 'INVALID_SETTINGS_RESPONSE',
    traceId: 'client',
  })
}

function isRecord(value: unknown): value is RecordValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function hasExactKeys(value: unknown, keys: readonly string[]): value is RecordValue {
  if (!isRecord(value)) return false
  const actual = Object.keys(value)
  return (
    actual.length === keys.length &&
    keys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  )
}

function hasRequiredAndOptionalKeys(
  value: unknown,
  required: readonly string[],
  optional: readonly string[],
): value is RecordValue {
  if (!isRecord(value)) return false
  const allowed = new Set([...required, ...optional])
  const actual = Object.keys(value)
  return (
    required.every((key) => Object.prototype.hasOwnProperty.call(value, key)) &&
    actual.every((key) => allowed.has(key))
  )
}

function isWellFormedUtf16(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index)
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false
      index += 1
    } else if (unit >= 0xdc00 && unit <= 0xdfff) {
      return false
    }
  }
  return true
}

function isBoundedText(
  value: unknown,
  maximumCodePoints: number,
  options: { allowBlank?: boolean; trimmed?: boolean } = {},
): value is string {
  if (typeof value !== 'string' || !isWellFormedUtf16(value)) return false
  if (!options.allowBlank && value.trim().length === 0) return false
  if (options.trimmed && value !== value.trim()) return false
  return Array.from(value).length <= maximumCodePoints
}

function isBoundedUnits(value: unknown, maximumUnits: number): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= maximumUnits &&
    isWellFormedUtf16(value)
  )
}

function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function isSessionStatus(value: unknown): value is AdminSessionStatus {
  return (
    typeof value === 'string' &&
    SESSION_STATUSES.has(value as AdminSessionStatus)
  )
}

function isAuditOutcome(value: unknown): value is AdminAuditOutcome {
  return typeof value === 'string' && AUDIT_OUTCOMES.has(value as AdminAuditOutcome)
}

function isMaintenanceStatus(value: unknown): value is MaintenanceStatus {
  return (
    typeof value === 'string' &&
    MAINTENANCE_STATUSES.has(value as MaintenanceStatus)
  )
}

interface InstantParts {
  readonly epochSecond: number
  readonly fraction: string
}

function instantParts(value: unknown, maximumFractionDigits = 9): InstantParts | null {
  if (typeof value !== 'string') return null
  const match = INSTANT.exec(value)
  if (match === null) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  const fraction = match[7] ?? ''
  if (
    year < 1970 ||
    year > 9999 ||
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31 ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    fraction.length > maximumFractionDigits ||
    (fraction.length > 0 &&
      (fraction === '000' ||
        (fraction.length > 3 && fraction.endsWith('000'))))
  ) {
    return null
  }

  const milliseconds = Date.UTC(year, month - 1, day, hour, minute, second)
  const parsed = new Date(milliseconds)
  if (
    !Number.isFinite(milliseconds) ||
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day ||
    parsed.getUTCHours() !== hour ||
    parsed.getUTCMinutes() !== minute ||
    parsed.getUTCSeconds() !== second
  ) {
    return null
  }
  return { epochSecond: milliseconds / 1_000, fraction }
}

function isCanonicalInstant(value: unknown): value is string {
  return instantParts(value) !== null
}

function isCanonicalAuditInstant(value: unknown): value is string {
  return instantParts(value, 6) !== null
}

function instantNanos(value: string): bigint {
  const parts = instantParts(value)
  if (parts === null) throw new TypeError('instant is invalid')
  const fraction = parts.fraction.padEnd(9, '0') || '0'
  return BigInt(parts.epochSecond) * 1_000_000_000n + BigInt(fraction)
}

function compareInstants(left: string, right: string): number {
  const leftValue = instantNanos(left)
  const rightValue = instantNanos(right)
  return leftValue === rightValue ? 0 : leftValue < rightValue ? -1 : 1
}

function optionalNullable(
  value: RecordValue,
  key: string,
): unknown | null {
  const candidate = value[key]
  return candidate === undefined || candidate === null ? null : candidate
}

function requirePasswordRequest(value: unknown): PasswordChangeRequest {
  if (
    !hasExactKeys(value, ['currentPassword', 'currentTotp', 'newPassword']) ||
    !isBoundedUnits(value.currentPassword, 256) ||
    typeof value.currentTotp !== 'string' ||
    !TOTP.test(value.currentTotp) ||
    !isBoundedUnits(value.newPassword, 256)
  ) {
    throw new TypeError('password-change request is invalid')
  }
  return {
    currentPassword: value.currentPassword,
    currentTotp: value.currentTotp,
    newPassword: value.newPassword,
  }
}

function requireReauthentication(value: unknown): ReauthenticationRequest {
  if (
    !hasExactKeys(value, ['currentPassword', 'currentTotp']) ||
    !isBoundedUnits(value.currentPassword, 256) ||
    typeof value.currentTotp !== 'string' ||
    !TOTP.test(value.currentTotp)
  ) {
    throw new TypeError('reauthentication request is invalid')
  }
  return { currentPassword: value.currentPassword, currentTotp: value.currentTotp }
}

function requireTotpConfirmation(value: unknown): TotpConfirmRequest {
  if (
    !hasExactKeys(value, ['enrollmentId', 'newTotp']) ||
    !isCanonicalUuid(value.enrollmentId) ||
    typeof value.newTotp !== 'string' ||
    !TOTP.test(value.newTotp)
  ) {
    throw new TypeError('TOTP confirmation request is invalid')
  }
  return { enrollmentId: value.enrollmentId, newTotp: value.newTotp }
}

function isProvisioningUri(value: unknown): value is string {
  if (
    typeof value !== 'string' ||
    value.length < 1 ||
    value.length > 2_048 ||
    /[\\\u0000-\u001f\u007f]/.test(value)
  ) {
    return false
  }
  try {
    const uri = new URL(value)
    const keys = [...uri.searchParams.keys()]
    if (
      uri.protocol !== 'otpauth:' ||
      uri.hostname !== 'totp' ||
      uri.port !== '' ||
      uri.username !== '' ||
      uri.password !== '' ||
      uri.hash !== '' ||
      uri.pathname.length < 4 ||
      !uri.pathname.includes(':') ||
      keys.length !== 5 ||
      new Set(keys).size !== 5 ||
      !['secret', 'issuer', 'algorithm', 'digits', 'period'].every((key) =>
        keys.includes(key),
      ) ||
      !/^[A-Z2-7]{16,128}$/.test(uri.searchParams.get('secret') ?? '') ||
      !isBoundedText(uri.searchParams.get('issuer'), 128) ||
      uri.searchParams.get('algorithm') !== 'SHA1' ||
      uri.searchParams.get('digits') !== '6' ||
      uri.searchParams.get('period') !== '30'
    ) {
      return false
    }
    return true
  } catch {
    return false
  }
}

function normalizeEnrollment(value: unknown): TotpEnrollmentResponse {
  if (
    !hasExactKeys(value, ['enrollmentId', 'provisioningUri', 'expiresAt']) ||
    !isCanonicalUuid(value.enrollmentId) ||
    !isProvisioningUri(value.provisioningUri) ||
    !isCanonicalInstant(value.expiresAt)
  ) {
    throw invalidServerResponse()
  }
  return {
    enrollmentId: value.enrollmentId,
    provisioningUri: value.provisioningUri,
    expiresAt: value.expiresAt,
  }
}

function normalizeRecoveryCodes(value: unknown): RecoveryCodesResponse {
  if (
    !hasExactKeys(value, ['recoveryCodes']) ||
    !Array.isArray(value.recoveryCodes) ||
    value.recoveryCodes.length !== 10
  ) {
    throw invalidServerResponse()
  }
  const codes: string[] = []
  const unique = new Set<string>()
  for (const candidate of value.recoveryCodes) {
    if (
      typeof candidate !== 'string' ||
      !RECOVERY_CODE.test(candidate) ||
      unique.has(candidate)
    ) {
      throw invalidServerResponse()
    }
    unique.add(candidate)
    codes.push(candidate)
  }
  return { recoveryCodes: codes }
}

function normalizeSession(value: unknown): SessionView | null {
  if (
    !hasRequiredAndOptionalKeys(
      value,
      ['id', 'status', 'createdAt', 'lastAccessMillis', 'clientSummary', 'current'],
      ['endedAt', 'reason'],
    ) ||
    !isCanonicalUuid(value.id) ||
    !isSessionStatus(value.status) ||
    !isCanonicalInstant(value.createdAt) ||
    !Number.isSafeInteger(value.lastAccessMillis) ||
    (value.lastAccessMillis as number) < 0 ||
    (value.lastAccessMillis as number) > MAX_CANONICAL_EPOCH_MILLIS ||
    !isBoundedText(value.clientSummary, 255, { trimmed: true }) ||
    typeof value.current !== 'boolean'
  ) {
    return null
  }
  const endedAt = optionalNullable(value, 'endedAt')
  const reason = optionalNullable(value, 'reason')
  if (
    (endedAt !== null && !isCanonicalInstant(endedAt)) ||
    (reason !== null &&
      (typeof reason !== 'string' || !SESSION_REASON.test(reason))) ||
    (value.status === 'ACTIVE' && (endedAt !== null || reason !== null)) ||
    (value.status !== 'ACTIVE' &&
      (endedAt === null ||
        reason === null ||
        compareInstants(endedAt as string, value.createdAt) < 0)) ||
    (value.current && value.status !== 'ACTIVE')
  ) {
    return null
  }
  return {
    id: value.id,
    status: value.status,
    createdAt: value.createdAt,
    endedAt: endedAt as string | null,
    lastAccessMillis: value.lastAccessMillis as number,
    clientSummary: value.clientSummary,
    reason: reason as string | null,
    current: value.current,
  }
}

function normalizeSessions(value: unknown): readonly SessionView[] {
  if (!Array.isArray(value)) throw invalidServerResponse()
  const result: SessionView[] = []
  const ids = new Set<string>()
  let currentCount = 0
  for (const candidate of value) {
    const session = normalizeSession(candidate)
    if (session === null || ids.has(session.id)) throw invalidServerResponse()
    ids.add(session.id)
    if (session.current) currentCount += 1
    result.push(session)
  }
  if (currentCount > 1) throw invalidServerResponse()
  for (let index = 1; index < result.length; index += 1) {
    const previous = result[index - 1]
    const current = result[index]
    if (previous === undefined || current === undefined) throw invalidServerResponse()
    const timeOrder = compareInstants(previous.createdAt, current.createdAt)
    if (timeOrder < 0 || (timeOrder === 0 && previous.id < current.id)) {
      throw invalidServerResponse()
    }
  }
  return result
}

function normalizeMetadata(value: unknown): Readonly<Record<string, string>> | null {
  if (!isRecord(value)) return null
  const entries = Object.entries(value)
  if (entries.some(([key]) => !AUDIT_METADATA_KEYS.has(key))) return null
  const result: Record<string, string> = {}
  for (const [key, candidate] of entries) {
    if (
      !isBoundedText(candidate, 128, { allowBlank: true }) ||
      /[\u0000-\u001f\u007f]/.test(candidate)
    ) {
      return null
    }
    result[key] = candidate
  }
  return result
}

function normalizeAuditItem(value: unknown): AdminAuditItem | null {
  if (
    !hasRequiredAndOptionalKeys(
      value,
      ['id', 'action', 'targetType', 'outcome', 'traceId', 'metadata', 'timestamp'],
      ['actorAdminId', 'targetId'],
    ) ||
    !isCanonicalUuid(value.id) ||
    typeof value.action !== 'string' ||
    !ACTION.test(value.action) ||
    typeof value.targetType !== 'string' ||
    !TARGET_TYPE.test(value.targetType) ||
    !isAuditOutcome(value.outcome) ||
    !isBoundedText(value.traceId, 64, { trimmed: true }) ||
    !isCanonicalAuditInstant(value.timestamp)
  ) {
    return null
  }
  const actorAdminId = optionalNullable(value, 'actorAdminId')
  const targetId = optionalNullable(value, 'targetId')
  const metadata = normalizeMetadata(value.metadata)
  if (
    (actorAdminId !== null && !isCanonicalUuid(actorAdminId)) ||
    (targetId !== null && !isBoundedText(targetId, 128)) ||
    metadata === null
  ) {
    return null
  }
  return {
    id: value.id,
    actorAdminId: actorAdminId as string | null,
    action: value.action,
    targetType: value.targetType,
    targetId: targetId as string | null,
    outcome: value.outcome,
    traceId: value.traceId,
    metadata,
    timestamp: value.timestamp,
  }
}

function blank(value: unknown): boolean {
  return typeof value === 'string' && value.trim().length === 0
}

function requireAuditQuery(value: unknown): AdminAuditQuery {
  if (
    !isRecord(value) ||
    Object.keys(value).some(
      (key) => !['cursor', 'action', 'outcome', 'from', 'to', 'limit'].includes(key),
    )
  ) {
    throw new TypeError('audit query is invalid')
  }

  const result: {
    cursor?: string
    action?: string
    outcome?: AdminAuditOutcome
    from?: string
    to?: string
    limit?: number
  } = {}

  if (value.cursor !== undefined && !blank(value.cursor)) {
    if (typeof value.cursor !== 'string' || !CURSOR.test(value.cursor)) {
      throw new TypeError('audit cursor is invalid')
    }
    result.cursor = value.cursor
  }
  if (value.action !== undefined && !blank(value.action)) {
    if (typeof value.action !== 'string') throw new TypeError('audit action is invalid')
    const action = value.action.trim()
    if (!ACTION.test(action)) throw new TypeError('audit action is invalid')
    result.action = action
  }
  if (value.outcome !== undefined && !blank(value.outcome)) {
    if (!isAuditOutcome(value.outcome)) throw new TypeError('audit outcome is invalid')
    result.outcome = value.outcome
  }
  if (value.from !== undefined && !blank(value.from)) {
    if (typeof value.from !== 'string') throw new TypeError('audit from is invalid')
    const from = value.from.trim()
    if (!isCanonicalAuditInstant(from)) throw new TypeError('audit from is invalid')
    result.from = from
  }
  if (value.to !== undefined && !blank(value.to)) {
    if (typeof value.to !== 'string') throw new TypeError('audit to is invalid')
    const to = value.to.trim()
    if (!isCanonicalAuditInstant(to)) throw new TypeError('audit to is invalid')
    result.to = to
  }
  if (value.limit !== undefined) {
    if (!Number.isSafeInteger(value.limit) || (value.limit as number) < 1 || (value.limit as number) > 100) {
      throw new RangeError('audit limit is invalid')
    }
    result.limit = value.limit as number
  }
  if (
    result.from !== undefined &&
    result.to !== undefined &&
    compareInstants(result.from, result.to) >= 0
  ) {
    throw new RangeError('audit range is invalid')
  }
  return result
}

function normalizeAuditPage(value: unknown, query: AdminAuditQuery): AdminAuditPage {
  if (
    !hasRequiredAndOptionalKeys(value, ['items'], ['nextCursor']) ||
    !Array.isArray(value.items)
  ) {
    throw invalidServerResponse()
  }
  const limit = query.limit ?? 50
  if (value.items.length > limit) throw invalidServerResponse()
  const nextCursor = optionalNullable(value, 'nextCursor')
  if (
    (nextCursor !== null &&
      (typeof nextCursor !== 'string' ||
        !CURSOR.test(nextCursor) ||
        value.items.length !== limit))
  ) {
    throw invalidServerResponse()
  }

  const items: AdminAuditItem[] = []
  const ids = new Set<string>()
  for (const candidate of value.items) {
    const item = normalizeAuditItem(candidate)
    if (item === null || ids.has(item.id)) throw invalidServerResponse()
    ids.add(item.id)
    items.push(item)
  }
  for (let index = 1; index < items.length; index += 1) {
    const previous = items[index - 1]
    const current = items[index]
    if (previous === undefined || current === undefined) throw invalidServerResponse()
    const timeOrder = compareInstants(previous.timestamp, current.timestamp)
    if (timeOrder < 0 || (timeOrder === 0 && previous.id > current.id)) {
      throw invalidServerResponse()
    }
  }
  return { items, nextCursor: nextCursor as string | null }
}

function normalizeMaintenance(
  value: unknown,
  expectedType: MaintenanceType,
): MaintenanceView | null {
  if (
    !hasExactKeys(
      value,
      [
        'type',
        'status',
        'startedAt',
        'finishedAt',
        'artifactChecksum',
        'errorCategory',
      ],
    ) ||
    value.type !== expectedType ||
    !isMaintenanceStatus(value.status) ||
    !isCanonicalInstant(value.startedAt)
  ) {
    return null
  }
  const finishedAt = optionalNullable(value, 'finishedAt')
  const artifactChecksum = optionalNullable(value, 'artifactChecksum')
  const errorCategory = optionalNullable(value, 'errorCategory')
  const expectedError = value.status === 'FAILED' ? FAILURE_CATEGORIES[expectedType] : null
  if (
    (finishedAt !== null && !isCanonicalInstant(finishedAt)) ||
    (artifactChecksum !== null &&
      (typeof artifactChecksum !== 'string' || !SHA_256.test(artifactChecksum))) ||
    errorCategory !== expectedError ||
    (value.status === 'RUNNING' && finishedAt !== null) ||
    (value.status !== 'RUNNING' &&
      (finishedAt === null || compareInstants(finishedAt as string, value.startedAt) < 0))
  ) {
    return null
  }
  return {
    type: expectedType,
    status: value.status,
    startedAt: value.startedAt,
    finishedAt: finishedAt as string | null,
    artifactChecksum: artifactChecksum as string | null,
    errorCategory: errorCategory as MaintenanceErrorCategory | null,
  }
}

function normalizeOperations(value: unknown): OperationsStatus {
  if (!hasExactKeys(value, [...OPERATIONS_KEYS, 'serverTime'])) {
    throw invalidServerResponse()
  }
  if (!isCanonicalInstant(value.serverTime)) throw invalidServerResponse()

  const result = {} as Record<OperationKey, MaintenanceView | null>
  for (const key of OPERATIONS_KEYS) {
    const candidate = optionalNullable(value, key)
    if (candidate === null) {
      result[key] = null
      continue
    }
    const normalized = normalizeMaintenance(candidate, OPERATION_TYPES[key])
    if (normalized === null) throw invalidServerResponse()
    result[key] = normalized
  }
  return {
    databaseBackup: result.databaseBackup,
    mediaBackup: result.mediaBackup,
    analyticsAggregation: result.analyticsAggregation,
    contactRetention: result.contactRetention,
    mediaCleanup: result.mediaCleanup,
    deployment: result.deployment,
    restoreDrill: result.restoreDrill,
    serverTime: value.serverTime,
  }
}

function requireNoContent(response: { status?: unknown; data?: unknown }): void {
  if (
    response.status !== 204 ||
    !(response.data === undefined || response.data === null || response.data === '')
  ) {
    throw invalidServerResponse()
  }
}

function requireOk(response: { status?: unknown }): void {
  if (response.status !== 200) throw invalidServerResponse()
}

function requireSessionId(value: unknown): string {
  if (!isCanonicalUuid(value)) throw new TypeError('session id must be a canonical UUID')
  return value
}

export function createSettingsApi(client: AxiosInstance): SettingsApi {
  return {
    async changePassword(body) {
      const request = requirePasswordRequest(body)
      const response = await client.post<unknown>('/api/admin/security/password', request)
      requireNoContent(response)
    },

    async beginTotpEnrollment(body) {
      const request = requireReauthentication(body)
      const response = await client.post<unknown>(
        '/api/admin/security/totp/enrollment',
        request,
      )
      requireOk(response)
      return normalizeEnrollment(response.data)
    },

    async confirmTotp(body) {
      const request = requireTotpConfirmation(body)
      const response = await client.post<unknown>(
        '/api/admin/security/totp/confirm',
        request,
      )
      requireOk(response)
      return normalizeRecoveryCodes(response.data)
    },

    async regenerateRecoveryCodes(body) {
      const request = requireReauthentication(body)
      const response = await client.post<unknown>(
        '/api/admin/security/recovery-codes/regenerate',
        request,
      )
      requireOk(response)
      return normalizeRecoveryCodes(response.data)
    },

    async listSessions() {
      const response = await client.get<unknown>('/api/admin/security/sessions')
      requireOk(response)
      return normalizeSessions(response.data)
    },

    async revokeSession(metadataId) {
      const id = requireSessionId(metadataId)
      const response = await client.post<unknown>(
        `/api/admin/security/sessions/${encodeURIComponent(id)}/revoke`,
      )
      requireNoContent(response)
    },

    async getAudit(query = {}) {
      const normalized = requireAuditQuery(query)
      const response = await client.get<unknown>('/api/admin/audit', {
        params: normalized,
      })
      requireOk(response)
      return normalizeAuditPage(response.data, normalized)
    },

    async getOperations() {
      const response = await client.get<unknown>('/api/admin/system/operations')
      requireOk(response)
      return normalizeOperations(response.data)
    },
  }
}

export const settingsApi = createSettingsApi(http)
