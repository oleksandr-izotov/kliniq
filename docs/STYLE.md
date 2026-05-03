# Design System — Kliniq Emerald

The visual language of Kliniq. Light + dark mode. Tokens encoded in Tailwind config and CSS variables.

---

## Brand identity

**Name:** Kliniq
**Wordmark:** lowercase, letter-spacing slightly tight. Final `q` is the brand quirk — render with a slight tail flourish.
**Voice:** Calm, professional, modern. Not playful. Not corporate-sterile.
**Audience:** Medical staff who use this 8 hours a day — clarity over cleverness.

---

## Color tokens

### Primary — Emerald

| Token | Hex | Use |
|---|---|---|
| `emerald-50` | `#ecfdf5` | Card backgrounds, hover tints |
| `emerald-100` | `#d1fae5` | Subtle highlights |
| `emerald-200` | `#a7f3d0` | Disabled active states |
| `emerald-300` | `#6ee7b7` | Less common accents |
| `emerald-400` | `#34d399` | Hover state for primary |
| `emerald-500` | `#10b981` | **Primary** — main CTAs |
| `emerald-600` | `#059669` | Active state for primary |
| `emerald-700` | `#047857` | Pressed / dark mode primary |
| `emerald-800` | `#065f46` | Strong text on light |
| `emerald-900` | `#064e3b` | Rare; deep accents |

### Neutrals — Slate

| Token | Hex | Use |
|---|---|---|
| `slate-50` | `#f8fafc` | Background light |
| `slate-100` | `#f1f5f9` | Surface light |
| `slate-200` | `#e2e8f0` | Borders light |
| `slate-300` | `#cbd5e1` | Disabled text |
| `slate-400` | `#94a3b8` | Placeholder text |
| `slate-500` | `#64748b` | Secondary text |
| `slate-600` | `#475569` | Body text dark mode |
| `slate-700` | `#334155` | Surface dark mode |
| `slate-800` | `#1e293b` | Background dark mode |
| `slate-900` | `#0f172a` | **Primary text** light mode |
| `slate-950` | `#020617` | Background deep dark |

### Semantic

| Token | Light | Dark | Use |
|---|---|---|---|
| `success` | `emerald-600` | `emerald-400` | Success messages, completed bookings |
| `warning` | `amber-500 #f59e0b` | `amber-400` | OR maintenance, soft warnings |
| `danger` | `red-500 #ef4444` | `red-400` | Errors, cancelled bookings, destructive |
| `info` | `blue-500 #3b82f6` | `blue-400` | Informational toasts |

### Surfaces (CSS variables, set per theme)

```css
:root {
  --bg: theme(colors.white);
  --surface: theme(colors.slate.50);
  --surface-2: theme(colors.slate.100);
  --border: theme(colors.slate.200);
  --text: theme(colors.slate.900);
  --text-muted: theme(colors.slate.500);
  --primary: theme(colors.emerald.500);
  --primary-hover: theme(colors.emerald.600);
  --primary-fg: theme(colors.white);
  --ring: theme(colors.emerald.500);
}

.dark {
  --bg: theme(colors.slate.950);
  --surface: theme(colors.slate.900);
  --surface-2: theme(colors.slate.800);
  --border: theme(colors.slate.700);
  --text: theme(colors.slate.50);
  --text-muted: theme(colors.slate.400);
  --primary: theme(colors.emerald.400);
  --primary-hover: theme(colors.emerald.300);
  --primary-fg: theme(colors.slate.950);
  --ring: theme(colors.emerald.400);
}
```

---

## Typography

| Role | Font | Weight | Size | Line height |
|---|---|---|---|---|
| Display | Inter | 700 | 36px / 2.25rem | 1.1 |
| H1 | Inter | 700 | 30px / 1.875rem | 1.2 |
| H2 | Inter | 600 | 24px / 1.5rem | 1.3 |
| H3 | Inter | 600 | 20px / 1.25rem | 1.4 |
| Body | Inter | 400 | 14px / 0.875rem | 1.5 |
| Body large | Inter | 400 | 16px / 1rem | 1.5 |
| Caption | Inter | 400 | 12px / 0.75rem | 1.4 |
| Mono | JetBrains Mono | 400 | 13px | 1.5 |

