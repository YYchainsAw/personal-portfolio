import type { Locale, PageBootstrap, ProjectCard, PublicBlock, PublicMedia, PublicProject, PublicSite, PublishedEnvelope } from '@/types/public'

export const media = (id = '1'): PublicMedia => ({
  assetId: `00000000-0000-0000-0000-0000000000${id.padStart(2, '0')}`,
  variant: 'w1200',
  src: `/api/public/media/00000000-0000-0000-0000-0000000000${id.padStart(2, '0')}/w1200`,
  srcset: `/api/public/media/00000000-0000-0000-0000-0000000000${id.padStart(2, '0')}/w640 640w, /api/public/media/00000000-0000-0000-0000-0000000000${id.padStart(2, '0')}/w1200 1200w`,
  alt: `Media ${id}`, caption: `Caption ${id}`, credit: 'Studio A', sourceUrl: 'https://example.com/source',
  width: 1200, height: 800,
})

export const site = (locale: Locale): PublicSite => ({
  identity: { monogram: 'YJX', displayName: locale === 'zh-CN' ? '易嘉轩' : 'Yi Jiaxuan', secondaryName: 'Yi Jiaxuan', email: 'hi@yychainsaw.xyz' },
  seo: { title: locale === 'zh-CN' ? '易嘉轩 · 游戏开发' : 'Yi Jiaxuan · Game Developer', description: 'Published portfolio' },
  accessibility: { skip: 'Skip', primaryNav: 'Primary', mobileNav: 'Mobile', openMenu: 'Open', closeMenu: 'Close', language: 'Language', backToTop: 'Top', projectTags: 'Tags' },
  navigation: [
    { target: '#about', sortOrder: 0, label: 'About' }, { target: '#work', sortOrder: 1, label: 'Work' },
    { target: '#roadmap', sortOrder: 2, label: 'Roadmap' }, { target: '#contact', sortOrder: 3, label: 'Contact' },
  ],
  hero: { eyebrow: 'Portfolio', displayName: locale === 'zh-CN' ? '易嘉轩' : 'Yi Jiaxuan', secondaryName: 'Yi Jiaxuan', role: 'Game developer', headline: 'Build playable worlds', introduction: 'Learning Unreal Engine.', availability: 'Available', primaryCta: 'Work', secondaryCta: 'Roadmap', visualLabel: 'Visual', stageLabel: 'UE study', objectPosition: '50% 50%', credit: 'Studio A', sourceUrl: 'https://example.com/source', media: media('1') },
  about: { label: 'About', title: 'About me', statement: 'JXNU student', focusLabel: 'Focus', focusTitle: 'Unreal Engine', focusIntro: 'Learning', facts: [{ label: 'School', value: 'JXNU' }], skills: [{ name: 'UE', status: 'Learning' }] },
  work: { label: 'Work', title: 'Selected work', introduction: 'Projects', imageNotice: 'Published media', openSlotLabel: 'Next', openSlotTitle: 'More soon', openSlotText: 'Expandable', openSlotMeta: 'Open slot' },
  roadmap: { label: 'Roadmap', title: 'Roadmap', introduction: 'Next steps', stages: [{ id: '00000000-0000-0000-0000-000000000020', number: '01', period: '2026', title: 'UE foundations', summary: 'Study', outcomes: ['Prototype'] }] },
  contact: { label: 'Contact', title: 'Say hello', introduction: 'Send a message', emailLabel: 'Email', email: 'hi@yychainsaw.xyz', workCta: 'Work', roadmapCta: 'Roadmap', footerNote: 'Built with care' },
  privacy: { title: locale === 'zh-CN' ? '隐私说明' : 'Privacy', html: '<p>Published privacy body.</p>' },
  socialLinks: [{ platform: 'GitHub', url: 'https://github.com/YYchainsAw' }],
  resume: { label: 'Resume', documentDate: '2026-07-19', href: '/api/public/media/00000000-0000-0000-0000-000000000099/document' },
})

export const card = (locale: Locale): ProjectCard => ({
  projectId: '00000000-0000-0000-0000-000000000001', slug: 'ue-study', number: '01', sortOrder: 0,
  featured: true, status: 'Published', eyebrow: 'Project', title: locale === 'zh-CN' ? 'UE 学习项目' : 'UE Study',
  summary: 'A published project', tags: ['UE5'], cover: media('2'),
})

