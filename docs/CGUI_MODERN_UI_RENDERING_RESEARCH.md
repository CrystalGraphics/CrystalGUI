# Modern UI rendering — reference research

**What this is.** The primary sources behind CrystalOS's taskbar, glass, blur and gradient work, with
the exact numbers each one publishes and what each implies for this engine. It exists because the
first version of every one of these was built from memory and looked plausible while being wrong in a
way only the source could show: a Gaussian that was a comb, a "Mica" that let hue through, a gradient
that banded. **Read the relevant section before touching `gui_glass.shader`, `gui_blur.shader`,
`gui_gradient.shader`, `CgUiBackdrop` or the taskbar's sheet.** Every claim below carries its source;
where a number is measured or community-reported rather than published, it says so.

Last researched 2026-08-26. The "Implications" column in each section is ours; everything else is
the source's.

---

## 1. Windows materials — Mica and Acrylic

### 1.1 What they are (Microsoft's own definitions)

- **Mica** is *opaque*. It "incorporates theme and desktop wallpaper" and "only samples the desktop
  wallpaper once to create its visualization"; it is for "long-lived windows such as apps and
  settings", applied as the base layer with the title bar showing it. It "falls back to a neutral
  color when the app is inactive." **Mica Alt** is the same with "stronger tinting of the user's
  desktop background color", intended for a **tabbed title bar**. Fallbacks are
  `SolidBackgroundFillColorBase` (Mica) / `SolidBackgroundFillColorBaseAlt` (Mica Alt), used when
  transparency is off, on battery saver, on low-end hardware, when the window deactivates, and below
  build 22000. Recommendations: set every layer above it transparent; **never apply a backdrop
  material more than once** in an app; never apply it to a UI element (it only appears through
  transparent layers down to the window).
  — [Mica material](https://learn.microsoft.com/en-us/windows/apps/design/style/mica),
  [Use Mica in Win32 apps](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-mica-win32)
- **Acrylic** is "a translucent texture" for **transient, light-dismiss surfaces** — context menus,
  flyouts, non-modal popups, panes that overlap content. Two blend types: *background acrylic*
  (reveals the desktop and other windows) and *in-app acrylic* (reveals the app's own content).
  Do's and don'ts, verbatim: "Do use acrylic on transient surfaces. Do extend acrylic to at least one
  edge of your app. **Don't put desktop acrylic on large background surfaces. Don't place multiple
  acrylic panes next to each other** because this results in an undesirable visible seam. **Don't
  place accent-colored text over acrylic.**" Rendering it "is GPU-intensive"; it is disabled on
  battery saver and by the user's transparency setting.
  — [Acrylic material](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
- **The acrylic recipe**, as Microsoft draws it: *background → blur → exclusion blend → color/tint
  overlay → noise*. "We added an exclusion blend mode layer to ensure contrast and legibility of UI
  placed on an acrylic background."
- **Fluent 2's material taxonomy**: *Solid* (opaque, elevation), *Acrylic* (transient surfaces),
  *Mica* (subtly tinted with the desktop colour on an **active** window, neutral when inactive),
  *Smoke* (an always-translucent-black dimming layer under modals; not mode-aware).
  — [Fluent 2 · Material](https://fluent2.microsoft.design/material)

### 1.2 The acrylic effect graph, from WinUI's source (the numbers)

From `controls/dev/Materials/Acrylic/AcrylicBrush.h` and `.cpp` in
[microsoft/microsoft-ui-xaml](https://github.com/microsoft/microsoft-ui-xaml):

| Constant | Value | Meaning |
|---|---|---|
| `sc_blurRadius` | **30.0f** | Gaussian blur, *Hard* border mode |
| `sc_saturation` | **1.25f** | a SaturationEffect on the blurred backdrop — the backdrop is **boosted**, not desaturated |
| `sc_noiseOpacity` | **0.02f** | the noise texture, tiled (BorderEffect wrap), at 2% |
| `sc_exclusionColor` | `{26, 255, 255, 255}` | white at **10%**, the exclusion layer of the older recipe |
| `sc_defaultTintColor` | `{204, 255, 255, 255}` | white at 80% — the legacy default tint |
| `sc_defaultTintOpacity` | 1.0 | |

The graph for a semi-transparent tint, in order: the backdrop is **composited SourceOver onto the
opaque fallback colour first** (so the input to the blur is opaque — exactly the alpha problem
`CgUiBackdrop`'s alpha-clear fixes) → Gaussian blur → a **luminosity blend** layer (a `BlendEffect`
whose mode the code calls `Color`, with a comment that the enum names are flipped) → a **tint**
blend layer (`Luminosity` mode, same caveat) → noise at `sc_noiseOpacity`, SourceOver.

Two formulas that make WinUI's tints behave, quoted from `GetEffectiveTintColor` /
`GetEffectiveLuminosityColor`:

- The **tint's alpha is compressed** by an HSV-derived modifier — "pure white maps to 45%, pure black
  to 85%" — so a white tint can never fully hide the backdrop and a black one nearly can.
- When `TintLuminosityOpacity` is unset, the luminosity layer's opacity is derived from the tint's
  alpha: `mappedTintOpacity = (tintColor.A / 255) × (1.03 − 0.15) + 0.15`, clamped to 1; the tint's
  HSV value is clamped to `[0.125, 0.965]` first.

**What "luminosity opacity" is.** `TintLuminosityOpacity` "controls the amount of saturation that is
allowed through the acrylic surface from the background" — "a lower value means more brightness from
the underlying pixels will be allowed through and a higher value means more brightness from the
TintColor property will be applied." Mechanically it is the W3C *luminosity* blend: the result takes
the **backdrop's hue and saturation with the tint's luminance** (`SetLum(backdrop, Lum(tint))`),
applied at that opacity, and only then is the tint colour itself alpha-blended at `TintOpacity`.
That is how Windows gets a surface that reads as a *temperature* — the wallpaper's hue survives at
its own saturation — while its brightness is the tint's. A plain alpha blend of the tint (what
`gui_glass.shader` does today) dims hue and brightness together and cannot separate the two.
— [AcrylicBrush.TintLuminosityOpacity](https://learn.microsoft.com/en-us/uwp/api/windows.ui.xaml.media.acrylicbrush.tintluminosityopacity?view=winrt-22621),
[W3C Compositing and Blending Level 1 §10 non-separable blend modes](https://www.w3.org/TR/compositing-1/#blendingnonseparable)

### 1.3 The shipped brush values (WinUI theme resources)

From [microsoft-ui-xaml issue #3478](https://github.com/microsoft/microsoft-ui-xaml/issues/3478),
which lists the resources verbatim:

| Brush | Dark | Light |
|---|---|---|
| `SystemControlTransientBackgroundBrush` (flyouts, menus) | Tint `#2C2C2C`, TintOpacity **0.15**, LuminosityOpacity **0.96**, fallback `#2C2C2C` | Tint `#FCFCFC`, 0.0, 0.85, fallback `#FCFCFC` |
| `SystemControlBaseAcrylicBrush` (base surfaces) | Tint `#202020`, TintOpacity **0.0**, LuminosityOpacity **0.96**, fallback `#202020` | Tint `#F3F3F3`, 0.0, 0.9, fallback `#F3F3F3` |

Read those numbers against §1.2: Windows' acrylic is almost entirely a **luminosity** layer (0.96)
with a whisper of colour tint (0.15 or none). The backdrop's hue is kept; its brightness is replaced.

The Mica controller's defaults are not published on Learn (the controller pages document only the
four properties). The values widely reproduced from the SDK are Base dark `#202020` at tint 0.8 /
luminosity 1.0 and light `#F3F3F3` at 0.5 / 1.0; Base Alt dark `#0A0A0A` at 0.0 / 1.0 and light
`#DADADA` at 0.5 / 1.0. **Treat these as community-reported.**
— [MicaController](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.composition.systembackdrops.micacontroller?view=windows-app-sdk-1.8),
[System backdrops](https://learn.microsoft.com/en-us/windows/apps/develop/ui/system-backdrops)

### 1.4 Implications for CrystalGUI

- `gui_glass.shader`'s `tint` was an alpha mix; Windows' is luminosity-blend + tint. **Done:** a
  `luminosity` term (`SetLum(backdrop, Lum(tint))` at a `luminosity` opacity, then the colour tint at
  `tint`'s alpha), so the backdrop can only ever contribute hue — the temperature without the colour.
  Recipe for the **bar** (Mica, base surface): the bar's own dark at tint 0.8, luminosity 1, saturation
  1, blur heavy. Recipe for **previews / jump list / switcher** (transient): acrylic — saturation 1.25,
  luminosity 0.96, tint `#2C2C2C`-class at 0.15, noise 0.02. *(The bar's maroon, which this section
  was first written to cure, turned out to be something else entirely: its tint pin was written
  alpha-first — the shipped `#E01F2023`, and again as `#EB1C1D21` when this section's recipe replaced
  it — which the sheet's `#RRGGBBAA` parser reads as a pure red at ~13%. The luminosity term is still
  the right material; it was never the fix for that.)*
- The backdrop must be **opaque before it is blurred** — WinUI composites onto the fallback first.
  `CgUiBackdrop` clears alpha to 1 after the scene blit for the same reason.
- Never two acrylics edge to edge; the preview above the bar is a *transient over a base*, which is
  Windows' own layering, not the seam case.
- Accent text on acrylic fails contrast; the pills carry the accent, the labels do not.

---

## 2. Fluent 2 / WinUI colour tokens (fills, strokes, text)

From the [Fluent v2 resource table](https://amwx.github.io/FluentAvaloniaDocs/pages/Resources)
(FluentAvalonia's transcription of WinUI's `Common_themeresources_any.xaml`; the page's light/dark
columns are transposed, corrected here against WinUI's naming — dark fills are white-with-alpha):

| Token | Dark | Light | Role |
|---|---|---|---|
| `SubtleFillColorSecondary` | `#0FFFFFFF` (5.9% white) | `#09000000` | **hover** on a list item / taskbar button |
| `SubtleFillColorTertiary` | `#0AFFFFFF` (3.9%) | `#06000000` | **pressed** |
| `ControlFillColorDefault` | `#0FFFFFFF` | `#B3FFFFFF` | a control's rest fill |
| `ControlFillColorSecondary` | `#15FFFFFF` (8.2%) | `#80F9F9F9` | control hover |
| `ControlFillColorTertiary` | `#08FFFFFF` | `#4DF9F9F9` | control pressed |
| `ControlStrokeColorDefault` | `#12FFFFFF` | `#0F000000` | control border |
| `CardStrokeColorDefault` | `#19000000` | `#0F000000` | card border |
| `LayerFillColorDefault` | `#4C3A3A3A` | `#80FFFFFF` | the content layer over Mica |
| `SolidBackgroundFillColorBase` | `#202020` | `#F3F3F3` | Mica's fallback |
| `SolidBackgroundFillColorSecondary` | `#1C1C1C` | `#EEEEEE` | |
| `SolidBackgroundFillColorTertiary` | `#282828` | `#F9F9F9` | |
| `TextFillColorPrimary` | `#FFFFFF` | `#E4000000` | |
| `TextFillColorSecondary` | `#C5FFFFFF` (77%) | `#9E000000` | |
| `TextFillColorTertiary` | `#87FFFFFF` (53%) | `#72000000` | |
| `TextFillColorDisabled` | `#5DFFFFFF` (36%) | `#5C000000` | |

**Implications.** Fluent's hover/press fills are *translucent white over whatever is there*, so they
read the same on Mica, acrylic and solid. Ours are opaque greys derived from the panel ladder, which
is why `--pressed-bg` vanished on the darker bar and had to be pinned. A translucent-white pair for
the bar's entries (`#0FFFFFFF` hover, `#0AFFFFFF` pressed, and a stronger step for *active*) is the
faithful port; the theme system cannot derive alpha, so they are pins.
— [XAML theme resources](https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources)

---

## 3. Windows 11 taskbar geometry

- Height **48 px** at 100% scaling (the *medium* size; small 32, large 72).
  — [Tom's Hardware](https://www.tomshardware.com/how-to/change-taskbar-icon-size-windows-11),
  [Windows Forum on the 2025 size setting](https://windowsforum.com/threads/windows-11-insider-june-26-new-taskbar-size-setting-explorer-and-security-fixes.431030/)
- Task buttons (`Taskbar.TaskListButton`) carry a **4 px corner radius** by default; tray icons are
  16 px in 32 px cells.
  — [Windows 11 taskbar styling guide](https://github.com/ramensoftware/windows-11-taskbar-styling-guide)
- The running indicator is the XAML element `Rectangle#RunningIndicator` under
  `Taskbar.TaskListLabeledButtonPanel#IconPanel`, with `RunningIndicatorStates` (`NoRunningIndicator`,
  `ActiveRunningIndicator`); the hover/active face is `Border#BackgroundElement`. Its *default* size
  is not published; measured, it is a **3 px-tall pill, ~6 px wide for a running window and ~16 px for
  the active one**, in the accent for the active window and a neutral for the rest — "a small
  pill-shaped indicator that changes size" ([Windows Central](https://www.windowscentral.com/whats-new-taskbar-windows-11)).
  *Measured/community values; treat the widths as approximate.*
- Previews, tooltips and the overflow menu are rounded and drawn on acrylic.

**Implications.** Our bar is 34 logical px = 68 surface px at uiScale 2, against Windows' 48 of a
1080 row: proportionally heavier, which is the price of a 2× UI over a 1× game. The pill (6/16 × 3),
the 4 px radius and the acrylic previews are the port; the taskbar's own material on Windows 11 is
Mica-class (opaque, wallpaper-tinted), not acrylic.

---

## 4. IntelliJ New UI — coloured project headers

- Since **2023.2**: "colored headers to simplify navigation between multiple open projects"; a
  project gets a colour and an icon; *Change Project Color* in the header's context menu offers a
  suggested list or the full palette; **Show Project Gradient** toggles the gradient, and *Appearance
  → Use project colors in main toolbar* turns it off globally.
  — [What's new in 2023.2](https://www.jetbrains.com/idea/whatsnew/2023-2/),
  [Menus and toolbars](https://www.jetbrains.com/help/idea/customize-actions-menus-and-toolbars.html)
- The New UI became the default in 2024.2; the *Islands* look (2025) has a registry key
  `idea.islands.color.gradient.enabled` for "brighter color gradients".
  — [The New UI becomes the default](https://blog.jetbrains.com/blog/2024/07/08/the-new-ui-becomes-the-default-in-2024-2/),
  [Per-project IDE colour (KB)](https://youtrack.jetbrains.com/articles/SUPPORT-A-2608/How-to-set-up-per-project-IDE-color-in-IntelliJ-IDEs)
- Visually: a linear wash of the project colour across the main toolbar, strongest at the left where
  the project widget sits, fading to the frame colour — an *identity* cue, not decoration.

**Implications.** The taskbar equivalent is a wash centred under the entry cluster (the bar's
centre of gravity), in the accent at low alpha, fading to the bar at either side. It must be
dithered (§7) or it bands.

---

## 5. Apple Liquid Glass (iOS 26 / macOS 26)

- Two variants, **regular** ("the most versatile and common") and **clear**. Clear "should be placed on
  top of a dimming layer to improve legibility"; foreground on clear is effectively white-only.
- It "forms a distinct functional layer for controls and navigation elements — like tab bars and
  sidebars — that floats above the content layer"; "**Don't use Liquid Glass in the content layer.**"
- Contrast: no published ratio; observed target about 4.5:1 for regular.
  — [Meet Liquid Glass (WWDC25)](https://developer.apple.com/videos/play/wwdc2025/219/),
  [HIG](https://developer.apple.com/design/human-interface-guidelines/),
  [Liquid Glass guidance for designers](https://designedforhumans.tech/blog/liquid-glass-smart-or-bad-for-accessibility)
- The lens itself — height profile, refraction, the specular rim — is researched in
  `plan_glass.md`; this section is only the *usage* guidance.

**Implications.** The taskbar is exactly a "functional layer above content"; the editor's islands
are content and must never be glass. The clear variant's dimming-layer rule is the same as Windows'
Smoke and the same reason our bar's tint is heavy.

---

## 6. Gaussian blur, as production renderers do it

- **Skia** (`GrBlurUtils` / `skgpu::BlurUtils`): `kMaxSigma = skgpu::kMaxLinearBlurSigma` (4). If
  `sigma > kMaxSigma` the source is **downscaled by `kMaxSigma / sigma`** on that axis (dimensions
  rounded *down* "so that when we recalculate sigmas we know they will be below kMaxSigma"), blurred
  there, then re-expanded with linear interpolation. The kernel radius comes from sigma
  (`BlurSigmaRadius`, three sigma), 1-D passes use **linear-filtered taps** (`Compute1DBlurLinearKernel`)
  to halve the sample count, and very small blurs ("no wider than 5×5") run as one non-separable
  pass because two render-pass switches cost more. Clamp mode adds a border of edge pixels before
  the downscale so the clamp reads real content.
  — [GrBlurUtils.cpp](https://skia.googlesource.com/skia/+/2e551697dc56/src/gpu/ganesh/GrBlurUtils.cpp),
  [skia-discuss: Skia blur algorithm](https://groups.google.com/g/skia-discuss/c/mL2iaiwulmc)
- **Flutter/Impeller** independently moved to "scale down the Gaussian blur in both directions prior
  to blurring" for the same reason.
  — [flutter#131580](https://github.com/flutter/flutter/issues/131580)
- **Linear sampling**: one bilinear tap between two texels equals two taps at the right weights, so a
  9-tap kernel becomes 5 fetches. — [RasterGrid](https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/)
- **Incremental Gaussian**: weights by a three-multiply recurrence instead of `exp` per tap
  (GPU Gems 3, ch. 40).
- A Gaussian's reach: taps beyond 3σ carry under half a percent; a *kernel is only a Gaussian if its
  taps are about a texel apart*. Stretching a fixed tap count to reach a radius is a comb.
  — [demofox: Gaussian Blur](https://blog.demofox.org/2015/08/19/gaussian-blur/)

**Implications.** `CgUiBackdrop.blurredBackdrop` follows this: σ = reach/3; scale until σ ≤ 4; a box
prefilter does the reduction; taps one texel apart, `ceil(3σ)` per side, incremental weights,
renormalised. Two refinements still open: Skia's scale is the *exact ratio* `4/σ` rather than a power
of two (ours is 1/2/4 because the box prefilter reduces cleanly only by integers), and linear-filtered
taps would halve the fetch count.

---

## 7. Backdrop edges — clamp vs mirror

- The CSS spec first had `backdrop-filter` sample outside the element by **duplicating edge pixels**;
  that "results in extreme flickering of content as it enters the backdrop edge" — "a tiny scroll
  causes the edge pixels to change, which causes the extended pixels to completely change." The spec
  now **mirrors** the backdrop beyond the edge, "which allows for a smooth gradual introduction of new
  colors at the edges without overweighting on single lines of color"; Chromium shipped
  `BackdropFilterMirrorEdgeMode` by default and it "closely or exactly" matches Safari.
  — [Chrome Platform Status](https://chromestatus.com/feature/5382638738341888),
  [blink-dev PSA](https://groups.google.com/a/chromium.org/g/blink-dev/c/ZtMnFCHZhMQ/m/ewdpvCq_AQAJ),
  [WebKit standards position #372](https://github.com/WebKit/standards-positions/issues/372),
  [Jim Fisher: backdrop blur without the flickering](https://jameshfisher.com/2024/04/23/backdrop-blur-without-the-flickering/)

**Implications.** `gui_blur.shader`'s `_Bounds` clamp is the *duplicate* rule. It is mostly hidden
because `CgUiBackdrop` pads the captured region by the blur's reach, so the clamp only bites at the
screen's own edges — which is precisely where the bar lives. Mirroring the tap coordinate into the
sub-rect (`u' = lo + |((u − lo) mod 2w) − w|` style reflection) is the spec's answer and removes the
edge streak at the bottom lip for good.

---

## 8. Gradient banding and dithering

- An 8-bit framebuffer quantises any shallow ramp into flat bands; per-fragment interpolation removes
  strip edges but not level edges. Skia's gradient shader dithers (`SkGradientShader`, a 0..3 toggle
  at 0.25 steps of the cache index — "4× higher resolution"); Chrome historically compiled it out with
  `SK_DISABLE_DITHER_32BIT_GRADIENT`, which is why web gradients banded; Firefox filed the same.
  — [SkGradientShader.cpp](https://skia.googlesource.com/skia/+/refs/heads/chrome/m54/src/effects/gradients/SkGradientShader.cpp),
  [chromium 41756](https://groups.google.com/a/chromium.org/g/chromium-bugs/c/O89TeGis1c4/m/B01cAt-OAAAJ),
  [mozilla 1168879](https://bugzilla.mozilla.org/show_bug.cgi?id=1168879),
  [react-native-skia #1368](https://github.com/Shopify/react-native-skia/issues/1368),
  [flutter #132860](https://github.com/flutter/flutter/issues/132860)
- Shader practice: add random noise of amplitude **±0.5/255** (`NOISE_GRANULARITY = 0.5/255.0`)
  to the colour *before* quantisation; unordered (hash) noise avoids the pattern ordered dithers show.
  — [Shader tutorial: colour banding and dithering](https://shader-tutorial.dev/advanced/color-banding-dithering/)

**Implications.** `gui_gradient.shader` is written to all of this and measured against it on the
`cgui-gradient-probe` scene: one draw evaluates up to eight stops per fragment along CSS's gradient
line (Skia's unrolled shape — a longer gradient is several draws, each owning a window of *t*),
interpolates **premultiplied** (CSS Images 3 §3.4.3: `transparent` is transparent black, so a straight
lerp toward it passes through a dark half-colour), and adds `hash12(gl_FragCoord) − 0.5` over 255 to
every channel including alpha (the bands in a translucent wash are alpha bands) as the LAST operation
before quantisation. Readback of a 16-level ramp across 1872px: column means step at most **0.23
levels** (a band edge is 1.0), dither σ 0.34; the fade to transparent matches the premultiplied
prediction to 0.1 level where straight interpolation would be off by up to 60. Strips were tried first
and banded at under two levels per twenty pixels; a quad per segment was tried second and drew each
segment with the previous one's colours, because the paint context uploads a material's properties on
the bind *after* the draw body.

---

## 9. Quick reference — the recipes, in this engine's units

| Surface | Material | Recipe |
|---|---|---|
| Taskbar (base, long-lived) | Mica-class | heavy blur, saturation ~1.0, **luminosity ≈ 1.0**, tint `#202020`-class at ≈0.8 (community) → reads as temperature only; opaque fallback `SolidBackgroundFillColorBase` |
| Preview / jump list / switcher (transient) | Acrylic | blur **30**, saturation **1.25**, **luminosity 0.96**, tint `#2C2C2C` at **0.15**, noise **0.02**, fallback `#2C2C2C` |
| Entry hover / pressed | Subtle fill | `#0FFFFFFF` / `#0AFFFFFF` over the material (light: `#09000000` / `#06000000`) |
| Running / active pill | — | 3 px tall; ~6 px / ~16 px; accent only when active |
| Any gradient | — | one draw, ≤8 stops per fragment along the CSS gradient line, **premultiplied** interpolation, ±0.5/255 hash dither last |
| Any backdrop blur | — | σ = reach/3; scale until σ ≤ 4; taps 1 texel apart, ⌈3σ⌉ per side; **mirror** at edges |

## 10. Sources (all)

- https://learn.microsoft.com/en-us/windows/apps/design/style/mica
- https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-mica-win32
- https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic
- https://learn.microsoft.com/en-us/windows/apps/develop/ui/system-backdrops
- https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.composition.systembackdrops.micacontroller?view=windows-app-sdk-1.8
- https://learn.microsoft.com/en-us/uwp/api/windows.ui.xaml.media.acrylicbrush.tintluminosityopacity?view=winrt-22621
- https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources
- https://github.com/microsoft/microsoft-ui-xaml (controls/dev/Materials/Acrylic/AcrylicBrush.h, .cpp)
- https://github.com/microsoft/microsoft-ui-xaml/issues/3478
- https://github.com/microsoft/microsoft-ui-xaml/issues/1191
- https://fluent2.microsoft.design/material
- https://amwx.github.io/FluentAvaloniaDocs/pages/Resources
- https://www.w3.org/TR/compositing-1/#blendingnonseparable
- https://github.com/ramensoftware/windows-11-taskbar-styling-guide
- https://www.windowscentral.com/whats-new-taskbar-windows-11
- https://www.tomshardware.com/how-to/change-taskbar-icon-size-windows-11
- https://www.jetbrains.com/idea/whatsnew/2023-2/
- https://www.jetbrains.com/help/idea/customize-actions-menus-and-toolbars.html
- https://blog.jetbrains.com/blog/2024/07/08/the-new-ui-becomes-the-default-in-2024-2/
- https://youtrack.jetbrains.com/articles/SUPPORT-A-2608/How-to-set-up-per-project-IDE-color-in-IntelliJ-IDEs
- https://developer.apple.com/videos/play/wwdc2025/219/
- https://developer.apple.com/design/human-interface-guidelines/
- https://designedforhumans.tech/blog/liquid-glass-smart-or-bad-for-accessibility
- https://skia.googlesource.com/skia/+/2e551697dc56/src/gpu/ganesh/GrBlurUtils.cpp
- https://groups.google.com/g/skia-discuss/c/mL2iaiwulmc
- https://github.com/flutter/flutter/issues/131580
- https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/
- https://blog.demofox.org/2015/08/19/gaussian-blur/
- https://chromestatus.com/feature/5382638738341888
- https://groups.google.com/a/chromium.org/g/blink-dev/c/ZtMnFCHZhMQ/m/ewdpvCq_AQAJ
- https://github.com/WebKit/standards-positions/issues/372
- https://jameshfisher.com/2024/04/23/backdrop-blur-without-the-flickering/
- https://skia.googlesource.com/skia/+/refs/heads/chrome/m54/src/effects/gradients/SkGradientShader.cpp
- https://groups.google.com/a/chromium.org/g/chromium-bugs/c/O89TeGis1c4/m/B01cAt-OAAAJ
- https://bugzilla.mozilla.org/show_bug.cgi?id=1168879
- https://github.com/Shopify/react-native-skia/issues/1368
- https://github.com/flutter/flutter/issues/132860
- https://shader-tutorial.dev/advanced/color-banding-dithering/
