<script setup lang="ts">
const year = new Date().getFullYear()

const groups = [
  {
    label: 'connect',
    items: [
      'tailscale ssh — magicdns, passwordless',
      'classic ssh — password and key auth',
      'ed25519 + rsa key import & generation',
      'mosh — roaming, low-latency mobile shell',
      'integrated sftp browser — upload, download, delete',
      'biometric lock — app-wide and per-server',
    ],
  },
  {
    label: 'terminal',
    items: [
      'kitty image protocol — inline image rendering',
      '400+ official ghostty themes',
      'configurable accessory key row — reorder, single / multi-row',
      'custom key macros and nested key groups',
      'chuchu key — leader-style prefix for app actions',
      'configurable fonts (jetbrains mono · fira code · hack) + size',
      'real resize, scrollback, focus, mouse and modifier keys',
      'system clipboard copy / paste',
    ],
  },
  {
    label: 'sessions',
    items: [
      'multi-session tabs with command palette switcher',
      'foreground service keeps sessions alive in background',
    ],
  },
  {
    label: 'under the hood',
    items: [
      'native libghostty terminal renderer via jni',
      'native libssh2 + openssl for ssh / sftp',
      'native mosh built in zig',
      'kotlin + jetpack compose ui',
      'room persistence for hosts and keys',
    ],
  },
]

// Theme toggle — sync with inline script in nuxt.config head
const isDark = ref(true)

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
  applyTheme(!isDark.value)
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
          <span class="seg text-muted">v0.1.4</span>
        </div>
        <div class="flex items-stretch text-muted">
          <button
            class="seg hover:text-fg transition-colors cursor-pointer"
            @click="toggleTheme"
            :aria-label="isDark ? 'switch to light theme' : 'switch to dark theme'"
          >
            {{ isDark ? 'dark' : 'light' }}
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
              <p class="text-muted text-sm">
                native android ssh client · powered by libghostty
              </p>
            </div>
          </div>

          <p class="text-fg text-base leading-relaxed max-w-2xl">
            <span class="text-accent">$</span> A modern, native Android SSH
            client with a real terminal renderer. Tailscale, SSH and Mosh ·
            kitty image protocol · 400+ ghostty themes · multi-session tabs ·
            sftp · biometric lock.
          </p>

          <div class="flex flex-wrap gap-2">
            <a
              href="https://github.com/jossephus/chuchu/releases/tag/v0.1.4"
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
            <a
              href="https://github.com/jossephus/chuchu/issues"
              class="bracket-btn"
            >
              <span class="bracket">[</span>
              <span>issues</span>
              <span class="bracket">]</span>
            </a>
          </div>
        </div>

        <!-- demo gif -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">demo</div>
          <a
            href="/chuchu_demo.gif"
            class="block w-full max-w-xs sm:max-w-sm mx-auto"
            aria-label="watch demo"
          >
            <div class="relative bg-surfaceVar border border-border p-2">
              <!-- notched top bar -->
              <div class="flex items-center justify-center h-5 mb-1">
                <div class="w-16 h-3 bg-surface border border-border" />
              </div>
              <!-- screen -->
              <img
                src="/chuchu_demo.gif"
                alt="chuchu demo"
                class="w-full object-contain border border-border"
              />
              <!-- bottom bar -->
              <div class="flex items-center justify-center h-5 mt-1">
                <div class="w-12 h-0.5 bg-muted/50" />
              </div>
            </div>
          </a>
        </div>

        <!-- features -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">features</div>
          <div class="grid md:grid-cols-2 gap-4">
            <div
              v-for="g in groups"
              :key="g.label"
              class="tui-card p-5"
            >
              <div class="flex items-center gap-3 mb-4">
                <span
                  class="text-accent text-[11px] font-bold uppercase tracking-widest"
                >
                  {{ g.label }}
                </span>
                <div class="flex-1 h-px bg-border" />
              </div>
              <ul class="space-y-2">
                <li
                  v-for="item in g.items"
                  :key="item"
                  class="feature-item text-[13px]"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </div>
        </div>

        <!-- install -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">install</div>
          <p class="mb-4 text-sm leading-relaxed">
            <span class="text-accent">#</span> users — sideload the apk from
            <a href="https://github.com/jossephus/chuchu/releases">releases</a>.
            no play store yet (payment limits in my country).
          </p>
          <div class="tui-card max-w-2xl">
            <div class="px-4 py-2.5 border-b border-border text-[11px] text-muted flex justify-between">
              <span>terminal</span>
              <span>bash</span>
            </div>
            <pre class="p-4 overflow-x-auto text-[13px]"><code><span class="text-muted"># build from source</span>
<span class="text-accent">$</span> nix develop
<span class="text-accent">$</span> make build      <span class="text-muted"># zig + jni native</span>
<span class="text-accent">$</span> make app        <span class="text-muted"># build apk + install</span></code></pre>
          </div>
        </div>

        <!-- about -->
        <div class="mt-10 md:mt-14">
          <div class="tui-rule">about</div>
          <p class="mb-3 text-sm leading-relaxed">
            inspired by
            <a href="https://github.com/vivy-company/vvterm">vvterm</a> on iOS —
            chuchu is the android-native version i wanted to exist.
          </p>
          <p class="text-muted text-sm">
            <span class="text-warning">chuchu</span> is a character from the
            Amharic novel <em>Yesinbit Kelemat</em> —
            <em>"colors of adios."</em>
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
