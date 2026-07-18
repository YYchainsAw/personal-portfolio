<script setup lang="ts">
const destinations = [
  {
    route: 'site',
    title: '站点内容',
    description: '维护首页介绍、个人资料与中英文内容，并检查发布前的翻译完整度。',
    action: '编辑站点内容',
  },
  {
    route: 'projects',
    title: '项目',
    description: '创建、排序和编辑作品项目，用结构化内容块持续扩展你的作品展示。',
    action: '管理项目',
  },
  {
    route: 'media',
    title: '媒体库',
    description: '上传并管理高清图片和文件，查看处理状态并安全复用可用素材。',
    action: '打开媒体库',
  },
  {
    route: 'messages',
    title: '留言',
    description: '阅读访客留言、更新处理状态，并在需要时重试邮件通知。',
    action: '查看留言',
  },
  {
    route: 'analytics',
    title: '访问统计',
    description: '查看带有口径、时区与数据延迟说明的访问趋势和来源分布。',
    action: '查看访问统计',
  },
  {
    route: 'settings',
    title: '安全与运维',
    description: '维护管理员安全、会话与恢复码，并查看审计记录和只读运行状态。',
    action: '进入设置',
  },
] as const
</script>

<template>
  <section aria-labelledby="dashboard-title">
    <header class="max-w-3xl">
      <p class="text-sm font-semibold tracking-[0.18em] text-blue-600">ADMIN WORKSPACE</p>
      <h1 id="dashboard-title" class="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">
        仪表盘
      </h1>
      <p class="mt-3 text-base leading-7 text-slate-600">
        从这里进入每个完整管理区域。各区域的数据与状态会在对应页面中准确呈现。
      </p>
    </header>

    <ul class="mt-8 grid list-none gap-4 p-0 sm:grid-cols-2 xl:grid-cols-3" role="list">
      <li v-for="destination in destinations" :key="destination.route" class="min-w-0">
        <article
          class="flex h-full flex-col rounded-2xl border border-slate-200 bg-white p-6 shadow-sm transition hover:-translate-y-0.5 hover:border-blue-200 hover:shadow-md motion-reduce:transform-none motion-reduce:transition-none"
          :aria-labelledby="`dashboard-card-${destination.route}`"
        >
          <h2
            :id="`dashboard-card-${destination.route}`"
            class="text-xl font-semibold text-slate-950"
          >
            {{ destination.title }}
          </h2>
          <p class="mt-3 flex-1 text-sm leading-6 text-slate-600">{{ destination.description }}</p>
          <RouterLink
            :to="{ name: destination.route }"
            class="mt-6 inline-flex w-fit items-center rounded-full bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-800 hover:bg-blue-100"
          >
            {{ destination.action }}
            <span class="ml-2" aria-hidden="true">→</span>
          </RouterLink>
        </article>
      </li>
    </ul>
  </section>
</template>
