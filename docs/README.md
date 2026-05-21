# chuchu docs site

Static landing page for [chuchu](https://github.com/jossephus/chuchu), built with
Nuxt 3 + Tailwind. Styled to match the in-app terminal aesthetic (Tokyo Night
palette, JetBrains Mono, terminal-block accents).

## Develop

```sh
cd docs
npm install
npm run dev      # local dev server
```

## Build static site

```sh
npm run generate
# output in .output/public — deploy that to any static host
```

If you deploy under a subpath (e.g. GitHub Pages at `/chuchu/`):

```sh
NUXT_APP_BASE_URL=/chuchu/ npm run generate
```

## Layout

- `app.vue` — single-page landing
- `assets/css/main.css` — global styles + custom utilities (`.term-block`, `.chip`, `.btn`)
- `tailwind.config.js` — palette + fonts
- `public/` — logo, sample screenshots and demo gif (copied from `../assets`)
