export type FieldErrors = Readonly<Record<string, string>>

export interface ApiProblemBody {
  readonly type: string
  readonly title: string
  readonly status: number
  readonly code: string
  readonly traceId: string
  readonly fieldErrors?: FieldErrors
  readonly retryAfterSeconds?: number
}

export class ApiProblem extends Error {
  readonly body: Readonly<ApiProblemBody>

  constructor(body: ApiProblemBody) {
    super(body.title)
    this.name = 'ApiProblem'
    this.body = Object.freeze({
      ...body,
      ...(body.fieldErrors === undefined
        ? {}
        : { fieldErrors: Object.freeze({ ...body.fieldErrors }) }),
    })
  }
}

export interface VersionedDraft<T> {
  readonly version: number
  readonly value: T
}

export interface Page<T> {
  readonly items: T[]
  readonly page: number
  readonly size: number
  readonly totalItems: number
  readonly totalPages: number
}

export interface CursorPage<T> {
  readonly items: T[]
  readonly nextCursor: string | null
}
