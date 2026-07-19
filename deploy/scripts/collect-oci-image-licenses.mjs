#!/usr/bin/env node

import { createHash } from 'node:crypto'
import {
  copyFileSync,
  cpSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, dirname, join, relative, resolve, sep } from 'node:path'
import { gunzipSync, inflateRawSync } from 'node:zlib'
import { TextDecoder } from 'node:util'
import { readStrictJson } from './node-production-closure.mjs'

function fail(message) {
  console.error(`collect-oci-image-licenses: ${message}`)
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
  for (const name of ['--archive', '--rootfs', '--sbom', '--image-metadata', '--kind', '--output', '--overrides']) {
    if (!values.has(name)) fail(`missing ${name}`)
  }
  const kind = values.get('--kind')
  if (!['api', 'postgres'].includes(kind)) fail('kind must be api or postgres')
  if (kind === 'api' && !values.has('--backend-licenses')) fail('api image requires --backend-licenses')
  return {
    archive: resolve(values.get('--archive')),
    rootfs: resolve(values.get('--rootfs')),
    sbom: resolve(values.get('--sbom')),
    imageMetadata: resolve(values.get('--image-metadata')),
    kind,
    output: resolve(values.get('--output')),
    overrides: resolve(values.get('--overrides')),
    backendLicenses: values.has('--backend-licenses') ? resolve(values.get('--backend-licenses')) : null,
  }
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function fileSha256(path) {
  return sha256(readFileSync(path))
}

function exactKeys(value, keys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object`)
  const actual = Object.keys(value).sort((left, right) => left.localeCompare(right, 'en'))
  const expected = [...keys].sort((left, right) => left.localeCompare(right, 'en'))
  if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} fields must be exactly: ${expected.join(', ')}`)
}

function safeName(value) {
  const result = value.replace(/^@/, '_at_').replaceAll('/', '__').replace(/[^A-Za-z0-9._@+-]/g, '_')
  if (!result || result === '.' || result === '..') fail(`unsafe output name: ${value}`)
  return result
}

function insideRoot(root, imagePath, allowSymlink = true) {
  if (typeof imagePath !== 'string' || !imagePath.startsWith('/') || imagePath.includes('\\')) {
    fail(`invalid image path: ${imagePath}`)
  }
  const parts = imagePath.slice(1).split('/')
  if (parts.some((part) => !part || part === '.' || part === '..')) fail(`unsafe image path: ${imagePath}`)
  const candidate = resolve(root, ...parts)
  const boundary = `${root}${sep}`
  if (!candidate.startsWith(boundary)) fail(`image path escapes evidence root: ${imagePath}`)
  let actual
  try {
    actual = realpathSync(candidate)
  } catch (error) {
    fail(`image evidence path is missing: ${imagePath}: ${error.message}`)
  }
  if (!actual.startsWith(boundary)) fail(`image evidence symlink escapes root: ${imagePath}`)
  const info = lstatSync(candidate)
  if (!allowSymlink && info.isSymbolicLink()) fail(`image evidence path must not be a symlink: ${imagePath}`)
  const actualInfo = statSync(actual)
  if (!actualInfo.isFile()) fail(`image evidence is not a regular file: ${imagePath}`)
  return { candidate, actual, info: actualInfo }
}

function normalizedLegalBytes(path, label) {
  const compressed = readFileSync(path)
  let bytes
  try {
    bytes = path.endsWith('.gz') ? gunzipSync(compressed) : compressed
  } catch (error) {
    fail(`${label} cannot be decompressed: ${error.message}`)
  }
  if (bytes.length <= 0 || bytes.length > 4 * 1024 * 1024) fail(`${label} has unsafe size`)
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    fail(`${label} is not UTF-8 text`)
  }
  return bytes
}

function legalTextBytes(bytes, label) {
  if (!Buffer.isBuffer(bytes) || bytes.length <= 0 || bytes.length > 4 * 1024 * 1024) fail(`${label} has unsafe size`)
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    fail(`${label} is not UTF-8 text`)
  }
  return bytes
}

function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

