import type { VersionedDraft } from '@/types/api'
import type {
  ProjectWorkspaceDto,
  TaxonomyWorkspaceDto,
} from '@/types/content'

export const PROJECT_IDS = Object.freeze({
  first: '20000000-0000-4000-8000-000000000001',
  second: '20000000-0000-4000-8000-000000000002',
  third: '20000000-0000-4000-8000-000000000003',
  tag: '21000000-0000-4000-8000-000000000001',
  secondTag: '21000000-0000-4000-8000-000000000002',
  skill: '22000000-0000-4000-8000-000000000001',
  secondSkill: '22000000-0000-4000-8000-000000000002',
  media: '23000000-0000-4000-8000-000000000001',
})

export function createTaxonomyFixtures(): {
  tags: TaxonomyWorkspaceDto[]
  skills: TaxonomyWorkspaceDto[]
} {
  return {
    tags: [
      {
        id: PROJECT_IDS.tag,
        normalizedKey: 'gameplay',
        version: 2,
        names: { 'zh-CN': '玩法', en: 'Gameplay' },
      },
      {
        id: PROJECT_IDS.secondTag,
        normalizedKey: 'systems',
        version: 1,
        names: { 'zh-CN': '系统设计', en: 'Systems' },
      },
    ],
    skills: [
      {
        id: PROJECT_IDS.skill,
        normalizedKey: 'unreal-engine',
        version: 3,
        names: { 'zh-CN': '虚幻引擎', en: 'Unreal Engine' },
      },
      {
        id: PROJECT_IDS.secondSkill,
        normalizedKey: 'cpp',
        version: 1,
        names: { 'zh-CN': 'C++', en: 'C++' },
      },
    ],
  }
}

function workspace(
  overrides: Partial<ProjectWorkspaceDto> = {},
): ProjectWorkspaceDto {
  return {
    id: PROJECT_IDS.first,
    externalKey: 'project-echoes',
    slug: 'project-echoes',
    number: '01',
    sortOrder: 0,
    featured: true,
    visible: true,
    publicationDirty: true,
    version: 4,
    translations: {
      'zh-CN': {
        status: '开发中',
        eyebrow: '游戏开发',
        title: '回声计划',
        summary: '一个用于练习玩法系统与关卡节奏的原型。',
        seoTitle: '回声计划｜易嘉轩',
        seoDescription: '易嘉轩的虚幻引擎游戏原型。',
      },
      en: {
        status: 'In development',
        eyebrow: 'Game development',
        title: 'Project Echoes',
        summary: 'A prototype for practicing gameplay systems and level pacing.',
        seoTitle: 'Project Echoes | Yijiaxuan Yi',
        seoDescription: 'An Unreal Engine game prototype by Yijiaxuan Yi.',
      },
    },
    tags: [
      {
        id: PROJECT_IDS.tag,
        normalizedKey: 'gameplay',
        sortOrder: 0,
        names: { 'zh-CN': '玩法', en: 'Gameplay' },
      },
    ],
    skills: [
      {
        id: PROJECT_IDS.skill,
        normalizedKey: 'unreal-engine',
        sortOrder: 0,
        names: { 'zh-CN': '虚幻引擎', en: 'Unreal Engine' },
      },
    ],
    media: [
      {
        assetId: PROJECT_IDS.media,
        usage: 'COVER',
        sortOrder: 0,
        layout: 'wide',
        objectPosition: '50% 50%',
        credit: '易嘉轩',
        sourceUrl: 'https://yychainsaw.xyz',
      },
    ],
    blocks: [],
    ...overrides,
  }
}

export function createProjectFixture(
  overrides: Partial<ProjectWorkspaceDto> = {},
): VersionedDraft<ProjectWorkspaceDto> {
  const value = workspace(overrides)
  return structuredClone({ version: value.version, value })
}

export function createProjectCatalogFixture(): ProjectWorkspaceDto[] {
  const third = workspace({
    id: PROJECT_IDS.third,
    externalKey: 'project-atlas',
    slug: 'project-atlas',
    number: '03',
    sortOrder: 2,
    featured: false,
    visible: false,
    publicationDirty: false,
    version: 1,
    translations: {
      'zh-CN': {
        ...workspace().translations['zh-CN'],
        status: '构思中',
        title: '地图集计划',
      },
      en: {
        ...workspace().translations.en,
        status: 'Concept',
        title: 'Project Atlas',
      },
    },
  })
  const first = workspace()
  const second = workspace({
    id: PROJECT_IDS.second,
    externalKey: 'project-rift',
    slug: 'project-rift',
    number: '02',
    sortOrder: 1,
    featured: false,
    visible: true,
    publicationDirty: false,
    version: 8,
    translations: {
      'zh-CN': {
        ...workspace().translations['zh-CN'],
        status: '已完成',
        title: '裂隙原型',
      },
      en: {
        ...workspace().translations.en,
        status: 'Complete',
        title: 'Rift Prototype',
      },
    },
  })

  return structuredClone([third, second, first])
}
