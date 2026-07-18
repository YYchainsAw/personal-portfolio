import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem } from '@/types/api'
import type { SiteWorkspaceDto } from '@/types/content'

import { http } from './http'
import { siteApi } from './siteApi'

const siteResponse = {
  siteId: '00000000-0000-0000-0000-000000000001',
  version: 7,
  monogram: 'YJX',
  email: 'hello@yychainsaw.xyz',
  identity: {
    'zh-CN': { displayName: '易嘉轩', secondaryName: 'YY Chainsaw' },
    en: { displayName: 'Jiaxuan Yi', secondaryName: 'YY Chainsaw' },
  },
  seo: {
    'zh-CN': { title: '易嘉轩的作品集', description: '游戏开发作品集' },
    en: { title: 'Jiaxuan Yi Portfolio', description: 'Game development portfolio' },
  },
  accessibility: {
    'zh-CN': {
      skip: '跳到正文',
      primaryNav: '主导航',
      mobileNav: '移动导航',
      openMenu: '打开菜单',
      closeMenu: '关闭菜单',
      language: '语言',
      backToTop: '返回顶部',
      projectTags: '项目标签',
    },
    en: {
      skip: 'Skip to content',
      primaryNav: 'Primary navigation',
      mobileNav: 'Mobile navigation',
      openMenu: 'Open menu',
      closeMenu: 'Close menu',
      language: 'Language',
      backToTop: 'Back to top',
      projectTags: 'Project tags',
    },
  },
  navigation: [
    {
      id: '10000000-0000-0000-0000-000000000001',
      target: 'work',
      sortOrder: 0,
      visible: true,
      labels: { 'zh-CN': '作品', en: 'Work' },
    },
  ],
  hero: {
    id: '20000000-0000-0000-0000-000000000001',
    version: 2,
    mediaAssetId: null,
    objectPosition: null,
    credit: null,
    sourceUrl: null,
    copy: {
      'zh-CN': {
        eyebrow: '你好',
        displayName: '易嘉轩',
        secondaryName: 'YY Chainsaw',
        role: '游戏开发学习者',
        headline: '制作有趣的互动体验',
        introduction: '正在学习 Unreal Engine。',
        availability: '欢迎交流',
        primaryCta: '查看作品',
        secondaryCta: '了解路线图',
        visualLabel: '个人照片',
        stageLabel: '当前阶段',
      },
      en: {
        eyebrow: 'Hello',
        displayName: 'Jiaxuan Yi',
        secondaryName: 'YY Chainsaw',
        role: 'Game development learner',
        headline: 'Building playful interactive experiences',
        introduction: 'Currently learning Unreal Engine.',
        availability: 'Open to conversations',
        primaryCta: 'View work',
        secondaryCta: 'See roadmap',
        visualLabel: 'Portrait',
        stageLabel: 'Current stage',
      },
    },
  },
  about: {
    'zh-CN': {
      label: '关于',
      title: '持续学习',
      statement: '江西师范大学学生。',
      focusLabel: '方向',
      focusTitle: '游戏开发',
      focusIntro: '关注玩法与技术实现。',
    },
    en: {
      label: 'About',
      title: 'Always learning',
      statement: 'A student at Jiangxi Normal University.',
      focusLabel: 'Focus',
      focusTitle: 'Game development',
      focusIntro: 'Interested in gameplay and implementation.',
    },
  },
  facts: [
    {
      id: '30000000-0000-0000-0000-000000000001',
      externalKey: 'education',
      sortOrder: 0,
      copy: {
        'zh-CN': { label: '学校', value: '江西师范大学' },
        en: { label: 'University', value: 'Jiangxi Normal University' },
      },
    },
  ],
  profileSkills: [
    {
      id: '40000000-0000-0000-0000-000000000001',
      externalKey: 'unreal-engine',
      sortOrder: 0,
      copy: {
        'zh-CN': { name: 'Unreal Engine', status: '学习中' },
        en: { name: 'Unreal Engine', status: 'Learning' },
      },
    },
  ],
  work: {
    'zh-CN': {
      label: '作品',
      title: '项目与实验',
      introduction: '这里会持续加入新作品。',
      imageNotice: '项目图片',
      openSlotLabel: '下一项',
      openSlotTitle: '扩展位',
      openSlotText: '为未来项目预留。',
      openSlotMeta: '持续更新',
    },
    en: {
      label: 'Work',
      title: 'Projects and experiments',
      introduction: 'New work will be added here.',
      imageNotice: 'Project image',
      openSlotLabel: 'Next',
      openSlotTitle: 'Open slot',
      openSlotText: 'Reserved for a future project.',
      openSlotMeta: 'Continuously updated',
    },
  },
  roadmap: {
    header: {
      'zh-CN': { label: '路线图', title: '接下来', introduction: '逐步积累能力。' },
      en: { label: 'Roadmap', title: 'What comes next', introduction: 'Growing step by step.' },
    },
    stages: [
      {
        id: '50000000-0000-0000-0000-000000000001',
        externalKey: 'learn-ue',
        number: '01',
        sortOrder: 0,
        visible: true,
        copy: {
          'zh-CN': { period: '现在', title: '学习 UE', summary: '完成可玩的原型。' },
          en: { period: 'Now', title: 'Learn UE', summary: 'Finish playable prototypes.' },
        },
        outcomes: [
          {
            id: '60000000-0000-0000-0000-000000000001',
            sortOrder: 0,
            text: { 'zh-CN': '完成第一个原型', en: 'Complete the first prototype' },
          },
        ],
      },
    ],
  },
  contact: {
    'zh-CN': {
      label: '联系',
      title: '一起交流',
      introduction: '欢迎讨论游戏开发。',
      emailLabel: '邮箱',
      workCta: '查看作品',
      roadmapCta: '查看路线图',
      footerNote: '感谢访问',
    },
    en: {
      label: 'Contact',
      title: 'Let us talk',
      introduction: 'Always happy to discuss game development.',
      emailLabel: 'Email',
      workCta: 'View work',
      roadmapCta: 'View roadmap',
      footerNote: 'Thanks for visiting',
    },
  },
  privacy: {
    'zh-CN': { title: '隐私', bodyMarkdown: '隐私说明' },
    en: { title: 'Privacy', bodyMarkdown: 'Privacy notice' },
  },
  socialLinks: [
    {
      id: '70000000-0000-0000-0000-000000000001',
      platform: 'GitHub',
      url: 'https://github.com/YYchainsAw',
      sortOrder: 0,
      visible: true,
    },
  ],
  resumes: [
    {
      id: '80000000-0000-0000-0000-000000000001',
      locale: 'zh-CN',
      mediaAssetId: '90000000-0000-0000-0000-000000000001',
      versionLabel: '2026.07',
      current: true,
      documentDate: '2026-07-18',
    },
  ],
} satisfies SiteWorkspaceDto

