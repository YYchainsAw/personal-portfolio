import { readFileSync } from 'node:fs'

function jsonError(label, message) {
  throw new Error(`${label}: ${message}`)
}

function assertUniqueJsonKeys(text, label) {
  let offset = 0

  const whitespace = () => {
    while (/\s/.test(text[offset] ?? '')) offset += 1
  }
  const string = () => {
    const start = offset
    if (text[offset] !== '"') jsonError(label, `expected string at byte ${offset}`)
    offset += 1
    while (offset < text.length) {
      if (text[offset] === '\\') {
        offset += 2
        continue
      }
      if (text[offset] === '"') {
        offset += 1
        try {
          return JSON.parse(text.slice(start, offset))
        } catch (error) {
          jsonError(label, `invalid string at byte ${start}: ${error.message}`)
        }
      }
      offset += 1
    }
    jsonError(label, `unterminated string at byte ${start}`)
  }
  const value = (path) => {
    whitespace()
    if (text[offset] === '{') {
      offset += 1
      whitespace()
      const keys = new Set()
      if (text[offset] === '}') {
        offset += 1
        return
      }
      while (offset < text.length) {
        whitespace()
        const key = string()
        if (keys.has(key)) jsonError(label, `duplicate key ${JSON.stringify(key)} at ${path}`)
        keys.add(key)
        whitespace()
        if (text[offset] !== ':') jsonError(label, `expected ':' at byte ${offset}`)
        offset += 1
        value(`${path}.${key}`)
        whitespace()
        if (text[offset] === '}') {
          offset += 1
          return
        }
        if (text[offset] !== ',') jsonError(label, `expected ',' at byte ${offset}`)
        offset += 1
      }
      jsonError(label, `unterminated object at ${path}`)
    }
    if (text[offset] === '[') {
      offset += 1
      whitespace()
      if (text[offset] === ']') {
        offset += 1
        return
      }
      let index = 0
      while (offset < text.length) {
        value(`${path}[${index}]`)
        index += 1
        whitespace()
        if (text[offset] === ']') {
          offset += 1
          return
        }
        if (text[offset] !== ',') jsonError(label, `expected ',' at byte ${offset}`)
        offset += 1
      }
      jsonError(label, `unterminated array at ${path}`)
    }
    if (text[offset] === '"') {
      string()
      return
    }
    const primitive = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(
      text.slice(offset),
    )
    if (!primitive) jsonError(label, `invalid value at byte ${offset}`)
    offset += primitive[0].length
  }

  value('$')
  whitespace()
  if (offset !== text.length) jsonError(label, `trailing content at byte ${offset}`)
}

export function readStrictJson(path, label) {
  const text = readFileSync(path, 'utf8')
  assertUniqueJsonKeys(text, label)
  try {
    return JSON.parse(text)
  } catch (error) {
    jsonError(label, error.message)
  }
}

function targetAllows(values, target) {
  if (!Array.isArray(values) || values.length === 0) return true
  if (!values.every((value) => typeof value === 'string' && value)) {
    throw new Error('os/cpu selectors must be non-empty strings')
  }
  if (values.includes(`!${target}`)) return false
  const positives = values.filter((value) => !value.startsWith('!'))
  return positives.length === 0 || positives.includes(target)
}

function packageAncestors(entryPath) {
  const ancestors = []
  const pattern = /(?:^|\/)node_modules\/(?:@[^/]+\/)?[^/]+/g
  let match
  while ((match = pattern.exec(entryPath))) ancestors.push(entryPath.slice(0, pattern.lastIndex))
  return ancestors
}

function resolveDependency(packages, entryPath, dependencyName) {
  for (const ancestor of packageAncestors(entryPath).reverse()) {
    const candidate = `${ancestor}/node_modules/${dependencyName}`
    if (Object.hasOwn(packages, candidate)) return candidate
  }
  const candidate = `node_modules/${dependencyName}`
  return Object.hasOwn(packages, candidate) ? candidate : null
}

function installedName(entryPath, metadata) {
  if (typeof metadata.name === 'string' && metadata.name) return metadata.name
  const name = entryPath.split('node_modules/').at(-1)
  if (!name || name.includes('/node_modules/')) throw new Error(`cannot derive package name: ${entryPath}`)
  return name
}

function dependencyEdges(metadata, isRoot) {
  const edges = new Map()
  for (const name of Object.keys(metadata.dependencies ?? {}).sort()) {
    edges.set(name, { optional: false, kind: 'dependency' })
  }
  for (const name of Object.keys(metadata.optionalDependencies ?? {}).sort()) {
    edges.set(name, { optional: true, kind: 'optionalDependency' })
  }
  if (!isRoot) {
    for (const name of Object.keys(metadata.peerDependencies ?? {}).sort()) {
      const optional = metadata.peerDependenciesMeta?.[name]?.optional === true
      edges.set(name, { optional, kind: optional ? 'optionalPeerDependency' : 'peerDependency' })
    }
  }
  return edges
}

