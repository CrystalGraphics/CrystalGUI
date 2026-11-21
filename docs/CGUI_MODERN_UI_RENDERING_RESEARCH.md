# Modern UI rendering — reference research

The primary sources behind CrystalOS's taskbar, glass, blur and gradients, with the numbers each publishes. **Read
the relevant section before touching CrystalGraphics' `ui/glass.shader`, `CgGpuOps.blurScaled`, `paint/gradient.glsl`,
`CgBackdropFilter` or the taskbar's sheet.** Each section's *Implications* are ours; the rest is the source's.
Community-reported numbers are marked. Researched 2026-08-26.

## 1. Windows materials — Mica and Acrylic

### 1.1 What they are (Microsoft's own definitions)

- **Mica** is opaque: it samples the wallpaper once, for long-lived windows, as the base layer under a transparent
  title bar, and falls back to a neutral colour when inactive. **Mica Alt** tints more strongly, for tabbed title
  bars. Fallbacks `SolidBackgroundFillColorBase` / `...BaseAlt` apply with transparency off, on battery saver, on
  low-end hardware, when inactive and below build 22000. Never apply a backdrop material twice in an app, nor to a UI
  element. — [Mica](https://learn.microsoft.com/en-us/windows/apps/design/style/mica),
  [Mica in Win32](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-mica-win32)
- **Acrylic** is translucent, for transient light-dismiss surfaces (menus, flyouts, popups), as *background*
  (desktop) or *in-app* acrylic. "Don't put desktop acrylic on large background surfaces. Don't place multiple
  acrylic panes next to each other ... Don't place accent-colored text over acrylic." It is disabled on battery saver
  and by the transparency setting. — [Acrylic](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
- **The acrylic recipe**: background → blur → exclusion blend → colour/tint overlay → noise.
- **Fluent 2's materials**: Solid, Acrylic, Mica (tinted when active, neutral when inactive) and Smoke (translucent
  black under modals). — [Fluent 2 · Material](https://fluent2.microsoft.design/material)

### 1.2 The acrylic effect graph, from WinUI's source (the numbers)

`controls/dev/Materials/Acrylic/AcrylicBrush.h` / `.cpp` in
[microsoft/microsoft-ui-xaml](https://github.com/microsoft/microsoft-ui-xaml):

| Constant | Value | Meaning |
|---|---|---|
| `sc_blurRadius` | **30.0f** | Gaussian blur, *Hard* border mode |
| `sc_saturation` | **1.25f** | the blurred backdrop is boosted, not desaturated |
| `sc_noiseOpacity` | **0.02f** | tiled noise at 2% |
| `sc_exclusionColor` | `{26, 255, 255, 255}` | white at 10%, the older recipe's exclusion layer |
| `sc_defaultTintColor` | `{204, 255, 255, 255}` | white at 80%, the legacy default tint |
| `sc_defaultTintOpacity` | 1.0 | |

For a semi-transparent tint: the backdrop is composited SourceOver onto the **opaque fallback first** → Gaussian
blur → a luminosity blend layer → a tint layer → noise SourceOver. (The code's blend enum names are flipped.)

- The tint's alpha is compressed by an HSV-derived modifier: pure white maps to 45%, pure black to 85%.
- With `TintLuminosityOpacity` unset: `mappedTintOpacity = (tintColor.A / 255) × (1.03 − 0.15) + 0.15`, clamped to 1,
  after clamping the tint's HSV value to `[0.125, 0.965]`.

**Luminosity opacity** is the W3C *luminosity* blend: the backdrop's hue and saturation with the tint's luminance
(`SetLum(backdrop, Lum(tint))`) at that opacity, then the tint colour alpha-blended at `TintOpacity`. The surface reads
as a temperature: the wallpaper's hue survives, its brightness is the tint's.
— [TintLuminosityOpacity](https://learn.microsoft.com/en-us/uwp/api/windows.ui.xaml.media.acrylicbrush.tintluminosityopacity?view=winrt-22621),
[W3C Compositing §10](https://www.w3.org/TR/compositing-1/#blendingnonseparable)

### 1.3 The shipped brush values (WinUI theme resources)

From [microsoft-ui-xaml #3478](https://github.com/microsoft/microsoft-ui-xaml/issues/3478):

| Brush | Dark | Light |
|---|---|---|
| `SystemControlTransientBackgroundBrush` (flyouts, menus) | Tint `#2C2C2C`, TintOpacity **0.15**, LuminosityOpacity **0.96**, fallback `#2C2C2C` | Tint `#FCFCFC`, 0.0, 0.85, fallback `#FCFCFC` |
| `SystemControlBaseAcrylicBrush` (base surfaces) | Tint `#202020`, TintOpacity **0.0**, LuminosityOpacity **0.96**, fallback `#202020` | Tint `#F3F3F3`, 0.0, 0.9, fallback `#F3F3F3` |

Acrylic is almost all luminosity layer with a whisper of tint. Mica's controller defaults are not published;
**community-reported**: Base dark `#202020` at tint 0.8 / luminosity 1.0, light `#F3F3F3` at 0.5 / 1.0; Base Alt dark
`#0A0A0A` at 0.0 / 1.0, light `#DADADA` at 0.5 / 1.0.
— [MicaController](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.composition.systembackdrops.micacontroller?view=windows-app-sdk-1.8),
[System backdrops](https://learn.microsoft.com/en-us/windows/apps/develop/ui/system-backdrops)

### 1.4 Implications for CrystalGUI

- The glass has a `luminosity` term (`SetLum(backdrop, Lum(tint))` at its opacity, then the colour tint), so the
  backdrop contributes only hue. Bar (Mica, base): its own dark at tint 0.8, luminosity 1, saturation 1, heavy blur.
  Previews, jump list, switcher (acrylic): saturation 1.25, luminosity 0.96, tint `#2C2C2C`-class at 0.15, noise 0.02.
- The backdrop must be opaque before it is blurred: `CgUiBackdrop` clears alpha to 1 after the scene blit.
- Never two acrylics edge to edge; a preview over the bar is a transient over a base, Windows' own layering.
- Accent text on acrylic fails contrast: the pills carry the accent, the labels do not.

## 2. Fluent 2 / WinUI colour tokens (fills, strokes, text)

From the [Fluent v2 resource table](https://amwx.github.io/FluentAvaloniaDocs/pages/Resources) (FluentAvalonia's
transcription of WinUI's `Common_themeresources_any.xaml`, light and dark columns corrected: dark fills are white with
alpha):

| Token | Dark | Light | Role |
|---|---|---|---|
| `SubtleFillColorSecondary` | `#0FFFFFFF` (5.9% white) | `#09000000` | hover on a list item / taskbar button |
| `SubtleFillColorTertiary` | `#0AFFFFFF` (3.9%) | `#06000000` | pressed |
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

These are WinUI's `#AARRGGBB`; a CrystalGUI sheet writes `#RRGGBBAA`.

**Implications.** Fluent's hover and press fills are translucent white over whatever is there, so they read the same
on any material; ours derive opaque greys from the panel ladder. The bar's entries pin the translucent pair; the theme
system cannot derive alpha. — [XAML theme resources](https://learn.microsoft.com/en-us/windows/apps/develop/platform/xaml/xaml-theme-resources)

## 3. Windows 11 taskbar geometry

- Height **48 px** at 100% (medium; small 32, large 72).
  — [Tom's Hardware](https://www.tomshardware.com/how-to/change-taskbar-icon-size-windows-11)
- Task buttons have a **4 px** corner radius; tray icons are 16 px in 32 px cells.
  — [Windows 11 taskbar styling guide](https://github.com/ramensoftware/windows-11-taskbar-styling-guide)
- The running indicator (`Rectangle#RunningIndicator`) measures as a **3 px-tall pill, ~6 px wide running, ~16 px
  active**, accent only when active (**measured, approximate**;
  [Windows Central](https://www.windowscentral.com/whats-new-taskbar-windows-11)).
- Previews, tooltips and the overflow menu are rounded and on acrylic; the bar itself is Mica-class.

**Implications.** The pill (6/16 × 3), the 4 px radius and acrylic previews are the port.

## 4. IntelliJ New UI — coloured project headers

- Since 2023.2 a project has a colour and an icon, washed across the main toolbar as a gradient strongest at the
  project widget (*Show Project Gradient*; *Use project colors in main toolbar*). Islands (2025) adds
  `idea.islands.color.gradient.enabled`.
  — [What's new in 2023.2](https://www.jetbrains.com/idea/whatsnew/2023-2/),
  [Per-project IDE colour](https://youtrack.jetbrains.com/articles/SUPPORT-A-2608/How-to-set-up-per-project-IDE-color-in-IntelliJ-IDEs)

**Implications.** The taskbar's equivalent is a wash under the entry cluster in the accent at low alpha, fading to the
bar either side. It must be dithered (§8).

## 5. Apple Liquid Glass (iOS 26 / macOS 26)

- Two variants: **regular** and **clear**; clear needs a dimming layer beneath, and its foreground is effectively
  white.
- A functional layer for controls and navigation over the content: "Don't use Liquid Glass in the content layer."
- No published contrast ratio; about 4.5:1 observed for regular.
  — [Meet Liquid Glass (WWDC25)](https://developer.apple.com/videos/play/wwdc2025/219/),
  [HIG](https://developer.apple.com/design/human-interface-guidelines/)
- The lens itself (height profile, refraction, rim) is in `plan/style-glass.md`.

**Implications.** The taskbar is a functional layer; the editor's islands are content and never glass.

## 6. Gaussian blur, as production renderers do it

- **Skia** (`GrBlurUtils`): `kMaxSigma` is 4. A larger sigma downscales the source by `kMaxSigma / sigma` on that
  axis (rounded down), blurs, and re-expands linearly. Radius is three sigma; 1-D passes use linear-filtered taps;
  blurs no wider than 5×5 run as one non-separable pass; clamp mode pads with edge pixels before the downscale.
  — [GrBlurUtils.cpp](https://skia.googlesource.com/skia/+/2e551697dc56/src/gpu/ganesh/GrBlurUtils.cpp)
- **Flutter/Impeller** also downscales before blurring. — [flutter#131580](https://github.com/flutter/flutter/issues/131580)
- **Linear sampling**: one bilinear tap equals two texel taps at the right weights.
  — [RasterGrid](https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/)
- **Incremental Gaussian**: weights by a three-multiply recurrence (GPU Gems 3, ch. 40).
- Taps past 3σ carry under half a percent, and a kernel is a Gaussian only if its taps are about a texel apart.
  — [demofox](https://blog.demofox.org/2015/08/19/gaussian-blur/)

**Implications.** `CgUiBackdrop.blurredBackdrop`: σ = reach/3; scale until σ ≤ 4 (by 1/2/4, since the box prefilter
reduces by integers; Skia uses the exact ratio); taps one texel apart, `ceil(3σ)` per side, incremental weights,
renormalised. Linear-filtered taps would halve the fetches.

## 7. Backdrop edges — clamp vs mirror

- Duplicating edge pixels makes content flicker as it enters the edge; the CSS spec now **mirrors** the backdrop
  past the edge, and Chromium's `BackdropFilterMirrorEdgeMode` matches Safari.
  — [Chrome Platform Status](https://chromestatus.com/feature/5382638738341888),
  [WebKit #372](https://github.com/WebKit/standards-positions/issues/372)

**Implications.** The blur mirrors at its region (`CgGpuOps.Edge.MIRROR`); this matters at the screen's own edges,
where the bar lives.

## 8. Gradient banding and dithering

- An 8-bit target quantises a shallow ramp into bands. Skia's gradient shader dithers; Chrome long compiled it out
  (`SK_DISABLE_DITHER_32BIT_GRADIENT`), which is why web gradients banded.
  — [SkGradientShader.cpp](https://skia.googlesource.com/skia/+/refs/heads/chrome/m54/src/effects/gradients/SkGradientShader.cpp),
  [mozilla 1168879](https://bugzilla.mozilla.org/show_bug.cgi?id=1168879)
- Add hash noise of **±0.5/255** before quantisation; unordered noise shows no pattern.
  — [Colour banding and dithering](https://shader-tutorial.dev/advanced/color-banding-dithering/)

**Implications.** The canvas's gradients (`paint/gradient.glsl`, stops from the recording's gradient table) evaluate
per fragment along CSS's gradient line, interpolate **premultiplied** (CSS Images 3 §3.4.3: `transparent` is
transparent black), and add `hash12(gl_FragCoord) − 0.5` over 255 to every channel, alpha included, last. Checked on
`cgui-gradient-probe`: a 16-level ramp over 1872 px steps at most 0.23 levels between column means.

## 9. Quick reference — the recipes, in this engine's units

| Surface | Material | Recipe |
|---|---|---|
| Taskbar (base, long-lived) | Mica-class | heavy blur, saturation ~1.0, luminosity ≈ 1.0, tint `#202020`-class at ≈0.8 (community); opaque fallback `SolidBackgroundFillColorBase` |
| Preview / jump list / switcher (transient) | Acrylic | blur **30**, saturation **1.25**, luminosity **0.96**, tint `#2C2C2C` at **0.15**, noise **0.02**, fallback `#2C2C2C` |
| Entry hover / pressed | Subtle fill | 5.9% / 3.9% white over the material (light: 3.5% / 2.4% black) |
| Running / active pill | — | 3 px tall; ~6 px / ~16 px; accent only when active |
| Any gradient | — | one draw, ≤8 stops per fragment along the CSS gradient line, premultiplied, ±0.5/255 hash dither last |
| Any backdrop blur | — | σ = reach/3; scale until σ ≤ 4; taps 1 texel apart, ⌈3σ⌉ per side; mirror at edges |