const blockBase = { width: 'WIDE', alignment: 'LEFT', emphasis: 'NONE', columns: 2 } as const
export const blocks: PublicBlock[] = [
  { ...blockBase, id: '00000000-0000-0000-0000-000000000100', type: 'MARKDOWN', sortOrder: 0, payload: { type: 'MARKDOWN', html: '<p>Safe published HTML</p>' } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000101', type: 'IMAGE', sortOrder: 1, emphasis: 'STRONG', payload: { type: 'IMAGE', media: media('3') } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000102', type: 'GALLERY', sortOrder: 2, payload: { type: 'GALLERY', media: [media('4')] } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000103', type: 'VIDEO', sortOrder: 3, payload: { type: 'VIDEO', provider: 'youtube', embedUrl: 'https://www.youtube.com/embed/dQw4w9WgXcQ', cover: media('5'), title: 'Demo', description: 'Video' } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000104', type: 'CODE', sortOrder: 4, payload: { type: 'CODE', code: '<Actor>value</Actor>', language: 'cpp', showLineNumbers: true, title: 'Code', description: 'Snippet' } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000105', type: 'QUOTE', sortOrder: 5, payload: { type: 'QUOTE', quote: 'Play first.', source: 'Designer' } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000106', type: 'METRICS', sortOrder: 6, emphasis: 'STRONG', payload: { type: 'METRICS', metrics: [{ id: '00000000-0000-0000-0000-000000000200', numericValue: 60, label: 'FPS', value: '60', suffix: '' }] } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000107', type: 'DOWNLOAD', sortOrder: 7, payload: { type: 'DOWNLOAD', href: '/api/public/media/00000000-0000-0000-0000-000000000006/document', label: 'Demo', description: 'Download', mimeType: 'application/zip', byteSize: 1_572_864 } },
  { ...blockBase, id: '00000000-0000-0000-0000-000000000108', type: 'LINK', sortOrder: 8, payload: { type: 'LINK', href: 'https://example.com', openNewTab: true, label: 'Source', description: 'External' } },
]

export const project = (locale: Locale): PublicProject => ({
  projectId: card(locale).projectId, slug: 'ue-study', number: '01', featured: true, status: 'Published', eyebrow: 'Project',
  title: card(locale).title, summary: 'A published project', seoTitle: `${card(locale).title} · Portfolio`, seoDescription: 'Project SEO',
  tags: ['UE5'], skills: ['Blueprints'], media: [media('2')], blocks,
})

export const zhSiteEnvelope: PublishedEnvelope<PublicSite> = { revisionVersion: 2, checksum: '1'.repeat(64), data: site('zh-CN') }
export const enSiteEnvelope: PublishedEnvelope<PublicSite> = { revisionVersion: 2, checksum: '2'.repeat(64), data: site('en') }
export const zhCatalogEnvelope: PublishedEnvelope<ProjectCard[]> = { revisionVersion: 2, checksum: '3'.repeat(64), data: [card('zh-CN')] }
export const enCatalogEnvelope: PublishedEnvelope<ProjectCard[]> = { revisionVersion: 2, checksum: '4'.repeat(64), data: [card('en')] }
export const zhProjectEnvelope: PublishedEnvelope<PublicProject> = { revisionVersion: 2, checksum: '5'.repeat(64), data: project('zh-CN') }
export const enProjectEnvelope: PublishedEnvelope<PublicProject> = { revisionVersion: 2, checksum: '6'.repeat(64), data: project('en') }

export const homeInitialPayload: PageBootstrap = { kind: 'home', locale: 'zh-CN', site: zhSiteEnvelope.data, catalog: zhCatalogEnvelope.data }
export const projectInitialPayload: PageBootstrap = { kind: 'project', locale: 'en', site: enSiteEnvelope.data, catalog: enCatalogEnvelope.data, project: enProjectEnvelope.data }
export const privacyInitialPayload: PageBootstrap = { kind: 'privacy', locale: 'zh-CN', site: zhSiteEnvelope.data }
