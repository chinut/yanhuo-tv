# 焰火TV 首页视觉重构 · TV UI 设计研究

Research report for redesigning the 焰火TV home screen (Android 6, 1080p, Xiaomi TV, no usable background blur).
Layout is **fixed and not to be changed**; this report only addresses *visual treatment*.

---

## 0. TL;DR — the three decisions that matter

1. **Delete the colorful background entirely.** Replace the 4-blob purple/blue mesh with a single flat near-black fill plus one very subtle vertical gradient. This single change fixes most of "背景和风格完全不符". Google's own TV dark-theme background token is `#1C1B1F`; Apple's tvOS home screen is a flat dark backdrop with a single full-bleed hero. Nobody premium uses aurora blobs.
2. **Cut the corner radius by ~3×.** Current 165px cut on the 833px block → **48px radius**. The 248px cards: 62px cut → **40px radius**. Keep radius roughly constant across card sizes; do not scale it with width.
3. **Stop trying to fake glass, and stop using thin text.** Blur is unavailable on your renderer, and glassmorphism on >2 surfaces is a named anti-pattern anyway. Also: the failed mockup uses `font-weight:300` at 42% opacity, which I measured at **3.37:1 contrast** — below WCAG AA and unreadable on a TV.

---

## 1. The unit conversion you must use (this reframes everything)

This is the single most important technical fact for this project, and it was missing from the failed attempt.

- Google's TV design spec says: *"Always design at MDPI resolution at 960px \* 540px. At MDPI 1px = 1dp. Assets need to aim for 1080p."* — [Layouts | TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/layouts)
- Android's density table: **1080p TV = `xhdpi` = ~320 dpi** — [Support different pixel densities](https://developer.android.com/training/multiscreen/screendensities)
- Conversion formula: `px = dp * (dpi / 160)` — same source. At 320 dpi that is **`px = dp × 2`**.

**So every Google/Material dp token must be doubled to become pixels on your 1920×1080 canvas:**

| Token | dp | **px @1920×1080** |
|---|---|---|
| Corner extra-small | 4dp | **8px** |
| Corner small | 8dp | **16px** |
| Corner medium | 12dp | **24px** |
| Corner large | 16dp | **32px** |
| **Corner extra-large** | **28dp** | **56px** |
| Overscan margin, sides | 48dp | **96px** |
| Overscan margin, top/bottom | 27dp | **54px** |
| Recommended side margin | 58–64dp | **116–128px** |
| Card gutter | 20dp | **40px** |
| Grid column width | 52dp | **104px** |
| Body text (tv type scale) | 16sp | **32px** |

### 1.1 Your approved layout is already correct — do not "fix" it

Cross-checking your fixed geometry against Google's official grid:

| Yours (px) | ÷2 = dp | Google's spec | Verdict |
|---|---|---|---|
| Left block width 833px | 416.5dp | 2-card layout = **412dp** | ✅ matches |
| Left margin 127px | 63.5dp | side margin **58–64dp** | ✅ matches |
| Vertical gutter 134px | 67dp | (2× 20dp gutter + 1 col) | ✅ plausible |
| Narrow card width 248px | 124dp | sits between 4-card **196dp** and 5-card **124dp** | ⚠️ on-scale, narrower than Google's 4-up |

