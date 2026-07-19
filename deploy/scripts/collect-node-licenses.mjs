#!/usr/bin/env node

import { createHash } from 'node:crypto'
import {
  copyFileSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, join, resolve, sep } from 'node:path'
import { TextDecoder } from 'node:util'
import { deriveNodeProductionClosure, readStrictJson } from './node-production-closure.mjs'

function fail(message) {
  console.error(`collect-node-licenses: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index]
    const value = argv[index + 1]
    if (!name?.startsWith('--') || !value) fail(`invalid argument near ${name ?? '<end>'}`)
    if (values.has(name)) fail(`duplicate argument: ${name}`)
    values.set(name, value)
  }
  for (const name of ['--project', '--component', '--output', '--overrides']) {
    if (!values.has(name)) fail(`missing ${name}`)
  }
  return {
    project: resolve(values.get('--project')),
    component: values.get('--component'),
    output: resolve(values.get('--output')),
    overrides: resolve(values.get('--overrides')),
  }
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function normalizeLicenses(pkg) {
  const raw = pkg.license ?? pkg.licenses
  const values = []
  const add = (value) => {
    if (typeof value === 'string') values.push(value.trim())
    else if (value && typeof value.type === 'string') values.push(value.type.trim())
  }
  if (Array.isArray(raw)) raw.forEach(add)
  else add(raw)
  const unique = [...new Set(values.filter(Boolean))].sort((left, right) => left.localeCompare(right, 'en'))
  if (unique.length === 0 || unique.some((value) => /^UNLICENSED$/i.test(value))) {
    fail(`${pkg.name}@${pkg.version} has no reviewed license expression`)
  }
  return unique
}

function packageDirectory(project, lockPath) {
  const candidate = resolve(project, ...lockPath.split('/'))
  const root = `${realpathSync(project)}${sep}`
  let actual
  try {
    actual = realpathSync(candidate)
  } catch {
    return null
  }
  if (!actual.startsWith(root)) fail(`package escapes project root: ${lockPath}`)
  return actual
}

function safeName(name, version) {
  const value = `${name}@${version}`
    .replace(/^@/, '_at_')
    .replaceAll('/', '__')
    .replace(/[^A-Za-z0-9._@+-]/g, '_')
  if (!value || value === '.' || value === '..') fail(`unsafe package name: ${name}`)
  return value
}

function assertExactKeys(value, expected, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object`)
  const actual = Object.keys(value).sort((left, right) => left.localeCompare(right, 'en'))
  const wanted = [...expected].sort((left, right) => left.localeCompare(right, 'en'))
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    fail(`${label} fields must be exactly: ${wanted.join(', ')}`)
  }
}

function validateOverrides(overrides, component) {
  assertExactKeys(overrides, ['schemaVersion', 'nodeComponents', 'packages', 'mavenPackages'], 'override manifest')
  if (overrides.schemaVersion !== 1) fail('license override manifest must use schemaVersion 1')
  if (
    !Array.isArray(overrides.nodeComponents) ||
    overrides.nodeComponents.length === 0 ||
    !overrides.nodeComponents.every((value) => typeof value === 'string' && /^[a-z][a-z0-9-]{1,31}$/.test(value)) ||
    new Set(overrides.nodeComponents).size !== overrides.nodeComponents.length
  ) {
    fail('nodeComponents must be a non-empty unique component list')
  }
  if (!overrides.nodeComponents.includes(component)) fail(`component is not declared in override manifest: ${component}`)
  if (!overrides.packages || typeof overrides.packages !== 'object' || Array.isArray(overrides.packages)) {
    fail('override packages must be an object')
  }
  if (!overrides.mavenPackages || typeof overrides.mavenPackages !== 'object' || Array.isArray(overrides.mavenPackages)) {
    fail('override mavenPackages must be an object')
  }

  const selected = new Map()
  for (const [identity, override] of Object.entries(overrides.packages)) {
    if (!/^(?:@[^/@]+\/)?[^/@]+@[^@/]+$/.test(identity)) fail(`invalid Node override identity: ${identity}`)
    assertExactKeys(override, ['components', 'file', 'license', 'sha256', 'source'], `${identity} override`)
    if (
      !Array.isArray(override.components) ||
      override.components.length === 0 ||
      !override.components.every((value) => overrides.nodeComponents.includes(value)) ||
      new Set(override.components).size !== override.components.length
    ) {
      fail(`${identity} override has invalid components`)
    }
    if (
      typeof override.license !== 'string' ||
      !override.license ||
      typeof override.file !== 'string' ||
      !/^license-texts\/[A-Za-z0-9._-]+$/.test(override.file) ||
      typeof override.sha256 !== 'string' ||
      !/^[0-9a-f]{64}$/.test(override.sha256) ||
      typeof override.source !== 'string' ||
      !/^https:\/\/raw\.githubusercontent\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/[0-9a-f]{40}\//.test(
        override.source,
      )
    ) {
      fail(`${identity} has an invalid reviewed override`)
    }
    if (override.components.includes(component)) selected.set(identity, override)
  }
  return selected
}

