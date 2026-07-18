interface OrderedItem {
  readonly id: string
  readonly sortOrder: number
}

export function renumberBlocks<T extends OrderedItem>(items: readonly T[]): T[] {
  return items.map((item, sortOrder) => ({ ...item, sortOrder }))
}

export function moveBlock<T extends OrderedItem>(
  items: readonly T[],
  id: string,
  targetIndex: number,
): T[] {
  const currentIndex = items.findIndex((item) => item.id === id)
  if (currentIndex < 0) return renumberBlocks(items)

  const next = items.map((item) => ({ ...item }))
  const [moved] = next.splice(currentIndex, 1)
  if (moved === undefined) return renumberBlocks(next)

  const safeTarget = Number.isFinite(targetIndex)
    ? Math.max(0, Math.min(Math.trunc(targetIndex), next.length))
    : currentIndex
  next.splice(safeTarget, 0, moved)
  return renumberBlocks(next)
}

export function removeBlock<T extends OrderedItem>(items: readonly T[], id: string): T[] {
  return renumberBlocks(items.filter((item) => item.id !== id))
}

export function replaceBlock<T extends OrderedItem>(
  items: readonly T[],
  id: string,
  replacement: T,
): T[] {
  return items.map((item) =>
    item.id === id
      ? ({ ...replacement, id: item.id, sortOrder: item.sortOrder } as T)
      : item,
  )
}