function parseZip(bytes, label) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 22) fail(`${label} is not a ZIP archive`)
  const earliest = Math.max(0, bytes.length - 65_557)
  let eocd = -1
  for (let offset = bytes.length - 22; offset >= earliest; offset -= 1) {
    if (bytes.readUInt32LE(offset) === 0x06054b50 && offset + 22 + bytes.readUInt16LE(offset + 20) === bytes.length) {
      eocd = offset
      break
    }
  }
  if (eocd < 0) fail(`${label} has no valid ZIP end record`)
  const disk = bytes.readUInt16LE(eocd + 4)
  const centralDisk = bytes.readUInt16LE(eocd + 6)
  const diskEntries = bytes.readUInt16LE(eocd + 8)
  const totalEntries = bytes.readUInt16LE(eocd + 10)
  const centralBytes = bytes.readUInt32LE(eocd + 12)
  const centralOffset = bytes.readUInt32LE(eocd + 16)
  if (
    disk !== 0 ||
    centralDisk !== 0 ||
    diskEntries !== totalEntries ||
    totalEntries === 0xffff ||
    centralBytes === 0xffffffff ||
    centralOffset === 0xffffffff
  ) {
    fail(`${label} uses unsupported split or ZIP64 metadata`)
  }
  if (centralOffset + centralBytes !== eocd) fail(`${label} central directory is not contiguous`)
  const decoder = new TextDecoder('utf-8', { fatal: true })
  const entries = new Map()
  let cursor = centralOffset
  for (let index = 0; index < totalEntries; index += 1) {
    if (cursor + 46 > eocd || bytes.readUInt32LE(cursor) !== 0x02014b50) fail(`${label} has an invalid central entry`)
    const flags = bytes.readUInt16LE(cursor + 8)
    const method = bytes.readUInt16LE(cursor + 10)
    const expectedCrc32 = bytes.readUInt32LE(cursor + 16)
    const compressedSize = bytes.readUInt32LE(cursor + 20)
    const uncompressedSize = bytes.readUInt32LE(cursor + 24)
    const nameLength = bytes.readUInt16LE(cursor + 28)
    const extraLength = bytes.readUInt16LE(cursor + 30)
    const commentLength = bytes.readUInt16LE(cursor + 32)
    const diskStart = bytes.readUInt16LE(cursor + 34)
    const localOffset = bytes.readUInt32LE(cursor + 42)
    const entryEnd = cursor + 46 + nameLength + extraLength + commentLength
    if (
      entryEnd > eocd ||
      diskStart !== 0 ||
      compressedSize === 0xffffffff ||
      uncompressedSize === 0xffffffff ||
      localOffset === 0xffffffff ||
      flags & 1
    ) {
      fail(`${label} has an unsupported ZIP entry`)
    }
    let name
    try {
      name = decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength))
    } catch {
      fail(`${label} has a non-UTF-8 ZIP path`)
    }
    if (
      !name ||
      name.startsWith('/') ||
      name.includes('\\') ||
      name.includes('\0') ||
      name.split('/').some((part) => part === '.' || part === '..') ||
      entries.has(name)
    ) {
      fail(`${label} has an unsafe or duplicate ZIP path: ${name}`)
    }
    entries.set(name, { name, flags, method, expectedCrc32, compressedSize, uncompressedSize, localOffset })
    cursor = entryEnd
  }
  if (cursor !== eocd) fail(`${label} central entry count is inconsistent`)
  return entries
}

function readZipEntry(bytes, entry, label) {
  const offset = entry.localOffset
  if (offset + 30 > bytes.length || bytes.readUInt32LE(offset) !== 0x04034b50) fail(`${label} has an invalid local entry`)
  const flags = bytes.readUInt16LE(offset + 6)
  const method = bytes.readUInt16LE(offset + 8)
  const nameLength = bytes.readUInt16LE(offset + 26)
  const extraLength = bytes.readUInt16LE(offset + 28)
  let localName
  try {
    localName = new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(offset + 30, offset + 30 + nameLength))
  } catch {
    fail(`${label} local entry path is not UTF-8`)
  }
  if (localName !== entry.name || flags !== entry.flags || method !== entry.method) fail(`${label} local/central entry metadata differs`)
  const start = offset + 30 + nameLength + extraLength
  const end = start + entry.compressedSize
  if (end > bytes.length) fail(`${label} entry data escapes the archive`)
  const compressed = bytes.subarray(start, end)
  let output
  try {
    if (method === 0) output = Buffer.from(compressed)
    else if (method === 8) output = inflateRawSync(compressed)
    else fail(`${label} uses unsupported ZIP compression method ${method}`)
  } catch (error) {
    fail(`${label} cannot be decompressed: ${error.message}`)
  }
  if (output.length !== entry.uncompressedSize || crc32(output) !== entry.expectedCrc32) fail(`${label} entry integrity check failed`)
  return output
}

function componentProperty(component, name) {
  const values = (component.properties ?? []).filter((property) => property?.name === name).map((property) => property.value)
  if (values.length !== 1 || typeof values[0] !== 'string' || !values[0]) fail(`SBOM component has invalid ${name}: ${component.purl}`)
  return values[0]
}

function parseDpkgStatus(path) {
  let text
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(path))
  } catch (error) {
    fail(`cannot read dpkg status: ${error.message}`)
  }
  const packages = new Map()
  for (const paragraph of text.replaceAll('\r\n', '\n').split(/\n\n+/)) {
    if (!paragraph.trim()) continue
    const fields = new Map()
    let current = null
    for (const line of paragraph.split('\n')) {
      if (/^[ \t]/.test(line)) {
        if (!current) fail('dpkg status has an orphan continuation line')
        fields.set(current, `${fields.get(current)}\n${line.slice(1)}`)
        continue
      }
      const separator = line.indexOf(':')
      if (separator <= 0) fail(`invalid dpkg status line: ${line}`)
      const key = line.slice(0, separator)
      if (fields.has(key)) fail(`duplicate dpkg status field: ${key}`)
      fields.set(key, line.slice(separator + 1).trimStart())
      current = key
    }
    if (fields.get('Status') !== 'install ok installed') continue
    const name = fields.get('Package')
    const version = fields.get('Version')
    const architecture = fields.get('Architecture')
    if (!name || !version || !architecture) fail('installed dpkg record has an incomplete identity')
    const identity = `${name}@${version}?arch=${architecture}`
    if (packages.has(identity)) fail(`duplicate installed dpkg identity: ${identity}`)
    packages.set(identity, { name, version, architecture })
  }
  if (packages.size === 0) fail('dpkg installed package closure is empty')
  return packages
}

