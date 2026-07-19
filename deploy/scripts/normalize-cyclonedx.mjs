#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

function fail(message) {
  console.error(`normalize-cyclonedx: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const values = new Map()
  let requireLicenses = false
  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index]
    if (name === '--require-licenses') {
      requireLicenses = true
      continue
    }
    if (!name?.startsWith('--') || !argv[index + 1]) fail(`invalid argument near ${name ?? '<end>'}`)
    if (values.has(name)) fail(`duplicate argument: ${name}`)
    values.set(name, argv[index + 1])
    index += 1
  }
  for (const name of ['--input', '--output']) if (!values.has(name)) fail(`missing ${name}`)
  return {
    input: resolve(values.get('--input')),
    output: resolve(values.get('--output')),
    requireLicenses,
  }
}

function hasLicense(component) {
  if (!Array.isArray(component.licenses) || component.licenses.length === 0) return false
  return component.licenses.every((entry) => {
    const value = entry?.expression ?? entry?.license?.id ?? entry?.license?.name
    return typeof value === 'string' && value.trim() && !/^(?:UNKNOWN|UNLICENSED)$/i.test(value.trim())
  })
}

const unorderedArrays = new Set([
  'components',
  'dependencies',
  'externalReferences',
  'hashes',
  'licenses',
  'properties',
  'services',
  'tools',
  'vulnerabilities',
])

function canonical(value, parentKey = '') {
  if (Array.isArray(value)) {
    const result = value.map((entry) => canonical(entry, parentKey))
    if (parentKey === 'dependsOn' || parentKey === 'provides' || result.every((entry) => typeof entry === 'string')) {
      return result.sort((left, right) => String(left).localeCompare(String(right), 'en'))
    }
    if (unorderedArrays.has(parentKey)) {
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
let bom
try {
  bom = JSON.parse(readFileSync(options.input, 'utf8'))
} catch (error) {
  fail(`cannot parse input: ${error.message}`)
}
if (bom.bomFormat !== 'CycloneDX' || bom.specVersion !== '1.6' || !Number.isInteger(bom.version)) {
  fail('input is not a CycloneDX 1.6 JSON BOM')
}
if (!bom.metadata?.component || typeof bom.metadata.component.name !== 'string') {
  fail('BOM has no metadata component')
}
if (!Array.isArray(bom.components) || bom.components.length === 0) fail('BOM component closure is empty')
if (options.requireLicenses) {
  for (const component of bom.components) {
    const internal =
      component.group === 'xyz.yychainsaw' ||
      (typeof component.purl === 'string' && component.purl.startsWith('pkg:maven/xyz.yychainsaw/'))
    if (!internal && !hasLicense(component)) {
      fail(`component has no reviewed license metadata: ${component.group ? `${component.group}/` : ''}${component.name}@${component.version}`)
    }
  }
}

delete bom.serialNumber
if (bom.metadata) delete bom.metadata.timestamp
const normalized = canonical(bom)
writeFileSync(options.output, `${JSON.stringify(normalized, null, 2)}\n`, { mode: 0o600 })
console.error(`normalize-cyclonedx: ${bom.components.length} components`)
