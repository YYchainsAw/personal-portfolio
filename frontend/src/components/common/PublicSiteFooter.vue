<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { PhArrowUpRight as ArrowUpRight } from '@phosphor-icons/vue'
import type { Locale, PublicSite } from '@/types/public'

const props = defineProps<{
  locale: Locale
  site?: PublicSite | null
}>()

const labels = computed(() => {
  const workLabel = props.site?.navigation.find((item) => item.target === '#work')?.label
  return props.locale === 'zh-CN'
    ? {
        home: '首页',
        work: workLabel || '作品',
        privacy: '隐私说明',
        mail: '发送邮件',
        copyright: '个人作品集',
      }
    : {
        home: 'Home',
        work: workLabel || 'Work',
        privacy: 'Privacy',
        mail: 'Email',
        copyright: 'Personal portfolio',
      }
})

const displayName = computed(
  () => props.site?.identity.displayName || (props.locale === 'zh-CN' ? '易嘉轩' : 'Jiaxuan Yi'),
)
const email = computed(() => props.site?.contact.email || props.site?.identity.email || '')
const year = new Date().getFullYear()
</script>

<template>
  <footer class="public-site-footer">
    <div class="footer-primary">
      <div class="footer-identity">
        <span class="micro-label">PORTFOLIO / {{ year }}</span>
        <strong>{{ displayName }}</strong>
        <p v-if="site?.contact.footerNote">{{ site.contact.footerNote }}</p>
      </div>

      <nav :aria-label="locale === 'zh-CN' ? '页脚导航' : 'Footer navigation'">
        <RouterLink :to="{ name: 'home', params: { locale } }">{{ labels.home }}</RouterLink>
        <RouterLink :to="{ name: 'home', params: { locale }, hash: '#work' }">
          {{ labels.work }}
        </RouterLink>
        <RouterLink :to="{ name: 'privacy', params: { locale } }">{{ labels.privacy }}</RouterLink>
        <a
          v-if="email"
          :href="`mailto:${email}`"
          data-analytics-type="EMAIL_CLICK"
          data-analytics-page-key="CONTACT"
        >
          {{ labels.mail }}
          <ArrowUpRight :size="15" weight="bold" aria-hidden="true" />
        </a>
      </nav>
    </div>

    <div class="footer-meta">
      <span>© {{ year }} {{ displayName }}</span>
      <span>{{ labels.copyright }}</span>
    </div>
  </footer>
</template>

<style scoped>
.public-site-footer {
  padding: clamp(3rem, 7vw, 6.5rem) var(--page-gutter) 1.2rem;
  border-top: 1px solid var(--line);
  color: var(--ink);
  background: #080d12;
}

.footer-primary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: clamp(3rem, 9vw, 9rem);
  max-width: var(--content-max);
  margin: 0 auto clamp(3.5rem, 8vw, 7rem);
}

.footer-identity {
  display: grid;
  align-content: start;
  gap: 0.9rem;
}

.micro-label {
  color: var(--accent);
  font-size: 0.62rem;
  font-weight: 600;
  letter-spacing: 0.13em;
}

.footer-identity strong {
  max-width: 12ch;
  font-size: clamp(2.25rem, 5vw, 5.25rem);
  font-weight: 500;
  line-height: 1;
  letter-spacing: -0.06em;
}

.footer-identity p {
  max-width: 36rem;
  color: var(--muted);
  font-size: 0.8rem;
  line-height: 1.7;
}

nav {
  display: grid;
  align-content: start;
  min-width: min(18rem, 32vw);
  border-top: 1px solid var(--line);
}

nav a {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 3.2rem;
  padding: 0.65rem 0;
  border-bottom: 1px solid var(--line);
  color: #c7d0d8;
  font-size: 0.74rem;
  font-weight: 600;
  transition:
    color 180ms ease,
    padding 180ms ease;
}

nav a:hover,
nav a:focus-visible {
  padding-left: 0.5rem;
  color: var(--accent);
}

.footer-meta {
  display: flex;
  justify-content: space-between;
  max-width: var(--content-max);
  margin: 0 auto;
  padding-top: 1rem;
  border-top: 1px solid var(--line);
  color: #6f7c87;
  font-size: 0.61rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

@media (max-width: 700px) {
  .footer-primary {
    grid-template-columns: 1fr;
    gap: 2.5rem;
  }

  nav {
    min-width: 0;
  }

  .footer-meta {
    display: grid;
    gap: 0.35rem;
  }
}
</style>