function purlParts(purl) {
  if (typeof purl !== 'string' || !purl.startsWith('pkg:')) return null
  try {
    const withoutFragment = purl.split('#', 1)[0]
    const [path, query = ''] = withoutFragment.split('?', 2)
    const typeEnd = path.indexOf('/')
    const type = path.slice(4, typeEnd)
    const rest = path.slice(typeEnd + 1)
    const versionAt = rest.lastIndexOf('@')
    if (typeEnd < 5 || versionAt <= 0) return null
    const namePath = rest.slice(0, versionAt).split('/').map(decodeURIComponent)
    return {
      type,
      namespace: namePath.length > 1 ? namePath.slice(0, -1).join('/') : '',
      name: namePath.at(-1),
      version: decodeURIComponent(rest.slice(versionAt + 1)),
      qualifiers: Object.fromEntries(new URLSearchParams(query)),
      canonical: purl.split(/[?#]/, 1)[0],
    }
  } catch {
    return null
  }
}

function licenses(component) {
  if (!Array.isArray(component.licenses) || component.licenses.length === 0) fail(`SBOM component has no license: ${component.purl}`)
  const values = component.licenses.map((entry) => entry?.expression ?? entry?.license?.id ?? entry?.license?.name)
  if (values.some((value) => typeof value !== 'string' || !value || /^(UNKNOWN|UNLICENSED)$/i.test(value))) {
    fail(`SBOM component has invalid license metadata: ${component.purl}`)
  }
  return [...new Set(values)].sort((left, right) => left.localeCompare(right, 'en'))
}

function findDpkgCopyright(root, pkg) {
  const candidate = `/usr/share/doc/${pkg.name}/copyright`
  try {
    realpathSync(resolve(root, ...candidate.slice(1).split('/')))
    return { imagePath: candidate, ...insideRoot(root, candidate) }
  } catch {
    // Some Debian binary packages share a source package's documentation directory.
  }
  const infoRoot = join(root, 'var', 'lib', 'dpkg', 'info')
  const listNames = [`${pkg.name}.list`, `${pkg.name}:${pkg.architecture}.list`]
  for (const listName of listNames) {
    const listPath = join(infoRoot, listName)
    if (!lstatSync(infoRoot).isDirectory()) fail('dpkg info evidence is missing')
    try {
      const lines = readFileSync(listPath, 'utf8').split(/\r?\n/)
      for (const imagePath of lines.filter((line) => /^\/usr\/share\/doc\/[^/]+\/copyright(?:\.gz)?$/.test(line)).sort()) {
        try {
          realpathSync(resolve(root, ...imagePath.slice(1).split('/')))
          return { imagePath, ...insideRoot(root, imagePath) }
        } catch {
          // Continue through deterministic alternatives.
        }
      }
    } catch (error) {
      if (error.code !== 'ENOENT') fail(`cannot read dpkg list ${listName}: ${error.message}`)
    }
  }
  fail(`installed dpkg package has no copyright text: ${pkg.name}@${pkg.version}`)
}

function walkLegal(directory, root, records) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) => left.name.localeCompare(right.name, 'en'))) {
    const path = join(directory, entry.name)
    if (entry.isSymbolicLink()) {
      let actual
      try {
        actual = realpathSync(path)
      } catch (error) {
        fail(`JRE legal symlink cannot be resolved: ${relative(root, path)}: ${error.message}`)
      }
      const boundary = `${root}${sep}`
      if (!actual.startsWith(boundary) || !statSync(actual).isFile()) {
        fail(`JRE legal symlink does not resolve to a regular file inside the JRE: ${relative(root, path)}`)
      }
      records.push({ path, actual, sourceType: 'symlink' })
    } else if (entry.isDirectory()) walkLegal(path, root, records)
    else if (entry.isFile()) records.push({ path, actual: path, sourceType: 'file' })
    else fail(`JRE legal tree contains a special file: ${relative(root, path)}`)
  }
}

