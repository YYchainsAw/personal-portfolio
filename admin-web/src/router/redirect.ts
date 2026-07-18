const DEFAULT_ADMIN_REDIRECT = '/admin/dashboard'
const MAX_REDIRECT_LENGTH = 2048
const REDIRECT_BASE = 'https://portfolio.invalid'
const AUTH_ROUTE_PATHS = new Set(['/admin/login', '/admin/totp'])

function containsUnsafeRedirectCharacters(value: string): boolean {
  return /[\\\u0000-\u001f\u007f]/.test(value)
}

function isAuthRoutePath(pathname: string): boolean {
  return AUTH_ROUTE_PATHS.has(pathname.replace(/\/+$/, ''))
}

function decodePathname(pathname: string): string | null {
  try {
    return decodeURIComponent(pathname)
  } catch {
    return null
  }
}

export function sanitizeAdminRedirect(value: unknown): string {
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > MAX_REDIRECT_LENGTH ||
    !value.startsWith('/admin/') ||
    value.startsWith('/admin//') ||
    containsUnsafeRedirectCharacters(value)
  ) {
    return DEFAULT_ADMIN_REDIRECT
  }

  try {
    const normalized = new URL(value, REDIRECT_BASE)
    const decodedPathname = decodePathname(normalized.pathname)
    if (
      normalized.origin !== REDIRECT_BASE ||
      decodedPathname === null ||
      !normalized.pathname.startsWith('/admin/') ||
      normalized.pathname.startsWith('/admin//') ||
      !decodedPathname.startsWith('/admin/') ||
      decodedPathname.startsWith('/admin//') ||
      containsUnsafeRedirectCharacters(decodedPathname) ||
      isAuthRoutePath(decodedPathname)
    ) {
      return DEFAULT_ADMIN_REDIRECT
    }
    return `${normalized.pathname}${normalized.search}${normalized.hash}`
  } catch {
    return DEFAULT_ADMIN_REDIRECT
  }
}
