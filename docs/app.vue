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
    label: 'built for thumbs',
    items: [
      'multi-session tabs with command palette',
      'configurable accessory key row',
      'custom key macros & nested key groups',
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

function toggleTheme() {
  const time = (isDark.value ? darkVideo.value : lightVideo.value)?.currentTime ?? 0
  applyTheme(!isDark.value)
  nextTick(() => {
    const target = isDark.value ? darkVideo.value : lightVideo.value
    if (target) target.currentTime = time
  })
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
    <!-- TUI window -->
    <div
      class="w-full max-w-5xl border border-border bg-bg overflow-hidden flex flex-col"
    >
      <!-- title bar -->
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
            class="seg hover:text-fg transition-colors cursor-pointer"
            @click="toggleTheme"
            :aria-label="isDark ? 'switch to light theme' : 'switch to dark theme'"
          >
            {{ isDark ? 'light' : 'dark' }}
          </button>
          <span class="seg hidden sm:inline-flex">~/chuchu</span>
          <span class="seg bg-surfaceVar text-fg">main</span>
        </div>
      </div>

      <!-- content -->
      <div class="p-6 md:p-10">
        <!-- hero -->
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

          </div>
        </div>

        <!-- demo video -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">demo</div>
          <div
            class="block w-full max-w-xs sm:max-w-sm mx-auto"
            aria-label="watch demo"
          >
            <div class="relative bg-surfaceVar border border-border p-2">
              <!-- notched top bar -->
              <div class="flex items-center justify-center h-4 mb-1.5">
                <div class="w-8 h-1 bg-border rounded" />
              </div>
              <!-- screen -->
              <video
                ref="darkVideo"
                v-show="isDark"
                src="/demo-dark.mp4"
                autoplay
                loop
                muted
                playsinline
                class="w-full object-contain border border-border"
              />
              <video
                ref="lightVideo"
                v-show="!isDark"
                src="/demo-light.mp4"
                autoplay
                loop
                muted
                playsinline
                class="w-full object-contain border border-border"
              />
              <!-- bottom bar -->
              <div class="flex items-center justify-center h-4 mt-1.5">
                <div class="w-16 h-0.5 bg-border" />
              </div>
            </div>
          </div>
        </div>

        <!-- features -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">features</div>
          <div class="grid md:grid-cols-3 gap-4">
            <div
              v-for="g in groups"
              :key="g.label"
              class="tui-card p-6"
            >
              <div class="flex items-center gap-3 mb-4">
                <span
                  class="text-accent text-xs font-bold uppercase tracking-widest"
                >
                  {{ g.label }}
                </span>
                <div class="flex-1 h-px bg-border" />
              </div>
              <ul class="space-y-2">
                <li
                  v-for="item in g.items"
                  :key="item"
                  class="feature-item"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </div>
          <p class="mt-6 text-muted text-xs leading-relaxed">
             <span class="text-accent">~</span> uses: libghostty · libssh2 + openssl · Mosh · Kotlin + Jetpack Compose
          </p>
        </div>

        <!-- about -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">about</div>
          <p class="text-muted text-sm">
            <span class="text-accent font-bold">chuchu</span> is a character from the
            Amharic novel <em>Yesinbit Kelemat</em> —
            <em>“colors of adios.”</em>
          </p>
        </div>
      </div>

      <!-- bottom status bar -->
      <div
        class="tui-bar flex items-stretch justify-between border-t border-border text-xs mt-auto"
      >
        <div class="flex items-stretch">
          <span class="seg bg-accent text-onAccent font-bold">CHUCHU</span>
          <span class="seg text-muted hidden sm:inline-flex">© {{ year }} · MIT</span>
        </div>
        <div class="flex items-stretch text-muted">
          <span class="seg hidden sm:inline-flex">utf-8</span>
          <span class="seg hidden sm:inline-flex">unix</span>
          <span class="seg bg-surfaceVar text-fg">:q</span>
        </div>
      </div>
    </div>
  </main>
</template>