const options = parseArgs(process.argv.slice(2))
let root
try {
  const rootInfo = lstatSync(options.rootfs)
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) fail('rootfs evidence must be a non-symlink directory')
  root = realpathSync(options.rootfs)
} catch (error) {
  fail(`cannot inspect rootfs evidence: ${error.message}`)
}
let sbom
let image
let overrides
try {
  sbom = readStrictJson(options.sbom, 'image SBOM')
  image = readStrictJson(options.imageMetadata, 'image metadata')
  overrides = readStrictJson(options.overrides, 'OCI legal overrides')
} catch (error) {
  fail(error.message)
}
if (sbom.bomFormat !== 'CycloneDX' || sbom.specVersion !== '1.6' || !Array.isArray(sbom.components)) {
  fail('image SBOM must be CycloneDX 1.6 with components')
}
exactKeys(image, ['archiveFormat', 'archiveSha256', 'configDigest', 'extractionMode', 'layers', 'platform', 'repoTags', 'schemaVersion'], 'image metadata')
if (
  image.schemaVersion !== 1 ||
  image.archiveFormat !== 'docker-save' ||
  image.extractionMode !== 'license-evidence-only' ||
  image.platform?.os !== 'linux' ||
  image.platform?.architecture !== 'amd64' ||
  !/^sha256:[0-9a-f]{64}$/.test(image.configDigest) ||
  !/^[0-9a-f]{64}$/.test(image.archiveSha256) ||
  fileSha256(options.archive) !== image.archiveSha256
) {
  fail('image metadata does not bind the supplied linux/amd64 docker archive')
}
exactKeys(overrides, ['postgres', 'schemaVersion'], 'OCI override manifest')
if (overrides.schemaVersion !== 1) fail('OCI override manifest schema changed')

mkdirSync(options.output, { recursive: true, mode: 0o700 })
if (readdirSync(options.output).length !== 0) fail('output directory is not empty')
const licenseRoot = join(options.output, 'licenses')
mkdirSync(licenseRoot, { mode: 0o700 })

const statusEvidence = insideRoot(root, '/var/lib/dpkg/status', false)
const dpkgPackages = parseDpkgStatus(statusEvidence.actual)
const syftDpkg = new Map()
const ecosystemComponents = new Map()
let fileCatalogEntries = 0
let operatingSystemEntries = 0
for (const component of sbom.components) {
  if (!component.purl) {
    if (component.type === 'file') {
      fileCatalogEntries += 1
      continue
    }
    if (component.type === 'operating-system') {
      operatingSystemEntries += 1
      continue
    }
    fail(`unclassified SBOM component without PURL: ${component.name ?? '<unknown>'}`)
  }
  const parsed = purlParts(component.purl)
  if (!parsed) fail(`invalid package URL in image SBOM: ${component.purl}`)
  if (parsed.type === 'deb') {
    if (!parsed.qualifiers.arch) fail(`deb PURL has no architecture: ${component.purl}`)
    const identity = `${parsed.name}@${parsed.version}?arch=${parsed.qualifiers.arch}`
    if (syftDpkg.has(identity)) fail(`duplicate deb identity in image SBOM: ${identity}`)
    syftDpkg.set(identity, { component, parsed, licenseExpressions: licenses(component) })
  } else {
    if (!ecosystemComponents.has(parsed.type)) ecosystemComponents.set(parsed.type, new Map())
    const identities = ecosystemComponents.get(parsed.type)
    if (identities.has(parsed.canonical)) fail(`duplicate ${parsed.type} identity in image SBOM: ${parsed.canonical}`)
    identities.set(parsed.canonical, { component, parsed })
  }
}
const installedIdentities = [...dpkgPackages.keys()].sort((left, right) => left.localeCompare(right, 'en'))
const syftIdentities = [...syftDpkg.keys()].sort((left, right) => left.localeCompare(right, 'en'))
if (JSON.stringify(installedIdentities) !== JSON.stringify(syftIdentities)) {
  const statusOnly = installedIdentities.filter((identity) => !syftDpkg.has(identity))
  const syftOnly = syftIdentities.filter((identity) => !dpkgPackages.has(identity))
  fail(`dpkg/Syft closure differs (statusOnly=${statusOnly.join(',') || 'none'} syftOnly=${syftOnly.join(',') || 'none'})`)
}

const dpkgRecords = []
for (const identity of installedIdentities) {
  const pkg = dpkgPackages.get(identity)
  const evidence = findDpkgCopyright(root, pkg)
  const bytes = normalizedLegalBytes(evidence.actual, `${identity} copyright`)
  const destinationDirectory = join(licenseRoot, 'deb', safeName(identity))
  mkdirSync(destinationDirectory, { recursive: true, mode: 0o700 })
  const destination = join(destinationDirectory, 'copyright')
  writeFileSync(destination, bytes, { mode: 0o600 })
  dpkgRecords.push({
    ...pkg,
    identity,
    purl: syftDpkg.get(identity).component.purl,
    licenses: syftDpkg.get(identity).licenseExpressions,
    sourcePath: evidence.imagePath,
    resolvedPath: `/${relative(root, evidence.actual).split(sep).join('/')}`,
    legalFile: `licenses/deb/${safeName(identity)}/copyright`,
    legalBytes: bytes.length,
    legalSha256: sha256(bytes),
  })
}