function withoutHeroMediaTuple(workspace: typeof siteResponse) {
  const {
    mediaAssetId: _mediaAssetId,
    objectPosition: _objectPosition,
    credit: _credit,
    sourceUrl: _sourceUrl,
    ...hero
  } = workspace.hero
  return { ...workspace, hero }
}

describe('siteApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads the exact SITE endpoint and exposes the DTO version to the draft adapter', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValueOnce({ data: siteResponse } as never)

    await expect(siteApi.get()).resolves.toEqual({
      version: 7,
      value: siteResponse,
    })
    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('/api/admin/site/workspace')
  })

  it('normalizes a production non-null GET response that omits the absent hero-media tuple', async () => {
    const response = withoutHeroMediaTuple(siteResponse)
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: response } as never)

    await expect(siteApi.get()).resolves.toMatchObject({
      version: 7,
      value: {
        hero: {
          mediaAssetId: null,
          objectPosition: null,
          credit: null,
          sourceUrl: null,
        },
      },
    })
    expect(response.hero).not.toHaveProperty('mediaAssetId')
    expect(response.hero).not.toHaveProperty('objectPosition')
    expect(response.hero).not.toHaveProperty('credit')
    expect(response.hero).not.toHaveProperty('sourceUrl')
  })

  it('saves only the backend expectedVersion/workspace envelope and uses the returned version', async () => {
    const saved = { ...siteResponse, version: 8, monogram: 'YJX!' }
    const put = vi.spyOn(http, 'put').mockResolvedValueOnce({ data: saved } as never)

    await expect(
      siteApi.save({ expectedVersion: 7, workspace: siteResponse }),
    ).resolves.toEqual({ version: 8, value: saved })

    expect(put).toHaveBeenCalledOnce()
    expect(put).toHaveBeenCalledWith('/api/admin/site/workspace', {
      expectedVersion: 7,
      workspace: siteResponse,
    })
  })

  it('normalizes a production non-null PUT response that omits the absent hero-media tuple', async () => {
    const saved = { ...siteResponse, version: 8 }
    const response = withoutHeroMediaTuple(saved)
    vi.spyOn(http, 'put').mockResolvedValueOnce({ data: response } as never)

    await expect(
      siteApi.save({ expectedVersion: 7, workspace: siteResponse }),
    ).resolves.toMatchObject({
      version: 8,
      value: {
        hero: {
          mediaAssetId: null,
          objectPosition: null,
          credit: null,
          sourceUrl: null,
        },
      },
    })
  })

  it('does not retry a failed SITE mutation', async () => {
    const conflict = new ApiProblem({
      type: 'conflict',
      title: '服务器版本已更新',
      status: 409,
      code: 'CONTENT_VERSION_CONFLICT',
      traceId: 'site-conflict',
    })
    const put = vi.spyOn(http, 'put').mockRejectedValue(conflict)

    await expect(
      siteApi.save({ expectedVersion: 7, workspace: siteResponse }),
    ).rejects.toBe(conflict)
    expect(put).toHaveBeenCalledOnce()
  })

  it.each([
    [
      'a missing locale',
      { ...siteResponse, identity: { 'zh-CN': siteResponse.identity['zh-CN'] } },
    ],
    [
      'a partial nullable hero-media tuple',
      { ...siteResponse, hero: { ...siteResponse.hero, mediaAssetId: '90000000-0000-0000-0000-000000000002' } },
    ],
    [
      'a partially omitted hero-media tuple',
      {
        ...siteResponse,
        hero: {
          ...withoutHeroMediaTuple(siteResponse).hero,
          mediaAssetId: '90000000-0000-0000-0000-000000000002',
        },
      },
    ],
    ['an unsafe version', { ...siteResponse, version: Number.MAX_SAFE_INTEGER + 1 }],
    ['a foreign SITE aggregate id', { ...siteResponse, siteId: '00000000-0000-0000-0000-000000000099' }],
  ])('rejects a successful response containing %s', async (_case, malformed) => {
    vi.spyOn(http, 'get').mockResolvedValueOnce({ data: malformed } as never)

    await expect(siteApi.get()).rejects.toMatchObject({
      body: {
        status: 0,
        code: 'INVALID_SERVER_RESPONSE',
        traceId: 'client',
      },
    })
  })
})
