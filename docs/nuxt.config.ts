// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-01-01',
  ssr: true,
  devtools: { enabled: false },
  modules: ['@nuxtjs/tailwindcss'],
  css: ['~/assets/css/main.css'],
  nitro: {
    preset: 'static',
  },
  app: {
    baseURL: process.env.NUXT_APP_BASE_URL || '/',
    head: {
      title: 'chuchu',
      htmlAttrs: { lang: 'en' },
      bodyAttrs: { class: 'antialiased' },
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        {
          name: 'description',
          content:
            'chuchu is a modern, native Android SSH client powered by libghostty.',
        },
        { name: 'theme-color', content: '#1E1E2E' },
        { property: 'og:title', content: 'chuchu' },
        {
          property: 'og:description',
          content:
            'A modern, native Android SSH client powered by libghostty.',
        },
        { property: 'og:type', content: 'website' },
        { property: 'og:image', content: '/logo.png' },
      ],
      link: [
        { rel: 'icon', type: 'image/png', href: '/logo.png' },
        {
          rel: 'preconnect',
          href: 'https://fonts.googleapis.com',
        },
        {
          rel: 'preconnect',
          href: 'https://fonts.gstatic.com',
          crossorigin: '',
        },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&display=swap',
        },
      ],
      script: [
        {
          innerHTML: `
            (function() {
              var theme = localStorage.getItem('chuchu-theme');
              if (theme === 'light' || (!theme && window.matchMedia('(prefers-color-scheme: light)').matches)) {
                document.documentElement.setAttribute('data-theme', 'light');
              }
            })();
          `,
          type: 'text/javascript',
          tagPosition: 'head',
        },
      ],
    },
  },
})