const jreRecords = []
if (options.kind === 'api') {
  const javaRoot = join(root, 'opt', 'java', 'openjdk')
  const legalDirectory = join(javaRoot, 'legal')
  let legalInfo
  try {
    legalInfo = lstatSync(legalDirectory)
  } catch (error) {
    fail(`API image has no JRE legal directory: ${error.message}`)
  }
  if (!legalInfo.isDirectory() || legalInfo.isSymbolicLink()) fail('JRE legal path is not a regular directory')
  const paths = []
  walkLegal(legalDirectory, javaRoot, paths)
  for (const name of readdirSync(javaRoot).filter((entry) => /^LICENSE$|^NOTICE/i.test(entry)).sort()) {
    const path = join(javaRoot, name)
    if (lstatSync(path).isFile()) paths.push({ path, actual: path, sourceType: 'file' })
  }
  if (!paths.some((record) => basename(record.path) === 'LICENSE') || !paths.some((record) => /^NOTICE/i.test(basename(record.path)))) {
    fail('JRE legal closure must contain LICENSE and NOTICE material')
  }
  for (const record of paths.sort((left, right) => relative(javaRoot, left.path).localeCompare(relative(javaRoot, right.path), 'en'))) {
    const relativePath = relative(javaRoot, record.path).split(sep).join('/')
    const resolvedPath = relative(javaRoot, record.actual).split(sep).join('/')
    const bytes = normalizedLegalBytes(record.actual, `JRE ${relativePath}`)
    const destination = join(licenseRoot, 'jre', ...relativePath.split('/'))
    mkdirSync(dirname(destination), { recursive: true, mode: 0o700 })
    writeFileSync(destination, bytes, { mode: 0o600 })
    jreRecords.push({
      path: `/opt/java/openjdk/${relativePath}`,
      resolvedPath: `/opt/java/openjdk/${resolvedPath}`,
      sourceType: record.sourceType,
      bytes: bytes.length,
      sha256: sha256(bytes),
    })
  }
}

