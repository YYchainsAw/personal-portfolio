import assert from 'node:assert/strict'
import { readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { canonicalize } from './canonical-json.mjs'
import { assertValidPortfolioPayload, exportPortfolio } from './export-portfolio.mjs'

const readExportedPayload = async () => {
  const output = join(tmpdir(), `portfolio-${crypto.randomUUID()}-schema.json`)
  await exportPortfolio({ outputPath: output })
  return {
    output,
    payload: JSON.parse(await readFile(output, 'utf8')),
  }
}

test('canonicalizes object keys by Unicode code unit', () => {
  const canonical = canonicalize({ z: 1, 'ä': 2, a: 3, Z: 4 })

  assert.deepEqual(Object.keys(canonical), ['Z', 'a', 'z', 'ä'])
})

test('exports schema v1 deterministically and preserves project order', async () => {
  const first = join(tmpdir(), `portfolio-${crypto.randomUUID()}-a.json`)
  const second = join(tmpdir(), `portfolio-${crypto.randomUUID()}-b.json`)
  try {
    const a = await exportPortfolio({ outputPath: first })
    const b = await exportPortfolio({ outputPath: second })
    const firstBytes = await readFile(first)
    const secondBytes = await readFile(second)
    const payload = JSON.parse(firstBytes.toString('utf8'))
    assert.deepEqual(firstBytes, secondBytes)
    assert.equal(a.sha256, b.sha256)
    assert.equal(payload.schemaVersion, 1)
    assert.deepEqual(
      payload.portfolioContent.en.projects.map((project) => project.id),
      ['ue-environment-study', 'gameplay-prototype', 'development-log'],
    )
  } finally {
    await Promise.all([rm(first, { force: true }), rm(second, { force: true })])
  }
})

test('allows a present blank translation but rejects a missing required translation field', async () => {
  const { output, payload } = await readExportedPayload()
  try {
    payload.portfolioContent.en.hero.headline = ''
    await assert.doesNotReject(() => assertValidPortfolioPayload(payload))
    delete payload.portfolioContent.en.hero.headline
    await assert.rejects(
      () => assertValidPortfolioPayload(payload),
      /portfolio schema validation failed/,
    )
  } finally {
    await rm(output, { force: true })
  }
})

test('rejects asset source URLs outside the bounded HTTPS contract', async () => {
  const { output, payload } = await readExportedPayload()
  try {
    const invalidSourceUrls = [
      ['non-string source URL', (candidate) => (candidate.heroAsset.sourceUrl = 42)],
      ['hero HTTP URL', (candidate) => (candidate.heroAsset.sourceUrl = 'http://example.com/image')],
      [
        'malformed URI',
        (candidate) => (candidate.heroAsset.sourceUrl = 'https://example.com/%zz'),
      ],
      [
        'hero URL with userinfo',
        (candidate) => (candidate.heroAsset.sourceUrl = 'https://user@example.com/image'),
      ],
      [
        'project URL with fragment',
        (candidate) => (candidate.projectAssets[0].sourceUrl = 'https://example.com/image#detail'),
      ],
      [
        'project URL with whitespace',
        (candidate) => (candidate.projectAssets[0].sourceUrl = 'https://example.com/image path'),
      ],
      [
        'source URL over 2048 characters',
        (candidate) =>
          (candidate.projectAssets[0].sourceUrl = `https://example.com/${'a'.repeat(2029)}`),
      ],
    ]

    for (const [label, mutate] of invalidSourceUrls) {
      const candidate = structuredClone(payload)
      mutate(candidate)
      await assert.rejects(
        () => assertValidPortfolioPayload(candidate),
        /portfolio schema validation failed/,
        label,
      )
    }
  } finally {
    await rm(output, { force: true })
  }
})
