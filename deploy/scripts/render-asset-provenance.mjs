#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, readdirSync, realpathSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'
import { readStrictJson } from './node-production-closure.mjs'

function fail(message) {
  console.error(`render-asset-provenance: ${message}`)
  process.exit(1)
}

function args(argv) {
  const result = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    if (!argv[index]?.startsWith('--') || !argv[index + 1]) fail('invalid arguments')
    if (result.has(argv[index])) fail(`duplicate argument: ${argv[index]}`)
    result.set(argv[index], argv[index + 1])
  }
  for (const required of ['--source', '--manifest', '--output']) {
    if (!result.has(required)) fail(`missing ${required}`)
  }
  return {
    source: realpathSync(resolve(result.get('--source'))),
    manifest: resolve(result.get('--manifest')),
    output: resolve(result.get('--output')),
  }
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function exactKeys(value, keys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object`)
  const actual = Object.keys(value).sort((left, right) => left.localeCompare(right, 'en'))
  const expected = [...keys].sort((left, right) => left.localeCompare(right, 'en'))
  if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} fields must be exactly: ${expected.join(', ')}`)
}

function walk(directory, source, paths) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    left.name.localeCompare(right.name, 'en'),
  )) {
    const path = join(directory, entry.name)
    if (entry.isSymbolicLink()) fail(`asset tree contains a symlink: ${relative(source, path)}`)
    if (entry.isDirectory()) walk(path, source, paths)
    else if (entry.isFile() && /\.(?:ico|jpe?g|png|gif|webp|avif|svg|woff2?|ttf|otf|mp4|webm|pdf)$/i.test(entry.name)) {
      paths.push(relative(source, path).split(sep).join('/'))
    }
  }
}

