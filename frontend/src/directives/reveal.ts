import type { ObjectDirective } from 'vue'

const observers = new WeakMap<HTMLElement, IntersectionObserver>()

export const revealDirective: ObjectDirective<HTMLElement> = {
  mounted(element) {
    element.classList.add('reveal')
    if (!('IntersectionObserver' in window) || matchMedia('(prefers-reduced-motion: reduce)').matches) {
      element.classList.add('is-visible')
      return
    }
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry?.isIntersecting) return
      element.classList.add('is-visible')
      observer.disconnect()
      observers.delete(element)
    }, { rootMargin: '0px 0px -7% 0px', threshold: 0.08 })
    observers.set(element, observer)
    observer.observe(element)
  },
  unmounted(element) {
    observers.get(element)?.disconnect()
    observers.delete(element)
  },
}
