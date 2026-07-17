export const canonicalize = (value) => {
  if (Array.isArray(value)) return value.map(canonicalize)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, canonicalize(value[key])]),
  )
}

export const canonicalStringify = (value) => `${JSON.stringify(canonicalize(value), null, 2)}\n`
