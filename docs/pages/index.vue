<script setup lang="ts">
const year = new Date().getFullYear()

const release = await $fetch('https://api.github.com/repos/jossephus/chuchu/releases/latest').catch(() => null)
const version = release?.tag_name ?? ''

const groups = [
  {
    label: 'ssh, mosh & tailscale',
    items: [
      'tailscale ssh — magicdns, passwordless',
      'classic ssh with password & key auth',
      'mosh — roaming, low-latency mobile shell',
      'sftp browser — upload, download, delete',
      'biometric lock, app-wide or per-server',
    ],
  },
  {
    label: 'a real terminal',
    items: [
      'native libghostty renderer via jni',
      'kitty image protocol — inline images',
      '400+ official ghostty themes',
      'true resize, scrollback, mouse & modifiers',
      'system clipboard copy / paste',
    ],
  },
  {
    label: 'optimized for phones',
    items: [
      'multi-session tabs with command palette',
      'configurable accessory key row',
      'custom key macros & nested key groups',
      'per-host post-connect actions',
      'chuchu key — leader prefix for app actions',
      'foreground service keeps sessions alive',
    ],
  },
]

// Theme toggle — sync with inline script in nuxt.config head
const isDark = ref(true)
const darkVideo = ref<HTMLVideoElement>()
const lightVideo = ref<HTMLVideoElement>()

function applyTheme(dark: boolean) {
  isDark.value = dark
  if (dark) {
    document.documentElement.removeAttribute('data-theme')
    localStorage.setItem('chuchu-theme', 'dark')
  } else {
    document.documentElement.setAttribute('data-theme', 'light')
    localStorage.setItem('chuchu-theme', 'light')
  }
}

let themeTransitionPending = false

function toggleTheme(event?: MouseEvent) {
  // Ignore taps that arrive mid-transition so we don't queue a second
  // toggle on top of an in-flight one.
  if (themeTransitionPending) return

  const time = (isDark.value ? darkVideo.value : lightVideo.value)?.currentTime ?? 0

  const swap = () => {
    applyTheme(!isDark.value)
    nextTick(() => {
      const target = isDark.value ? darkVideo.value : lightVideo.value
      if (target) target.currentTime = time
    })
  }

  // Set the reveal origin to the click position (falls back to center).
  const root = document.documentElement
  if (event) {
    root.style.setProperty('--theme-x', `${event.clientX}px`)
    root.style.setProperty('--theme-y', `${event.clientY}px`)
  } else {
    root.style.removeProperty('--theme-x')
    root.style.removeProperty('--theme-y')
  }

  // Progressive enhancement: use the View Transitions API when available.
  // @ts-expect-error — startViewTransition is not yet in lib.dom for all TS versions
  if (typeof document.startViewTransition === 'function') {
    themeTransitionPending = true
    root.classList.add('theme-transitioning')

    const release = () => {
      themeTransitionPending = false
      root.classList.remove('theme-transitioning')
    }

    // Safety net: if `finished` never resolves (e.g. transition is skipped
    // by another navigation), still release the lock so the button stays
    // responsive.
    const timeoutId = window.setTimeout(release, 1000)

    // @ts-expect-error — see above
    const transition = document.startViewTransition(() => swap())
    transition.finished.finally(() => {
      clearTimeout(timeoutId)
      release()
    })
  } else {
    swap()
  }
}

onMounted(() => {
  // Inline script may have already set the theme; read the ground truth
  const attr = document.documentElement.getAttribute('data-theme')
  if (attr === 'light') {
    isDark.value = false
  } else if (attr === null) {
    // No inline script ran; fall back to system preference
    const prefersLight = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches
    applyTheme(!prefersLight)
  }
})
</script>

