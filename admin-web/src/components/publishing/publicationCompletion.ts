import type { ContentBlockDto } from '@/types/blocks'
import type { Locale, ProjectWorkspaceDto, TranslationStatus } from '@/types/content'

function requiredBlockCopy(block: ContentBlockDto, locale: Locale): readonly string[] {
  if (!block.visible) return []
  const payload = block.payload
  switch (payload.type) {
    case 'MARKDOWN':
      return [payload.markdown[locale]]
    case 'IMAGE':
    case 'GALLERY':
      return []
    case 'VIDEO':
    case 'CODE':
      return [payload.copy[locale].title, payload.copy[locale].description]
    case 'QUOTE':
      return [payload.copy[locale].quote, payload.copy[locale].source]
    case 'METRICS':
      return payload.metrics.flatMap((metric) => [
        metric.copy[locale].label,
        metric.copy[locale].value,
        metric.copy[locale].suffix,
      ])
    case 'DOWNLOAD':
    case 'LINK':
      return [payload.copy[locale].label, payload.copy[locale].description]
    default:
      return assertNever(payload)
  }
}

function assertNever(value: never): never {
  throw new Error(`Unsupported publication completion payload: ${String(value)}`)
}

function requiredProjectCopy(workspace: ProjectWorkspaceDto, locale: Locale): readonly string[] {
  return [
    ...Object.values(workspace.translations[locale]),
    ...workspace.tags.map((tag) => tag.names[locale]),
    ...workspace.skills.map((skill) => skill.names[locale]),
    ...workspace.blocks.flatMap((block) => requiredBlockCopy(block, locale)),
  ]
}

function localeCompletion(
  workspace: ProjectWorkspaceDto,
  locale: Locale,
): Readonly<{ complete: number; total: number }> {
  const values = requiredProjectCopy(workspace, locale)
  return Object.freeze({
    complete: values.filter((value) => value.trim().length > 0).length,
    total: values.length,
  })
}

/**
 * Counts bilingual copy available in the project workspace. Media translation
 * completeness remains server-authoritative because only media IDs live here.
 */
export function projectPublicationCompletion(
  workspace: ProjectWorkspaceDto,
): TranslationStatus {
  return Object.freeze({
    'zh-CN': localeCompletion(workspace, 'zh-CN'),
    en: localeCompletion(workspace, 'en'),
  })
}