const reviewedRecords = []
const scriptRecords = []
const jreComponentRecords = []
let backendBinding = null
if (options.kind === 'postgres') {
  const postgresOverrides = overrides.postgres
  exactKeys(postgresOverrides, ['packages', 'scripts'], 'postgres OCI overrides')
  if (!Array.isArray(postgresOverrides.scripts) || !postgresOverrides.packages || typeof postgresOverrides.packages !== 'object') {
    fail('postgres OCI overrides are invalid')
  }
  const goComponents = ecosystemComponents.get('golang') ?? new Map()
  const overrideIdentities = Object.keys(postgresOverrides.packages).sort((left, right) => left.localeCompare(right, 'en'))
  const goIdentities = [...goComponents.keys()].sort((left, right) => left.localeCompare(right, 'en'))
  if (JSON.stringify(overrideIdentities) !== JSON.stringify(goIdentities)) {
    fail('reviewed Go overrides and Syft Go identities differ')
  }
  for (const identity of overrideIdentities) {
    const override = postgresOverrides.packages[identity]
    exactKeys(
      override,
      ['artifactPath', 'artifactSha256', 'license', 'licenseFile', 'licenseSha256', 'source'],
      `${identity} OCI override`,
    )
    const artifact = insideRoot(root, override.artifactPath, false)
    if (fileSha256(artifact.actual) !== override.artifactSha256) fail(`${identity} artifact digest mismatch`)
    const licensePath = resolve(dirname(options.overrides), ...override.licenseFile.split('/'))
    if (fileSha256(licensePath) !== override.licenseSha256) fail(`${identity} reviewed license digest mismatch`)
    const destinationName = `${safeName(identity)}-${basename(override.licenseFile)}`
    const destination = join(licenseRoot, 'reviewed', destinationName)
    mkdirSync(dirname(destination), { recursive: true, mode: 0o700 })
    copyFileSync(licensePath, destination)
    reviewedRecords.push({ identity, ...override, output: `licenses/reviewed/${destinationName}` })
  }
  for (const override of postgresOverrides.scripts) {
    exactKeys(
      override,
      ['license', 'licenseFile', 'licenseSha256', 'licenseSource', 'path', 'sha256', 'source'],
      'postgres script override',
    )
    const artifact = insideRoot(root, override.path, false)
    if (fileSha256(artifact.actual) !== override.sha256) fail(`${override.path} digest mismatch`)
    const licensePath = resolve(dirname(options.overrides), ...override.licenseFile.split('/'))
    if (fileSha256(licensePath) !== override.licenseSha256) fail(`${override.path} license digest mismatch`)
    const artifactOutput = join(options.output, 'artifacts', basename(override.path))
    const licenseOutput = join(licenseRoot, 'reviewed', `script-${basename(override.path)}-${basename(override.licenseFile)}`)
    mkdirSync(dirname(artifactOutput), { recursive: true, mode: 0o700 })
    mkdirSync(dirname(licenseOutput), { recursive: true, mode: 0o700 })
    copyFileSync(artifact.actual, artifactOutput)
    copyFileSync(licensePath, licenseOutput)
    scriptRecords.push({
      ...override,
      output: `artifacts/${basename(override.path)}`,
      licenseOutput: `licenses/reviewed/${basename(licenseOutput)}`,
    })
  }
  const unknown = [...ecosystemComponents.keys()].filter((type) => type !== 'golang')
  if (unknown.length > 0) fail(`unclassified PostgreSQL image package ecosystems: ${unknown.join(', ')}`)
} else {
  const mavenComponents = ecosystemComponents.get('maven') ?? new Map()
  const genericComponents = ecosystemComponents.get('generic') ?? new Map()
  const unknown = [...ecosystemComponents.keys()].filter((type) => !['generic', 'maven'].includes(type))
  if (unknown.length > 0) fail(`unclassified API image package ecosystems: ${unknown.join(', ')}`)
  if (genericComponents.size !== 1) fail('API image must contain exactly one OpenJDK generic component')
  const openjdk = [...genericComponents.values()][0]
  if (openjdk.parsed.namespace !== 'oracle' || openjdk.parsed.name !== 'openjdk') {
    fail(`unclassified API generic component: ${openjdk.component.purl}`)
  }
  jreComponentRecords.push({ purl: openjdk.component.purl, classification: 'Temurin/OpenJDK runtime' })
  const fatJarEvidence = insideRoot(root, '/app/portfolio-server.jar', false)
  const fatJarBytes = readFileSync(fatJarEvidence.actual)
  const fatJarEntries = parseZip(fatJarBytes, 'API fat JAR')
  const physicalJarPaths = [...fatJarEntries.keys()]
    .filter((path) => /^BOOT-INF\/lib\/[^/]+\.jar$/.test(path))
    .sort((left, right) => left.localeCompare(right, 'en'))
  if (physicalJarPaths.length === 0) fail('API fat JAR contains no BOOT-INF/lib JARs')
  const physicalJarSet = new Set(physicalJarPaths)

  const backendManifestPath = join(options.backendLicenses, 'manifest.json')
  const backendNoticePath = join(options.backendLicenses, 'THIRD_PARTY_NOTICES.txt')
  let backendManifest
  try {
    backendManifest = readStrictJson(backendManifestPath, 'backend license manifest')
  } catch (error) {
    fail(error.message)
  }
  if (backendManifest.schemaVersion !== 1 || !Array.isArray(backendManifest.packages)) {
    fail('backend license manifest schema changed')
  }
  const backendByJar = new Map()
  for (const entry of backendManifest.packages) {
    if (
      !entry ||
      typeof entry.group !== 'string' ||
      !entry.group ||
      typeof entry.artifact !== 'string' ||
      !entry.artifact ||
      typeof entry.version !== 'string' ||
      !entry.version ||
      typeof entry.jar !== 'string' ||
      !/^[^/\\]+\.jar$/.test(entry.jar) ||
      typeof entry.jarSha256 !== 'string' ||
      !/^[0-9a-f]{64}$/.test(entry.jarSha256) ||
      !Array.isArray(entry.licenses) ||
      entry.licenses.length === 0 ||
      !Array.isArray(entry.legalFiles) ||
      entry.legalFiles.length === 0
    ) {
      fail('backend license manifest contains an invalid package binding')
    }
    if (backendByJar.has(entry.jar)) fail(`backend license manifest has a duplicate JAR: ${entry.jar}`)
    backendByJar.set(entry.jar, entry)
  }

  const directComponents = new Map()
  const embeddedComponents = []
  const applicationComponents = []
  for (const { parsed, component } of mavenComponents.values()) {
    if (parsed.namespace === 'jrt-fs' && parsed.name === 'jrt-fs') {
      if (!openjdk.parsed.version.startsWith(parsed.version)) fail('OpenJDK and jrt-fs versions differ')
      jreComponentRecords.push({ purl: component.purl, classification: 'Temurin/OpenJDK jrt-fs provider' })
      continue
    }
    const location = componentProperty(component, 'syft:location:0:path')
    const virtualPath = componentProperty(component, 'syft:metadata:virtualPath')
    if (location !== '/app/portfolio-server.jar') fail(`Maven component is outside the API fat JAR: ${component.purl}`)
    if (virtualPath === '/app/portfolio-server.jar') {
      if (parsed.namespace !== 'xyz.yychainsaw') fail(`fat-JAR root is not an internal component: ${component.purl}`)
      applicationComponents.push({ purl: component.purl, classification: 'application-root' })
      continue
    }
    const direct = virtualPath.match(/^\/app\/portfolio-server\.jar:(BOOT-INF\/lib\/[^/:]+\.jar)$/)
    if (direct) {
      const jarPath = direct[1]
      if (!physicalJarSet.has(jarPath)) fail(`SBOM direct Maven JAR is absent from the fat JAR: ${jarPath}`)
      if (directComponents.has(jarPath)) fail(`multiple SBOM Maven components bind the same JAR: ${jarPath}`)
      directComponents.set(jarPath, { component, parsed })
      continue
    }
    const embedded = virtualPath.match(/^\/app\/portfolio-server\.jar:(BOOT-INF\/lib\/[^/:]+\.jar):(.+)$/)
    if (!embedded || !physicalJarSet.has(embedded[1])) fail(`unclassified Maven virtual path: ${component.purl}: ${virtualPath}`)
    embeddedComponents.push({ component, parsed, ownerJarPath: embedded[1], metadataIdentity: embedded[2] })
  }
  if (applicationComponents.length !== 1) fail('API image must contain exactly one internal fat-JAR root component')
  if (directComponents.size !== physicalJarPaths.length) fail('Syft direct Maven components do not cover every physical nested JAR')

  const runtimePackages = []
  const nonRuntimeStarterPackages = []
  const runtimeBackendJars = new Set()
  for (const entry of backendManifest.packages) {
    const identity = `${entry.group}:${entry.artifact}:${entry.version}`
    const jarPath = `BOOT-INF/lib/${entry.jar}`
    if (!physicalJarSet.has(jarPath)) {
      if (
        entry.group !== 'org.springframework.boot' ||
        !/^spring-boot-starter(?:-|$)/.test(entry.artifact) ||
        entry.jar !== `${entry.artifact}-${entry.version}.jar`
      ) {
        fail(`backend manifest package is absent from the fat JAR and is not a Spring Boot starter: ${identity}`)
      }
      nonRuntimeStarterPackages.push(identity)
      continue
    }
    const binding = directComponents.get(jarPath)
    if (binding.parsed.namespace === 'xyz.yychainsaw') fail(`external backend package binds an internal JAR: ${entry.jar}`)
    if (binding.parsed.version !== entry.version) fail(`Syft/backend version mismatch for ${entry.jar}`)
    const jarBytes = readZipEntry(fatJarBytes, fatJarEntries.get(jarPath), `API fat JAR ${jarPath}`)
    const jarSha256 = sha256(jarBytes)
    if (jarSha256 !== entry.jarSha256) fail(`fat-JAR payload digest differs from backend legal manifest: ${entry.jar}`)
    runtimeBackendJars.add(jarPath)
    runtimePackages.push({
      identity,
      syftPurl: binding.component.purl,
      jarPath,
      jarBytes: jarBytes.length,
      jarSha256,
    })
  }

  const internalMavenComponents = []
  const injectedPackages = []
  for (const jarPath of physicalJarPaths) {
    if (runtimeBackendJars.has(jarPath)) continue
    const binding = directComponents.get(jarPath)
    const jarName = basename(jarPath)
    const jarBytes = readZipEntry(fatJarBytes, fatJarEntries.get(jarPath), `API fat JAR ${jarPath}`)
    const jarSha256 = sha256(jarBytes)
    if (binding.parsed.namespace === 'xyz.yychainsaw') {
      if (jarName !== `${binding.parsed.name}-${binding.parsed.version}.jar`) fail(`internal JAR/PURL identity mismatch: ${binding.component.purl}`)
      internalMavenComponents.push({ purl: binding.component.purl, classification: 'internal-library', jarPath, jarBytes: jarBytes.length, jarSha256 })
      continue
    }
    if (
      binding.parsed.name !== 'spring-boot-jarmode-tools' ||
      jarName !== `spring-boot-jarmode-tools-${binding.parsed.version}.jar` ||
      JSON.stringify(licenses(binding.component)) !== JSON.stringify(['Apache-2.0'])
    ) {
      fail(`unclassified fat-JAR package not present in the backend legal manifest: ${binding.component.purl}`)
    }
    const injectedEntries = parseZip(jarBytes, `${jarName} nested JAR`)
    const legalFiles = []
    for (const legalPath of ['META-INF/LICENSE.txt', 'META-INF/NOTICE.txt']) {
      const legalEntry = injectedEntries.get(legalPath)
      if (!legalEntry) fail(`${jarName} is missing ${legalPath}`)
      const bytes = legalTextBytes(readZipEntry(jarBytes, legalEntry, `${jarName} ${legalPath}`), `${jarName} ${legalPath}`)
      const outputName = `${safeName(jarName)}-${basename(legalPath)}`
      const destination = join(licenseRoot, 'backend-injected', outputName)
      mkdirSync(dirname(destination), { recursive: true, mode: 0o700 })
      writeFileSync(destination, bytes, { mode: 0o600 })
      legalFiles.push({ path: legalPath, output: `licenses/backend-injected/${outputName}`, bytes: bytes.length, sha256: sha256(bytes) })
    }
    injectedPackages.push({
      purl: binding.component.purl,
      classification: 'spring-boot-repackager-injected',
      jarPath,
      jarBytes: jarBytes.length,
      jarSha256,
      licenses: ['Apache-2.0'],
      legalFiles,
    })
  }
  if (internalMavenComponents.length === 0) fail('API image SBOM has no internal application library identity')
  if (injectedPackages.length !== 1) fail('API image must contain exactly one reviewed repackager-injected Maven package')

  const embeddedMetadataComponents = embeddedComponents.map(({ component, ownerJarPath, metadataIdentity }) => {
    const owner = runtimePackages.find((record) => record.jarPath === ownerJarPath)
    if (!owner) fail(`embedded Maven metadata is not owned by a backend-licensed JAR: ${component.purl}`)
    return { purl: component.purl, classification: 'embedded-metadata', ownerJarPath, ownerIdentity: owner.identity, metadataIdentity }
  })
  const sbomMavenBindings = [
    ...applicationComponents,
    ...runtimePackages.map((record) => ({ purl: record.syftPurl, classification: 'backend-runtime', ownerIdentity: record.identity })),
    ...internalMavenComponents.map(({ purl, classification, jarPath }) => ({ purl, classification, jarPath })),
    ...injectedPackages.map(({ purl, classification, jarPath }) => ({ purl, classification, jarPath })),
    ...embeddedMetadataComponents.map(({ purl, classification, ownerJarPath, ownerIdentity }) => ({ purl, classification, ownerJarPath, ownerIdentity })),
  ].sort((left, right) => left.purl.localeCompare(right.purl, 'en'))
  const expectedSbomMavenPurls = [...mavenComponents.values()]
    .filter(({ parsed }) => !(parsed.namespace === 'jrt-fs' && parsed.name === 'jrt-fs'))
    .map(({ component }) => component.purl)
    .sort((left, right) => left.localeCompare(right, 'en'))
  if (
    new Set(sbomMavenBindings.map((entry) => entry.purl)).size !== sbomMavenBindings.length ||
    JSON.stringify(sbomMavenBindings.map((entry) => entry.purl)) !== JSON.stringify(expectedSbomMavenPurls)
  ) {
    fail('API Maven legal bindings do not cover every Syft component exactly once')
  }
  if (jreComponentRecords.length !== 2) fail('API image JRE SBOM classification is incomplete')
  const destination = join(licenseRoot, 'backend')
  cpSync(options.backendLicenses, destination, { recursive: true, dereference: false, errorOnExist: true })
  backendBinding = {
    fatJar: {
      path: '/app/portfolio-server.jar',
      bytes: fatJarBytes.length,
      sha256: sha256(fatJarBytes),
      nestedJarCount: physicalJarPaths.length,
    },
    manifestSha256: fileSha256(backendManifestPath),
    noticesSha256: fileSha256(backendNoticePath),
    manifestPackageCount: backendManifest.packages.length,
    runtimePackages: runtimePackages.sort((left, right) => left.identity.localeCompare(right.identity, 'en')),
    nonRuntimeStarterPackages: nonRuntimeStarterPackages.sort((left, right) => left.localeCompare(right, 'en')),
    injectedPackages,
    embeddedMetadataComponents: embeddedMetadataComponents.sort((left, right) => left.purl.localeCompare(right.purl, 'en')),
    internalMavenComponents: internalMavenComponents.sort((left, right) => left.purl.localeCompare(right.purl, 'en')),
    applicationComponent: applicationComponents[0],
    sbomMavenBindings,
  }
}

