# Visuals — needed assets and AI prompts

What images we need, in what order, and exact prompts you can paste into your AI image tool of choice. Generate them now in batches; we plug them into the project later as we hit the relevant features.

---

## Visual style — the unifying constraint

**All Kliniq illustrations follow ONE style** so they look like a system, not a stock-photo collage:

> **Modern minimalist line illustration. Clean thin lines (1.5-2px stroke). Soft geometric shapes. Selective fills with emerald (#10b981) accent. Off-white or transparent background. Subtle depth via overlapping shapes — no heavy shadows. Professional medical/healthcare aesthetic without clichés (avoid stethoscopes, red crosses). Inspired by Linear, Notion, Stripe documentation illustrations.**

Reuse this paragraph as a **style preamble** in every prompt below.

---

## Image generation tools — what to use

| Tool | Best for | Notes |
|---|---|---|
| **Midjourney v6** | Hero illustrations, empty states | Best quality. Use `--style raw` and `--ar` for ratio. |
| **Flux Pro** (via fal.ai or Replicate) | Same as MJ, alternative | Slightly more controllable. Open weights = no policy issues. |
| **Recraft AI** | **Logo + vector assets** | Generates SVGs natively. Best for logo work. |
| **Ideogram** | Anything needing readable text in image | The only AI that does text decently. |
| **DALL-E 3** (ChatGPT) | Quick iterations | Lower ceiling than MJ but easy. |
| **Photopea / Figma** | Final touch-up | Always edit AI output by hand for production use. |

**Tips:**
- Always generate 4-6 variants per prompt, pick best, regenerate similar.
- For consistent characters/scenes across multiple images, save the seed + prompt and reuse.
- AI **fails at text** — don't put text in image; add via CSS/SVG over the illustration.
- Final assets: export PNG @2x for raster, SVG for icons/logo.

---

## Tier 1 — needed for V1 (generate FIRST)

### 1. Logo / Wordmark

**Use:** Topbar, login page, favicon, OG image, README header.

**Recommended tool:** Recraft AI (vector output) or Midjourney + manual SVG cleanup.

**Prompt (Recraft, vector mode):**
```
Minimalist wordmark logo for a medical software company called "Kliniq".
Lowercase letters, rounded sans-serif, slightly tighter letter-spacing.
The final letter "q" has a small subtle tail flourish that hints at a calendar tick or ECG line.
Color: emerald green #10b981 on transparent background.
Modern, clean, professional, calm. Aimed at medical professionals.
No icon, just the wordmark. Vector, flat, no gradient, no shadow.
```

**Also generate a separate icon** (square, for favicon and PWA):
```
Minimalist app icon for "Kliniq" — a medical scheduling app.
Square format. The letter "K" rendered as overlapping geometric shapes that subtly form a calendar grid OR a heartbeat tick mark.
Solid emerald green #10b981 background, white shape on top.
Flat, no gradients. Modern. Should look great at 16x16 and at 512x512.
```

**Variants to keep:**
- Wordmark on transparent (light theme)
- Wordmark in white on emerald background (dark theme + OG)
- Icon 1024×1024 PNG + SVG
- Icon on transparent

**Output spec:**
- `web/static/logo.svg` — wordmark, transparent
- `web/static/logo-light.svg` — wordmark for light theme
- `web/static/logo-dark.svg` — wordmark for dark theme
- `web/static/icon.svg` — square icon
- `web/static/favicon-32.png`, `favicon-16.png`, `apple-touch-icon.png` (180×180), `android-chrome-512.png` — generated from icon.svg later

---

### 2. Empty state — "No bookings yet"

**Use:** [/schedule](.) and [/bookings](.) when the list is empty.

**Aspect ratio:** Square or 4:3, ~400×400 px display size.

**Prompt:**
```
[STYLE PREAMBLE — paste from top of this doc]

A minimalist line illustration of an empty calendar grid floating in soft empty space, with one small emerald-green dot (representing the first booking-to-be) hovering above it as if about to land. Clean thin black lines (1.5px) on off-white background. Selective emerald (#10b981) fill on the floating dot only. No text. Subtle, calm, professional.

Aspect ratio 1:1, --ar 1:1
```

**Output:** `web/static/illustrations/empty-bookings.svg` (or PNG @2x).

---

### 3. Empty state — "No operating rooms configured"

**Use:** [/operating-rooms](.) page when admin has not set up any ORs yet.

**Prompt:**
```
[STYLE PREAMBLE]

A minimalist line illustration of an empty rectangular floor plan suggesting an operating room layout, drawn in clean thin black lines on off-white background. The rectangle is bare except for a single small emerald-green plus-sign (#10b981) in the center, suggesting "add the first room". Geometric and simple. No medical equipment, no people. No text.

Aspect ratio 1:1
```

**Output:** `web/static/illustrations/empty-rooms.svg`.

---

### 4. Empty state — "No users invited yet"

**Use:** [/users](.) when admin has not invited anyone.

**Prompt:**
```
[STYLE PREAMBLE]

A minimalist line illustration of three abstract circular avatar shapes arranged in a row — the first two are empty dashed-line circles, the third is filled with emerald green (#10b981) and represents a single existing user. Drawn in clean thin black lines on off-white background. Suggests "invite more". No faces, no detail inside circles. No text.

Aspect ratio 1:1
```

**Output:** `web/static/illustrations/empty-users.svg`.

---

### 5. Login page illustration (split-screen left side)

**Use:** Left half of `/login`, `/register`, `/reset` pages on desktop.

**Aspect ratio:** Vertical, 9:16 or 3:4.

**Prompt:**
```
[STYLE PREAMBLE]

A vertical minimalist line illustration evoking a calm modern medical workspace. Suggested elements: an abstract calendar grid floating in space, a stylized window with soft daylight, a small potted plant, geometric shapes suggesting an operating room schedule on a wall. NO people, NO faces, NO stethoscopes, NO red crosses. Clean thin black lines on off-white background. Selective emerald (#10b981) accents on 2-3 elements (the plant leaves, one calendar event, a small dot). Spacious composition with negative space. Calm, professional, modern.

Aspect ratio 3:4, --ar 3:4
```

**Output:** `web/static/illustrations/auth-side.svg` or `auth-side.png` @ 1200×1600.

---

### 6. 404 page illustration

**Prompt:**
```
[STYLE PREAMBLE]

A minimalist line illustration of an empty hospital corridor stretching into the distance with a single closed door at the end. Clean thin black lines on off-white background. The door has a small emerald (#10b981) handle. NO people. Atmospheric, calm — suggesting "this room doesn't exist". No text.

Aspect ratio 4:3, --ar 4:3
```

**Output:** `web/static/illustrations/error-404.svg`.

---

### 7. 500 page illustration

**Prompt:**
```
[STYLE PREAMBLE]

A minimalist line illustration of an unplugged cable lying on a clean surface, with one end glowing softly emerald (#10b981) as if waiting to reconnect. Clean thin black lines on off-white background. Calm, not alarming. No text, no error symbols.

Aspect ratio 4:3, --ar 4:3
```

**Output:** `web/static/illustrations/error-500.svg`.

---

### 8. OG / social share image

**Use:** Meta tags `og:image`, `twitter:card` on every public page.

**Aspect ratio:** 1200×630 (standard OG).

**Prompt (use Ideogram for readable text):**
```
A clean modern social media preview image for "Kliniq" — operating room scheduling software. Background: soft gradient from white to emerald-50 (#ecfdf5). Center-left: the word "Kliniq" in lowercase rounded sans-serif, dark slate (#0f172a). Below: tagline "Scheduling for modern clinics" in slate-500. Right side: a minimalist line illustration of a calendar grid with one emerald-green (#10b981) booking block highlighted. Professional, calm, modern medical software aesthetic. NO stethoscopes, NO clichés.

Dimensions 1200x630
```

**Output:** `web/static/og-image.png`.

---

## Tier 2 — for V2 / landing page (generate when needed, not now)

- **Hero illustration** for landing page
- **Feature section illustrations** ×3 (Schedule, Real-time, Audit)
- **Surgeon portal welcome illustration**
- **Email header banner** for transactional emails
- **About / team illustration**

Drop these into `docs/VISUALS.md` as new entries when V2 starts.

---

## What to do with generated assets

1. Save originals (PNG / SVG) somewhere safe — I'd suggest a folder `kliniq-assets/` in your local Drive/Dropbox, NOT committed to the repo at first (large binaries bloat git).
2. **Optimize before committing:**
   - SVG: run through [SVGOMG](https://jakearchibald.github.io/svgomg/)
   - PNG: run through [Squoosh](https://squoosh.app/) — convert to WebP/AVIF where supported, keep PNG fallback
3. Final assets go in `apps/web/static/` (or `apps/web/static/illustrations/` for grouping).
4. Reference in code via Vite's static handling: `<img src="/illustrations/empty-bookings.svg" alt="No bookings" />`.

---

## Quality gate — when an image is "done"

- [ ] Style matches preamble (consistent across all illustrations)
- [ ] Emerald color is exactly `#10b981` (not approximate)
- [ ] No text in the image (text added via CSS/HTML)
- [ ] No medical clichés (stethoscopes, red crosses, white coats unless tasteful)
- [ ] Looks good at small AND large sizes (test the empty states at 200px and 600px)
- [ ] Both light-theme and dark-theme versions exist (or one transparent SVG that works on both)
- [ ] File optimized (SVG < 10KB ideally, PNG WebP-converted)
- [ ] Alt text written

---

## Generation batching plan

Don't try to generate all 8 in one sitting. Suggested order:

**Batch 1 — Brand (most important, iterate most):**
1. Logo wordmark
2. App icon
3. OG image

Iterate until you really like these — they define the brand. Spend a few hours.

**Batch 2 — V1 in-app illustrations (one style session, ~30 min):**
4. Empty bookings
5. Empty rooms
6. Empty users

Generate these together to ensure stylistic consistency. Same prompt structure, same seed if possible.

**Batch 3 — Auth + errors:**
7. Login side illustration
8. 404 illustration
9. 500 illustration

Now you have everything V1 needs visually.
