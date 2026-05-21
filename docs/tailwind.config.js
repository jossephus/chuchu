/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    './pages/**/*.vue',
    './app.vue',
    './nuxt.config.{js,ts}',
  ],
  theme: {
    extend: {
      fontFamily: {
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      colors: {
        bg: 'var(--bg)',
        surface: 'var(--surface)',
        surfaceVar: 'var(--surfaceVar)',
        fg: 'var(--fg)',
        fgSec: 'var(--fgSec)',
        muted: 'var(--muted)',
        border: 'var(--border)',
        accent: 'var(--accent)',
        accentSec: 'var(--accentSec)',
        error: 'var(--error)',
        success: 'var(--success)',
        warning: 'var(--warning)',
        onAccent: 'var(--onAccent)',
      },
    },
  },
  plugins: [],
}
