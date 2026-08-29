# Liquid glass — a backdrop material for CrystalGUI

A frosted, refracting, specular surface that a UI element can be filled with, sampling whatever is
already on screen behind it. Written for the taskbar island, designed so any element can opt in.

> **Status**: planned, nothing built. Written 2026-08-24, off the back of `plan_windowing.md` W13.

---

## Why this, and why now

The taskbar sits over a live 3D world. A flat `#2B2D30` fill is the one thing on the desktop that
cannot acknowledge what is behind it, and it reads as a sticker — which is exactly what a taskbar,
the only permanent fixture on the screen, must not read as. Every compositor that overlays a
photographic backdrop reaches for the same answer: DWM's Acrylic, Quartz's `NSVisualEffectView`,
KWin's blur effect, and now Apple's Liquid Glass.

**This is not a theme change.** A tint can be authored in `crystal-dark.css` today and would not
help: the problem is that the surface carries no information about its backdrop, and no colour
does. What is missing is a *material*.

---

## Scope

**In:** a `CgUiDrawable` that fills a rect with blurred, refracted, tinted backdrop; the paint-context
primitive that captures the backdrop; the shaders behind both; a `glass(...)` value for `background`.
Applied to the taskbar island first.

**Out:** applying it to windows by default (see [Costs](#costs-and-the-three-things-that-will-bite));
animated/"gel" deformation; anything that needs the backdrop from a *previous* frame.

---

## What "Liquid Glass" actually is

Decomposed from Apple's own effect and the reimplementations that have picked it apart. It is five
separable things, and only the first two are what people usually mean by "glass":

| # | Layer | What it does |
|---|---|---|
| 1 | **Backdrop blur** | Samples what is behind and blurs it. The frosted look. |
| 2 | **Tint + saturation lift** | A translucent colour over the blur, with saturation pushed up so the backdrop's colour survives the blur. |
| 3 | **Refraction** | The glass acts as a *lens*: it **displaces** the backdrop near the edges rather than only blurring it. This is the single thing that separates Liquid Glass from a decade of frosted panels. |
| 4 | **Specular** | A directional highlight, a brighter Fresnel edge, and a thin rim where the boundary catches light. |
| 5 | **Chromatic aberration** | R/G/B refract by slightly different amounts at the edge, producing colour fringing. Physically the reason real lenses fringe. |

### The refraction, precisely

The important finding, and the one that changes the design: **the displacement is not derived from
the SDF distance directly.** It comes from a *surface height profile* across the bezel.

1. Let `x ∈ [0,1]` be the normalised distance from the outer edge inward across a bezel of width `b`
   (`x = 0` at the very edge, `x = 1` where the glass becomes flat).
2. A **surface function** `h(x)` gives the glass's height at that point. Four useful profiles:
   - convex circle — `h = √(1 − (1−x)²)`
   - **convex squircle** — `h = ⁴√(1 − (1−x)⁴)` — the smooth transition Apple's own surface matches
   - concave — `h = 1 − convex(x)`
   - lip — smootherstep blend of convex and concave, which is what gives the "poured" look
3. The **surface normal** is the derivative: `h' = (h(x+δ) − h(x−δ)) / 2δ`, `n = normalize(−h', 1)`.
4. **Snell's law** refracts a ray that arrives perpendicular to the backdrop:
   `n₁·sin θ₁ = n₂·sin θ₂`, with `n₁ = 1` (air) and `n₂ ≈ 1.5` (glass).
5. The refracted ray, projected back onto the backdrop plane, is the **offset** to add to the sample
   coordinate. Magnitude scales with the bezel width.

The browser reimplementations bake this into an offscreen displacement map because SVG's
`feDisplacementMap` needs one — computing the magnitude once along a single radius and rotating it
around the centre, exploiting radial symmetry. **We do not need any of that.** We are already in a
fragment shader with an analytic SDF, so `x`, `h'`, and the refraction offset are all computable per
pixel. The map, the 127-sample radius, and the R/G-channel encoding are artefacts of the SVG filter
pipeline, not of the technique.

### The blur, precisely

**Dual Kawase** (Marius Bjørge, *Bandwidth-Efficient Rendering*, SIGGRAPH 2015). A downsample chain
followed by an upsample chain, each pass a handful of bilinear taps at successively lower resolution.

- **1.5×–15× faster than Gaussian** depending on radius and hardware, and it was designed on and for
  mobile GPUs with exactly our bandwidth concerns.
- No **ringing** (which fast-Gaussian approximations show at bright transitions) and no **truncation**
  (which a clipped Gaussian kernel shows) — both of which would be conspicuous against a sky.
- Its natural radii are roughly powers of two, one per chain level. That reads as a limitation and is
  not one: the **sample offset** within a level varies strength continuously, so radius maps to
  `(iterations, offset)` rather than to a chain length alone. See the answer to open question 1.

---

## Prior art

| Source | What is worth taking |
|---|---|
| [Bjørge, *Bandwidth-Efficient Rendering* (SIGGRAPH 2015)](https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_slides.pdf) | The dual-Kawase down/up chain, and why it is the right blur for a bandwidth-bound target. |
| [kube.io, *Liquid Glass in the Browser*](https://kube.io/blog/liquid-glass-css-svg/) | The surface-height → normal → Snell derivation above, and the four profile functions. The clearest account of the refraction anywhere. |
| [Aghajari, *Liquid Glass: iOS Effect Explanation*](https://medium.com/@aghajari/liquid-glass-ios-effect-explanation-dabadd6414ae) | The five-component decomposition, and that SDF + lens curve + edge-only chromatic aberration is the whole of it. |
| [Sorrell, *I Rebuilt Apple's Liquid Glass for the Web*](https://www.sorrell.info/blog/liquid-glass-lens-effect) | Specular as three separate things — directional highlight, Fresnel edge, rim — rather than one gloss. |
| [LiquidGlassKit](https://github.com/DnV1eX/LiquidGlassKit) | Which knobs are worth exposing, from someone who had to re-expose them. |
| [Liquid Glass, Godot](https://godotshaders.com/shader/liquid-glass-ui-customizable/) | A working single-pass reference in a comparable engine. |

---

## What already exists — verified, not assumed

| Need | Have it? | Where |
|---|---|---|
| Region blit that **resolves MSAA** | ✅ | `CgFrameBuffer.blitFrom(src, dst, srcX0, srcY0, srcX1, srcY1, …)` |
| Render-to-texture with saved GL state | ✅ | `CgUiPaintContext.beginLayerFbo(fbo)` / `endLayerFbo()` |
| Screen-sized FBO pool, warmed | ✅ | `layerFboPool`, `acquireLayerFbo`, `warmUpLayer` |
| Rounded-box SDF, incl. per-corner radii | ✅ | `sdf.glsl` — `sdf_rounded_box(p, halfSize, radius \| vec4 \| vec4,vec4)` |
| Antialiased SDF coverage | ✅ | `sdf_coverage` — **fragment-only, already guarded** |
| Pluggable "fill this rect" SPI | ✅ | `CgUiDrawable.draw(ctx, mouseX, mouseY, x, y, w, h)` |
| CSS function-valued backgrounds | ✅ | `TextureValue` already parses `image(`, `sprite(`, `asset(`, `icon(` |
| Instanced quad material authoring | ✅ | `#pragma cg_use quad`, `gui_layer_blit.shader` as precedent |
| **A blur shader** | ❌ | nothing in either project — verified by grep |
| **A backdrop-capture step** | ❌ | the primitive exists; nothing calls it mid-frame |

**The MSAA detail that makes this work at all:** `msaaFbo` is a *renderbuffer*, deliberately never
sampled — `endFrame` resolves it into `msaaResolveFbo` with `blitFrom`. A backdrop grab is the same
operation on a *region*, mid-frame. So the one thing that would otherwise sink this — "you cannot
sample the multisampled target" — is already solved by the code path the frame ends with.

---

## Architecture

```
element paints  ─┐
                 │  1. GRAB     blitFrom(current target → backdrop FBO, region)   ← resolves MSAA
                 │  2. BLUR     dual-Kawase down chain ▸ up chain (quarter res)
                 │  3. COMPOSE  one draw: refract + tint + specular + SDF mask
                 └─▶ back to the element's own target
```

Three shaders, all CrystalGUI-side (`gui_layer_blit.shader` is the precedent for a UI-compositing
material living here, so **CrystalGraphics needs no new capability**):

| Shader | Job |
|---|---|
| `gui_blur_down.shader` | one dual-Kawase downsample tap set |
| `gui_blur_up.shader` | one upsample tap set |
| `gui_glass.shader` | the composite — refraction, tint, saturation, specular, SDF mask |

One drawable: `CgUiGlass implements CgUiDrawable`, holding the parameters and orchestrating the
three steps. One paint-context method: `captureBackdrop(x, y, w, h)` → a texture.

### CSS surface

```css
taskbar .__entries__ {
    background: glass(12, #2B2D3088);         /* blur radius, tint */
}
```

with the richer form taking the knobs that turn out to matter:

```css
background: glass(blur 12, tint #2B2D3088, bezel 8, ior 1.5, specular 0.35);
```

Parsed in `TextureValue` beside `asset()` and `icon()`. A glass background that cannot capture a
backdrop (no GL, headless, capture failed) **falls back to its tint colour**, so a sheet using it
degrades to exactly what we have today rather than to nothing.

---

## The CrystalGUI side — a drawable, not a change to the quad path

**`CgUiGlass implements CgUiDrawable`, owning its own material.** It is `CgUiRoundedRect` again, with
more layers — and that class is the precedent to copy rather than a coincidence:

```java
final class CgUiGlass implements CgUiDrawable {
    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_glass.shader");
    // …setCornerRadius / setTint / setBlur / setBezel / setIor / setSpecular / setNoise / setFallback
    @Override public void draw(CgUiPaintContext ctx, float mx, float my,
                               float x, float y, float w, float h) { … }
}
```

`CgUiRoundedRect` already does every structural thing this needs: it holds a **static shared
`CgMaterial`** loaded from its own `.shader`, it flips optional work on with **keyword variants**
(`MATERIAL.toggleKeyword("WITH_BORDER", borderWidth > 0f)` — compiled and cached per combination, so an
absent border costs nothing at runtime), and it takes per-corner radii through ordinary setters that the
style system fills in from the cascade. Glass differs only in having more to switch on and a backdrop
to sample.

### Why not fold it into the default quad path

`gui_quad.shader` is bound **once** by `beginFrame`, and every box, every glyph and every icon in the
engine batches through it. Putting glass there would mean:

- two extra samplers and a block of uniforms on **every quad in the application**, glass or not;
- a **mid-frame render-target switch** inside the one material that must never break its batch —
  `CgUiPaintContext` already documents that switching materials flushes pending work, which is a
  correctness requirement for painter's order, not a tidiness one.

The cost of glass is real and belongs **on the elements that ask for it**, not spread across the
engine. A drawable with its own material is exactly the shape that does that, which is why the SDF
rounded rect is one too.

### The one wrinkle: radii reach the drawable, not the shader

Glass needs the element's corner radii **twice** — once to mask the fill, once to measure the bezel the
refraction is computed across — and `CgUiDrawable.draw` is handed only `(x, y, w, h)`. That is already
solved for the rounded rect: the style system resolves `border-radius` against the box and pushes it in
with `setCornerRadius(...)` before the draw.

So either `CgUiGlass` repeats those setters, or the two share a small `CornerRadiusAware` contract and
the push is written once. **The second**, because a third SDF-shaped drawable is plainly coming and
"resolve the radii and hand them over" is one behaviour, not per-class boilerplate.

---

## The CSS

`background` already takes drawable-producing functions — `image()`, `sprite()`, `asset()`, `icon()` —
so glass is a fifth, parsed in `TextureValue` beside them. **One property, one drawable**, rather than a
`backdrop-filter` family of five new `StyleProperty` triples.

**Short form**, which is what a theme will actually write:

```css
background: glass(12);                       /* blur radius; everything else from tokens */
background: glass(12, #2B2D3088);            /* blur radius, tint */
```

**Long form**, keyword pairs, order-independent:

```css
background: glass(blur 12, tint #2B2D3088, bezel 8, ior 1.5,
                  specular 0.35, noise 0.04, fallback #2B2D30);
```

| Key | Meaning | Default |
|---|---|---|
| `blur` | radius in px → `(iterations, offset)` | `--glass-blur` |
| `tint` | ARGB over the blurred backdrop | `--glass-tint` |
| `bezel` | width of the refracting band, in px | `--glass-bezel` |
| `ior` | index of refraction (`1` = none, `1.5` = glass) | `1.5` |
| `specular` | highlight strength, `0` disables | `--glass-specular` |
| `noise` | grain amount, `0` disables | `--glass-noise` |
| `fallback` | solid colour when glass cannot render | `--glass-fallback` |

Unknown keys **warn and are ignored** rather than throwing, which is the rule every `StyleValue`
already follows: a malformed declaration degrades, it never breaks the cascade.

The taskbar then reads:

```css
taskbar .__entries__ {
    background: glass(12, var(--taskbar-glass-tint, #2B2D3066));
    border-radius: var(--radius-panel, 8px);
}
```

Note what is **not** in that rule. The shape still comes from `border-radius`, so glass composes with
everything the box model already does — `outline`, `overlay`, `mask`, a `transform` — and an element
does not become a special kind of element by being made of glass.

### Every optional layer is a keyword variant

`specular 0`, `noise 0` and `ior 1` each switch a `#pragma cg_feature` off, so a theme that wants plain
frosted glass gets a **shader with none of that code in it**, compiled once and cached — the same
mechanism `WITH_BORDER` already uses on the rounded rect. That is what makes Tier 1 through Tier 3 a
single material rather than three, and what lets a low-end fallback be `glass(blur 8, specular 0,
noise 0, ior 1)` instead of a second code path.

---

## Costs, and the three things that will bite

1. **A mid-frame target switch is the expensive operation.** This session measured stalls of that
   exact shape in game, and the window-animation work turned on it. One glass element per frame is
   negligible; glass on every window is not. **Therefore: opt-in per element, never a default**, and
   the taskbar is the one consumer until measured otherwise.
2. **The backdrop is a live 3D world**, so it changes every frame and cannot be cached across frames
   the way a desktop wallpaper can. This is the opposite of iOS's cheap case.
3. **Painter's order defines "behind".** The taskbar draws late, so its backdrop is complete. Two
   overlapping glass surfaces are a real ordering question, and the answer for now is: the second one
   captures the first's *output*, which is correct, and costs a second grab.

A fourth, smaller: `sdf_coverage` uses `fwidth`, which is **fragment-only**. `sdf.glsl` already guards
it with `#ifndef CG_VERTEX_STAGE`, and the new shaders must not undo that — the shipped-shader stage
purity test will catch it, but only if the guard stays inside the lib.

---

## The steps

Each step ends with something visible in `--mode=cgui-desktop`.

### G1 — `captureBackdrop`, and a pass-through material
`CgUiPaintContext.captureBackdrop(x, y, w, h)`: acquire an FBO sized to the rect (device pixels),
`blitFrom` the current target's matching region into it, return the texture. Plus `CgUiGlass` drawing
that texture back **unblurred and untinted** into the same rect.

**Done when** the taskbar island is visually indistinguishable from having no background at all — which
proves the grab, the region maths and the coordinate spaces, and nothing else. A one-pixel offset here
is invisible in every later step and fatal to all of them.

### G2 — dual-Kawase blur
`gui_blur_down.shader` / `gui_blur_up.shader`, ping-ponging between two pool FBOs. Radius maps to
**iterations plus offset** (open question 1), 8 taps per pass. The first downsample takes two inputs —
the shared scene texture and this consumer's UI-so-far resolve — and composites them, so the backdrop
is assembled for free in a pass that had to happen anyway.

**Done when** the island is frosted. Measure the frame cost here, before anything is built on top.

### G3 — tint and saturation
Composite the tint over the blur, with a saturation multiplier applied to the blurred backdrop first.
Both from CSS. This is the step that makes it look *deliberate* rather than smeared, and it is one
line of shader each.

**Done when** the island reads as a material rather than a blur.

### G4 — the SDF mask, and the rim
Mask the composite with `sdf_rounded_box` at the element's own border radii, and add the thin bright
rim where the boundary catches light — the cheapest half of the specular, and the one that does most
of the work of making an edge look like glass rather than like a crop.

**Done when** the island's corners are glass corners, not a rounded crop of a blur.

### G5 — refraction
The surface-height profile from the research above: normalised bezel distance → `h(x)` → derivative →
normal → Snell → sample offset. Ship the **convex squircle** as the default profile with the others
behind a parameter, since that is the one that matches Apple's surface.

**Done when** a straight edge in the world visibly *bends* as it passes under the island's bezel. This
is the step the whole plan is for.

### G6 — specular and Fresnel
The directional highlight and the Fresnel edge, over the rim from G4. Light direction fixed in element
space, so it does not swim when a window moves.

### G7 — chromatic aberration
Sample R, G and B with slightly different indices of refraction, **edge-only** — the fringing must
vanish where the glass is flat, or the whole surface looks broken rather than optical.

### G8 — the CSS value, and one worked theme
`glass(...)` in `TextureValue`, the fallback-to-tint path, and `crystal-dark.css` tokens so a theme can
turn it down or off. Apply to the taskbar island; leave windows alone.

---

## What this deliberately does not do

- **No glass on windows by default.** The cost is per-element and per-frame; the taskbar earns it
  because it is one element that is always on screen over a photographic backdrop. A window over
  another window has very little to refract.
- **No backdrop caching.** Correct for a static wallpaper, wrong for a world that moves.
- **No animated deformation.** Apple's "liquid" merge/split between adjacent controls is a separate
  feature with its own geometry problem, and nothing here needs it.
- **No new CrystalGraphics API.** Everything lands in `core/render/`, alongside the layer machinery it
  extends. If that turns out to be wrong the rule in `AGENTS.md` decides it, not convenience.

---

## Grabbing the Minecraft world

**The UI's own target does not contain the world, and this is the fact the whole feature turns on.**

`CgUiPaintContext.beginFrame` does two things, in this order:

```java
glScope = CgGlState.save(CgGlSlot.FBO, …);   // 1. the PRE-FRAME target is recorded here
msaaFbo.bind();
msaaFbo.clearColor(0f, 0f, 0f, 0f);          // 2. …and the UI's target is cleared FULLY TRANSPARENT
```

So `msaaFbo` holds the UI and nothing else. Everything behind it — the world, the HUD, the hotbar —
is in whatever was bound *before* that call, which `endFrame` composites back onto. A backdrop grab
that read `msaaFbo` would capture an empty buffer, and that would look exactly like the effect not
working, in the one place where it is most obviously wrong.

### Two sources, and no host change

| Source | What it holds | How to reach it |
|---|---|---|
| **Scene** | world + HUD in game; the harness's ground in the harness | the FBO id inside `glScope`, saved at `beginFrame` |
| **UI so far** | whatever the tree has already painted beneath this element | `msaaFbo` — multisampled, so a `blitFrom` resolve |

Reading the saved target rather than asking the host for one is deliberate:

- **It needs no loader change.** `CgRenderPipeline` takes an explicit `sourceFboId` and blits a DEPTH
  snapshot from MC's main render target for the 3D pipeline — that plumbing exists and is the obvious
  thing to copy. Copying it would mean a new host call per loader, and it would be *less* correct:
  "whatever we were drawing onto" is true by construction, in game, in the harness, and in whatever
  host comes next.
- **It survives `framebufferMc` being off.** With FBOs disabled the saved id is `0`, and
  `glBlitFramebuffer` reads the back buffer perfectly well. An explicitly-registered MC FBO id would
  be wrong in exactly that configuration, and wrong only for the players who have it.
- **Depth is deliberately not wanted.** `cg_DepthBuffer` already exists and is tempting; glass
  refracts what is behind it regardless of how far away that is. A depth-aware variation — distant
  things blurred more — is a real idea and is out of scope.

### The shape of the capture

**One shared scene capture per frame, taken lazily on first use.** The first glass element to draw
triggers a single full-surface `blitFrom(sceneTarget → sceneTexture)`, and every later consumer in the
same frame samples its own region out of it. That is **one target switch per frame however many
consumers there are**, rather than one each — which is what makes the answer to "can windows have glass
too?" something other than a flat no.

The UI-so-far half stays **per consumer**, because it is the half that depends on painter's order: a
region resolve out of `msaaFbo` at the moment that element draws. For the taskbar it genuinely matters —
a window may legitimately overhang the strip, since the clamp lets a body hang off the bottom, and a
window missing from the glass beneath it is the sort of wrong that is hard to un-see.

The two are composited **inside the blur chain's first downsample pass** — sample scene, sample
UI-so-far, `over` — rather than into a third FBO. Two texture reads in a pass that was happening anyway
cost nothing; a separate composite target would cost another switch.

> **The caveat, stated now rather than discovered later.** A shared capture is taken once, so a consumer
> drawn later does not see a consumer drawn earlier. With one taskbar that is exact. With two glass
> surfaces that overlap, the upper one will not show the lower one. The honest fix is a per-consumer
> scene grab, which is available and costs a switch — so this is a **default, not a limitation**.

### Coordinates, and a documented rule that inverts here

The grab region is in **surface pixels**, because that is the framebuffer's space. So it comes from the
**transform chain** (`localToWorld`), *not* from the layout chain.

That is the exact opposite of the rule `AGENTS.md` states for placing a popup — "position from
`getWindowX/Y`, never from `localToWorld`" — and it inverts for the same underlying reason it exists:
the transform chain is in surface pixels with the root transform already baked in. A popup's
`left`/`top` are logical and get scaled again, so surface pixels are wrong there; a framebuffer region
is *already* surface pixels, so they are the only right answer here. The chain is populated during
`drawSubtree`, which is exactly when a drawable runs.

**And Y is flipped.** GL framebuffers are bottom-left origin and the UI is top-left, so
`glY = surfaceHeight − (y + h)`. `blitLayer` already carries the same flip, spelled `uv(0, 1, 1, 0)`.

---

## Open questions — answered by research

### 1. What resolution should the blur chain run at? — *the question was wrong*

Not "quarter or half". KWin's dual-Kawase implementation is parameterised by **downsample iterations**
(each halving: `size/2`, `/4`, `/8`, `/16`) **plus an offset** controlling how far apart samples are
taken within a level. The offset is what varies blur strength *without* spending another iteration —
KWin builds 15 evenly-distributed strength presets by walking the offset ranges across those levels.

That also disposes of the "discrete powers-of-two radii" limitation the SIGGRAPH work notes: the fix is
the offset, not a blend between two chains as G2 originally proposed. **Radius → (iterations, offset)**
is the mapping to implement, and **8 texture samples per pass** is the budget.

### 2. One shared backdrop, or one per consumer? — *shared, lazily*

Settled above: a single scene capture on first use per frame, region-sampled by every consumer, with
the UI-so-far half per consumer because it is the order-dependent one.

### 3. What does glass do when the backdrop is flat? — *noise, and a fallback colour*

Windows' Acrylic answers both halves, and it is worth taking wholesale because it is the same problem:
a material that has to look like a material even when it is over nothing interesting.

Acrylic's recipe is **backdrop → blur → exclusion blend → tint → noise**, and the two parts we were
missing are the last one and the escape hatch:

- **Noise.** A subtle grain over the whole surface. This *is* the answer to a flat backdrop: it gives
  the surface its own texture, so glass over an empty sky still reads as glass rather than as a
  slightly-wrong rectangle. It is also what stops a heavy blur banding on a gradient.
- **`FallbackColor`.** Acrylic names a solid colour that replaces the material outright when it cannot
  be rendered — battery saver, transparency disabled, low-end hardware. We need the same for: no GL
  context, a failed capture, headless, and a user setting. The plan already said "falls back to its
  tint"; making it a **named parameter** rather than an internal path is what lets a theme and a
  settings toggle reach it.

One further idea worth stealing: background Acrylic **falls back to solid when its window deactivates**.
The same logic applies to anything paying per-frame cost — an inactive window's glass buys very little.
Noted for after G8, not scoped now.

---

## Open questions — still open

1. **Does the exclusion-blend layer earn its place?** Acrylic uses one to guarantee contrast for text
   over an arbitrary backdrop. Our taskbar labels sit on a tint we control, so it may be unnecessary —
   but "unnecessary" is a judgement about legibility over a *live world*, which is exactly the thing
   that cannot be reasoned about without looking at it. Revisit at G3.
2. **Should the shared scene capture be unconditional** once any consumer exists, rather than lazy on
   first draw? Lazy is strictly cheaper when nothing is glass; unconditional is simpler and would let a
   later consumer see an earlier one. Decide when there is a second consumer.
3. **Does the taskbar want the UI-so-far half at all?** It costs a resolve per frame and buys
   correctness for one case: a window overhanging the strip. If that reads as unimportant on screen, the
   scene-only path is a measurable saving. Answerable only at G1, with a window dragged over the bar.


---

## Recorded revisions

### R1 — the blur is a separable Gaussian, not a dual-Kawase pyramid

G2 specifies dual Kawase, open question 1 answers its parameterisation, and the research behind both is
sound and stays in this document. It is not what shipped.

**What happened.** The pyramid was implemented as specified and the effect never once looked right. The
symptom was not a wrong radius: panels grew **hard-edged dark regions that expanded with the blur
slider**, starting from the side nearest the capture's edge. `blur 0` — which bypasses the chain and
hands back the capture itself — was always correct, which localised the fault to the chain and nowhere
else. Five rounds went into it. The radius mapping was rederived three times, each version defensible on
paper and each producing the same picture; the upsample's texel size was corrected; the passes were made
to stop averaging alpha. None of it closed the gap, and a **one-iteration** chain — which by arithmetic
reaches about four pixels — was visibly darkening a hundred.

**Two defects were found in the end, and only one of them is about Kawase.**

1. **Nothing clamped the taps to the captured content.** A tap falling outside read the sampler's clamp,
   which for a target cleared once is transparent black. That is a mechanism that produces exactly the
   observed picture: darkness dragged inward, growing with radius, bounded by a hard line where the taps
   stop reaching. A blur cannot otherwise produce a hard edge, and that is the tell that should have been
   followed on round one instead of round five.
2. **The pass quad's flip disagreed with every other full-surface blit in the class.** `drawOver` uses
   `uv(0, 1, 1, 0)` because a layer FBO is bottom-left origin and the UI is top-left; the chain's passes
   used `uv(0, 0, 1, 1)`. Each pass flipped its image, and the output looked upright only because an
   equal number of down and up passes cancelled — correct output from two errors, which holds until
   somebody changes the level count.

**Why the replacement rather than the repair.** Both defects were fixable in place. The reason not to is
that a pyramid's correctness is distributed across every level's viewport, texel size, quad orientation
and integer-halved size, and **nothing in this codebase can observe a single level**: there is no GL
context in any test source set, so the whole structure is only ever verifiable by looking at it. A
separable blur has one texel size, one direction and one radius, and can be checked by reading it. That
is worth more here than the bandwidth saving, on a feature that draws a handful of elements rather than a
full-screen post pass.

**What it costs.** Two passes at quarter resolution, nine Gaussian taps each, against the pyramid's
five-and-eight over a shrinking chain. Strictly more bandwidth at large radii and immaterial at the sizes
a taskbar and a few panels ask for. Quarter resolution is doing double duty: it is the cheaper option and
it is what keeps the kernel well sampled, since nine taps spread across a large radius leave visible gaps
at full resolution and land barely a texel apart at a quarter of it.

**The pyramid is worth returning to** — it is the better algorithm, and the research in G2 and open
question 1 is why. Return to it **with a way to see one level in isolation**, which today means a harness
scene that dumps each level rather than a unit test. Without that, it is the same five rounds again.


### R2 — the backdrop must be captured PER REGION, not per surface

**Measured, in game:** the desktop held a steady 120fps before glass and dropped to **50–70** with a
single glass element on it — the taskbar island. One strip, roughly a fortieth of the screen, halved
the frame rate of everything.

**It is not the shader.** The composite pass draws one quad. The cost is entirely in what
`CgUiBackdrop`'s capture and blur do to get it its inputs, and every one of those is sized
to the SURFACE rather than to the element asking:

| Per frame, for one taskbar | Sized to |
|---|---|
| framebuffer blit of the scene | whole surface |
| MSAA resolve of `msaaFbo` | whole surface |
| composite of the resolve, plus one per enclosing layer | whole surface, one textured quad each |
| two blur passes | quarter surface |
| four render-target switches | — |

**The design took "shared" to mean "whole-surface".** G1 argues for one capture per frame shared by every
consumer, and that argument is right — it is what stops a second glass element costing a second target
switch. The error is the step that followed silently: sharing does not require capturing everything. The
union of the consumers' rects, expanded by the blur reach, is just as shareable and is a fortieth of the
pixels for the case that actually ships.

**What that changes.** `backdropFor` already computes the element's rect in surface pixels; the capture
needs to take the union of the rects asked for on the frame, blit only that, and hand back UVs into it.
The one real subtlety is ordering — the union is not known until every consumer has asked, and consumers
ask during their own paint. Two workable shapes: capture the union of the PREVIOUS frame's consumers
(one frame stale, invisible for a strip that does not move), or capture per consumer and accept a switch
each, which for one or two elements is still far cheaper than the whole surface. The MSAA resolve should
likewise be region-limited, and skipped outright when the surface is not multisampled.

**Until then the taskbar is a flat fill.** `ua/desktop.css` carries the `glass()` value it wants in a
comment beside the rule. Un-shipping the effect rather than the code is the right way round: nothing
about the material is known to be wrong on cost grounds, and the whole desktop should not pay for one
strip while that is being fixed.

#### R2 — done, and measured in game

Implemented as the union of what asks for a backdrop, padded by the blur's reach, seeded from the
previous frame's union so a settled UI captures once for every consumer and widened immediately when
something is not covered — so the answer is exact and only the COST is speculative. The MSAA resolve is
region-limited with it, which on a multisampled surface is the most expensive thing in the path.

**The targets stay screen-sized and a sub-rectangle is used.** Resizing them to the region is the obvious
move and is exactly the incomplete-framebuffer hazard `CgUiBackdrop.prepareFrame` exists to avoid, since the
capture happens mid-paint. A corner costs nothing: the sub-rect is the same FRACTION of every target in
the chain, so a normalised UV means the same thing in all of them and the blur's step maths needed no
change at all.

Measured with `-PcgGlassProbe`, dev client, desktop open, one consumer (the taskbar):

| | before | after |
|---|---|---|
| captured region | whole surface | `792x79` — **1.8%** of `2560x1377` |
| frame | 20ms (50–70fps, reported) | **8.33ms (120.0 fps), sustained** |
| glass, CPU side | — | **0.02ms** settled |

The harness agrees: the glass page, eight consumers spread over 35% of the screen, went from ~101fps to
a settled 8.33ms — the vsync cap. The gallery is the honest worst case and it is still free.

**What the probe is really for is the negative result.** The same run dipped to 39, 40 and 47fps several
times, and `glass` stayed at 0.03ms through every one of them — the frame period collapsed while the
stages it measures did not move. Without the split those dips would have been attributed to the newest
thing on screen, which is what the first report of this bug did.


### R3 — what the greyness actually was, and what found it

Two defects, neither in the blur maths, both invisible to reading:

1. **The blur targets inherited the ambient SCISSOR.** A clip rect is in screen pixels; a 480x270 blur
   target is not in screen pixels. Glass inside an `overflow: hidden` subtree therefore had ~37% of its
   blur target left at the clear instead of ~9%, and every panel over that strip sampled transparent
   black. Dark, hard-edged, growing with radius — all three of the symptoms, from a hole in the input
   rather than anything about the filter.
2. **`gui_blur.shader` did not compile**, once it was rewritten: `cg_tap` sat between `vertex()` and
   `fragment()`, which reaches the vertex stage only. The pass still ran, so the targets came out part
   clear-black and part fallback-white.

**The lesson is about method, not about blur.** Six rounds were spent reasoning about sampling from
screenshots of the final composite, which is the last stage of a five-stage pipeline. Every hypothesis
was plausible and none was checkable. What ended it in one run was reading the intermediate buffers back
and printing them — `captured`, `blurA`, `blurB` as coarse luminance/alpha grids. The empty left strip
was unmistakable, and it named the cause immediately.

**So the harness is the instrument, and it can run unattended.** `--seconds=N` caps a scene and
`ArtifactService` writes a PNG, so a scene can be photographed with no one watching. `CgUiGalleryScene`
now takes `-Dcrystalgui.gallery.page=<label>` to open on a named page and capture it — which is what
turns "please look at your screen and tell me what you see" into something answerable in thirty seconds:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery --seconds=6" \
    -Dcrystalgui.gallery.page=glass
# -> gl-debug-harness/harness-output/cgui-gallery/cgui-gallery-glass.png
```

The property prefix must be `crystalgui.` or `crystalgraphics.`; the build forwards only those two to the
harness JVM, and a differently-named flag is accepted on the command line and reaches nothing.


### R4 — what production implementations do that the first version did not

Researched against two working recreations and the reference write-up behind them:
[kube.io's derivation](https://kube.io/blog/liquid-glass-css-svg/),
[PallavAg/liquid-glass-web-react](https://github.com/PallavAg/liquid-glass-web-react) (the most rigorous
of them — an analytic displacement map with baked specular), and
[ui-layouts' component](https://github.com/ui-layouts/uilayouts) (the one that prompted the pass).

**The physics was already right.** Convex squircle height profile, normal by central difference on it,
Snell for the bend, displacement along the SDF gradient — kube.io derives exactly that, and G5 has
implemented it since it shipped. ui-layouts is the outlier and is *less* physical: its "bend layer" is
`feTurbulence` fractal noise through `feDisplacementMap`, which is a wobble rather than a lens. Worth
knowing before copying it — what is worth taking from it is its LAYER STACK, not its distortion.

Three things were genuinely wrong or missing:

1. **THE HIGHLIGHT WAS ONE-SIDED, AND THAT IS WHY IT READ AS AN EMBOSSED BUTTON.** The first version
   used `max(0, dot(-n, L))` — lambertian, bright where the surface faces the light and flat where it
   faces away, which is a bevel. A lens is not a lambertian surface: light entering one edge leaves
   through the opposite one, so BOTH ends of the light axis catch it. Both references model exactly
   this and neither is subtle about it — the reference map writes the same highlight value into the
   top-left and bottom-right quadrants (`Math.abs(px + py)` for both), and the CSS recreations spell it
   as a matched PAIR of opposed inset shadows: `inset 3px 3px 3px rgba(255,255,255,.45)` **and**
   `inset -3px -3px 3px rgba(255,255,255,.45)`. It is now `abs(dot(grad, L))`.

2. **THE THIN RIM MUST DOMINATE THE BROAD GLOW.** The first version weighted a Fresnel falloff up to
   1.0 against a 0.35 rim — nearly three to one the wrong way. A highlight made mostly of broad
   falloff reads as bloom; the hairline at the boundary is what says "this has an edge". The
   reference's defaults are `edgeHighlight: 0.25` against `glow: 0.1`, both with exponent 1.5, and the
   rim band is measured in PIXELS (`edgeWidth: 3`) rather than as a fraction of the bezel — a rim is a
   hairline whatever the bezel behind it is doing.

3. **CHROMATIC ABERRATION IS THREE TAPS, NOT ONE TAP TINTED.** A prism separates colours because each
   wavelength refracts through a different ANGLE, so the faithful model runs the displacement three
   times and keeps one channel from each: the reference uses scales `[s(1+0.2c), s(1+0.1c), s]`. The
   first version scaled red and blue by 0.985 and 1.015 — a 3% spread against the reference's 20%
   default, which is invisible at any radius anybody would use and reads as the feature not being
   wired up.

**`specular` is now a MASTER MULTIPLIER** over both terms, as it is in the reference (`specular: 1`
over `glow: 0.1` and `edgeHighlight: 0.25`), and defaults to 1. Defaulting it to a fraction silently
scaled the researched weights down to a third of themselves.

**Still not taken, deliberately:** the reference's `splay` (damps X displacement near the top and bottom
edges so refraction follows the nearest edge rather than being purely radial) is largely what an SDF
gradient already does, since that gradient points along the nearest edge's normal by construction. Its
`domeDepth` — a whole-surface spherical magnification on top of the bezel — is a real effect we do not
have and would be the next thing worth adding. And an outer DROP SHADOW (`0 4px 4px rgba(0,0,0,.15)`,
in every CSS recreation) cannot be drawn by a `CgUiDrawable` at all: a drawable paints inside its own
rect, and a shadow is outside it. That belongs to the box model, not to this material.
