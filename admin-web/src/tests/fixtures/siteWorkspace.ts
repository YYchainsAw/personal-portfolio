import type { VersionedDraft } from '@/types/api'
import type { SiteWorkspaceDto } from '@/types/content'

const ids = {
  hero: '10000000-0000-0000-0000-000000000001',
  heroMedia: '20000000-0000-0000-0000-000000000001',
  navWork: '30000000-0000-0000-0000-000000000001',
  navRoadmap: '30000000-0000-0000-0000-000000000002',
  factSchool: '40000000-0000-0000-0000-000000000001',
  factFocus: '40000000-0000-0000-0000-000000000002',
  skillGame: '50000000-0000-0000-0000-000000000001',
  skillUe: '50000000-0000-0000-0000-000000000002',
  stageNow: '60000000-0000-0000-0000-000000000001',
  stageNext: '60000000-0000-0000-0000-000000000002',
  outcomeOne: '70000000-0000-0000-0000-000000000001',
  outcomeTwo: '70000000-0000-0000-0000-000000000002',
  outcomeThree: '70000000-0000-0000-0000-000000000003',
  socialGithub: '80000000-0000-0000-0000-000000000001',
  socialBilibili: '80000000-0000-0000-0000-000000000002',
  resumeZh: '90000000-0000-0000-0000-000000000001',
  resumeEn: '90000000-0000-0000-0000-000000000002',
  resumeZhMedia: 'a0000000-0000-0000-0000-000000000001',
  resumeEnMedia: 'a0000000-0000-0000-0000-000000000002',
} as const

