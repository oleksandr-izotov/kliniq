# Brand Assets

All visual assets for Kliniq, organized for use across the app, marketing pages, and social cards.

**Status:** V1 set complete. Created using DALL-E 3 (illustrations) and Figma (logo, icon, OG text overlay) on 2026-05-04.

**Style anchor:** Modern flat 2D illustration in contemporary SaaS aesthetic (Linear/Notion/Stripe-inspired). Palette: emerald `#10b981`, mint `#a7f3d0`, slate, warm beige `#f5e6d3`, cream `#fafaf7`.

---

## Folder map

```
brand-assets/
├── logo/
│   ├── kliniq-wordmark.png      # green lowercase wordmark, white bg (Figma)
│   ├── kliniq-icon.png          # app icon, 1024×1024, emerald squircle + white "k"
│   └── kliniq-icon.svg          # app icon, vector — preferred source for derivatives
│
├── illustrations/
│   ├── empty-bookings.png       # square 1024×1024 — calendar with one event
│   ├── empty-rooms.png          # square 1024×1024 — door with green plus
│   ├── empty-users.png          # square 1024×1024 — three avatars, one filled
│   ├── login-side.png           # vertical 1024×1792 — workspace scene
│   ├── error-404.png            # horizontal 1792×1024 — corridor with green door
│   └── error-500.png            # horizontal 1792×1024 — unplugged cable
│
└── og/
    └── og-image.png             # 1200×630 — final OG card with text overlay (Figma; illustration generated in DALL-E)
```

---

## How each asset is used

| Asset | Where it goes in the app | Use |
|---|---|---|
| `logo/kliniq-wordmark.png` | `apps/web/static/logo.png` (light bg) | Topbar, login page header, README |
| `logo/kliniq-icon.svg` | `apps/web/static/icon.svg` | Favicon source, PWA icon source |
| `logo/kliniq-icon.png` | `apps/web/static/icon-1024.png` | Apple touch icon, social fallback |
| `illustrations/empty-bookings.png` | `apps/web/static/illustrations/empty-bookings.png` | `/schedule` and `/bookings` empty state |
| `illustrations/empty-rooms.png` | `apps/web/static/illustrations/empty-rooms.png` | `/operating-rooms` empty state |
| `illustrations/empty-users.png` | `apps/web/static/illustrations/empty-users.png` | `/users` empty state (admin) |
| `illustrations/login-side.png` | `apps/web/static/illustrations/login-side.png` | Left panel of `(auth)` layout |
| `illustrations/error-404.png` | `apps/web/static/illustrations/error-404.png` | 404 page |
| `illustrations/error-500.png` | `apps/web/static/illustrations/error-500.png` | 500 page / `+error.svelte` |
| `og/og-image.png` | `apps/web/static/og-image.png` | `<meta property="og:image">` and Twitter card |

**Note:** Files are copied (not moved) into `apps/web/static/` during Sprint 0/1. The originals stay here as the source of truth.

---

## Pre-flight before using in the app

1. **Optimize PNGs** — run each through [Squoosh](https://squoosh.app/). Target: under 100 KB each. WebP variants for modern browsers, PNG fallback.
2. **Optimize SVGs** — run `kliniq-icon.svg` through [SVGOMG](https://jakearchibald.github.io/svgomg/). Target: under 5 KB.
3. **Generate favicons** — drop `kliniq-icon.svg` into [realfavicongenerator.net](https://realfavicongenerator.net/) once. It outputs the full set: `favicon-16.png`, `favicon-32.png`, `apple-touch-icon.png` (180×180), `android-chrome-192.png`, `android-chrome-512.png`, plus `site.webmanifest`. Drop the whole pack into `apps/web/static/`.
4. **Verify alt text** — every `<img>` reference in the app needs a meaningful `alt`.

---

## Source files (Figma)

The `kliniq-wordmark.png`, `kliniq-icon.png/svg`, and the OG-text-overlay version of `og-image.png` were composed in Figma. The source `.fig` file is in the user's Figma workspace (Drafts → "Untitled"). Export it as `kliniq.fig` and drop here if you want a fully self-contained brand-assets folder.

## Source prompts

The DALL-E illustrations (`illustrations/*.png` and `og/og-base.png`) were generated using prompts documented in [`../docs/VISUALS.md`](../docs/VISUALS.md). To regenerate or extend the set, reuse those prompts in the same chat thread to preserve stylistic consistency.
