import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, normalize } from 'node:path'
import { expect, it } from 'vitest'

function sourceFiles(path: string): string[] {
  return readdirSync(path).flatMap((name) => {
    const next = join(path, name)
    return statSync(next).isDirectory() ? sourceFiles(next) : /\.(ts|vue)$/u.test(name) ? [next] : []
  })
}

it('keeps portfolio.ts exclusively as the exporter source', () => {
  const dataFile = normalize(join('src', 'data', 'portfolio.ts'))
  const offenders = sourceFiles('src').filter((file) => normalize(file) !== dataFile)
    .filter((file) => readFileSync(file, 'utf8').includes('@/data/portfolio'))
  expect(offenders).toEqual([])
})