Self-hosted via `@fontsource/inter` and `@fontsource/jetbrains-mono`. No Google Fonts CDN (privacy + EU compliance).

---

## Spacing

Tailwind defaults (4-px grid). Most-used: `2, 3, 4, 6, 8, 12, 16, 24`.

---

## Radius

| Token | Tailwind | Use |
|---|---|---|
| `xs` | `rounded-sm` (2px) | Inputs |
| `md` | `rounded-md` (6px) | **Default** — buttons, cards |
| `lg` | `rounded-lg` (8px) | Modals, alert boxes |
| `xl` | `rounded-xl` (12px) | Large feature cards |
| `full` | `rounded-full` | Avatars, pill badges |

---

## Shadows

```
sm:  0 1px 2px rgb(0 0 0 / 0.05)
md:  0 4px 6px -1px rgb(0 0 0 / 0.08), 0 2px 4px -2px rgb(0 0 0 / 0.04)
lg:  0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.05)
focus-ring: 0 0 0 3px rgb(16 185 129 / 0.3)   (emerald-500 30%)
```

In dark mode, use a subtle inner glow instead of dark shadows (shadows disappear on dark surfaces).

---

## Motion

- Default duration: 150ms
- Easing: `cubic-bezier(0.4, 0, 0.2, 1)` (Tailwind's `ease-in-out`)
- Page transitions: 200ms fade
- No animations longer than 400ms anywhere
- Respect `prefers-reduced-motion`: disable all non-essential animations

---

## Iconography

- **Lucide Icons** exclusively (comes free with shadcn-svelte)
- Default size: 16px in body, 20px in headings, 24px in buttons
- Stroke width: 2 (default)
- Color: inherit from text
- Custom icons only if Lucide doesn't have it (rare) — match Lucide's style: 24×24 grid, 2px stroke, round caps

---

## Component density

- **Default:** comfortable (Tailwind's defaults). Used by clinic staff at 1080p+.
- **Compact mode** (V2 stretch): for the schedule grid where staff want to see more bookings per screen.

---

## Accessibility

- WCAG 2.1 AA minimum
- Color contrast: text on bg ≥ 4.5:1 for body, ≥ 3:1 for large
- Focus rings always visible (never `outline: none` without replacement)
- Keyboard navigation works everywhere — no mouse-only interactions
- Error messages associated with inputs via `aria-describedby`
- Loading states announce via `aria-live="polite"`

---

## Empty states

Every list view that can be empty has an empty state with:
- Illustration (see [VISUALS.md](VISUALS.md))
- Headline ("No bookings yet")
- One-line explanation
- Primary CTA ("Create first booking")

Don't ship a feature without its empty state.

---

## Tailwind config skeleton (for `apps/web/tailwind.config.ts`)

```ts
import type { Config } from 'tailwindcss';

export default {
  content: ['./src/**/*.{html,js,svelte,ts}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: 'rgb(var(--primary) / <alpha-value>)',
          hover: 'rgb(var(--primary-hover) / <alpha-value>)',
          fg: 'rgb(var(--primary-fg) / <alpha-value>)',
        },
        bg: 'rgb(var(--bg) / <alpha-value>)',
        surface: 'rgb(var(--surface) / <alpha-value>)',
        border: 'rgb(var(--border) / <alpha-value>)',
        text: {
          DEFAULT: 'rgb(var(--text) / <alpha-value>)',
          muted: 'rgb(var(--text-muted) / <alpha-value>)',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
} satisfies Config;
```

(Actual values use HEX directly; CSS-vars-with-alpha pattern shown for shadcn compat.)
