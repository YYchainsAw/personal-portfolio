import type { Localized } from '@/types/public'

export const uiCopy: Localized<{
  skip: string
  loading: string
  retry: string
  error: string
  empty: string
  notFoundTitle: string
  notFoundText: string
  backHome: string
  backWork: string
  privacy: string
}> = {
  'zh-CN': {
    skip: '跳到主要内容', loading: '正在加载已发布内容…', retry: '重试',
    error: '暂时无法加载此内容。', empty: '作品正在整理中，欢迎稍后再来。',
    notFoundTitle: '页面未找到', notFoundText: '这个地址不存在，或内容尚未发布。',
    backHome: '返回首页', backWork: '返回作品', privacy: '隐私说明',
  },
  en: {
    skip: 'Skip to main content', loading: 'Loading published content…', retry: 'Retry',
    error: 'This content is temporarily unavailable.', empty: 'Projects are being prepared. Please check back soon.',
    notFoundTitle: 'Page not found', notFoundText: 'This address does not exist or has not been published.',
    backHome: 'Back home', backWork: 'Back to work', privacy: 'Privacy',
  },
}
