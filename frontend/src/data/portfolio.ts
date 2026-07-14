import type { Locale, Localized } from '@/composables/useLocale'

export type ProjectAsset = {
  id: string
  image: string
  layout: 'wide' | 'standard'
  objectPosition: string
  credit: string
  sourceUrl: string
  alt: Localized<string>
}

export type ProjectCopy = {
  id: string
  number: string
  status: string
  eyebrow: string
  title: string
  summary: string
  tags: string[]
}

export type RoadmapStage = {
  id: string
  number: string
  period: string
  title: string
  summary: string
  outcomes: string[]
}

export type PortfolioCopy = {
  seo: { title: string; description: string }
  a11y: {
    skip: string
    primaryNav: string
    mobileNav: string
    openMenu: string
    closeMenu: string
    language: string
    backToTop: string
    projectTags: string
  }
  nav: { about: string; work: string; roadmap: string; contact: string }
  hero: {
    eyebrow: string
    displayName: string
    secondaryName: string
    role: string
    headline: string
    introduction: string
    availability: string
    primaryCta: string
    secondaryCta: string
    visualLabel: string
    stageLabel: string
  }
  about: {
    label: string
    title: string
    statement: string
    focusLabel: string
    focusTitle: string
    focusIntro: string
    facts: Array<{ label: string; value: string }>
    skills: Array<{ name: string; status: string }>
  }
  work: {
    label: string
    title: string
    introduction: string
    imageNotice: string
    openSlotLabel: string
    openSlotTitle: string
    openSlotText: string
    openSlotMeta: string
  }
  projects: ProjectCopy[]
  roadmap: {
    label: string
    title: string
    introduction: string
    stages: RoadmapStage[]
  }
  contact: {
    label: string
    title: string
    introduction: string
    emailLabel: string
    email: string
    workCta: string
    roadmapCta: string
    footerNote: string
  }
}

export const identity = {
  monogram: 'YJX',
  nameZh: '易嘉轩',
  nameEn: 'Jiaxuan Yi',
  email: 'your-email@example.com',
}

export const heroAsset = {
  image: '/images/game-dev-hero.jpg',
  objectPosition: 'center',
  credit: 'Zulian Firmansyah',
  sourceUrl: 'https://unsplash.com/photos/a-minimalist-room-with-a-surreal-background-zFLn5fDZYw0',
  alt: {
    'zh-CN': '蓝白色超现实 3D 创作工作室与电脑工作台',
    en: 'A bright blue-and-white surreal 3D creative studio with a computer workstation',
  },
} satisfies Omit<ProjectAsset, 'id' | 'layout'>

export const projectAssets: ProjectAsset[] = [
  {
    id: 'ue-environment-study',
    image: '/images/ue-environment-study.jpg',
    layout: 'wide',
    objectPosition: 'center',
    credit: 'BROTHERHOOD STUDIO',
    sourceUrl:
      'https://unsplash.com/photos/a-small-island-with-palm-trees-in-calm-water-VU7z8SQsBTo',
    alt: {
      'zh-CN': '平静水面上的极简 3D 热带小岛与棕榈树',
      en: 'A minimalist 3D tropical island with palm trees reflected in calm water',
    },
  },
  {
    id: 'gameplay-prototype',
    image: '/images/gameplay-prototype.jpg',
    layout: 'standard',
    objectPosition: 'center 62%',
    credit: 'Tima Miroshnichenko',
    sourceUrl: 'https://www.pexels.com/photo/a-person-reaching-for-a-game-controller-7047667/',
    alt: {
      'zh-CN': '手在明亮白色空间中伸向米黄色游戏手柄',
      en: 'A hand reaching for a beige game controller in a bright white studio',
    },
  },
  {
    id: 'development-log',
    image: '/images/development-log.jpg',
    layout: 'standard',
    objectPosition: '35% 55%',
    credit: 'Daniil Komov',
    sourceUrl:
      'https://www.pexels.com/photo/modern-workspace-with-laptop-and-code-display-34804001/',
    alt: {
      'zh-CN': '明亮现代办公室中打开代码编辑器的笔记本电脑',
      en: 'An open laptop displaying code in a bright, modern office workspace',
    },
  },
]

