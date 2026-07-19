#!/usr/bin/env node

import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { deriveNodeProductionClosure, readStrictJson } from './node-production-closure.mjs'

function fail(message) {
  console.error(`reconcile-node-sbom: ${message}`)
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
  for (const name of ['--lock', '--raw-sbom', '--license-manifest', '--output']) {
    if (!values.has(name)) fail(`missing ${name}`)
  }
  return Object.fromEntries(
    [...values].map(([name, value]) => [name.slice(2).replaceAll('-', '_'), resolve(value)]),
  )
}

function npmPurl(name, version) {
  const encodedName = name.split('/').map(encodeURIComponent).join('/')
  return `pkg:npm/${encodedName}@${encodeURIComponent(version)}`
}

function identityFromPurl(purl) {
  if (typeof purl !== 'string' || !purl.startsWith('pkg:npm/')) return null
  const value = purl.slice('pkg:npm/'.length).split(/[?#]/, 1)[0]
  const separator = value.lastIndexOf('@')
  if (separator <= 0 || separator === value.length - 1) return null
  try {
    return `${decodeURIComponent(value.slice(0, separator))}@${decodeURIComponent(value.slice(separator + 1))}`
  } catch {
    return null
  }
}

function integrityHash(integrity) {
  const separator = integrity.indexOf('-')
  const algorithm = integrity.slice(0, separator)
  const encoded = integrity.slice(separator + 1)
  const names = { sha256: 'SHA-256', sha384: 'SHA-384', sha512: 'SHA-512' }
  const expectedBytes = { sha256: 32, sha384: 48, sha512: 64 }
  const bytes = Buffer.from(encoded, 'base64')
  if (!names[algorithm] || bytes.length !== expectedBytes[algorithm]) fail(`invalid package integrity: ${integrity}`)
  return { alg: names[algorithm], content: bytes.toString('hex') }
}

const unorderedArrays = new Set([
  'components',
  'dependencies',
  'dependsOn',
  'externalReferences',
  'hashes',
  'licenses',
  'properties',
  'tools',
])

function canonical(value, parentKey = '') {
  if (Array.isArray(value)) {
    const result = value.map((entry) => canonical(entry, parentKey))
    if (result.every((entry) => typeof entry === 'string') || unorderedArrays.has(parentKey)) {
      return result.sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right), 'en'))
    }
    return result
  }
  if (!value || typeof value !== 'object') return value
  const result = {}
  for (const key of Object.keys(value).sort((left, right) => left.localeCompare(right, 'en'))) {
    result[key] = canonical(value[key], key)
  }
  return result
}

const options = parseArgs(process.argv.slice(2))
let lock
let raw
let manifest
let closure
try {
  lock = readStrictJson(options.lock, 'package-lock.json')
  raw = readStrictJson(options.raw_sbom, 'raw Node SBOM')
  manifest = readStrictJson(options.license_manifest, 'Node license manifest')
  closure = deriveNodeProductionClosure(lock, { os: 'linux', cpu: 'x64' })
} catch (error) {
  fail(error.message)
}
if (raw.bomFormat !== 'CycloneDX' || raw.specVersion !== '1.6' || !Number.isInteger(raw.version)) {
  fail('raw input is not a CycloneDX 1.6 JSON BOM')
}
if (raw.metadata?.component?.name !== closure.root.name || raw.metadata.component.version !== closure.root.version) {
  fail('raw SBOM root identity differs from package lock')
}
if (!Array.isArray(raw.components) || raw.components.length === 0) fail('raw SBOM component closure is empty')
if (
  manifest.schemaVersion !== 2 ||
  !Array.isArray(manifest.packages) ||
  manifest.packages.length === 0 ||
  JSON.stringify(manifest.target) !== JSON.stringify(closure.target) ||
  JSON.stringify(manifest.root) !== JSON.stringify(closure.root)
) {
  fail('license manifest is not the canonical Linux/x64 production closure')
}

const manifestByIdentity = new Map()
for (const component of manifest.packages) {
  const identity = `${component.name}@${component.version}`
  if (manifestByIdentity.has(identity)) fail(`duplicate license manifest identity: ${identity}`)
  if (!Array.isArray(component.licenses) || component.licenses.length === 0) {
    fail(`license manifest identity has no licenses: ${identity}`)
  }
  manifestByIdentity.set(identity, component)
}
const expectedIdentities = closure.identities.map(({ identity }) => identity)
const actualIdentities = [...manifestByIdentity.keys()].sort((left, right) => left.localeCompare(right, 'en'))
if (JSON.stringify(actualIdentities) !== JSON.stringify(expectedIdentities)) {
  fail('license manifest identity set differs from canonical package-lock closure')
}
for (const expected of closure.identities) {
  const actual = manifestByIdentity.get(expected.identity)
  for (const field of ['name', 'version', 'integrity', 'lockPaths', 'dependsOn']) {
    if (JSON.stringify(actual[field]) !== JSON.stringify(expected[field])) {
      fail(`${expected.identity} license manifest ${field} differs from package lock`)
    }
  }
}

const rawIdentities = new Set()
for (const component of raw.components) {
  const identity = identityFromPurl(component.purl)
  if (!identity) fail('raw SBOM contains a component without a valid npm PURL')
  rawIdentities.add(identity)
}
for (const identity of closure.root.dependencies) {
  if (!rawIdentities.has(identity)) fail(`raw SBOM omitted direct production dependency: ${identity}`)
}

const components = closure.identities.map((expected) => {
  const legal = manifestByIdentity.get(expected.identity)
  return {
    type: 'library',
    name: expected.name,
    version: expected.version,
    hashes: [integrityHash(expected.integrity)],
    licenses: legal.licenses.map((name) => ({ license: { name } })),
    purl: npmPurl(expected.name, expected.version),
    'bom-ref': npmPurl(expected.name, expected.version),
    properties: expected.lockPaths.map((path, index) => ({
      name: `xyz.yychainsaw:package-lock:path:${String(index).padStart(4, '0')}`,
      value: path,
    })),
  }
})
const reference = new Map(closure.identities.map((entry) => [entry.identity, npmPurl(entry.name, entry.version)]))
const rootRef = npmPurl(closure.root.name, closure.root.version)
const dependencies = [
  { ref: rootRef, dependsOn: closure.root.dependencies.map((identity) => reference.get(identity)) },
  ...closure.identities.map((entry) => ({
    ref: reference.get(entry.identity),
    dependsOn: entry.dependsOn.map((identity) => reference.get(identity)),
  })),
]
if (dependencies.some((entry) => entry.dependsOn.includes(undefined))) fail('dependency graph references an unknown identity')

const bom = {
  bomFormat: 'CycloneDX',
  specVersion: '1.6',
  version: 1,
  metadata: {
    ...(raw.metadata.tools ? { tools: raw.metadata.tools } : {}),
    component: {
      type: 'application',
      name: closure.root.name,
      version: closure.root.version,
      purl: rootRef,
      'bom-ref': rootRef,
    },
  },
  components,
  dependencies,
}
const outputIdentities = bom.components
  .map((component) => identityFromPurl(component.purl))
  .sort((left, right) => left.localeCompare(right, 'en'))
if (JSON.stringify(outputIdentities) !== JSON.stringify(actualIdentities)) {
  fail('reconciled SBOM and legal manifest identity sets differ')
}
writeFileSync(options.output, `${JSON.stringify(canonical(bom), null, 2)}\n`, { mode: 0o600 })
console.error(`reconcile-node-sbom: ${components.length} exact production identities`)
