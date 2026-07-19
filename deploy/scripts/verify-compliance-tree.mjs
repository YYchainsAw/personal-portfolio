#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstatSync, readFileSync, readdirSync, realpathSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { TextDecoder } from 'node:util'
import { readStrictJson } from './node-production-closure.mjs'

function fail(message) {
  console.error(`verify-compliance-tree: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  if (argv.length !== 2 || argv[0] !== '--tree' || !argv[1]) {
    fail('usage: verify-compliance-tree.mjs --tree DIR')
  }
  return resolve(argv[1])
}

function byteCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
}

function unsafePath(path) {
  return (
    !path ||
    path.startsWith('/') ||
    path.includes('\\') ||
    /[\u0000-\u001f\u007f]/.test(path) ||
    path.split('/').some((part) => !part || part === '.' || part === '..') ||
    path.split('/').some((part) => part === '.tmp' || part.endsWith('.tmp') || part.includes('.tmp.'))
  )
}

function walk(directory, root, files) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    byteCompare(left.name, right.name),
  )) {
    const path = join(directory, entry.name)
    const relativePath = relative(root, path).split(sep).join('/')
    if (unsafePath(relativePath)) fail(`unsafe path in compliance tree: ${JSON.stringify(relativePath)}`)
    if (entry.isSymbolicLink()) fail(`symlink in compliance tree: ${relativePath}`)
    if (entry.isDirectory()) walk(path, root, files)
    else if (entry.isFile()) files.push(relativePath)
    else fail(`unsupported filesystem entry in compliance tree: ${relativePath}`)
  }
}

async function sha256(path) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}

const requestedTree = parseArgs(process.argv.slice(2))
let requestedInfo
try {
  requestedInfo = lstatSync(requestedTree)
} catch (error) {
  fail(`cannot inspect tree: ${error.message}`)
}
if (!requestedInfo.isDirectory() || requestedInfo.isSymbolicLink()) {
  fail('tree must be a regular non-symlink directory')
}
const tree = realpathSync(requestedTree)
const sumsPath = join(tree, 'SHA256SUMS')
let sumsInfo
try {
  sumsInfo = lstatSync(sumsPath)
} catch (error) {
  fail(`SHA256SUMS is missing: ${error.message}`)
}
if (!sumsInfo.isFile() || sumsInfo.isSymbolicLink()) fail('SHA256SUMS must be a regular non-symlink file')

let sums
try {
  sums = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(sumsPath))
} catch (error) {
  fail(`SHA256SUMS is not valid UTF-8: ${error.message}`)
}
if (!sums.endsWith('\n') || sums.includes('\r')) fail('SHA256SUMS must use a final LF and no CR bytes')

const expected = new Map()
for (const line of sums.slice(0, -1).split('\n')) {
  const match = /^([0-9a-f]{64})  (.+)$/.exec(line)
  if (!match) fail(`invalid SHA256SUMS line: ${JSON.stringify(line)}`)
  const [, digest, path] = match
  if (unsafePath(path) || path === 'SHA256SUMS') fail(`unsafe checksum path: ${JSON.stringify(path)}`)
  if (expected.has(path)) fail(`duplicate checksum path: ${path}`)
  expected.set(path, digest)
}
if (expected.size === 0) fail('SHA256SUMS has no payload entries')

const listed = [...expected.keys()]
const sortedListed = [...listed].sort(byteCompare)
if (JSON.stringify(listed) !== JSON.stringify(sortedListed)) fail('SHA256SUMS paths are not byte-sorted')

const discovered = []
walk(tree, tree, discovered)
const payload = discovered.filter((path) => path !== 'SHA256SUMS').sort(byteCompare)
if (JSON.stringify(payload) !== JSON.stringify(sortedListed)) {
  const missing = payload.filter((path) => !expected.has(path))
  const stale = listed.filter((path) => !payload.includes(path))
  fail(`checksum inventory is not closed (unlisted=${missing.join(',') || 'none'} stale=${stale.join(',') || 'none'})`)
}

const boundary = `${tree}${sep}`
for (const [path, digest] of expected) {
  const candidate = resolve(tree, ...path.split('/'))
  if (!candidate.startsWith(boundary) || dirname(candidate) === candidate) fail(`checksum path escapes tree: ${path}`)
  const info = lstatSync(candidate)
  if (!info.isFile() || info.isSymbolicLink() || realpathSync(candidate) !== candidate) {
    fail(`checksum path is not a regular in-tree file: ${path}`)
  }
  const actual = await sha256(candidate)
  if (actual !== digest) fail(`checksum mismatch: ${path}`)
}

function npmIdentity(purl) {
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

for (const component of ['frontend', 'admin-web']) {
  const sbomRelative = `sbom/${component}.cdx.json`
  const manifestRelative = `licenses/${component}/manifest.json`
  const hasSbom = expected.has(sbomRelative)
  const hasManifest = expected.has(manifestRelative)
  if (hasSbom !== hasManifest) fail(`${component} SBOM/license pair is incomplete`)
  if (!hasSbom) continue
  let sbom
  let manifest
  try {
    sbom = readStrictJson(join(tree, ...sbomRelative.split('/')), `${component} SBOM`)
    manifest = readStrictJson(join(tree, ...manifestRelative.split('/')), `${component} license manifest`)
  } catch (error) {
    fail(`${component} SBOM/license pair is invalid JSON: ${error.message}`)
  }
  if (sbom.bomFormat !== 'CycloneDX' || sbom.specVersion !== '1.6' || manifest.schemaVersion !== 2) {
    fail(`${component} SBOM/license pair has an unsupported schema`)
  }
  const sbomIdentities = (sbom.components ?? []).map((entry) => npmIdentity(entry.purl))
  const licenseIdentities = (manifest.packages ?? []).map((entry) => `${entry.name}@${entry.version}`)
  if (sbomIdentities.some((identity) => !identity)) fail(`${component} SBOM has a non-npm component`)
  if (new Set(sbomIdentities).size !== sbomIdentities.length) fail(`${component} SBOM has duplicate identities`)
  if (new Set(licenseIdentities).size !== licenseIdentities.length) {
    fail(`${component} license manifest has duplicate identities`)
  }
  sbomIdentities.sort(byteCompare)
  licenseIdentities.sort(byteCompare)
  if (JSON.stringify(sbomIdentities) !== JSON.stringify(licenseIdentities)) {
    fail(`${component} SBOM and license identity sets differ`)
  }
}

const pythonBase = 'licenses/cos-prune-runtime'
const pythonPaths = {
  manifest: `${pythonBase}/manifest.json`,
  notice: `${pythonBase}/THIRD_PARTY_NOTICES.txt`,
  requirements: `${pythonBase}/requirements-cos-prune.txt`,
  sbom: `${pythonBase}/sbom.cdx.json`,
}
const pythonPresence = Object.values(pythonPaths).filter((path) => expected.has(path)).length
if (pythonPresence !== 0 && pythonPresence !== Object.keys(pythonPaths).length) {
  fail('COS prune Python compliance closure is incomplete')
}
if (pythonPresence > 0) {
  let manifest
  let sbom
  try {
    manifest = readStrictJson(join(tree, ...pythonPaths.manifest.split('/')), 'COS prune Python license manifest')
    sbom = readStrictJson(join(tree, ...pythonPaths.sbom.split('/')), 'COS prune Python SBOM')
  } catch (error) {
    fail(`COS prune Python compliance closure is invalid JSON: ${error.message}`)
  }
  if (
    manifest.schemaVersion !== 1 ||
    manifest.component !== 'cos-prune-runtime' ||
    manifest.target?.os !== 'ubuntu' ||
    manifest.target?.osVersion !== '22.04' ||
    manifest.target?.architecture !== 'x86_64' ||
    manifest.target?.python !== '3.10' ||
    manifest.requirements?.path !== 'requirements-cos-prune.txt' ||
    !/^[0-9a-f]{64}$/.test(manifest.requirements?.sha256 ?? '') ||
    !Array.isArray(manifest.packages) ||
    manifest.packages.length !== 12
  ) {
    fail('COS prune Python license manifest schema/target/count changed')
  }
  if ((await sha256(join(tree, ...pythonPaths.requirements.split('/')))) !== manifest.requirements.sha256) {
    fail('COS prune Python requirements digest differs from its license manifest')
  }
  if (sbom.bomFormat !== 'CycloneDX' || sbom.specVersion !== '1.6' || !Array.isArray(sbom.components)) {
    fail('COS prune Python SBOM schema changed')
  }
  const packageByIdentity = new Map()
  for (const entry of manifest.packages) {
    const identity = `${entry?.name}==${entry?.version}`
    if (
      typeof entry?.name !== 'string' ||
      !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(entry.name) ||
      typeof entry.version !== 'string' ||
      !entry.version ||
      entry.identity !== identity ||
      !/^[0-9a-f]{64}$/.test(entry.artifactSha256 ?? '') ||
      !/^[0-9a-f]{64}$/.test(entry.metadataSha256 ?? '') ||
      !Array.isArray(entry.licenseMetadata) ||
      entry.licenseMetadata.length === 0 ||
      !Array.isArray(entry.legalFiles) ||
      entry.legalFiles.length === 0
    ) {
      fail(`COS prune Python package record is invalid: ${identity}`)
    }
    if (packageByIdentity.has(identity)) fail(`duplicate COS prune Python package identity: ${identity}`)
    for (const legal of entry.legalFiles) {
      if (
        typeof legal?.output !== 'string' ||
        unsafePath(legal.output) ||
        !legal.output.startsWith('licenses/') ||
        !Number.isSafeInteger(legal.bytes) ||
        legal.bytes <= 0 ||
        !/^[0-9a-f]{64}$/.test(legal.sha256 ?? '')
      ) {
        fail(`COS prune Python package has an invalid legal-file record: ${identity}`)
      }
      const treePath = `${pythonBase}/${legal.output}`
      if (!expected.has(treePath) || expected.get(treePath) !== legal.sha256) {
        fail(`COS prune Python legal file is missing or has a mismatched digest: ${treePath}`)
      }
    }
    packageByIdentity.set(identity, entry)
  }
  const sbomIdentities = new Set()
  for (const component of sbom.components) {
    const match = /^pkg:pypi\/([^@/?#]+)@([^?#]+)$/.exec(component?.purl ?? '')
    if (!match) fail('COS prune Python SBOM contains a non-PyPI component')
    let identity
    try {
      identity = `${decodeURIComponent(match[1])}==${decodeURIComponent(match[2])}`
    } catch {
      fail(`COS prune Python SBOM contains an invalid PURL: ${component.purl}`)
    }
    if (sbomIdentities.has(identity)) fail(`COS prune Python SBOM has a duplicate identity: ${identity}`)
    const record = packageByIdentity.get(identity)
    const artifactHash = (component.hashes ?? []).find((hash) => hash?.alg === 'SHA-256')?.content
    if (!record || artifactHash !== record.artifactSha256) {
      fail(`COS prune Python SBOM/license binding differs: ${identity}`)
    }
    sbomIdentities.add(identity)
  }
  if (sbomIdentities.size !== packageByIdentity.size) fail('COS prune Python SBOM/license identity sets differ')
}

for (const definition of [
  { name: 'api-image', kind: 'api' },
  { name: 'postgres-image', kind: 'postgres' },
]) {
  const base = `licenses/${definition.name}`
  const paths = {
    manifest: `${base}/manifest.json`,
    notice: `${base}/THIRD_PARTY_NOTICES.txt`,
    sbom: `sbom/${definition.name}.cdx.json`,
    metadata: `oci/${definition.name}-metadata.json`,
  }
  const presence = Object.values(paths).filter((path) => expected.has(path)).length
  if (presence !== 0 && presence !== Object.keys(paths).length) {
    fail(`${definition.name} OCI compliance closure is incomplete`)
  }
  if (presence === 0) continue

  let manifest
  let metadata
  let sbom
  try {
    manifest = readStrictJson(join(tree, ...paths.manifest.split('/')), `${definition.name} license manifest`)
    metadata = readStrictJson(join(tree, ...paths.metadata.split('/')), `${definition.name} image metadata`)
    sbom = readStrictJson(join(tree, ...paths.sbom.split('/')), `${definition.name} SBOM`)
  } catch (error) {
    fail(`${definition.name} OCI compliance closure is invalid JSON: ${error.message}`)
  }
  const configDigest = manifest.image?.configDigest
  if (
    manifest.schemaVersion !== 1 ||
    manifest.kind !== definition.kind ||
    !/^sha256:[0-9a-f]{64}$/.test(configDigest ?? '') ||
    !/^[0-9a-f]{64}$/.test(manifest.image?.archiveSha256 ?? '') ||
    !/^[0-9a-f]{64}$/.test(manifest.image?.sbomSha256 ?? '') ||
    !Array.isArray(manifest.image?.repoTags) ||
    manifest.image.repoTags.length !== 1 ||
    !Array.isArray(manifest.dpkgPackages) ||
    manifest.dpkgPackages.length < 100
  ) {
    fail(`${definition.name} OCI license manifest schema/identity changed`)
  }
  if (
    metadata.schemaVersion !== 1 ||
    metadata.archiveFormat !== 'docker-save' ||
    metadata.extractionMode !== 'license-evidence-only' ||
    metadata.configDigest !== configDigest ||
    metadata.archiveSha256 !== manifest.image.archiveSha256 ||
    metadata.platform?.os !== 'linux' ||
    metadata.platform?.architecture !== 'amd64' ||
    JSON.stringify(metadata.repoTags) !== JSON.stringify(manifest.image.repoTags) ||
    !Array.isArray(metadata.layers) ||
    metadata.layers.length === 0
  ) {
    fail(`${definition.name} OCI extraction metadata is not bound to the license manifest`)
  }
  const layerDiffIds = metadata.layers.map((entry) => entry?.diffId)
  if (
    layerDiffIds.some((value) => !/^sha256:[0-9a-f]{64}$/.test(value ?? '')) ||
    new Set(layerDiffIds).size !== layerDiffIds.length ||
    metadata.layers.some((entry, index) => entry?.index !== index || !Number.isSafeInteger(entry?.filesApplied))
  ) {
    fail(`${definition.name} OCI layer evidence is invalid`)
  }
  if (
    sbom.bomFormat !== 'CycloneDX' ||
    sbom.specVersion !== '1.6' ||
    sbom.serialNumber !== undefined ||
    sbom.metadata?.timestamp !== undefined ||
    (await sha256(join(tree, ...paths.sbom.split('/')))) !== manifest.image.sbomSha256
  ) {
    fail(`${definition.name} OCI SBOM is not reproducible or is not bound to its manifest`)
  }

  const dpkgIdentities = new Set()
  for (const entry of manifest.dpkgPackages) {
    if (
      typeof entry?.identity !== 'string' ||
      dpkgIdentities.has(entry.identity) ||
      typeof entry.legalFile !== 'string' ||
      unsafePath(entry.legalFile) ||
      !/^[0-9a-f]{64}$/.test(entry.legalSha256 ?? '') ||
      !Number.isSafeInteger(entry.legalBytes) ||
      entry.legalBytes <= 0
    ) {
      fail(`${definition.name} has an invalid or duplicate dpkg legal record`)
    }
    const legalPath = `${base}/${entry.legalFile}`
    if (!expected.has(legalPath) || expected.get(legalPath) !== entry.legalSha256) {
      fail(`${definition.name} dpkg legal file is missing or has a mismatched digest: ${legalPath}`)
    }
    dpkgIdentities.add(entry.identity)
  }
  for (const entry of manifest.reviewedPackages ?? []) {
    const legalPath = `${base}/${entry?.output ?? ''}`
    if (
      unsafePath(entry?.output ?? '') ||
      !/^[0-9a-f]{64}$/.test(entry?.licenseSha256 ?? '') ||
      !expected.has(legalPath) ||
      expected.get(legalPath) !== entry.licenseSha256
    ) {
      fail(`${definition.name} reviewed-package legal binding is invalid`)
    }
  }
  for (const entry of manifest.scripts ?? []) {
    for (const [outputKey, digestKey] of [
      ['output', 'sha256'],
      ['licenseOutput', 'licenseSha256'],
    ]) {
      const relativePath = entry?.[outputKey]
      const digest = entry?.[digestKey]
      const treePath = `${base}/${relativePath ?? ''}`
      if (
        unsafePath(relativePath ?? '') ||
        !/^[0-9a-f]{64}$/.test(digest ?? '') ||
        !expected.has(treePath) ||
        expected.get(treePath) !== digest
      ) {
        fail(`${definition.name} reviewed script binding is invalid`)
      }
    }
  }
  if (definition.kind === 'api') {
    if (
      manifest.backendBinding?.fatJar?.path !== '/app/portfolio-server.jar' ||
      !/^[0-9a-f]{64}$/.test(manifest.backendBinding?.fatJar?.sha256 ?? '') ||
      !Array.isArray(manifest.backendBinding?.runtimePackages) ||
      manifest.backendBinding.runtimePackages.length === 0 ||
      !Array.isArray(manifest.jreLegalFiles) ||
      manifest.jreLegalFiles.length === 0
    ) {
      fail('API OCI backend/JRE legal binding is incomplete')
    }
  }
}

console.error(`verify-compliance-tree: ${expected.size} payload files`)