const manifest = {
  schemaVersion: 1,
  kind: options.kind,
  image: {
    archiveSha256: image.archiveSha256,
    configDigest: image.configDigest,
    repoTags: image.repoTags,
    sbomSha256: fileSha256(options.sbom),
  },
  catalog: { fileEntries: fileCatalogEntries, operatingSystemEntries },
  dpkgPackages: dpkgRecords,
  jreComponents: jreComponentRecords,
  jreLegalFiles: jreRecords,
  reviewedPackages: reviewedRecords,
  scripts: scriptRecords,
  backendBinding,
}
writeFileSync(join(options.output, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 })
let notice = `OCI THIRD-PARTY NOTICES - ${options.kind}\n\n`
notice += `Docker archive SHA-256: ${image.archiveSha256}\nImage config digest: ${image.configDigest}\n\n`
notice += `Debian/Ubuntu packages (${dpkgRecords.length}): legal texts are under licenses/deb/.\n`
if (jreRecords.length) notice += `Temurin/OpenJDK legal files (${jreRecords.length}): licenses/jre/.\n`
if (reviewedRecords.length) notice += `Reviewed non-dpkg packages (${reviewedRecords.length}): licenses/reviewed/.\n`
if (scriptRecords.length) notice += `Official image scripts (${scriptRecords.length}): artifacts/.\n`
if (backendBinding) {
  notice += `Fat-JAR backend runtime packages (${backendBinding.runtimePackages.length}): licenses/backend/.\n`
  notice += `Fat-JAR repackager-injected packages (${backendBinding.injectedPackages.length}): licenses/backend-injected/.\n`
}
writeFileSync(join(options.output, 'THIRD_PARTY_NOTICES.txt'), notice, { mode: 0o600 })
console.error(
  `collect-oci-image-licenses: ${options.kind}: ${dpkgRecords.length} dpkg, ${reviewedRecords.length} reviewed, ${jreRecords.length} JRE legal files`,
)
