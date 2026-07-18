#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import {
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, join, relative, resolve, sep } from 'node:path'
import { TextDecoder } from 'node:util'

function fail(message) {
  console.error(`collect-maven-licenses: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const values = new Map()
  for (let index = 0; index < argv.length; index += 2) {
    if (!argv[index]?.startsWith('--') || !argv[index + 1]) fail('invalid arguments')
    if (values.has(argv[index])) fail(`duplicate argument: ${argv[index]}`)
    values.set(argv[index], argv[index + 1])
  }
  for (const name of ['--repository', '--sbom', '--output', '--overrides']) {
    if (!values.has(name)) fail(`missing ${name}`)
  }
  return {
    repository: realpathSync(resolve(values.get('--repository'))),
    sbom: resolve(values.get('--sbom')),
    output: resolve(values.get('--output')),
    overrides: resolve(values.get('--overrides')),
  }
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function hasLicense(component) {
  return (
    Array.isArray(component?.licenses) &&
    component.licenses.length > 0 &&
    component.licenses.every((entry) => {
      const value = entry?.expression ?? entry?.license?.id ?? entry?.license?.name
      return typeof value === 'string' && value.trim() && !/^(?:UNKNOWN|UNLICENSED)$/i.test(value.trim())
    })
  )
}

function walk(directory, paths) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((left, right) =>
    left.name.localeCompare(right.name, 'en'),
  )) {
    const path = join(directory, entry.name)
    if (entry.isSymbolicLink()) fail(`runtime repository contains a symlink: ${path}`)
    if (entry.isDirectory()) walk(path, paths)
    else if (entry.isFile() && entry.name.endsWith('.jar')) paths.push(path)
  }
}

function runUnzip(args, jar) {
  const result = spawnSync('unzip', [...args, jar], {
    encoding: null,
    maxBuffer: 16 * 1024 * 1024,
    windowsHide: true,
  })
  if (result.error || result.status !== 0) {
    fail(`unzip failed for ${jar}: ${result.error?.message ?? String(result.stderr)}`)
  }
  return result.stdout
}

function coordinate(repository, jar) {
  const parts = relative(repository, jar).split(sep)
  if (parts.length < 4) fail(`jar is not in Maven repository layout: ${jar}`)
  const file = parts.at(-1)
  const version = parts.at(-2)
  const artifact = parts.at(-3)
  const group = parts.slice(0, -3).join('.')
  if (!group || !artifact || !version || !file.startsWith(`${artifact}-${version}`)) {
    fail(`invalid Maven repository identity: ${jar}`)
  }
  return { group, artifact, version, file }
}

function safeName(value) {
  return value.replace(/[^A-Za-z0-9._@+-]/g, '_')
}

function embeddedLicenseTexts(component, identity) {
  const results = []
  for (const entry of component.licenses ?? []) {
    const identifier = entry?.expression ?? entry?.license?.id ?? entry?.license?.name
    const text = entry?.license?.text
    if (!identifier || !text) continue
    if (
      text.encoding !== 'base64' ||
      !['plain/text', 'text/plain'].includes(text.contentType) ||
      typeof text.content !== 'string' ||
      text.content.length === 0 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text.content)
    ) {
      fail(`runtime component has an invalid embedded license text: ${identity}`)
    }
    const bytes = Buffer.from(text.content, 'base64')
    if (bytes.length <= 0 || bytes.length > 1024 * 1024) {
      fail(`embedded license text has unsafe size: ${identity}`)
    }
    results.push({
      identifier,
      bytes,
      source: 'cyclonedx-maven-plugin embedded license text',
    })
  }
  return results
}

function reviewedOverride(overrides, overridesRoot, component, identity) {
  const override = overrides.mavenPackages[identity]
  if (!override || typeof override !== 'object') return []
  const declared = (component.licenses ?? [])
    .map((entry) => entry?.expression ?? entry?.license?.id ?? entry?.license?.name)
    .filter((value) => typeof value === 'string' && value)
  if (
    typeof override.declaredLicense !== 'string' ||
    !declared.includes(override.declaredLicense) ||
    typeof override.license !== 'string' ||
    !/^[A-Za-z0-9.+-]+$/.test(override.license) ||
    typeof override.file !== 'string' ||
    !/^license-texts\/[A-Za-z0-9._-]+$/.test(override.file) ||
    typeof override.sha256 !== 'string' ||
    !/^[0-9a-f]{64}$/.test(override.sha256) ||
    typeof override.source !== 'string' ||
    !/^https:\/\/raw\.githubusercontent\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/[0-9a-f]{40}\//.test(
      override.source,
    )
  ) {
    fail(`runtime component has an invalid reviewed override: ${identity}`)
  }
  const path = resolve(overridesRoot, ...override.file.split('/'))
  const boundary = `${overridesRoot}${sep}`
  if (!path.startsWith(boundary)) fail(`runtime component override escapes compliance root: ${identity}`)
  const info = lstatSync(path)
  if (!info.isFile() || info.isSymbolicLink()) fail(`runtime component override is not a regular file: ${identity}`)
  const bytes = readFileSync(path)
  if (bytes.length <= 0 || bytes.length > 1024 * 1024 || sha256(bytes) !== override.sha256) {
    fail(`runtime component override digest/size mismatch: ${identity}`)
  }
  return [
    {
      identifier: override.license,
      bytes,
      source: override.source,
      name: basename(override.file),
    },
  ]
}

const options = parseArgs(process.argv.slice(2))
let bom
let overrides
try {
  bom = JSON.parse(readFileSync(options.sbom, 'utf8'))
  overrides = JSON.parse(readFileSync(options.overrides, 'utf8'))
} catch (error) {
  fail(`cannot parse backend SBOM: ${error.message}`)
}
if (bom.bomFormat !== 'CycloneDX' || bom.specVersion !== '1.6' || !Array.isArray(bom.components)) {
  fail('backend SBOM is not CycloneDX 1.6 JSON')
}
if (
  overrides.schemaVersion !== 1 ||
  !overrides.mavenPackages ||
  typeof overrides.mavenPackages !== 'object' ||
  Array.isArray(overrides.mavenPackages)
) {
  fail('Maven license override manifest must contain a schemaVersion 1 mavenPackages object')
}
const overridesRoot = realpathSync(resolve(options.overrides, '..'))
const components = new Map()
for (const component of bom.components) {
  if (typeof component.group === 'string' && typeof component.name === 'string' && typeof component.version === 'string') {
    components.set(`${component.group}:${component.name}:${component.version}`, component)
  }
}

mkdirSync(options.output, { recursive: true, mode: 0o700 })
if (readdirSync(options.output).length !== 0) fail(`output directory is not empty: ${options.output}`)
const licenseRoot = join(options.output, 'licenses')
mkdirSync(licenseRoot, { mode: 0o700 })

const jars = []
walk(options.repository, jars)
if (jars.length === 0) fail('runtime Maven dependency repository is empty')
const records = []
const decoder = new TextDecoder('utf-8', { fatal: true })
const seen = new Map()
const missingLegal = []

for (const jar of jars.sort((left, right) => left.localeCompare(right, 'en'))) {
  const id = coordinate(options.repository, jar)
  if (id.group === 'xyz.yychainsaw') continue
  const component = components.get(`${id.group}:${id.artifact}:${id.version}`)
  if (!component) fail(`runtime jar is absent from backend SBOM: ${id.group}:${id.artifact}:${id.version}`)
  if (!hasLicense(component)) fail(`runtime component has no reviewed license: ${id.group}:${id.artifact}:${id.version}`)

  const jarBytes = readFileSync(jar)
  const jarHash = sha256(jarBytes)
  const identity = `${id.group}:${id.artifact}:${id.version}`
  if (seen.has(identity) && seen.get(identity) !== jarHash) fail(`conflicting runtime jars: ${identity}`)
  seen.set(identity, jarHash)

  const entries = runUnzip(['-Z1'], jar)
    .toString('utf8')
    .split(/\r?\n/)
    .filter((name) => /^(?:META-INF\/)?(?:LICEN[CS]E|COPYING|NOTICE)(?:[._-].*)?$/i.test(name))
    .sort((left, right) => left.localeCompare(right, 'en'))
  const hasPackagedLicense = entries.some((name) =>
    /(?:^|\/)(?:LICEN[CS]E|COPYING)(?:[._-].*)?$/i.test(name),
  )
  let fallback = hasPackagedLicense ? [] : embeddedLicenseTexts(component, identity)
  if (!hasPackagedLicense && fallback.length === 0) {
    fallback = reviewedOverride(overrides, overridesRoot, component, identity)
  }
  if (!hasPackagedLicense && fallback.length === 0) {
    missingLegal.push(identity)
    continue
  }

  const destination = join(licenseRoot, safeName(identity))
  mkdirSync(destination, { recursive: true, mode: 0o700 })
  const legalFiles = []
  const usedNames = new Set()
  for (const entry of entries) {
    const result = spawnSync('unzip', ['-p', jar, entry], {
      encoding: null,
      maxBuffer: 2 * 1024 * 1024,
      windowsHide: true,
    })
    if (result.error || result.status !== 0) fail(`cannot extract ${identity}/${entry}`)
    const bytes = result.stdout
    if (bytes.length <= 0 || bytes.length > 1024 * 1024) fail(`legal file has unsafe size: ${identity}/${entry}`)
    try {
      decoder.decode(bytes)
    } catch {
      fail(`legal file is not UTF-8: ${identity}/${entry}`)
    }
    let name = entry.replaceAll('/', '__').replace(/[^A-Za-z0-9._-]/g, '_')
    if (usedNames.has(name)) name = `${sha256(Buffer.from(entry)).slice(0, 12)}-${name}`
    usedNames.add(name)
    writeFileSync(join(destination, name), bytes, { mode: 0o600 })
    legalFiles.push({ entry, name, bytes: bytes.length, sha256: sha256(bytes), source: 'runtime-jar' })
  }
  for (const { identifier, bytes, source, name: reviewedName } of fallback) {
    try {
      decoder.decode(bytes)
    } catch {
      fail(`embedded license text is not UTF-8: ${identity}/${identifier}`)
    }
    let name = reviewedName ?? `SBOM-LICENSE-${safeName(identifier)}.txt`
    if (usedNames.has(name)) name = `${sha256(Buffer.from(identifier)).slice(0, 12)}-${name}`
    usedNames.add(name)
    writeFileSync(join(destination, name), bytes, { mode: 0o600 })
    legalFiles.push({
      entry: reviewedName ? `Reviewed license override: ${identifier}` : `CycloneDX embedded license text: ${identifier}`,
      name,
      bytes: bytes.length,
      sha256: sha256(bytes),
      source,
    })
  }

  records.push({
    group: id.group,
    artifact: id.artifact,
    version: id.version,
    jar: id.file,
    jarBytes: statSync(jar).size,
    jarSha256: jarHash,
    licenses: component.licenses,
    legalFiles,
  })
}

const missingRuntimeJars = [...components.keys()]
  .filter((identity) => !identity.startsWith('xyz.yychainsaw:') && !seen.has(identity))
  .sort((left, right) => left.localeCompare(right, 'en'))
if (missingRuntimeJars.length > 0) {
  fail(`backend SBOM components are absent from the runtime jar closure: ${missingRuntimeJars.join(', ')}`)
}
if (missingLegal.length > 0) {
  fail(`runtime jars have no packaged, CycloneDX-embedded, or reviewed license text: ${missingLegal.join(', ')}`)
}
if (records.length === 0) fail('no third-party runtime Maven jars were collected')
records.sort((left, right) =>
  `${left.group}:${left.artifact}:${left.version}`.localeCompare(
    `${right.group}:${right.artifact}:${right.version}`,
    'en',
  ),
)
writeFileSync(
  join(options.output, 'manifest.json'),
  `${JSON.stringify({ schemaVersion: 1, component: 'backend', packages: records }, null, 2)}\n`,
  { mode: 0o600 },
)

let notice = 'THIRD-PARTY NOTICES - backend\n\n'
for (const record of records) {
  const identity = `${record.group}:${record.artifact}:${record.version}`
  notice += `${'='.repeat(78)}\n${identity}\n${'='.repeat(78)}\n`
  const directory = join(licenseRoot, safeName(identity))
  for (const file of record.legalFiles) {
    let text = readFileSync(join(directory, file.name), 'utf8').replaceAll('\r\n', '\n')
    if (!text.endsWith('\n')) text += '\n'
    notice += `\n--- ${file.entry} ---\n${text}`
  }
  notice += '\n'
}
writeFileSync(join(options.output, 'THIRD_PARTY_NOTICES.txt'), notice, { mode: 0o600 })
console.error(`collect-maven-licenses: ${records.length} runtime jars`)