export const portfolioContent: Localized<PortfolioCopy> = {
  'zh-CN': {
    seo: {
      title: '易嘉轩 - 游戏开发与 Unreal Engine 学习作品集',
      description:
        '易嘉轩的中英双语游戏开发作品集，记录 Unreal Engine 5 学习、玩法原型、开发日志与成长路线。',
    },
    a11y: {
      skip: '跳到主要内容',
      primaryNav: '主要导航',
      mobileNav: '移动端导航',
      openMenu: '打开导航菜单',
      closeMenu: '关闭导航菜单',
      language: '选择网站语言',
      backToTop: '返回顶部',
      projectTags: '项目标签',
    },
    nav: { about: '关于', work: '作品', roadmap: '路线', contact: '联系' },
    hero: {
      eyebrow: '江西师范大学 / 游戏开发',
      displayName: '易嘉轩',
      secondaryName: 'Jiaxuan Yi',
      role: '游戏开发者 / Unreal Engine 5 学习中',
      headline: '正在把想法做成可以玩的世界。',
      introduction:
        '即将进入大三，专注游戏开发。我用 Blueprint 快速验证玩法，并逐步深入 C++、Gameplay Framework、AI、动画与性能优化。',
      availability: '期待 Game Jam、协作与实习机会',
      primaryCta: '查看学习与作品',
      secondaryCta: '我的发展路线',
      visualLabel: '视觉概念图 / 之后替换为本人 UE 截图',
      stageLabel: '即将进入大三',
    },
    about: {
      label: '01 / 关于我',
      title: '先做出可玩的东西，再让它变得更好。',
      statement:
        '现阶段我不追求堆砌项目，而是持续完成可玩、可复现、能清楚说明设计与技术取舍的小型作品。',
      focusLabel: '当前学习重点',
      focusTitle: '从 Blueprint 原型到可维护的 Gameplay 系统',
      focusIntro: '每一个学习主题都要落到一个可运行的 Demo、一次记录或一次复盘。',
      facts: [
        { label: '学校', value: '江西师范大学' },
        { label: '阶段', value: '即将进入大三' },
        { label: '当前重点', value: 'Unreal Engine 5' },
        { label: '目标方向', value: 'Gameplay Programmer / Technical Designer' },
      ],
      skills: [
        { name: 'UE5 & Blueprint', status: '持续练习' },
        { name: 'C++', status: '正在深入' },
        { name: 'Gameplay Framework', status: '当前重点' },
        { name: 'AI & Animation', status: '下一阶段' },
        { name: 'Performance Profiling', status: '下一阶段' },
        { name: 'Git + LFS', status: '项目基础' },
      ],
    },
    work: {
      label: '02 / 学习与作品',
      title: '作品会随着能力一起长大。',
      introduction:
        '这里不包装不存在的成品。每个位置都对应一个明确的学习目标，并能在未来直接扩展为完整案例页。',
      imageNotice: '概念占位图，之后替换为本人 UE 截图',
      openSlotLabel: '04 / OPEN SLOT',
      openSlotTitle: '下一个项目的位置',
      openSlotText: '数据结构已经预留。新增项目时只需补充标题、状态、说明、标签与截图。',
      openSlotMeta: '可扩展为 Game Jam、团队项目或技术专项 Demo',
    },
    projects: [
      {
        id: 'ue-environment-study',
        number: '01',
        status: '学习中',
        eyebrow: 'UE5 场景 / 交互',
        title: 'UE5 场景与交互习作',
        summary: '练习关卡搭建、材质、灯光、角色移动与基础交互，并记录每次迭代中的视觉与性能取舍。',
        tags: ['UE5', 'Blueprint', 'Level Design'],
      },
      {
        id: 'gameplay-prototype',
        number: '02',
        status: '计划中',
        eyebrow: 'Gameplay / Systems',
        title: '游戏机制原型',
        summary:
          '围绕一个核心机制制作 5-10 分钟可玩原型，覆盖输入、状态、UI 与 Gameplay Framework。',
        tags: ['C++', 'Blueprint', 'Gameplay'],
      },
      {
        id: 'development-log',
        number: '03',
        status: '持续更新',
        eyebrow: 'Devlog / Breakdown',
        title: '开发日志与技术拆解',
        summary: '用中英文记录问题、实现方案、性能数据与复盘，让每次学习都留下可复现的证据。',
        tags: ['Devlog', 'Git + LFS', 'Profiling'],
      },
    ],
    roadmap: {
      label: '03 / 发展路线',
      title: '从现在到毕业，我会这样推进。',
      introduction: '路线以可玩成果、公开记录和真实反馈为衡量标准，而不是只统计学过多少教程。',
      stages: [
        {
          id: 'now',
          number: '01',
          period: '现在',
          title: '打牢 UE5 基础',
          summary: '完成第一个可以从头到尾运行的小型原型。',
          outcomes: [
            '5-10 分钟可玩 Demo',
            '用 C++ 重构一个 Blueprint 功能',
            'Git + LFS、README 与 4 篇开发记录',
          ],
        },
        {
          id: 'six-months',
          number: '02',
          period: '未来 6 个月',
          title: '完成玩法切片',
          summary: '把系统、表现和性能放进同一个可玩的体验里。',
          outcomes: [
            '10-20 分钟 Vertical Slice',
            'AI、动画状态机与反馈系统',
            '2 次 Game Jam 与项目复盘',
          ],
        },
        {
          id: 'junior-year',
          number: '03',
          period: '大三',
          title: '团队项目与实习准备',
          summary: '证明自己能协作、交付，并清楚解释个人贡献。',
          outcomes: [
            '1 个公开团队 Demo',
            '代表作、演示视频与技术拆解',
            '双语简历、模拟面试与定向投递',
          ],
        },
        {
          id: 'graduation',
          number: '04',
          period: '大四 / 毕业前',
          title: '形成求职作品集',
          summary: '用少而强的项目展现完整的开发能力与成长轨迹。',
          outcomes: [
            '2-3 个精选项目',
            '至少 10 名玩家测试与两轮迭代',
            '统一作品集、GitHub、简历与 Demo Reel',
          ],
        },
      ],
    },
    contact: {
      label: '04 / 保持联系',
      title: '一起做点好玩的。',
      introduction: '欢迎交流 Unreal Engine、Game Jam、学生项目与游戏开发实习机会。',
      emailLabel: '联系邮箱（待替换）',
      email: identity.email,
      workCta: '查看作品位置',
      roadmapCta: '查看发展路线',
      footerNote: '在江西学习，在游戏世界里持续探索。',
    },
  },
  en: {
    seo: {
      title: 'Jiaxuan Yi - Game Development & Unreal Engine Portfolio',
      description:
        'Jiaxuan Yi bilingual game development portfolio, documenting Unreal Engine 5 studies, gameplay prototypes, devlogs, and a practical growth roadmap.',
    },
    a11y: {
      skip: 'Skip to main content',
      primaryNav: 'Primary navigation',
      mobileNav: 'Mobile navigation',
      openMenu: 'Open navigation menu',
      closeMenu: 'Close navigation menu',
      language: 'Choose site language',
      backToTop: 'Back to top',
      projectTags: 'Project tags',
    },
    nav: { about: 'About', work: 'Work', roadmap: 'Roadmap', contact: 'Contact' },
    hero: {
      eyebrow: 'Jiangxi Normal University / Game Development',
      displayName: 'Jiaxuan Yi',
      secondaryName: '易嘉轩',
      role: 'Game Developer / Learning Unreal Engine 5',
      headline: 'Turning ideas into worlds you can play.',
      introduction:
        'I am a soon-to-be junior focused on game development. I prototype quickly with Blueprints while progressing into C++, Gameplay Framework, AI, animation, and optimization.',
      availability: 'Open to game jams, collaboration, and internships',
      primaryCta: 'Explore my work',
      secondaryCta: 'See my roadmap',
      visualLabel: 'Visual concept image / replace with my own UE capture',
      stageLabel: 'Soon-to-be junior',
    },
    about: {
      label: '01 / ABOUT',
      title: 'Make it playable first. Then make it better.',
      statement:
        'At this stage I am not collecting projects for volume. I am building focused, reproducible work that clearly explains the design and technical trade-offs behind it.',
      focusLabel: 'CURRENT FOCUS',
      focusTitle: 'From Blueprint prototypes to maintainable gameplay systems',
      focusIntro:
        'Every topic should end in a running demo, a clear note, or an honest postmortem.',
      facts: [
        { label: 'University', value: 'Jiangxi Normal University' },
        { label: 'Stage', value: 'Soon-to-be junior' },
        { label: 'Current focus', value: 'Unreal Engine 5' },
        { label: 'Target roles', value: 'Gameplay Programmer / Technical Designer' },
      ],
      skills: [
        { name: 'UE5 & Blueprint', status: 'Practicing' },
        { name: 'C++', status: 'Going deeper' },
        { name: 'Gameplay Framework', status: 'Current focus' },
        { name: 'AI & Animation', status: 'Next phase' },
        { name: 'Performance Profiling', status: 'Next phase' },
        { name: 'Git + LFS', status: 'Project foundation' },
      ],
    },
    work: {
      label: '02 / LEARNING & WORK',
      title: 'The portfolio will grow with the skills.',
      introduction:
        'Nothing here pretends to be a finished commercial project. Every slot maps to a real learning goal and can expand into a full case study later.',
      imageNotice: 'Concept placeholder - to be replaced with my own UE capture',
      openSlotLabel: '04 / OPEN SLOT',
      openSlotTitle: 'Space for the next project',
      openSlotText:
        'The data structure is ready. Add a title, status, summary, tags, and screenshots to publish another case study.',
      openSlotMeta: 'Ready for a game jam, team project, or focused technical demo',
    },
    projects: [
      {
        id: 'ue-environment-study',
        number: '01',
        status: 'In progress',
        eyebrow: 'UE5 Environment / Interaction',
        title: 'UE5 Environment & Interaction Study',
        summary:
          'Practicing level assembly, materials, lighting, character movement, and basic interactions while documenting visual and performance trade-offs.',
        tags: ['UE5', 'Blueprint', 'Level Design'],
      },
      {
        id: 'gameplay-prototype',
        number: '02',
        status: 'Planned',
        eyebrow: 'Gameplay / Systems',
        title: 'Gameplay Mechanic Prototype',
        summary:
          'A focused 5-10 minute prototype built around one core mechanic, covering input, state, UI, and the Gameplay Framework.',
        tags: ['C++', 'Blueprint', 'Gameplay'],
      },
      {
        id: 'development-log',
        number: '03',
        status: 'Ongoing',
        eyebrow: 'Devlog / Breakdown',
        title: 'Development Logs & Technical Breakdowns',
        summary:
          'Bilingual notes on problems, implementations, profiling data, and postmortems so every study leaves reproducible evidence.',
        tags: ['Devlog', 'Git + LFS', 'Profiling'],
      },
    ],
    roadmap: {
      label: '03 / ROADMAP',
      title: 'A practical path from now to graduation.',
      introduction:
        'Progress is measured by playable outcomes, public documentation, and real feedback - not by the number of tutorials completed.',
      stages: [
        {
          id: 'now',
          number: '01',
          period: 'Now',
          title: 'Build the UE5 foundation',
          summary: 'Finish a small prototype that can be played from beginning to end.',
          outcomes: [
            'A 5-10 minute playable demo',
            'Rebuild one Blueprint feature in C++',
            'Git + LFS, README, and four devlogs',
          ],
        },
        {
          id: 'six-months',
          number: '02',
          period: 'Next 6 months',
          title: 'Complete a vertical slice',
          summary: 'Bring systems, presentation, and performance into one playable experience.',
          outcomes: [
            'A 10-20 minute vertical slice',
            'AI, animation state, and feedback systems',
            'Two game jams with postmortems',
          ],
        },
        {
          id: 'junior-year',
          number: '03',
          period: 'Junior year',
          title: 'Team delivery & internship prep',
          summary: 'Show that I can collaborate, ship, and explain my own contribution.',
          outcomes: [
            'One public team demo',
            'Flagship project, video, and breakdown',
            'Bilingual resume, mock interviews, and targeted applications',
          ],
        },
        {
          id: 'graduation',
          number: '04',
          period: 'Senior year / Before graduation',
          title: 'Create a job-ready portfolio',
          summary: 'Use a few strong projects to show complete development ability and growth.',
          outcomes: [
            'Two to three curated projects',
            'Ten playtesters and two feedback iterations',
            'Aligned portfolio, GitHub, resume, and demo reel',
          ],
        },
      ],
    },
    contact: {
      label: '04 / CONTACT',
      title: 'Let us build something playable.',
      introduction:
        'I would love to talk about Unreal Engine, game jams, student projects, and game development internships.',
      emailLabel: 'Email placeholder',
      email: identity.email,
      workCta: 'View project slots',
      roadmapCta: 'View the roadmap',
      footerNote: 'Studying in Jiangxi, exploring through game worlds.',
    },
  },
}

export const getCopy = (locale: Locale) => portfolioContent[locale]
