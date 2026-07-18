export type AdminSessionStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED'

export interface SessionView {
  readonly id: string
  readonly status: AdminSessionStatus
  readonly createdAt: string
  readonly endedAt: string | null
  readonly lastAccessMillis: number
  readonly clientSummary: string
  readonly reason: string | null
  readonly current: boolean
}

export interface PasswordChangeRequest {
  readonly currentPassword: string
  readonly currentTotp: string
  readonly newPassword: string
}

export interface ReauthenticationRequest {
  readonly currentPassword: string
  readonly currentTotp: string
}

export interface TotpConfirmRequest {
  readonly enrollmentId: string
  readonly newTotp: string
}

export interface TotpEnrollmentResponse {
  readonly enrollmentId: string
  readonly provisioningUri: string
  readonly expiresAt: string
}

export interface RecoveryCodesResponse {
  readonly recoveryCodes: readonly string[]
}

export type AdminAuditOutcome = 'SUCCESS' | 'FAILURE'

export interface AdminAuditItem {
  readonly id: string
  readonly actorAdminId: string | null
  readonly action: string
  readonly targetType: string
  readonly targetId: string | null
  readonly outcome: AdminAuditOutcome
  readonly traceId: string
  readonly metadata: Readonly<Record<string, string>>
  readonly timestamp: string
}

export interface AdminAuditPage {
  readonly items: readonly AdminAuditItem[]
  readonly nextCursor: string | null
}

export interface AdminAuditQuery {
  readonly cursor?: string
  readonly action?: string
  readonly outcome?: AdminAuditOutcome
  readonly from?: string
  readonly to?: string
  readonly limit?: number
}

export type MaintenanceType =
  | 'DATABASE_BACKUP'
  | 'MEDIA_BACKUP'
  | 'ANALYTICS_AGGREGATE'
  | 'CONTACT_RETENTION'
  | 'MEDIA_CLEANUP_SCAN'
  | 'DEPLOYMENT'
  | 'RESTORE_DRILL'

export type MaintenanceStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type MaintenanceErrorCategory =
  | 'DATABASE_BACKUP_FAILED'
  | 'MEDIA_BACKUP_FAILED'
  | 'ANALYTICS_AGGREGATION_FAILED'
  | 'CONTACT_RETENTION_FAILED'
  | 'MEDIA_CLEANUP_FAILED'
  | 'DEPLOYMENT_FAILED'
  | 'RESTORE_DRILL_FAILED'

export interface MaintenanceView {
  readonly type: MaintenanceType
  readonly status: MaintenanceStatus
  readonly startedAt: string
  readonly finishedAt: string | null
  readonly artifactChecksum: string | null
  readonly errorCategory: MaintenanceErrorCategory | null
}

export interface OperationsStatus {
  readonly databaseBackup: MaintenanceView | null
  readonly mediaBackup: MaintenanceView | null
  readonly analyticsAggregation: MaintenanceView | null
  readonly contactRetention: MaintenanceView | null
  readonly mediaCleanup: MaintenanceView | null
  readonly deployment: MaintenanceView | null
  readonly restoreDrill: MaintenanceView | null
  readonly serverTime: string
}
