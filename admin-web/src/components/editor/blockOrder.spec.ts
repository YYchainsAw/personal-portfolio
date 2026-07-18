import { describe, expect, it } from 'vitest'

import {
  moveBlock,
  removeBlock,
  renumberBlocks,
  replaceBlock,
} from './blockOrder'

interface OrderedItem {
  readonly id: string
  readonly sortOrder: number
  readonly label: string
}

const source = Object.freeze([
  Object.freeze({ id: 'a', sortOrder: 8, label: 'Alpha' }),
  Object.freeze({ id: 'b', sortOrder: 3, label: 'Beta' }),
  Object.freeze({ id: 'c', sortOrder: 42, label: 'Gamma' }),
]) satisfies readonly OrderedItem[]

describe('block ordering', () => {
  it('renumbers a frozen source without retaining caller-owned item objects', () => {
    const normalized = renumberBlocks(source)

    expect(normalized.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'a', sortOrder: 0 },
      { id: 'b', sortOrder: 1 },
      { id: 'c', sortOrder: 2 },
    ])
    expect(normalized).not.toBe(source)
    expect(normalized.every((item) => !source.includes(item))).toBe(true)
    expect(source.map((item) => item.sortOrder)).toEqual([8, 3, 42])
  })

  it('moves by stable id, clamps the target, and rewrites contiguous sortOrder values', () => {
    expect(moveBlock(source, 'c', -10).map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'c', sortOrder: 0 },
      { id: 'a', sortOrder: 1 },
      { id: 'b', sortOrder: 2 },
    ])
    expect(moveBlock(source, 'a', 99).map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'b', sortOrder: 0 },
      { id: 'c', sortOrder: 1 },
      { id: 'a', sortOrder: 2 },
    ])
    expect(source.map((item) => item.id)).toEqual(['a', 'b', 'c'])
  })

  it('normalizes safely when a move id is stale', () => {
    const result = moveBlock(source, 'missing', 1)

    expect(result.map((item) => [item.id, item.sortOrder])).toEqual([
      ['a', 0],
      ['b', 1],
      ['c', 2],
    ])
    expect(result.every((item) => !source.includes(item))).toBe(true)
  })

  it('removes by stable id and renumbers the remaining objects', () => {
    const result = removeBlock(source, 'b')

    expect(result.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: 'a', sortOrder: 0 },
      { id: 'c', sortOrder: 1 },
    ])
    expect(result.every((item) => !source.includes(item))).toBe(true)
  })

  it('replaces by stable id while retaining the canonical identity and order', () => {
    const replacement: OrderedItem = { id: 'forged', sortOrder: 999, label: 'Updated' }
    const result = replaceBlock(source, 'b', replacement)

    expect(result).not.toBe(source)
    expect(result.map(({ id, sortOrder, label }) => ({ id, sortOrder, label }))).toEqual([
      { id: 'a', sortOrder: 8, label: 'Alpha' },
      { id: 'b', sortOrder: 3, label: 'Updated' },
      { id: 'c', sortOrder: 42, label: 'Gamma' },
    ])
    expect(result[0]).toBe(source[0])
    expect(result[1]).not.toBe(source[1])
    expect(result[2]).toBe(source[2])
  })
})