const { project, component, output, overrides: overridesPath } = parseArgs(process.argv.slice(2))
if (!/^[a-z][a-z0-9-]{1,31}$/.test(component)) fail('component must be a short lowercase identifier')

const lockPath = join(project, 'package-lock.json')
const rootPackagePath = join(project, 'package.json')
let lock
let rootPackage
let overrides
let closure
try {
  lock = readStrictJson(lockPath, 'package-lock.json')
  rootPackage = readStrictJson(rootPackagePath, 'package.json')
  overrides = readStrictJson(overridesPath, 'license-overrides.json')
  closure = deriveNodeProductionClosure(lock, { os: 'linux', cpu: 'x64' })
} catch (error) {
  fail(`cannot read production closure: ${error.message}`)
}
if (rootPackage.name !== closure.root.name || rootPackage.version !== closure.root.version) {
  fail('package.json and package-lock.json root identities differ')
}
const selectedOverrides = validateOverrides(overrides, component)
const usedOverrides = new Set()
const overridesRoot = realpathSync(resolve(overridesPath, '..'))

mkdirSync(output, { recursive: true, mode: 0o700 })
if (readdirSync(output).length !== 0) fail(`output directory is not empty: ${output}`)
const licenseRoot = join(output, 'licenses')
mkdirSync(licenseRoot, { mode: 0o700 })