const options = args(process.argv.slice(2))
let manifest
try {
  manifest = readStrictJson(options.manifest, 'asset provenance manifest')
} catch (error) {
  fail(`cannot parse manifest: ${error.message}`)
}
exactKeys(manifest, ['assets', 'licenseDefinitions', 'licenseNotices', 'schemaVersion'], 'asset provenance manifest')
if (manifest.schemaVersion !== 2 || !Array.isArray(manifest.assets) || manifest.assets.length === 0) {
  fail('manifest must contain a non-empty schemaVersion 2 asset list')
}
if (!manifest.licenseDefinitions || typeof manifest.licenseDefinitions !== 'object' || Array.isArray(manifest.licenseDefinitions)) {
  fail('manifest has no license definitions')
}
if (!manifest.licenseNotices || typeof manifest.licenseNotices !== 'object' || Array.isArray(manifest.licenseNotices)) {
  fail('manifest has no reviewed third-party license notices')
}
for (const [identifier, notice] of Object.entries(manifest.licenseNotices)) {
  if (!Object.hasOwn(manifest.licenseDefinitions, identifier)) fail(`license notice has no definition: ${identifier}`)
  exactKeys(notice, ['reviewedAt', 'summary', 'url'], `${identifier} notice`)
  if (typeof notice.url !== 'string' || !/^https:\/\//.test(notice.url)) fail(`invalid license notice URL: ${identifier}`)
  if (typeof notice.reviewedAt !== 'string' || !/^20[0-9]{2}-[01][0-9]-[0-3][0-9]$/.test(notice.reviewedAt)) {
    fail(`invalid license review date: ${identifier}`)
  }
  if (typeof notice.summary !== 'string' || notice.summary.trim().length < 40) fail(`incomplete license notice summary: ${identifier}`)
}

const discovered = []
for (const root of ['frontend/public', 'frontend/src/assets']) {
  const directory = resolve(options.source, root)
  const boundary = `${options.source}${sep}`
  if (!realpathSync(directory).startsWith(boundary)) fail(`asset root escapes source: ${root}`)
  walk(directory, options.source, discovered)
}
discovered.sort((left, right) => left.localeCompare(right, 'en'))

const records = []
const listed = new Set()
for (const asset of manifest.assets) {
  const thirdParty = asset && Object.hasOwn(manifest.licenseNotices, asset.license)
  exactKeys(
    asset,
    thirdParty ? ['bytes', 'credit', 'license', 'origin', 'path', 'sha256', 'sourceUrl'] : ['bytes', 'license', 'origin', 'path', 'sha256'],
    `asset ${asset?.path ?? '<unknown>'}`,
  )
  if (
    !asset ||
    typeof asset.path !== 'string' ||
    !/^(?:frontend\/public|frontend\/src\/assets)\/[A-Za-z0-9._/-]+$/.test(asset.path) ||
    asset.path.includes('/../')
  ) {
    fail('manifest contains an unsafe asset path')
  }
  if (listed.has(asset.path)) fail(`duplicate asset: ${asset.path}`)
  listed.add(asset.path)
  if (!Number.isSafeInteger(asset.bytes) || asset.bytes <= 0) fail(`invalid byte count: ${asset.path}`)
  if (!/^[0-9a-f]{64}$/.test(asset.sha256)) fail(`invalid SHA-256: ${asset.path}`)
  if (typeof asset.origin !== 'string' || !asset.origin.trim()) fail(`missing origin: ${asset.path}`)
  if (
    typeof asset.license !== 'string' ||
    (!Object.hasOwn(manifest.licenseDefinitions, asset.license) && !/^[A-Za-z0-9-.+]+$/.test(asset.license))
  ) {
    fail(`unknown license: ${asset.path}`)
  }
  if (asset.license.startsWith('LicenseRef-') && asset.license !== 'LicenseRef-Portfolio-Content-All-Rights-Reserved' && !thirdParty) {
    fail(`third-party asset license has no reviewed notice: ${asset.path}`)
  }
  if (
    thirdParty &&
    (typeof asset.credit !== 'string' || !asset.credit.trim() || typeof asset.sourceUrl !== 'string' || !/^https:\/\//.test(asset.sourceUrl))
  ) {
    fail(`third-party asset has incomplete credit/source provenance: ${asset.path}`)
  }
  const path = resolve(options.source, ...asset.path.split('/'))
  const boundary = `${options.source}${sep}`
  if (!path.startsWith(boundary)) fail(`asset escapes source: ${asset.path}`)
  const info = lstatSync(path)
  if (!info.isFile() || info.isSymbolicLink()) fail(`asset is not a regular file: ${asset.path}`)
  if (info.size !== asset.bytes) fail(`asset size changed: ${asset.path}`)
  if (sha256(path) !== asset.sha256) fail(`asset digest changed: ${asset.path}`)
  records.push(asset)
}

const expected = [...listed].sort((left, right) => left.localeCompare(right, 'en'))
if (JSON.stringify(expected) !== JSON.stringify(discovered)) {
  const missing = discovered.filter((path) => !listed.has(path))
  const stale = expected.filter((path) => !discovered.includes(path))
  fail(`asset inventory is not closed (unlisted=${missing.join(',') || 'none'} stale=${stale.join(',') || 'none'})`)
}

records.sort((left, right) => left.path.localeCompare(right.path, 'en'))
let markdown = '# Asset provenance\n\n'
markdown +=
  'This inventory covers production media and identity assets shipped from the repository. ' +
  'It does not grant an open-source license for portfolio content.\n\n'
markdown += '| Path | Bytes | SHA-256 | Origin / credit | Source | License |\n'
markdown += '| --- | ---: | --- | --- | --- | --- |\n'
for (const asset of records) {
  const origin = asset.origin.replaceAll('|', '\\|')
  const source = asset.sourceUrl ? `[source](${asset.sourceUrl})` : 'repository owner'
  markdown += `| \`${asset.path}\` | ${asset.bytes} | \`${asset.sha256}\` | ${origin} | ${source} | \`${asset.license}\` |\n`
}
markdown += '\n## License definitions\n\n'
for (const [identifier, description] of Object.entries(manifest.licenseDefinitions).sort(([left], [right]) =>
  left.localeCompare(right, 'en'),
)) {
  if (typeof description !== 'string' || !description.trim()) fail(`empty license definition: ${identifier}`)
  markdown += `- \`${identifier}\`: ${description}\n`
}
markdown += '\n## Reviewed third-party license notices\n\n'
for (const [identifier, notice] of Object.entries(manifest.licenseNotices).sort(([left], [right]) =>
  left.localeCompare(right, 'en'),
)) {
  markdown += `- \`${identifier}\` ([official terms](${notice.url}), reviewed ${notice.reviewedAt}): ${notice.summary}\n`
}
writeFileSync(options.output, markdown, { mode: 0o600 })
console.error(`render-asset-provenance: verified ${records.length} production assets`)