<template>
  <main class="min-h-screen flex flex-col items-center px-4 py-6 md:py-10 font-mono">
    <div
      class="w-full max-w-5xl border border-border bg-bg overflow-hidden flex flex-col"
    >
      <div
        class="tui-bar flex items-stretch justify-between border-b border-border text-xs"
      >
        <div class="flex items-stretch">
          <span class="seg bg-accent text-onAccent font-bold">NORMAL</span>
          <span class="seg text-fg">chuchu</span>
          <span class="seg text-muted">{{ version }}</span>
        </div>
        <div class="flex items-stretch text-muted">
          <button
            class="seg theme-toggle cursor-pointer"
            @click="toggleTheme($event)"
            :aria-label="isDark ? 'switch to light theme' : 'switch to dark theme'"
            :title="isDark ? 'switch to light theme' : 'switch to dark theme'"
          >
            <span class="bracket">[</span>
            <span class="icon" aria-hidden="true">{{ isDark ? '☀' : '☾' }}</span>
            <span class="label">{{ isDark ? 'light' : 'dark' }}</span>
            <span class="bracket">]</span>
          </button>
          <span class="seg hidden sm:inline-flex">~/chuchu</span>
          <span class="seg bg-surfaceVar text-fg">main</span>
        </div>
      </div>

      <div class="p-6 md:p-10">
        <div class="flex flex-col items-start gap-6 md:gap-8">
          <div class="flex items-start gap-3">
            <img src="/logo.png" alt="" class="w-10 h-10" />
            <div>
              <h1 class="text-fg text-lg font-bold">chuchu</h1>
              <p class="text-fg text-base">
                <span class="font-bold">native android ssh client</span>
                <span class="text-muted"> · powered by libghostty</span>
              </p>
            </div>
          </div>

          <p class="text-fg text-base leading-relaxed max-w-2xl">
            <span class="text-accent">$</span> chuchu — a native
            <span class="bg-accent text-onAccent font-bold px-1.5 py-0.5">Android SSH client</span>
            with a beautiful terminal renderer. Tailscale, SSH and Mosh ·
            kitty image protocol · 400+ ghostty themes · multi-session tabs ·
            sftp · biometric lock.
          </p>

          <div class="flex flex-wrap gap-2">
            <a
              :href="`https://github.com/jossephus/chuchu/releases/tag/${version}`"
              class="bracket-btn accent"
            >
              <span class="bracket">[</span>
              <span>download apk</span>
              <span class="bracket">]</span>
            </a>
            <a
              href="https://github.com/jossephus/chuchu/releases"
              class="bracket-btn"
            >
              <span class="bracket">[</span>
              <span>releases</span>
              <span class="bracket">]</span>
            </a>
            <a
              href="https://github.com/jossephus/chuchu"
              class="bracket-btn"
            >
              <span class="bracket">[</span>
              <span>github</span>
              <span class="bracket">]</span>
            </a>
            <NuxtLink to="/privacy" class="bracket-btn">
              <span class="bracket">[</span>
              <span>privacy</span>
              <span class="bracket">]</span>
            </NuxtLink>
          </div>
        </div>

        <div class="mt-10 md:mt-14">
          <div class="tui-rule">demo</div>
          <div
            class="block w-full max-w-xs sm:max-w-sm mx-auto"
            aria-label="watch demo"
          >
            <div class="relative bg-surfaceVar border border-border p-2">
              <div class="flex items-center justify-center h-4 mb-1.5">
                <div class="w-8 h-1 bg-border rounded" />
              </div>
              <div class="relative aspect-[9/19.5] border border-border bg-black overflow-hidden">
                <video
                  ref="darkVideo"
                  class="absolute inset-0 w-full h-full object-cover"
                  src="/demo-dark.mp4"
                  autoplay
                  muted
                  loop
                  playsinline
                  :class="isDark ? 'opacity-100' : 'opacity-0'"
                />
                <video
                  ref="lightVideo"
                  class="absolute inset-0 w-full h-full object-cover"
                  src="/demo-light.mp4"
                  autoplay
                  muted
                  loop
                  playsinline
                  :class="isDark ? 'opacity-0' : 'opacity-100 invert hue-rotate-180'"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="mt-10 md:mt-14 grid gap-8 md:grid-cols-3">
          <section v-for="group in groups" :key="group.label">
            <div class="tui-rule">{{ group.label }}</div>
            <ul class="space-y-2">
              <li
                v-for="item in group.items"
                :key="item"
                class="feature-item border border-border bg-surface px-3 py-2 text-sm text-fg"
              >
                {{ item }}
              </li>
            </ul>
          </section>
        </div>

        <div class="mt-10 md:mt-14">
          <div class="tui-rule">privacy</div>
          <p class="max-w-3xl text-sm text-fgSec leading-relaxed">
            Chuchu is local-first. It stores your server profiles, SSH keys, host key fingerprints,
            and app settings on your device, then uses the network only to connect to the servers
            you configure. The published privacy policy is available at
            <NuxtLink to="/privacy" class="text-accent">/privacy</NuxtLink>.
          </p>
        </div>
      </div>

      <footer class="border-t border-border px-6 py-4 text-xs text-muted md:px-10 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <span>© {{ year }} chuchu</span>
        <div class="flex flex-wrap gap-4">
          <NuxtLink to="/privacy">privacy policy</NuxtLink>
          <a href="https://github.com/jossephus/chuchu/issues">contact</a>
        </div>
      </footer>
    </div>
  </main>
</template>
