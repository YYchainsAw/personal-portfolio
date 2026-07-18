import { expect, it } from 'vitest'
import {
  publicBlockAlignments,
  publicBlockEmphases,
  publicBlockWidths,
  publicVideoProviders,
} from '@/types/public'
import { blocks } from '../fixtures/publicSnapshots'

it('mirrors the closed published block and video enum sets', () => {
  expect(publicBlockWidths).toEqual(['NARROW', 'STANDARD', 'WIDE', 'FULL'])
  expect(publicBlockAlignments).toEqual(['LEFT', 'CENTER', 'RIGHT'])
  expect(publicBlockEmphases).toEqual(['NONE', 'SOFT', 'STRONG'])
  expect(publicVideoProviders).toEqual(['youtube', 'vimeo', 'bilibili'])
  expect(blocks.every((block) => publicBlockWidths.includes(block.width))).toBe(true)
  expect(blocks.every((block) => publicBlockAlignments.includes(block.alignment))).toBe(true)
  expect(blocks.every((block) => publicBlockEmphases.includes(block.emphasis))).toBe(true)
})