const identityMaterials = new Map()
const decoder = new TextDecoder('utf-8', { fatal: true })
for (const entry of closure.entries) {
  const directory = packageDirectory(project, entry.path)
  if (!directory) fail(`production package is not installed: ${entry.path}`)
  let pkg
  try {
    pkg = readStrictJson(join(directory, 'package.json'), `${entry.path}/package.json`)
  } catch (error) {
    fail(error.message)
  }
  if (pkg.name !== entry.name || pkg.version !== entry.version) {
    fail(`lock/install identity mismatch: ${entry.path}`)
  }
  const licenses = normalizeLicenses(pkg)
  const legalNames = readdirSync(directory)
    .filter((name) => /^(?:licen[cs]e|copying|notice)(?:[._-].*)?$/i.test(name))
    .sort((left, right) => left.localeCompare(right, 'en'))
  const licenseNames = legalNames.filter((name) => /^(?:licen[cs]e|copying)(?:[._-].*)?$/i.test(name))
  const legalSources = legalNames.map((name) => ({ name, path: join(directory, name), source: 'package' }))
  if (licenseNames.length === 0) {
    const override = selectedOverrides.get(entry.identity)
    if (!override) fail(`${entry.identity} has no packaged license text or reviewed override`)
    if (!licenses.includes(override.license)) fail(`${entry.identity} override license does not match package metadata`)
    const path = resolve(overridesRoot, ...override.file.split('/'))
    const boundary = `${overridesRoot}${sep}`
    if (!path.startsWith(boundary)) fail(`${entry.identity} override escapes compliance root`)
    let info
    try {
      info = lstatSync(path)
    } catch (error) {
      fail(`${entry.identity} override cannot be inspected: ${error.message}`)
    }
    if (!info.isFile() || info.isSymbolicLink()) fail(`${entry.identity} override is not a regular file`)
    const bytes = readFileSync(path)
    if (sha256(bytes) !== override.sha256) fail(`${entry.identity} override digest mismatch`)
    legalSources.push({ name: basename(override.file), path, source: override.source })
    usedOverrides.add(entry.identity)
  }

  for (const expression of licenses) {
    const match = /^SEE LICENSE IN (.+)$/i.exec(expression)
    if (match && !legalSources.some(({ name }) => name.toLowerCase() === basename(match[1]).toLowerCase())) {
      fail(`${entry.identity} references a missing license file`)
    }
  }
  if (new Set(legalSources.map(({ name }) => name)).size !== legalSources.length) {
    fail(`${entry.identity} has duplicate legal file names`)
  }

  const files = []
  const destination = join(licenseRoot, safeName(entry.name, entry.version))
  for (const legalSource of legalSources) {
    const info = lstatSync(legalSource.path)
    if (!info.isFile() || info.isSymbolicLink()) fail(`legal file is not regular: ${entry.path}/${legalSource.name}`)
    if (info.size <= 0 || info.size > 1024 * 1024) fail(`legal file has unsafe size: ${entry.path}/${legalSource.name}`)
    const bytes = readFileSync(legalSource.path)
    try {
      decoder.decode(bytes)
    } catch {
      fail(`legal file is not UTF-8 text: ${entry.path}/${legalSource.name}`)
    }
    files.push({
      name: legalSource.name,
      bytes: bytes.length,
      sha256: sha256(bytes),
      source: legalSource.source,
      sourcePath: legalSource.path,
    })
  }
  const publicFiles = files.map(({ sourcePath, ...file }) => file)
  const signature = JSON.stringify({ licenses, files: publicFiles })
  const previous = identityMaterials.get(entry.identity)
  if (previous && previous.signature !== signature) {
    fail(`same package identity has conflicting legal material: ${entry.identity}`)
  }
  if (!previous) {
    mkdirSync(destination, { recursive: true, mode: 0o700 })
    for (const file of files) copyFileSync(file.sourcePath, join(destination, file.name))
    identityMaterials.set(entry.identity, { signature, licenses, files: publicFiles })
  }
}

const unusedOverrides = [...selectedOverrides.keys()]
  .filter((identity) => !usedOverrides.has(identity))
  .sort((left, right) => left.localeCompare(right, 'en'))
if (unusedOverrides.length > 0) fail(`unused reviewed Node overrides: ${unusedOverrides.join(', ')}`)

const records = closure.identities.map((entry) => {
  const material = identityMaterials.get(entry.identity)
  if (!material) fail(`missing legal material for closure identity: ${entry.identity}`)
  return {
    name: entry.name,
    version: entry.version,
    integrity: entry.integrity,
    lockPaths: entry.lockPaths,
    dependsOn: entry.dependsOn,
    licenses: material.licenses,
    files: material.files,
  }
})
const manifest = {
  schemaVersion: 2,
  component,
  target: closure.target,
  root: closure.root,
  packages: records,
}
writeFileSync(join(output, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 })

let notice = `THIRD-PARTY NOTICES - ${component}\n\n`
for (const record of records) {
  const identity = `${record.name}@${record.version}`
  notice += `${'='.repeat(78)}\n${identity}\nLicense: ${record.licenses.join(' OR ')}\n${'='.repeat(78)}\n`
  const directory = join(licenseRoot, safeName(record.name, record.version))
  for (const file of record.files) {
    let text = readFileSync(join(directory, file.name), 'utf8').replaceAll('\r\n', '\n')
    if (!text.endsWith('\n')) text += '\n'
    notice += `\n--- ${file.name} ---\n${text}`
  }
  notice += '\n'
}
writeFileSync(join(output, 'THIRD_PARTY_NOTICES.txt'), notice, { mode: 0o600 })

const manifestInfo = statSync(join(output, 'manifest.json'))
console.error(
  `collect-node-licenses: ${component}: ${closure.entries.length} lock entries, ${records.length} package identities, manifest ${manifestInfo.size} bytes`,
)
