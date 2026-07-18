export type SecondFactorMethod = 'TOTP' | 'RECOVERY_CODE'

export interface PasswordStageRequest {
  readonly username: string
  readonly password: string
}

export interface SecondFactorRequest {
  readonly method: SecondFactorMethod
  readonly code: string
}

export interface PasswordStageResponse {
  readonly next: 'SECOND_FACTOR'
  readonly expiresAt: string
}

export interface CsrfResponse {
  readonly headerName: string
  readonly parameterName: string
  readonly token: string
}

export interface MeResponse {
  readonly id: string
  readonly username: string
}