export function deriveNodeProductionClosure(lock, target = { os: 'linux', cpu: 'x64' }) {
  if (lock?.lockfileVersion !== 3 || !lock.packages || typeof lock.packages !== 'object') {
    throw new Error('package-lock.json must use lockfileVersion 3')
  }
  const root = lock.packages['']
  if (!root || typeof root !== 'object') throw new Error('package-lock.json has no root package')
  if (typeof root.name !== 'string' || !root.name || typeof root.version !== 'string' || !root.version) {
    throw new Error('package-lock.json root identity is incomplete')
  }

  const reached = new Set()
  const queue = ['']
  const pathEdges = new Map([['', new Set()]])
  while (queue.length > 0) {
    const entryPath = queue.shift()
    const metadata = lock.packages[entryPath]
    for (const [dependencyName, edge] of dependencyEdges(metadata, entryPath === '')) {
      if (edge.kind === 'optionalPeerDependency') continue
      const dependencyPath = resolveDependency(lock.packages, entryPath, dependencyName)
      if (!dependencyPath) {
        if (edge.optional) continue
        throw new Error(`${entryPath || '<root>'} cannot resolve production dependency ${dependencyName}`)
      }
      const dependency = lock.packages[dependencyPath]
      const allowed = targetAllows(dependency.os, target.os) && targetAllows(dependency.cpu, target.cpu)
      if (!allowed) {
        if (edge.optional) continue
        throw new Error(`${dependencyPath} excludes required target ${target.os}/${target.cpu}`)
      }
      if (dependency.dev === true) {
        throw new Error(`${dependencyPath} is production-reachable but marked dev-only`)
      }
      if (dependency.link === true) throw new Error(`production lock entry is a link: ${dependencyPath}`)
      if (typeof dependency.version !== 'string' || !dependency.version) {
        throw new Error(`missing version: ${dependencyPath}`)
      }
      if (typeof dependency.integrity !== 'string' || !/^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$/.test(dependency.integrity)) {
        throw new Error(`missing integrity for production package: ${dependencyPath}`)
      }
      if (!pathEdges.has(entryPath)) pathEdges.set(entryPath, new Set())
      pathEdges.get(entryPath).add(dependencyPath)
      if (!reached.has(dependencyPath)) {
        reached.add(dependencyPath)
        pathEdges.set(dependencyPath, new Set())
        queue.push(dependencyPath)
      }
    }
  }

  // Optional peers describe a relationship only when another production edge already installed the peer.
  for (const entryPath of reached) {
    const metadata = lock.packages[entryPath]
    for (const [dependencyName, edge] of dependencyEdges(metadata, false)) {
      if (edge.kind !== 'optionalPeerDependency') continue
      const dependencyPath = resolveDependency(lock.packages, entryPath, dependencyName)
      if (dependencyPath && reached.has(dependencyPath)) pathEdges.get(entryPath).add(dependencyPath)
    }
  }

  const entries = [...reached]
    .sort((left, right) => left.localeCompare(right, 'en'))
    .map((entryPath) => {
      const metadata = lock.packages[entryPath]
      const name = installedName(entryPath, metadata)
      return {
        path: entryPath,
        name,
        version: metadata.version,
        identity: `${name}@${metadata.version}`,
        integrity: metadata.integrity,
        dependencyPaths: [...(pathEdges.get(entryPath) ?? [])].sort((left, right) => left.localeCompare(right, 'en')),
      }
    })
  if (entries.length === 0) throw new Error('production dependency closure is empty')

  const byPath = new Map(entries.map((entry) => [entry.path, entry]))
  const identityMap = new Map()
  for (const entry of entries) {
    if (!identityMap.has(entry.identity)) {
      identityMap.set(entry.identity, {
        identity: entry.identity,
        name: entry.name,
        version: entry.version,
        integrity: entry.integrity,
        lockPaths: [],
        dependsOn: new Set(),
      })
    }
    const identity = identityMap.get(entry.identity)
    if (identity.integrity !== entry.integrity) {
      throw new Error(`same package identity has conflicting integrity: ${entry.identity}`)
    }
    identity.lockPaths.push(entry.path)
    for (const dependencyPath of entry.dependencyPaths) identity.dependsOn.add(byPath.get(dependencyPath).identity)
  }

  const rootDependencies = [...(pathEdges.get('') ?? [])]
    .map((entryPath) => byPath.get(entryPath).identity)
    .filter((identity, index, values) => values.indexOf(identity) === index)
    .sort((left, right) => left.localeCompare(right, 'en'))
  const identities = [...identityMap.values()]
    .map((entry) => ({
      ...entry,
      lockPaths: entry.lockPaths.sort((left, right) => left.localeCompare(right, 'en')),
      dependsOn: [...entry.dependsOn].sort((left, right) => left.localeCompare(right, 'en')),
    }))
    .sort((left, right) => left.identity.localeCompare(right.identity, 'en'))

  return {
    target,
    root: { name: root.name, version: root.version, dependencies: rootDependencies },
    entries,
    identities,
  }
}