Sources: [Layouts](https://developer.android.com/design/ui/tv/guides/styles/layouts), [Cards](https://developer.android.com/design/ui/tv/guides/components/cards).
**Conclusion: the user approved a layout that is measurably correct. The problem is purely surface treatment.**

---

## 2. What real TV launchers actually do

### 2.1 Background — the honest answer is "flat dark + one hero"

| Platform | Background treatment | Evidence |
|---|---|---|
| **Google TV (2024–2025)** | Near-black tinted neutral `#1C1B1F` (`Neutral10`). **Not a gradient, not colored blobs.** The 2025 homescreen redesign is explicitly *"mainly a reorganization"* of navigation — no new background treatment was introduced. | Official dark token `Background = PaletteTokens.Neutral10 = Color(28,27,31)` = `#1C1B1F` — [ColorDarkTokens.kt, androidx tv-material](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ColorDarkTokens.kt); redesign coverage — [9to5Google, Nov 2025](https://9to5google.com/2025/11/13/google-tv-homescreen-redesign-2025/) |
| **Apple tvOS** | Flat dark backdrop; the "visual interest" comes from **one full-screen piece of content** (Top Shelf), which is video or a single layered image — not scenery painted behind the UI. Apple explicitly flips/blurs a static fallback *to fill 1920px at 16:9* so the entire backdrop is content. | [Top Shelf | Apple HIG](https://developer.apple.com/design/human-interface-guidelines/top-shelf) |
| **Netflix / Prime Video TV apps** | Flat near-black (`#000`–`#141414` range), zero background ornament; all colour comes from artwork tiles. | Widely documented industry practice; see [Netflix design system analysis](https://explainx.ai/designs/whyashthakker-design-md-templates-skills/netflix/design-md) |
| **Samsung Tizen / LG webOS** | Same: dark flat base, content-forward. | [webOS Design Considerations (Enyo)](https://nightly.enyojs.com/enyo-nightly-20180328015137/docs/developer-guide/design/webOS/index.html), [Tizen Background Color](https://developer.tizen.org/print/24845?langswitch=en) |

### 2.2 Android TV's own background rule — this is decisive for you

Google's TV layout guide contains a rule that directly conflicts with the current mockup:

> **"Fill the full screen** — Don't adjust or clip background screen elements to the overscan safe area. Instead, allow partial display of offscreen elements."
> — [Layouts | TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/layouts)

And from the developer guide:

> "Don't adjust background screen elements that the user doesn't directly interact with, and don't clip the elements to the overscan-safe area."
> — [Build TV layouts](https://developer.android.com/training/tv/playback/compose/layouts)

The background is meant to be a **quiet full-bleed field**, not a composed focal element. A drifting 4-blob nebula violates the spirit of this: it competes with the content and it draws the eye to the background.

### 2.3 Card treatment in the real systems

Google's TV card components are **flat images with no chrome** — no border, no glass, no shadow by default. Interaction is signalled by scale/border/glow *states*, not by permanent decoration:

> "Each state of a focusable element is configured by adjusting the following properties: **Scale** — Change the size of a focused element; **Border** — Draw an outline around the element; **Glow** — Create a shadow under element (commonly used on cards); **Colors** — Change element background and content color."
> — [Focus system | TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/focus-system)

> "**Glow level:** suggests elevation of the element, ranging from **2dp – 32dp**" (i.e. 4px–64px at your density)
> "**Scale indication** — Default scaling values are: **1.025, 1.05 and 1.1x**"
> — same source

The Compose TV `Card` API confirms this is a *state* model, not a material model — `CardShape`, `CardColors`, `CardScale`, `CardBorder`, `CardGlow` — [androidx.tv.material3.Card](https://developer.android.com/reference/kotlin/androidx/tv/material3/Card.composable). Notably **`CardDefaults` has no "glass" or "blur" parameter at all.**

---

## 3. Corner radius — concrete numbers

### 3.1 The official radius scale

Google's TV Material library defines the complete shape scale (verbatim):

```kotlin
val CornerExtraSmall = RoundedCornerShape(4.0.dp)
val CornerSmall      = RoundedCornerShape(8.0.dp)
val CornerMedium     = RoundedCornerShape(12.0.dp)
val CornerLarge      = RoundedCornerShape(16.0.dp)
val CornerExtraLarge = RoundedCornerShape(28.0.dp)
val CornerFull       = CircleShape
val CornerNone       = RectangleShape
```
— [ShapeTokens.kt, androidx tv-material](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ShapeTokens.kt)

At density 2.0 → **8 / 16 / 24 / 32 / 56 px**. Material 3 publishes the same scale — [M3 corner radius scale](https://m3.material.io/styles/shape/corner-radius-scale). The `28dp` "extra-large" value is the largest non-circular radius in the system; anything above it has no token and reads as off-system.

### 3.2 Radius vs. card size — what real designs do

| System | Card size | Radius | Radius as % of width |
|---|---|---|---|
| Material 3 / TV | — | 12dp = 24px | design token |
| Material 3 / TV, "extra large" | — | 28dp = **56px** | design token (max) |
| tvOS Top Shelf, 16:9 focused | 852pt | — | *no visible radius; edge-to-edge art* |
| Apple tvOS card tokens | 380–570pt | small (system) | — |

Sources: [ShapeTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ShapeTokens.kt), [Apple HIG Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf).

**The rule: radius is roughly constant across a card family; it does NOT scale with card width.** An 833px hero and a 248px tile should sit within one or two steps of the same token, not at proportionally-equal radii. This is why the current design looks wrong in two directions at once.

### 3.3 Diagnosing the current cut-corner (measured)

The current implementation is a chamfer (straight 45° cut). For a chamfer with cut length `c`, the visually-equivalent circular radius is `r = c / √2`:

| Element | Current cut | Equivalent radius | % of width | Target | Verdict |
|---|---|---|---|---|---|
| Left live block 833×747 | 165px | ~117px | 19.8% | **48px** | ❌ ~2.4× too large |
| Right card 248×747 | 62px | ~44px | 25.0% | **40px** | ⚠️ slightly too round + inconsistent |

Note the geometry also breaks on the wide block: a 165px cut on an 833px edge leaves a **503px straight edge**, so the block reads as a chunky truncated rectangle rather than a softly rounded card. On the narrow card the same treatment leaves only 124px of straight edge, which reads correctly as "rounded". **That mismatch is exactly the "cut corner too big" complaint.**

### 3.4 Recommended radii (final)

| Element | Radius | Rationale |
|---|---|---|
| Left live block 833×747 | **48px** (24dp) | Between `CornerLarge` 32px and `CornerExtraLarge` 56px; visually right for a hero that is 3.4× the narrow card's width |
| Right cards 248×747 | **40px** (20dp) | Between `CornerLarge` and `CornerExtraLarge` |
| Inner badges/icons | **16–20px** | `CornerSmall`/`CornerMedium` |
| Header pill / clock chip | **999px (full pill)** or 24px | keep pill only if the chip is short |

If you prefer to stay strictly on-token and want maximum system fidelity: **use 56px (28dp, `CornerExtraLarge`) on the big block and 32px (16dp, `CornerLarge`) on the narrow ones.** That is the most defensible choice because both are literal published tokens. My 48/40 recommendation is a slight visual optimization; 56/32 is the "by-the-book" answer. Either is vastly better than 165/62.

Do **not** use a `ContourShape`/squircle approximated with a big radius — at TV viewing distance the difference is invisible and it costs render time.

---

## 4. Contrast, readability and safe areas

### 4.1 Official safe area (already satisfied, keep it)

> "To keep your content and information safe, use a 5% margin layout (**58dp on the sides and 28dp on the top and bottom edges**)."
> "**960 × ~5% = 48dp**; **540 × ~5% = 27dp** round off to 24dp"
> "Use 12 columns that are **52dp wide with 20dp of space between them**. There needs to be **58dp of space on both sides and 4dp of vertical spacing** between lines."
> — [Layouts | TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/layouts)

Also: *"Most modern TVs no longer have overscan issues."* (same source) — so this is a floor, not a target. At density 2.0: **side margin 116px, top/bottom 56px, column 104px, gutter 40px.** Your 127px left margin is comfortably inside this.

Apple's equivalent: *"Minimum body text size is 29pt"* and a **60pt safe inset** — [Apple HIG Typography](https://developer.apple.com/design/human-interface-guidelines/typography) (official: tvOS default 29pt / minimum 23pt) and the tvOS design reference ([tvOS design guidelines](https://github.com/dirnbauer/webconsulting-skills/blob/main/skills/tvos-design/SKILL.md)).

### 4.2 Type scale — the official tvOS table (use this as your ceiling)

Apple publishes exact tvOS text sizes — [Apple HIG Typography](https://developer.apple.com/design/human-interface-guidelines/typography):

| tvOS style | Weight | Size | Leading |
|---|---|---|---|
| Title 1 | Medium | 76pt | 96 |
| Title 2 | Medium | 57pt | 66 |
| Title 3 | Medium | 48pt | 56 |
| Headline | Medium | 38pt | 46 |
| Callout | Medium | 31pt | 38 |
| **Body** | **Medium** | **29pt** | 36 |
| Caption 1 | Medium | 25pt | 32 |
| **Caption 2** | **Medium** | **23pt** | 30 |

Apple's two governing rules:
- *"In general, **avoid light font weights**. …prefer Regular, Medium, Semibold, or Bold font weights, and avoid Ultralight, Thin, and Light font weights"* — official, HIG Typography.
- Platform minimum for tvOS is **23pt**; default **29pt** (official table above).

**Every tvOS style is Medium weight or heavier.** That is the direct fix for the failed mockup.

### 4.3 Converting Apple pt to your canvas

tvOS renders at 1920×1080 for @1x with pt ≈ px at that resolution. So at 1080p:

| Purpose | Apple tvOS | Google TV (dp→px) | **Recommended px @1920×1080** |
|---|---|---|---|
| Hero title (CCTV-1 综合) | Title 3, 48pt | DisplayMedium 45sp | **56–64px, Medium/Semibold** |
| Section title (影视/短剧) | Headline, 38pt | TitleLarge 22sp | **32–36px, Medium** |
| Body / subtitle | Body, 29pt | TitleMedium 16sp | **24–28px, Regular** |
| Metadata / caption | Caption 1, 25pt | LabelLarge 14sp | **22–24px, Regular** |
| Absolute floor | Caption 2, 23pt | BodySmall 12sp | **≥20px** |

Google's TV type scale numbers are from [TypeScaleTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/TypeScaleTokens.kt).

### 4.4 Contrast — measured failures in the current mockup

I computed WCAG 2.x contrast ratios against the actual backgrounds in `docs/mockups/home-liquid.html`:

| Element | Current value | Effective colour | **Contrast** | Verdict |
|---|---|---|---|---|
| Hint text "按「确定」进入全屏" | `rgba(214,224,255,.42)`, weight 300 | `rgb(96,102,126)` on `#0A0E20` | **3.37:1** | ❌ fails WCAG AA (4.5:1) |
| Brand tagline "焰火随想 美好随现" | `rgba(220,228,255,.46)`, weight 300 | `rgb(105,110,130)` on `#070A18` | **3.90:1** | ❌ fails AA |
| "影视库 12 部" | `rgba(214,224,255,.56)` | `rgb(123,130,153)` on `#080B18` | **5.13:1** | ⚠️ passes AA, weak for TV |
| "正在直播" | `rgba(226,234,255,.62)` | `rgb(144,150,170)` | **6.50:1** | ⚠️ acceptable, aim higher |

**Target:** ≥ **7:1** for all text on TV (users sit further away, panels are often uncalibrated, and there is no anti-aliasing headroom on a cheap panel). WCAG AA 4.5:1 is a *web* floor, not a TV floor.

Verified contrast for the recommended palette on `#0B0D12`:

| Colour | Contrast on `#0B0D12` | Use |
|---|---|---|
| `#FFFFFF` | 19.43:1 | hero title |
| `#F0F1F5` | 17.22:1 | primary text |
| `#C8C9D0` | 11.77:1 | section titles |
| `#9A9BA4` | 7.03:1 | secondary / metadata (floor) |
| `#6C6E78` | 3.83:1 | **do not use for text** |

For comparison, Google's own dark pair is excellent: `#E6E1E5` on `#1C1B1F` = **13.27:1**, and `#CAC4D0` on `#1C1B1F` = **10.05:1** (tokens from [ColorDarkTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ColorDarkTokens.kt)).

### 4.5 Scrims — do it the way Google says

Google's card guidance is explicit that text on imagery requires a scrim, and that its absence is a documented "Don't":

> "To make text more readable on an image, add a **semi-transparent black gradient overlay**. This darkens the background without obscuring the image too much, making the text easier to see."
> "**Don't** — Don't use compact cards without scrim on top of the background image."
> — [Cards | TV | Android Developers](https://developer.android.com/design/ui/tv/guides/components/cards)

The current mockup's scrims (`.veil`, `.psc`) are directionally right but too weak and too evenly distributed. Recommended scrim spec below in §5.

---

## 5. Five named design directions

Each is a complete, buildable treatment for your fixed layout. All avoid blur (unavailable) and all clear 7:1 on body text.

---

### Direction A — "Flat Cinema Dark" ★ *my primary recommendation*

The safest, most premium, and closest to what Google TV and Apple TV actually ship. Reads as "professional broadcaster app".

- **Background:** flat `#0B0D12` base. One vertical gradient only, `linear-gradient(180deg, #14171E 0%, #0B0D12 55%, #07090D 100%)`. **No blobs. No animation. No grain.**
- **Cards:** flat surfaces, no glass. Card fill `#15181F`. Border: `1px solid rgba(255,255,255,0.06)` (12% is too much; 6% is a whisper).
- **Focus state:** `2px solid rgba(255,255,255,0.85)` border + `scale(1.05)` + glow `0 0 48px rgba(0,0,0,0.9)`. Per Google's focus spec, glow 2dp–32dp = **4–64px**; use 48px. Scale 1.05 is a literal published default value.
- **Radius:** 48px left block, 40px narrow cards.
- **Accent:** none on the surface. Accent is reserved for the live indicator only: `#FF4D5E`.
- **Scrim (on the video preview):** `linear-gradient(180deg, transparent 45%, rgba(7,9,13,0.55) 72%, rgba(7,9,13,0.92) 100%)` — much stronger than current at the bottom, completely clear at the top.
- **Why it works on TV:** zero decoration competes with the live video; the dark base maximises perceived contrast of the (bright) video preview; it's the only direction that costs literally nothing to render on an Android 6 device; it matches the published background token of the platform it's running on.

---

### Direction B — "Single Hero Backdrop"

Use the *content itself* as the background, exactly as Apple's Top Shelf does.

- **Background:** when the left block is focused on a live channel, echo that channel's poster/frame full-bleed behind the whole 1920×1080 canvas, then cover it with a **heavy scrim**: `linear-gradient(90deg, rgba(7,9,13,0.97) 0%, rgba(7,9,13,0.88) 38%, rgba(7,9,13,0.55) 100%)`. The image is atmosphere, not information.
- **Cards:** near-transparent flat panels over the scrim — `rgba(16,19,26,0.72)`. No glass highlights.
- **Radius:** 48/40px as above.
- **Accent:** warm brand colour allowed here because the background is photographic. Fire/amber suits 焰火: `#FF7A45` at full opacity for the live dot and the focus ring.
- **Crossfade:** 400ms ease-out when the channel changes. Nothing drifts on its own.
- **Why it works on TV:** it is precisely Apple's model (one full-bleed 1920×1080 hero, blurred/flipped to fill, per [Apple HIG Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf)); it makes the screen feel alive without any background animation; the heavy scrim guarantees text contrast regardless of the artwork.
- **Risk:** depends on good channel artwork. If channel images are low-res, this looks worse than Direction A. **Check your artwork quality before choosing this.**

---

### Direction C — "Material You Tonal"

Pure Google TV system fidelity. Use Google's own published tokens verbatim.

- **Background:** `#1C1B1F` (Google's exact TV dark background token).
- **Surfaces (tonal elevation, +1…+5):** `#1C1B1F` → `#232228` → `#2A2930` → `#313037` → `#38373E`. Google's spec: *"Surfaces at elevation levels +1 to +5 are tinted via color overlays based on the primary color. This introduces tonal variation to the surface baseline."* ([Focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system))
- **Text:** primary `#E6E1E5` (13.27:1), secondary `#CAC4D0` (10.05:1) — literal tokens.
- **Cards:** tonal fills, `CornerExtraLarge` 56px on the hero, `CornerLarge` 32px on the narrow cards.
- **Accent:** Material primary tonal ramp — `Primary80 = #D0BCFF`, `Primary30 = #4F378B` for containers ([PaletteTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ColorDarkTokens.kt)).
- **Why it works on TV:** it is the platform's own language, so it can never look "wrong" to a Google-TV-trained eye; the tonal ramp gives you hierarchy **without any borders or shadows at all**, which is ideal for a weak renderer.
- **Risk:** `#D0BCFF` is a lavender — it may clash with a fire-themed brand. Swap the hue to a warm amber and rebuild the ramp if so.

---

### Direction D — "Broadcast Editorial"

Treat the home screen like a news/EPG layout rather than an app.

- **Background:** flat `#0A0A0B` — the darkest of the five.
- **Cards:** **no fill at all.** Separated purely by 1px rules in `rgba(255,255,255,0.10)` and by whitespace. The live preview is the only "filled" element on screen.
- **Radius:** 24px on the hero, 16px on the narrow cards — tighter than A/B, more "graphic".
- **Type:** heavier and larger. Hero at **64px Semibold**, section titles at **34px Medium**, metadata **22px**.
- **Accent:** a single high-chroma channel colour, used only for the live flag and underline: `#E8412F` (broadcast red) or brand amber `#FF7A45`.
- **Scrim:** minimal — rely on the flat background instead.
- **Why it works on TV:** maximum information density with maximum calm; the absence of card chrome removes every opportunity for the "cheap" look; large heavy type is the single biggest readability win available.

---

### Direction E — "Warm Ember Dark" (brand-forward)

Direction A, but with the brand hue present as a *very* restrained warm undertone.

- **Background:** `linear-gradient(180deg, #14100E 0%, #0C0A09 60%, #080706 100%)` — neutral dark warmed by ~8° toward red. One gradient, no blobs.
- **Optional single low-opacity ember glow:** **one** radial, `radial-gradient(1200px 700px at 22% 92%, rgba(255,94,45,0.055), transparent 70%)`. Note the opacity: **0.055, i.e. 5.5%** — not the 0.56–0.78 the current mockup uses. It should be barely perceptible.
- **Cards:** `#1A1512` fill, border `1px solid rgba(255,180,140,0.07)`.
- **Accent:** `#FF7A45` for live/focus; `#FFB27A` for hover text.
- **Radius:** 48/40px.
- **Why it works on TV:** keeps the discipline of Direction A while making the app feel branded and warm rather than generic-dark; a *single* 5% glow adds depth without becoming scenery.
- **Guardrail:** if you can see the glow while watching, it's too strong. Halve it.

---

### Direction comparison

| | A Flat Cinema | B Hero Backdrop | C Material Tonal | D Broadcast | E Warm Ember |
|---|---|---|---|---|---|
| Render cost on Android 6 | lowest | medium | lowest | lowest | low |
| Contrast safety | excellent | good (needs scrim) | excellent | excellent | excellent |
| Looks "premium TV" | ✅✅ | ✅✅✅ | ✅✅ | ✅✅ | ✅✅ |
| Risk of looking cheap | very low | medium | low | very low | low |
| Depends on artwork quality | no | **yes** | no | no | no |
| Brand expression | low | medium | medium | medium | **high** |

**Recommendation: build A and E as two mockups and choose between them.** A is the safe, correct, system-faithful answer. E is the same discipline plus brand warmth. If your channel artwork is high quality, also try B.

---

## 6. Anti-patterns — what makes a TV UI look cheap

Two independent design-review references name the exact patterns in your failed mockup. These are third-party sources, not platform vendors, but both are specific and actionable:

**From [Visual Quality and Undesired Model Patterns](https://raw.githubusercontent.com/btfranklin/skills/refs/heads/main/skills/design-ui-style-guide/references/visual-quality-and-ai-tells.md):**
> "Avoid clustering these patterns by default: **Purple-blue mesh gradients, neon glows, glassmorphism, or bokeh orbs used as generic atmosphere**"
> "Keep one consistent container and geometry system. **Use mixed radii, border weights, shadows, or pill treatments only when an explicit semantic rule requires them.**"
> "**Use shadows, blur, transparency, gradient text, and texture only when necessary.** …They must clarify hierarchy or surface treatment."
> "Keep the palette and color temperature consistent. **Do not add unrelated accent colors.**"

**From [AI Slop Detection Catalog](https://github.com/catlog22/Claude-Code-Workflow/blob/5ff5e86257b7e55aec93663930e21ff274f4c023/.claude/skills/team-uidesign/specs/anti-patterns.md), severity P1 = "immediately recognizable as AI slop":**
> "**Glassmorphism Everywhere — P1.** `backdrop-filter: blur()` on more than 2 components. Frosted glass effect as default surface treatment. …**Fix: Reserve glass effect for 1-2 overlay/modal surfaces max.**"
> "**Default Dark Mode with Glowing Accents — P2.** …**Fix:** Use subtle elevation shadows, **not glows**."
> "**AI Color Palette — P1.** Cyan-on-dark, purple-blue gradients, neon accent colors against dark backgrounds. The 'AI dashboard' look."

### 6.1 Audit of the current failed mockup against these

`docs/mockups/home-liquid.html`:

| Line | What it does | Anti-pattern |
|---|---|---|
| 30–32 | 4-stop radial purple gradient base `#171E42 → #080B1A` | purple-blue gradient |
| 34–43 | **4** `filter:blur(170px)` blobs, `mix-blend-mode:screen`, opacity **.78/.72/.60/.56** | bokeh orbs as generic atmosphere (P1) |
| 47–51 | `@keyframes drift` — **perpetual 34s animation** on all 4 blobs | perpetual decorative motion |
| 99 | `border-radius:26px` on an 833px card | radius not on the token scale |
| 101–112 | glass fill (2 gradients) + 2 drop shadows + **4 inset highlights** | glassmorphism on every surface (P1) |
| 114–122 | 1.4px white gradient border overlay | decorative border |
| 124–127 | top gloss band `rgba(255,255,255,.085)` | fake glass thickness |
| 142–143 | focus adds `0 0 64px rgba(126,168,255,.34)` | neon glow (P2) |
| 63–68 | logo with 3-layer shadow + 2 insets + text-shadow | layered decoration on a 58px icon |
| 72, 187, 191, 218 | `font-weight:300` at 42–62% opacity | thin text + sub-AA contrast |

**That is eight distinct P1/P2 anti-patterns in one screen.** The redesign should be defined largely by what it *removes*.

### 6.2 The five rules to enforce

1. **One background idea.** Flat or one gradient. If you use a glow, it is one radial at ≤6% opacity and it does not move.
2. **Glass on at most one surface** — and since you have no blur, effectively zero. Use flat fills and tonal steps.
3. **One radius family.** 48/40px. No pills except the clock chip, no mixed radii without a reason.
4. **No neon.** Glows are black and soft (`rgba(0,0,0,...)`), not coloured.
5. **No weight below 400, no text below 7:1.** Non-negotiable on TV.

---

## 7. Implementation-ready spec sheet

### 7.1 Colour tokens (Direction A / E)

```css
/* Background */
--bg-base:        #0B0D12;                    /* flat, the single most important change */
--bg-grad:        linear-gradient(180deg, #14171E 0%, #0B0D12 55%, #07090D 100%);
--bg-ember:       #14100E;                    /* Direction E variant */
--bg-ember-grad:  linear-gradient(180deg, #14100E 0%, #0C0A09 60%, #080706 100%);

/* Surfaces — tonal steps, no glass */
--surface-0:      #0F1218;                    /* card base */
--surface-1:      #15181F;                    /* raised / focused card */
--surface-2:      #1C2029;                    /* chips, badges */

/* Borders — barely there */
--border-subtle:  rgba(255,255,255,0.06);
--border-focus:   rgba(255,255,255,0.85);

/* Text — all verified ≥7:1 */
--text-primary:   #F0F1F5;   /* 17.22:1 */
--text-secondary: #C8C9D0;   /* 11.77:1 */
--text-tertiary:  #9A9BA4;   /*  7.03:1  ← floor, never go below */

/* Accent */
--accent-live:    #FF4D5E;                    /* Direction A */
--accent-warm:    #FF7A45;                    /* Direction E / B */
--accent-warm-lt: #FFB27A;
```

### 7.2 Scrim gradients

```css
/* Left live-preview scrim — clear at top, near-opaque at bottom */
.veil { background: linear-gradient(180deg,
  rgba(7,9,13,0)    0%,
  rgba(7,9,13,0)   45%,
  rgba(7,9,13,.55) 72%,
  rgba(7,9,13,.92) 100%); }

/* Narrow card poster scrim — protect the bottom text block */
.psc { background: linear-gradient(180deg,
  rgba(7,9,13,.35)  0%,
  rgba(7,9,13,.10) 28%,
  rgba(7,9,13,.62) 68%,
  rgba(7,9,13,.94) 100%); }
```

### 7.3 Focus states (from Google's published values)

```css
.card                 { border-radius:48px; }        /* 833px block */
.card.narrow          { border-radius:40px; }        /* 248px cards */
.card:focus           { transform: scale(1.05);      /* published default: 1.025/1.05/1.1 */
                        border: 2px solid rgba(255,255,255,0.85);
                        box-shadow: 0 24px 64px rgba(0,0,0,0.85); } /* glow 2–32dp → 4–64px */
```
Transition: `transform 150ms cubic-bezier(0.25,1,0.5,1), border-color 150ms linear`. Avoid bounce/elastic easing — named as a "toylike" anti-pattern.

### 7.4 Type scale (px @1920×1080)

| Role | Size | Weight | Colour | Contrast |
|---|---|---|---|---|
| Hero channel name | 60px | 600 | `--text-primary` | 17.2:1 |
| Section title 影视/短剧 | 34px | 500 | `--text-primary` | 17.2:1 |
| Body / subtitle | 26px | 400 | `--text-secondary` | 11.8:1 |
| Metadata 影视库 12 部 | 22px | 400 | `--text-tertiary` | 7.0:1 |
| Live label 正在直播 | 22px | 500 | `--text-secondary` | 11.8:1 |
| Minimum anywhere | **20px** | 400 | ≥ `--text-tertiary` | ≥7:1 |

Track the hero slightly negative (`letter-spacing: -0.5px`); SF Pro's own tracking table goes negative above 13pt — [Apple HIG Typography](https://developer.apple.com/design/human-interface-guidelines/typography).

### 7.5 Spacing

- Side margin **127px** (keep — matches Google's 58–64dp recommendation).
- Top margin **207px** for cards (keep), gutter **134px** (keep).
- Card inner padding: **48px** horizontal, **44px** vertical.
- Vertical gap between the two narrow cards: use **40px** (20dp gutter × 2) rather than a proportionally-scaled value, and check it against the approved layout before changing anything.

---

## 8. Direct answers to your five questions

1. **Real launcher backgrounds:** flat near-black. Google TV uses `#1C1B1F`; Apple tvOS uses a flat dark backdrop with one full-bleed hero; the 2025 Google TV redesign was "mainly a reorganization". None use gradient blobs.
2. **Card treatment:** Google's TV cards are flat images with no chrome by default — radius from the 4/8/12/16/28dp token scale, border/glow/scale used only as *focus states*, and a scrim is mandatory for text over imagery. No shadows or borders on the resting state.
3. **Radius for an 833px card:** 48px (24dp) recommended; 56px (28dp, the `CornerExtraLarge` token) if you want strict system fidelity. The current 165px is 2.4–3× too large. Radius should be near-constant across the family, not proportional to width.
4. **Contrast/readability:** 5% overscan margin = **48dp sides / 27dp top-bottom** (96px / 54px for you), 12-column grid at 52dp + 20dp gutters. tvOS text: Body 29pt Medium, minimum 23pt, and never below Regular weight. Target **≥7:1** contrast on TV, not WCAG's 4.5:1 web floor. Apple: "avoid light font weights".
5. **Anti-patterns:** purple-blue mesh gradients, bokeh orbs, glassmorphism on >2 surfaces, coloured neon glows, mixed radii, sub-400 font weights, sub-7:1 text, perpetual background animation. The current mockup contains eight of these.

---

## Sources

**Primary / official (Tier A):**
- [Layouts — TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/layouts) — 960×540 MDPI, 1px=1dp, 5% overscan, 12-col grid, card widths
- [Cards — TV | Android Developers](https://developer.android.com/design/ui/tv/guides/components/cards) — variants, aspect ratios, 844dp 1-card, scrim requirement
- [Focus system — TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/focus-system) — scale 1.025/1.05/1.1, glow 2–32dp, tonal elevation
- [Typography — TV | Android Developers](https://developer.android.com/design/ui/tv/guides/styles/typography) — Roboto, 15-style scale, legibility rules
- [Build TV layouts — Android Developers](https://developer.android.com/training/tv/playback/compose/layouts) — overscan 48dp/27dp, light-on-dark, layout anti-patterns
- [Support different pixel densities](https://developer.android.com/training/multiscreen/screendensities) — density table, `px = dp × (dpi/160)`
- [androidx.tv.material3.Card](https://developer.android.com/reference/kotlin/androidx/tv/material3/Card.composable) — CardShape/CardColors/CardScale/CardBorder/CardGlow APIs
- [ShapeTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ShapeTokens.kt) — 4/8/12/16/28dp radius scale
- [ColorDarkTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/ColorDarkTokens.kt) — background `#1C1B1F`, text `#E6E1E5`
- [TypeScaleTokens.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/tokens/TypeScaleTokens.kt) — TV type sizes 11–57sp
- [Material 3 — Cards specs](https://m3.material.io/components/cards/specs)
- [Material 3 — Corner radius scale](https://m3.material.io/styles/shape/corner-radius-scale)
- [Apple HIG — Typography](https://developer.apple.com/design/human-interface-guidelines/typography) — tvOS table, 29pt default / 23pt min, avoid light weights
- [Apple HIG — Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf) — full-screen hero, 1920px fill, 16:9 sizes

**Secondary (Tier B — used for platform/news context and design-review heuristics):**
- [9to5Google — Google TV homescreen redesign (Nov 2025)](https://9to5google.com/2025/11/13/google-tv-homescreen-redesign-2025/)
- [tvOS design guidelines (HIG extract)](https://github.com/dirnbauer/webconsulting-skills/blob/main/skills/tvos-design/SKILL.md)
- [Visual Quality and Undesired Model Patterns](https://raw.githubusercontent.com/btfranklin/skills/refs/heads/main/skills/design-ui-style-guide/references/visual-quality-and-ai-tells.md)
- [AI Slop Detection Catalog](https://github.com/catlog22/Claude-Code-Workflow/blob/5ff5e86257b7e55aec93663930e21ff274f4c023/.claude/skills/team-uidesign/specs/anti-patterns.md)
- [webOS Design Considerations](https://nightly.enyojs.com/enyo-nightly-20180328015137/docs/developer-guide/design/webOS/index.html)

**Limitations:** `m3.material.io` ships a JavaScript-only shell, so its numeric specs were taken from the Material/androidx source tokens instead (linked above) rather than scraped from the site. Apple's HIG pages likewise render client-side; the tvOS type table and Top Shelf sizes were read from HIG reference extracts and cross-checked against the official `developer.apple.com` page URLs. Blur availability on Android 6 was not independently verified online — it was taken as given from the task brief and is consistent with `RenderEffect` being an API 31+ class.
