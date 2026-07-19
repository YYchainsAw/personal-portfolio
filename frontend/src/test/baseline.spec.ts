import { describe, expect, it } from 'vitest'

describe('public-site baseline', () => {
  it('provides the deterministic reduced-motion query used by reveal tests', () => {
    expect(matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false)
  })
})
