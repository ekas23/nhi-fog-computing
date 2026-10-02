# Design.md — Visual system for the NHI dashboard

This applies to `dashboard/index.html` only. The offline matplotlib figures
in `results/*.png` already exist and are not restyled — they were built for
the written report and stay as-is (colour-blind-friendly viridis palette,
plain white background, print-appropriate).

## 1. Overall direction

Editorial, dark, confident — a research result being presented, not a SaaS
product pitch. Generous whitespace, one accent pairing used sparingly rather
than a rainbow of chart colours.

## 2. Color palette

| Role | Color | Hex | Usage |
|---|---|---|---|
| Background (base) | Near-black ink | `#12141a` | Page background, hero section |
| Surface | Charcoal | `#1b1e26` | Cards, panels, table rows |
| Surface raised | Lighter charcoal | `#242833` | Hovered/active cards |
| Primary accent | Saffron | `#e0954f` | Primary actions, "dynamic weighting" series, highlighted winner |
| Secondary accent | Deep green | `#2f7a5c` | Secondary series, "fixed weighting" baseline, positive deltas |
| Text primary | Off-white | `#f2ede4` | Headings, body text on dark |
| Text secondary | Warm grey | `#a9a49b` | Captions, metadata, axis labels |
| Border/divider | Subtle warm grey | `#33363f` | Card borders, table rules |
| Danger/negative | Muted red-orange | `#c1543f` | "worse than baseline" indicators only — used sparingly |
| Success/positive | Same as secondary accent | `#2f7a5c` | "better than baseline" indicators |

Policy-series colors (used consistently across every chart in the dashboard,
never reassigned per-view):

| Policy | Color |
|---|---|
| Round-robin | `#6c757d` (neutral grey) |
| Least-loaded | `#8d99ae` (cool grey-blue) |
| Latency-only | `#c1543f` (danger red — it's the cautionary baseline) |
| Mobility-aware | `#2f7a5c` (secondary green — the strongest competitor) |
| NHI (fixed) | `#e0954f` at 60% opacity |
| NHI (dynamic) | `#e0954f` at full opacity — the proposed method |

Fixed and dynamic NHI share a hue since they're the same method at different
maturity; opacity encodes "the improved version," which still reads correctly
in a black-and-white handout.

## 3. Typography

Per existing preference — Google Fonts: **Playfair Display**, **DM Sans**,
**DM Mono**.

| Role | Font | Weight | Notes |
|---|---|---|---|
| Hero headline / section titles | Playfair Display | 600–700 | Serif gives the "research finding" gravitas; headlines only, never body text |
| Body text, UI labels, buttons | DM Sans | 400 / 500 / 700 | Everything else |
| Numbers: statistics, p-values, table data, slider values | DM Mono | 400–500 | Monospaced numerals so columns of numbers align and stats "read like data" |

```css
@import url('https://fonts.googleapis.com/css2?family=Playfair+Display:wght@600;700&family=DM+Sans:wght@400;500;700&family=DM+Mono:wght@400;500&display=swap');

:root {
  --font-display: 'Playfair Display', Georgia, serif;
  --font-body: 'DM Sans', -apple-system, sans-serif;
  --font-mono: 'DM Mono', 'SF Mono', monospace;
}
```

Type scale (rem, base 16px):
- Hero headline: 3.2rem / 700, Playfair Display
- Section title: 1.75rem / 600, Playfair Display
- Card title: 1.1rem / 700, DM Sans
- Body: 1rem / 400, DM Sans
- Caption / axis label: 0.8rem / 500, DM Sans, `--text-secondary`
- Stat figure (big number): 2.5rem / 500, DM Mono
- Table/slider values: 0.95rem / 400, DM Mono

## 4. Layout

- **Hero section:** full-width, `--bg-base`, large Playfair Display headline
  ("Adaptive Node Health Index"), one-line subhead in DM Sans, the headline
  statistic (−30.4%, p<1e-8) rendered large in DM Mono as a focal point.
- **Three-tab navigation** directly below the hero: Results Explorer · Live
  Playground · Method & Findings. Tabs, not a scrolling single page — a
  presenter needs to jump straight to the Playground mid-talk.
- **Cards** for every distinct panel (chart, table, stat block): `--surface`
  background, 1px `--border` outline, 12px border radius, generous (24–32px)
  internal padding. No drop shadows — flat, editorial, not skeuomorphic.
- **Charts:** hand-rolled inline SVG bars, never more than 6 series per chart
  (matches the six policies exactly), grid lines in `--border` at 40%
  opacity, axis text in `--text-secondary`.
- **Grid:** 12-column CSS grid for panel layout; single column on narrow
  windows. Design for 1280×720 as the minimum target (a projector), not just
  a wide desktop.

## 5. Interactive elements (Live Playground specifics)

- **Sliders:** track in `--border`, filled portion in `--primary-accent`,
  thumb a solid saffron circle with a subtle `--surface-raised` halo on
  hover/focus. Current value shown in DM Mono directly above the thumb.
- **Editable table cells:** on focus, cell background shifts to
  `--surface-raised` with a 2px saffron inset border — obvious at
  presentation viewing distance.
- **Winning node highlight:** the top-scoring row gets a saffron left border
  (4px) and a small "Selected" badge in DM Sans 500, uppercase, letter-
  spacing 0.05em — text-based, not an emoji, to stay in the editorial
  register.
- **Fixed vs dynamic toggle:** a two-state pill switch with "Fixed"/"Dynamic"
  labelled directly on the pill so its state is legible from the back of a
  room.

## 6. Motion

Minimal and functional only:
- Bar chart transitions: 250ms ease-out on height/width change when
  switching dropdowns or moving a slider.
- No decorative animation, no auto-playing transitions, no parallax.

## 7. Accessibility / venue-hardware notes

- Minimum contrast: body text `#f2ede4` on `#12141a` exceeds WCAG AAA; don't
  introduce a lighter "subtle" text color that drops below AA on a
  washed-out projector.
- All interactive elements reachable via keyboard (Tab/Arrow keys) — a
  presenter may be using a remote clicker, not a mouse.
- The policy palette avoids relying on red/green alone where adjacency
  matters — bar order and labels always disambiguate too.