export function createSiteWorkspace(): SiteWorkspaceDto {
  return {
    siteId: '00000000-0000-0000-0000-000000000001',
    version: 4,
    monogram: 'YJX',
    email: 'hello@yychainsaw.xyz',
    identity: {
      'zh-CN': { displayName: '易嘉轩', secondaryName: 'YYchainsaw' },
      en: { displayName: 'Yixuan Yi', secondaryName: 'YYchainsaw' },
    },
    seo: {
      'zh-CN': { title: '易嘉轩｜游戏开发作品集', description: '游戏开发与 Unreal Engine 学习作品。' },
      en: { title: 'Yixuan Yi | Game Developer', description: 'Game development and Unreal Engine work.' },
    },
    accessibility: {
      'zh-CN': {
        skip: '跳到主要内容', primaryNav: '主导航', mobileNav: '移动导航', openMenu: '打开菜单', closeMenu: '关闭菜单', language: '切换语言', backToTop: '返回顶部', projectTags: '项目标签',
      },
      en: {
        skip: 'Skip to content', primaryNav: 'Primary navigation', mobileNav: 'Mobile navigation', openMenu: 'Open menu', closeMenu: 'Close menu', language: 'Change language', backToTop: 'Back to top', projectTags: 'Project tags',
      },
    },
    navigation: [
      { id: ids.navWork, target: 'work', sortOrder: 0, visible: true, labels: { 'zh-CN': '作品', en: 'Work' } },
      { id: ids.navRoadmap, target: 'roadmap', sortOrder: 1, visible: true, labels: { 'zh-CN': '路线图', en: 'Roadmap' } },
    ],
    hero: {
      id: ids.hero,
      version: 2,
      mediaAssetId: ids.heroMedia,
      objectPosition: '50% 50%',
      credit: '易嘉轩',
      sourceUrl: 'https://yychainsaw.xyz',
      copy: {
        'zh-CN': {
          eyebrow: '游戏开发学生', displayName: '易嘉轩', secondaryName: 'YYchainsaw', role: '游戏开发 / UE 学习者', headline: '把系统做扎实，把体验做明亮。', introduction: '江西师范大学学生，正在学习 Unreal Engine。', availability: '开放实习与合作', primaryCta: '查看作品', secondaryCta: '成长路线', visualLabel: '个人视觉', stageLabel: '当前阶段',
        },
        en: {
          eyebrow: 'Game development student', displayName: 'Yixuan Yi', secondaryName: 'YYchainsaw', role: 'Game developer / UE learner', headline: 'Solid systems, bright experiences.', introduction: 'Student at Jiangxi Normal University learning Unreal Engine.', availability: 'Open to internships and collaboration', primaryCta: 'View work', secondaryCta: 'Roadmap', visualLabel: 'Profile visual', stageLabel: 'Current stage',
        },
      },
    },
    about: {
      'zh-CN': { label: '关于', title: '持续学习的游戏开发者', statement: '我喜欢把创意落实成可玩的系统。', focusLabel: '当前专注', focusTitle: 'Unreal Engine', focusIntro: '从蓝图、C++ 与玩法原型开始。' },
      en: { label: 'About', title: 'A game developer who keeps learning', statement: 'I turn ideas into playable systems.', focusLabel: 'Current focus', focusTitle: 'Unreal Engine', focusIntro: 'Starting with Blueprints, C++, and gameplay prototypes.' },
    },
    facts: [
      { id: ids.factSchool, externalKey: 'school', sortOrder: 0, copy: { 'zh-CN': { label: '学校', value: '江西师范大学' }, en: { label: 'University', value: 'Jiangxi Normal University' } } },
      { id: ids.factFocus, externalKey: 'focus', sortOrder: 1, copy: { 'zh-CN': { label: '方向', value: '游戏开发' }, en: { label: 'Focus', value: 'Game development' } } },
    ],
    profileSkills: [
      { id: ids.skillGame, externalKey: 'game-development', sortOrder: 0, copy: { 'zh-CN': { name: '游戏开发', status: '持续实践' }, en: { name: 'Game development', status: 'Practising' } } },
      { id: ids.skillUe, externalKey: 'unreal-engine', sortOrder: 1, copy: { 'zh-CN': { name: 'Unreal Engine', status: '正在学习' }, en: { name: 'Unreal Engine', status: 'Learning' } } },
    ],
    work: {
      'zh-CN': { label: '作品', title: '项目与实验', introduction: '这里会持续加入完整项目。', imageNotice: '图片来自媒体库。', openSlotLabel: '扩展位', openSlotTitle: '下一项作品', openSlotText: '为未来项目预留。', openSlotMeta: '持续更新' },
      en: { label: 'Work', title: 'Projects and experiments', introduction: 'Complete projects will be added here.', imageNotice: 'Images come from the media library.', openSlotLabel: 'Open slot', openSlotTitle: 'Next project', openSlotText: 'Reserved for future work.', openSlotMeta: 'Continuously updated' },
    },
    roadmap: {
      header: {
        'zh-CN': { label: '路线图', title: '接下来的成长', introduction: '按阶段积累作品与工程能力。' },
        en: { label: 'Roadmap', title: 'What comes next', introduction: 'Build work and engineering skills stage by stage.' },
      },
      stages: [
        {
          id: ids.stageNow, externalKey: 'now', number: '01', sortOrder: 0, visible: true,
          copy: { 'zh-CN': { period: '现在', title: 'UE 基础', summary: '完成玩法原型。' }, en: { period: 'Now', title: 'UE foundations', summary: 'Complete gameplay prototypes.' } },
          outcomes: [
            { id: ids.outcomeOne, sortOrder: 0, text: { 'zh-CN': '掌握蓝图工作流', en: 'Learn Blueprint workflows' } },
            { id: ids.outcomeTwo, sortOrder: 1, text: { 'zh-CN': '完成一个可玩原型', en: 'Finish a playable prototype' } },
          ],
        },
        {
          id: ids.stageNext, externalKey: 'next', number: '02', sortOrder: 1, visible: true,
          copy: { 'zh-CN': { period: '下一阶段', title: '团队项目', summary: '参与完整开发流程。' }, en: { period: 'Next', title: 'Team project', summary: 'Join a full development cycle.' } },
          outcomes: [{ id: ids.outcomeThree, sortOrder: 0, text: { 'zh-CN': '发布可展示版本', en: 'Ship a presentable build' } }],
        },
      ],
    },
    contact: {
      'zh-CN': { label: '联系', title: '一起做点好玩的', introduction: '欢迎交流游戏开发与实习机会。', emailLabel: '邮箱', workCta: '查看作品', roadmapCta: '查看路线图', footerNote: '感谢来访。' },
      en: { label: 'Contact', title: 'Let us build something fun', introduction: 'Open to game development conversations and internships.', emailLabel: 'Email', workCta: 'View work', roadmapCta: 'View roadmap', footerNote: 'Thanks for visiting.' },
    },
    privacy: {
      'zh-CN': { title: '隐私说明', bodyMarkdown: '仅收集必要的访问统计。' },
      en: { title: 'Privacy', bodyMarkdown: 'Only essential analytics are collected.' },
    },
    socialLinks: [
      { id: ids.socialGithub, platform: 'GitHub', url: 'https://github.com/YYchainsAw', sortOrder: 0, visible: true },
      { id: ids.socialBilibili, platform: 'Bilibili', url: 'https://space.bilibili.com', sortOrder: 1, visible: true },
    ],
    resumes: [
      { id: ids.resumeZh, locale: 'zh-CN', mediaAssetId: ids.resumeZhMedia, versionLabel: '2026.07', current: true, documentDate: '2026-07-01' },
      { id: ids.resumeEn, locale: 'en', mediaAssetId: ids.resumeEnMedia, versionLabel: '2026.07', current: true, documentDate: '2026-07-01' },
    ],
  }
}

export function createSiteFixture(): VersionedDraft<SiteWorkspaceDto> {
  return { version: 4, value: createSiteWorkspace() }
}

export const siteFixture = createSiteFixture()
