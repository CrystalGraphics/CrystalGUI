# CrystalGUI — Agent Knowledge Base

**Project type**: Platform-agnostic retained-mode UI engine, shaped like a lightweight web browser
(DOM + CSS cascade + Taffy layout + immediate-mode painting).
**Authored in**: Java 21 (Jabel-desugared toward Java 8 bytecode) · **Layout**: Taffy · **Backend**: CrystalGraphics
**Targets**: MC 1.7.10 (Forge/LWJGL2) · MC 1.20.1/1.20.4 (Forge/NeoForge/Fabric, LWJGL3) — *neither is in the build today; see [Module layout](#module-layout--what-actually-compiles)*

---

# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

## Required reading

Read the files relevant to your task scope **before** doing any work.

| When | Read |
|---|---|
| Always | `CrystalGraphics/docs/CRYSTALSHADER_MANIFESTO.md` — grand goal, rendering philosophy |
| Touching style, CSS, painting, drawables, compositing | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Writing or modifying a widget | `docs/CGUI_WIDGETS.md` |
| Touching `serialization/` or `net/` | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Touching `dock/`, `workbench/` or `editor/` | `docs/CGUI_WORKBENCH_SERVICES.md` — **and add any new service API to it in the same commit** |
| Touching any rendering/buffer/shader/VAO/mesh code | `CrystalGraphics/AGENTS.md` |
| Working inside a package | any `AGENTS.md` found in that package (none exist under `core/` today) |

## NO RE-DELEGATION

**Subagents MUST NOT delegate their assigned work to another agent.**

When you are assigned a task — by an orchestrator, a plan, or a user — you execute it yourself with
the tools you have (Read, Edit, Write, Bash, Glob, Grep). You do not spawn a child agent, fire a
background task, or hand off via `task()`.

**Absolute prohibition. No exceptions.** Complexity is not a reason to re-delegate. The only
cross-agent tool use permitted is asking the orchestrator a clarifying question, inline.

**If you are a subagent and find yourself writing a `task()` call: STOP. Do the work yourself.**

---

# THE GRAND GOAL

> Every line of code in this repository serves one end: a **node-based shader graph for Minecraft**
> (cross-version 1.7.10 and 1.20.1) — like Unity's Shader Graph, but true to GLSL, on a modern GL 3.x+
> pipeline, with instancing as the default draw path.
>
> CrystalGUI is the UI engine that graph will be built in.
>
> 📄 **[CrystalShader Manifesto](CrystalGraphics/docs/CRYSTALSHADER_MANIFESTO.md)**

---

# TO BUILD

```bash
./gradlew :core:compileJava       # the engine — enforces the MC/Forge/LWJGL import guard
./gradlew :core:test              # unit tests, CrystalGraphics ON the classpath
./gradlew :core:headlessTest      # server-side tests, CrystalGraphics CORE deliberately absent
./gradlew :core:check             # both test tasks
```

There is **no in-game Minecraft integration reachable from this build.** The two things you can
compile and run end-to-end today are `core/` and the harness. Do not claim otherwise.

## Render testing — the GL debug harness

For anything visual, **do not test via Minecraft** — it isn't wired up. Use the harness: it boots in
seconds, needs no Minecraft context, and gives you a real GL surface.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"   # start here
./gradlew :gl-debug-harness:runHarness --args="--list"                # all scenes
```

| Mode | Scene class | Covers |
|---|---|---|
| `cgui-gallery` | `CgUiGalleryScene` | Every widget at once — the default smoke test |
| `cgui-test` | `CgUiTestScene` | General engine scratch scene |
| `cgui-button` | `CgUiButtonScene` | `Button`, activation semantics |
| `cgui-checkbox` | `CgUiCheckboxScene` | `Checkbox`, `CheckboxGroup` |
| `cgui-switch` | `CgUiSwitchScene` | `Switch` (CSS-driven knob transition) |
| `cgui-slider` | `CgUiSliderScene` | `Slider`, drag |
| `cgui-textfield` | `CgUiTextFieldScene` | `TextField`, caret, selection |
| `cgui-text` | `CgUiTextScene` | `UIText` wrapping/measurement |
| `cgui-text-stress` | `CgUiTextStressScene` | Many text nodes — shaping/layout cost |
| `cgui-scroller` | `CgUiScrollerScene` | `Scroller`, `ScrollerView`, overflow |
| `cgui-splitview` | `CgUiSplitViewScene` | `SplitView`, divider drag |
| `cgui-tabview` | `CgUiTabViewScene` | `TabView`, `Tab` |
| `cgui-styling` | `CgUiStylingScene` | Cascade, selectors, transitions |
| `cgui-nineslice` | `CgUiNineSliceScene` | `CgUiSprite` 9-slice |
| `cgui-ore-theme` | `CgUiOreThemeScene` | `ore.css` + sprite registry end-to-end |
| `cgui-visual-layers` | `CgUiVisualLayersScene` | FBO layer opacity + masking |

Harness scenes live in `gl-debug-harness/src/main/java/.../harness/scene/ui/`; register new ones in
`SceneRegistry`. Harness authoring rules are in `gl-debug-harness/AGENTS.md` — never call raw GL.

---

# Start Here By Task

| I need to… | Section | Deep reference |
|---|---|---|
| Add or change a widget | [Widgets](#widgets) | `docs/CGUI_WIDGETS.md` |
| Add a CSS property | [Adding a CSS property](#adding-a-css-property) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Change how something paints | [Render stack](#stack-4-render--immediate-mode) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8 |
| Work on layout / Taffy | [Style stack](#taffybridge--the-layout-seam) | — |
| Work on events, focus, hover, drag | [Input stack](#stack-3-events-input-focus) | — |
| Serialize a tree / send UI over a wire | [Server layer](#server-layer--serialization--net) | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Understand a frame | [Frame lifecycle](#frame-lifecycle) | — |
| Debug "my selector doesn't match" | [Load-bearing invariants](#load-bearing-invariants) | — |
| Debug "my layout is wrong by default" | [Taffy default divergences](#taffy-defaults-diverge-from-css-deliberately) | — |
| Add a rendering backend capability | [CrystalGraphics boundary](#crystalgraphics-ownership-boundary) | `CrystalGraphics/AGENTS.md` |

---

# Module layout — what actually compiles

`settings.gradle.kts` includes `core`, `language` and `gl-debug-harness`. CrystalGraphics is an
`includeBuild` composite with three `dependencySubstitution` entries, which is how the
`compileOnly("com.crystalgraphics:core:1.0.0")` coordinates resolve to local source.

| Module | In build? | State |
|---|---|---|
| `core/` | ✅ | The engine. Java 21 → Java 8 bytecode. Everything below lives here. |
| `language/` | ✅ | The language stack — everything with a native or an engine behind it. Depends on `core/`; **`core/` must never depend on it**, which is what keeps tree-sitter's `.so`s and ECJ's ~13MB off a dedicated server. `.grammar` (six tree-sitter grammars), `.engine` (band selection, the ONE shared loader per band — `EngineHost` — the language-neutral `Analysis` answer and the `AnalysedLanguageServices` attachment every engine extends), `.java` (everything Java: the ECJ adapters, `JavaLanguageServices`, and `ScriptHost`, the Java `ScriptRuntime`), `.map` (the readable↔runtime boundary, on ASM), `.run` (the **engine-neutral** Run shell: `ScriptRuntime` SPI + `ScriptRuntimes` registry, commands, console, rail, sessions — `RunShellIsEngineNeutralTest` forbids it naming `.java`, ECJ or Rhino). `.js`/`.resolve` are reserved. *(Was `syntax-treesitter/` until M4.)* |
| `gl-debug-harness/` | ✅ | Git submodule (branch `crystalgui`). 16 CrystalGUI scenes. The only way to run the UI. |
| `CrystalGraphics/` | ✅ (composite) | The rendering backend. Consumed, never reimplemented. |
| `mc1710/` | ❌ commented out | Bare `@Mod` stub. No CrystalGUI integration at all. Scaffolding. |
| `mc1201/` | ❌ commented out | Has *real* code (`CgPlatformService1201`, per-loader entrypoints, event bridges, mixins) but does not compile from this build. |

`core/build.gradle.kts` runs an **import guard** as a `doLast` on `compileJava`: any source line
importing `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, or `org.lwjgl.*` fails the
build. There are currently **no exemptions** — the guard is clean.

## Three test source sets, and they are not interchangeable

| Source set | CrystalGraphics on classpath? | What belongs there |
|---|---|---|
| `core/src/test/` | ✅ `testImplementation` | Anything needing `CgIO`, fonts, `StyleSheet`, sprites, drawables |
| `core/src/headlessTest/` | ❌ **core deliberately absent**, `platform` present | Everything a dedicated server must run: `serialization/`, `net/`, tree/state logic, and **`text.lang` — the language SPIs, which run here precisely because no engine and no grammar is on this classpath** |
| `language/src/test/` | ✅ (plus the tree-sitter natives) | Grammars, queries, the tokenizer. Skips cleanly when a native will not load on the running platform |
| harness scenes | ✅ full GL | Anything visual |

**The absence is the assertion.** On a dedicated Minecraft server there is no GL context and no fonts.
Anything in `core/` that reaches a CrystalGraphics **core** type *outside a paint-method body* fails in
`headlessTest` with `NoClassDefFoundError` rather than in production.

> **`com.crystalgraphics:platform` is the one CG module that stays**, and it is not an exception to the
> rule — it is what the rule is actually modelling. `platform` is pure SPI (interfaces, key-code
> constants, the `CgPlatform` registry) with no GL calls and no context requirement, and it ships inside
> every loader jar, so a dedicated server genuinely has it. Excluding it would assert something untrue of
> production, and `core/` reaches it for real: `UIInputHandler` *implements* `CgSystemInput`, which is a
> supertype and therefore resolves at class load, not in a method body.

JOML and Taffy **must stay** on the headless classpath: `UIElement` and `ElementStyle` have *fields*
of those types (`Matrix4f`, `NodeId`, `TaffyStyle`), and field descriptors resolve at class load —
unlike method-body references, which don't. Someone will eventually try to strip them; don't.

> **The trap, found the hard way:** `StyleSheet.DEFAULT` is a `static final` that reads `default.css`
> through `CgIO` at class-init, so the entire `StyleSheet` class is unloadable headlessly — even
> `StyleSheet.parse()`. **If a test needs CSS text, it belongs in `test`, not `headlessTest`.**

---

# Frame lifecycle

```
UIWindow.paintFrame():
  advanceFrame():
    styleEngine.calculateStyle(delta)     // 1. drain dirty-match, re-run selectors, cascade, tick transitions
    tickAnimations(delta)                 // 2. smooth scrolls + every registered UIFrameTicker
    calculateLayout()                     // 3. taffyTree.computeLayout() while dirty; fires onLayoutChanged
  CgUiPaintContext.getInstance()
  paintContext.beginFrame(actualScreenW, actualScreenH)   // GL save, ortho, bind gui_quad, reset scissor
    pose.pushPose(); pose.mulPoseMatrix(rootTransform)     // rootTransform = the ONE definition of uiScale
    ui.rootElement.drawSubtree(paintContext)               // recursive, immediate, painter's order
    pose.popPose()
  paintContext.endFrame()                 // GL restore
  inputHandler.beginFrame()               // invalidate hover cache
  inputHandler.endFrame()                 // hover diff + dispatch of accumulated mouse events
```

**`calculateStyle` running first is load-bearing.** A style change this frame must reach Taffy
*before* layout, or it lands one frame late. `drainDirtyMatch` only runs inside `calculateStyle`, so
a window that is never painted never matches any selector.

**`updateWithoutPainting()`** runs `advanceFrame()` alone — style + animations + layout, no GL, no
draw, and deliberately no input handling (no frame was presented, so hover has nothing to be relative
to). Use it for headless tests and for benchmarks that need to isolate layout/shaping cost from
render cost, since per-element material binds at draw time can dwarf everything else.

---

# Stack 1: DOM — `ui/`

## There is no `UIContainer`

`UIElement` is both leaf and container, exactly like a real DOM `Element` — "a general-purpose,
styleable, extensible container, conceptually like an HTML `<div>`". It owns:

| Concern | Surface |
|---|---|
| Tree | `addChild`/`addChildAt`/`addChildren`/`removeChild`/`removeSelf`/`clearAllChildren`/`hasChild`/`getSiblingIndex` |
| Identity | `setId`, `addClass`/`removeClass`/`hasClass`, `tagName()` (via `ElementRegistry`) |
| State flags | `setEnabled`/`setPressed`/`setFocused`/`setHovered`; overridable `isChecked`/`isBlank`/`isInvalid`/`consumesTextInput` |
| Focus | `setFocusPolicy`, `focusable()`, `tabbable()`, `invalidateFocusableChain()` |
| Hit testing | `setHitTest`, `containsScreenPoint`, `screenToLocal` |
| Inertness | `setInert`/`isInertAttribute`/`isInert`, `requestClose()` (the close-watcher hook) |
| Transform | `getTransform`/`setTransform` (`UITransform`), `invalidatePoseCachesRecursively()` |
| Scrolling | `setScrollTop`/`setScrollLeft`/`setScroll`/`setScrollImmediate`/`scrollIntoView`/`clampScroll`/`getMaxScroll*`/`getClientWidth`/`getScrollWidth`/`setScrollExempt` |
| Querying | `querySelector`/`querySelectorAll`/`getElementById`/`getElementsByClassName` — same selector engine the stylesheets use |
| Internal children | `markAsInternal`/`addInternalChild`/`insertInternalChildAt`/`removeInternalChild`/`acceptsPublicChildren` |
| Styling | one owned `ElementStyle`; `style(...)`, `layout(...)`, `generalStyle(...)`, `moveInlineAsDefault()` |
| Events | pre-bound `EventListenerGroup<T>` fields (`onMouseDown`/`onMouseUp`/`onMouseMove`/`onMouseEnter`/`onMouseLeave`/`onMouseScroll`/`onFocus`/`onBlur`) |
| Serialization | `writeState`/`readState` (protected) + `writeStateTo`/`readStateFrom` (final), `networkId`, `addReportedEvent`, `setObserver` |
| Painting | `drawSubtree` is **final**; override `paintSelf` / `paintOverlay` / `paintOutline` |
| Layout hooks | `onLayoutChanged()`, `getTaffyLayout()`, `measureFunc()`, `markTreeDirty()`, `clearLayoutCache()` |

**Scrolling is an ordinary element capability** driven by `overflow`, not a widget feature.
`ScrollerView` only adds visible bars on top of it.

## `RuntimeCache` — five dirty-flag memo cells

Backed by `core/data/CacheCell` / `IntCacheCell`:

| Cell | Holds |
|---|---|
| `sortedChildren` | Children in paint order, z-index descending |
| `localToWorld` | This element's world matrix (falls back to `UIWindow.getRootTransform()` at the root) |
| `worldToLocal` | The inverse — `localToWorld.get().invert(old)` |
| `depth` | Tree depth |
| `hasFocusableDescendant` | Tab-order pruning |

> `sortedChildren` orders **equal z-index later-inserted-first**. Painting walks it in reverse
> (lowest z first) so the highest-z child ends up visually on top, matching which child hit-testing
> prioritizes — these two used to disagree, which is why the order is spelled out here.

## `UIWindow` — the runtime engine

Owns the live Taffy tree, a `StyleEngine`, a `UIInputHandler`, screen/layout dimensions, the
scroll-animation set, and the `UIFrameTicker` set. It **does not own a paint context** — that is a
singleton (see [Render stack](#stack-4-render--immediate-mode)).

- `init(w, h)` attaches the root element — **required.** `invalidateStyleMatch()` early-returns on a
  detached element, so without `init` no selector ever matches anything.
- `registerElement`/`unregisterElement` create and destroy the Taffy node (and install a
  `measureFunc()` if the element supplies one).
- `getRootTransform()` is the **single definition of what `uiScale` means** — `paintFrame` seeds the
  `PoseStack` from it and `RuntimeCache.localToWorld` falls back to it, so hit-testing is correct
  *before* the first paint. The scale lives here and in the `PoseStack`, deliberately **not** in the
  ortho projection: `CgTextRenderer` picks glyph raster size from the pose scale, so moving it would
  rasterize glyphs at logical size and let the projection magnify them — blurry text.
- `setUiScale(f)` rescales and invalidates every cached transform.
- `getHoveredElement(x, y)` is the z-order- and clip-aware hit test.

`UIWindow` deliberately implements **no** platform Screen/widget interface — loader modules own that.

## `Ui`

Trivial immutable `{ rootElement }` holder (`Ui.of(root)` / `Ui.of()`). No runtime, layout, or GL
state. The declarative description layer it seeded now lives in `serialization/UIDescriptionCodec`.

## `ElementRegistry`

Bidirectional `tag ↔ class` map with a factory per tag; `bootstrapBuiltins()` registers eighteen:
`element`, `button`, `checkbox`, `scroller`, `scrollerview`, `slider`, `splitview`, `switch`, `tab`,
`tabview`, `textfield`, `text`, `tooltip`, `dialog`, `popover`, `menu`, `menuitem`, `dropdown`.
Unknown tags **throw** on decode — a typo must not silently become a
styleless div.

## `UITreeObserver`

Four callbacks — `onAttached`, `onDetached`, `onStateDirty`, `onIdentityDirty` — covering the only
things a networked peer cannot reconstruct: tree shape, element identity, and authored widget state.

Deliberately **not** hooked to `onStyleChanged()`: that fires on every transition tick and every
`IMPORTANT`-origin write a widget makes about itself, so the dirty set would be pure churn — and what
it reports is *computed styles*, which a session never sends. `onStateDirty` carries no value; state
is re-read at flush time so ten mutations in a tick collapse to one entry. Cost when nobody is
watching: one nullable field and one null check per mutation.

---

# Stack 2: Style — the cascade

> **Full reference: `docs/CGUI_STYLE_RENDER_PIPELINE.md`.** This is the map, not the territory.
> 86 files — the largest stack in the engine.

## Origins

`StyleOrigin` is priority-ordered: `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) <
IMPORTANT(4) < ANIMATION(5)`. Two are easy to get backwards and both are deliberate:

- **`USER_AGENT`** is `default.css`, sitting *below* author sheets so a theme always wins at any
  specificity — exactly how a browser's UA sheet behaves.
- **`ANIMATION`** sits *above* `IMPORTANT` because a transition must be able to override an
  `!important` value mid-flight. Matches CSS Cascade L4/5.

## `ElementStyle` — two winner maps, not one

```
candidates: Map<StyleProperty, List<StyleSlot>>   // every value ever set, at any origin
    ↓ computeCandidateSlot  (origin → specificity → source order)
computedSlots  — the DISPLAYED winner, INCLUDING any ANIMATION candidate  → getComputed()
realSlots      — the REAL winner, IGNORING ANIMATION  → what we're settling toward
```

**Why two.** An `ANIMATION` candidate always wins the priority comparison. If the per-pass diff
compared against the *displayed* value, an in-flight transition would always look unchanged (it'd see
its own last tick), silently defeating both mid-flight retargeting and cleanup. So the diff runs
against `realSlots`.

`StyleSlot<T>` is `record(property, origin, specificity, sourceOrder, value)` with full CSS-cascade
`compareTo`. `replaceOrPutCandidate` **no-ops when the pushed value is unchanged** — which is what
makes widget-driven geometry feedback loops settle instead of oscillating.

## Writing styles — the origin pipelines

`StyleGroup` is the fluent write surface, and every write carries an origin. The static pipelines are
the idiom widgets use:

```java
StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.height(measured));
StyleGroup.inlinePipeline(group, g -> ...);
StyleGroup.defaultPipeline(group, g -> ...);
StyleGroup.pipeline(origin, group, g -> ...);
```

Two concrete groups:
- **`GeneralGroup`** — visual: `background`, `overlay` (+`-fit`/`-origin`/`-position`), `outline`
  (+per-edge offsets), `opacity`, `color`, `backgroundColor`, `fontSize`, `fontFamily`, `lineHeight`,
  `caretWidth`, `selectionColor`, `textOffsetX/Y`, `transform`, `transformOriginX/Y`, `zIndex`,
  `overflow`, `scrollBehavior`, `scrollDuration`, `mask`.
- **`LayoutGroup`** — ~150-method fluent CSS box-model/flex/grid API, feeding Taffy.

## `StyleProperty<T>`

Carries `name`, `type`, `initialValue`, a `ValueParser`, plus three configuration flags that decide
its cascade behaviour:

| Flag | Meaning |
|---|---|
| `inheritable` | No candidate at any origin → fall back to the **parent's computed value** instead of `initialValue` (e.g. `color`). Anything box-model/layout leaves this false. |
| `allowTransition` | Whether `transition:` may animate it |
| `interpolator` | `IValueInterpolator<T>`; defaults to `BINARY` (snap) |

Properties also carry change listeners — this is how `LayoutProperties.init()` wires every layout
property straight through to `TaffyBridge`.

**Registered CSS properties** (`StylePropertyRegistry`) — the full set, alphabetically, so a missing
entry is visible rather than merely absent: `background`, `background-color`, `border-bottom-color`,
`border-color`, `border-top-color`, `caret-color`, `caret-width`, `color`, `cursor`, `font-family`, `font-size`,
`font-style`, `font-weight`, `line-height`, `mask`, `mask-fit`,
`mask-offset`, `mask-origin`, `mask-position`, `opacity`, `outline`, `outline-color`,
`outline-offset-{top,right,bottom,left}`, `outline-width`, `overflow`, `overlay`, `overlay-fit`,
`overlay-origin`, `overlay-position`, `resize`, `scroll-behavior`, `scroll-duration`,
`selection-color`, `text-align`, `text-decoration-color`, `text-decoration-line`, `text-offset-x`, `text-offset-y`,
`text-overflow`, `text-shadow`, `transform`, `transform-origin-x`, `transform-origin-y`,
`transition`, `white-space`, `z-index` — plus the whole layout set from `LayoutProperties`.

> **This list goes stale silently.** Registering a property is a one-line addition in a 300-line file and
> nothing links the two, so three of the entries above (`text-align`, `white-space`, `text-overflow`) were
> missing for a full release cycle after 5.2 shipped them, and `text-decoration-line` nearly repeated it.
> If you add a property, add it here in the same edit. `grep -oE 'create\("[a-z-]+"' StylePropertyRegistry.java`
> regenerates the set in one command.

## Adding a CSS property

Every property is a **triple**: a `StyleValue` (parse), a `StyleProperty` (identity + cascade
behaviour), and a registry constant. This is why `style/property/**` is ~60 files of near-identical
`Foo`/`FooValue`/`FooProperty` sets — the shape is formulaic, so copy the closest existing family
rather than inventing a new one.

**1. `StyleValue<T>` — the parser.** One method, `doCompute(String) -> T`. Computed **lazily and
exactly once**, then cached; a thrown exception is caught, logged as a warning, and yields `null`
rather than propagating — a malformed declaration degrades, it never breaks the cascade.

```java
public class FloatValue extends StyleValue<Float> {
    public FloatValue(String rawValue) { super(rawValue); }
    @Override protected Float doCompute(String raw) { return Float.parseFloat(raw.trim()); }
}
```

**2. `StyleProperty<T>` — identity and cascade behaviour.** Takes `(name, type, initialValue,
ValueParser<T>)`, where `ValueParser<T>` is just `String -> StyleValue<T>` (so `FloatValue::new`).
Configure with `setInheritable`, `setAllowTransition`, `setInterpolator`, `addListener`.

Prefer an existing **specialized subclass** — it presets those flags correctly:

| Subclass | Adds |
|---|---|
| `FloatProperty` | `min`/`max`/`step`, `setRange`, linear interpolator, `allowTransition` **on** by default |
| `AutoFloatProperty` | as above, plus an `auto` keyword |
| `IntProperty` | integer equivalent |
| `ColorProperty` | ARGB parsing + colour interpolation |
| `EnumProperty` | keyword → enum constant |
| `DimensionProperty`, `LPAProperty`, `LPSizeProperty`, `LPARectProperty` | Taffy-shaped length/percent/auto types |
| `LengthPercentProperty` | `LengthPercent` (used by offsets, radii, transform origin) |
| `GridProperty`, `GridTemplateProperty`, `GridAutoProperty`, `GridTemplateAreasProperty` | grid types |
| `TextureProperty`, `TransformProperty` | drawable / `UITransform` |

**3. Register it** as a `public static final` in `StylePropertyRegistry`, via `create(...)`:

```java
public static final StyleProperty<Float> OPACITY   = create("opacity", 1f).setRange(0f, 1f);
public static final StyleProperty<Integer> COLOR   = create(new ColorProperty("color", -1)).setInheritable(true);
public static final StyleProperty<Overflow> OVERFLOW = create("overflow", Overflow.class, Overflow.VISIBLE);
```

The string here **is** the CSS property name — registering it is what makes it parseable in a
stylesheet. A property parsed but not yet acted on should still be registered, so sheets can declare
it without a warning.

**4. Expose a fluent accessor** on `GeneralGroup` (visual) or `LayoutGroup` (layout) — a getter
returning `getValueSave(PROP)` and a setter calling `set(PROP, v)`.

**5. Layout properties only:** `LayoutProperties.init()` attaches a `TaffyBridge`-calling
`addListener` so the computed value reaches the live Taffy style. Without that step a layout property
cascades correctly and changes nothing on screen.

## `TaffyBridge` — the layout seam

Mutates the live Taffy `TaffyStyle` and marks the tree dirty. `LayoutProperties.init()` attaches a
`TaffyBridge`-calling listener to every layout `StyleProperty`, so `LayoutGroup.width(100)` flows
straight into the live layout with no intermediate step.

### Taffy defaults diverge from CSS, deliberately

`TaffyBridge.DEFAULT_TAFFY_STYLE` is **not** the CSS initial value set. Expect these:

| Property | CrystalGUI default | Real CSS |
|---|---|---|
| `flex-direction` | `COLUMN` | `row` |
| `flex-shrink` | `0` | `1` |
| `box-sizing` | `BORDER_BOX` | `content-box` |
| `align-content` | `FLEX_START` | `stretch` |
| `min-size` | `0` both axes | `auto` |

`border-box` is a project choice matching the common UI-framework convention (Bootstrap et al.) where
a declared width already includes padding+border. It happens to match Taffy's own default too, but is
assigned explicitly so it stays self-documented rather than at the mercy of a future Taffy version.

## Selectors

`style/selector/` — `Selector`, `CompoundSelector`, `SelectorType` with real CSS specificity weights:
`UNIVERSAL(0)`, `TYPE(1)`, `PSEUDO_ELEMENT(1)`, `CLASS(10)`, `PSEUDO_CLASS(10)`, `ID(100)`. Descendant
and child combinators are supported.

**One pseudo-element exists: `::highlight(name)`** — the CSS Custom Highlight API, for styling text
ranges without wrapping them in elements. It never matches the originating element (that is what
`matchesOriginating` is for), and `StyleEngine` cascades it into a `HighlightStyle` kept apart from
`ElementStyle`. `::before`/`::after` are rejected at parse time — internal children are the substitute.

**Not supported:** `:nth-child`, attribute selectors, `~`/`+` sibling combinators, `@media`, `@import`.

`PseudoClasses` — `ENABLED`, `DISABLED`, `CHECKED`, `BLANK`, `INVALID`, `HOVER`, `ACTIVE`, `FOCUS` —
each bound to a real `UIElement` getter. **A widget gets a pseudo-class for free by overriding the
getter**; `Tab.isChecked()` is the whole implementation of `tab:checked`.

## Stylesheets

- `StyleSheet.parse(String)` — inline CSS text.
- `StyleSheetRegistry.of("crystalgui:ore")` — loads `assets/{ns}/ui/styles/{path}.css`, lazily parsed
  and `ConcurrentHashMap`-cached, so repeated calls return the same instance.
- `DeclarationParser` — declaration-level parsing including `var(--x)` custom-property substitution.
- `StyleSheet.DEFAULT` — the user-agent sheet (see the headless trap above). **Not applied
  automatically** — `window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT)` is a real call a caller
  has to make. A test that asserts on `default.css` behaviour without it silently exercises no CSS and
  passes for the wrong reason.

## `StyleEngine` — the per-window driver

A flat ordered sheet list (`addStylesheet`/`removeStylesheet`/`getSheets` — there is **no**
`clearStylesheets`), a dirty-match set, and `calculateStyle(delta)` which re-matches, cascades, and
ticks transitions.

> Sheet order is registration order, and re-adding a sheet **appends** it — i.e. at the *highest*
> priority. This matters for any runtime theme switch.

## Transitions

`transition: <prop> <dur> <easing>` on any property with `allowTransition`. `TransitionEngine` writes
at `ANIMATION` origin via `startAnimationSlot`/`tickAnimationSlot`/`endAnimationSlot`. Easings:
`Linear`, `CubicBezier`, `LinearPiecewise`, `ConstantEasing`, plus `ProgressFunctions`.

## `transform`

`UITransform` is an **ordered list of ops**, not translate/scale/rotate fields — because CSS composes
left-to-right as matrix multiplication, so `translate(10px) scale(2)` ≠ `scale(2) translate(10px)`,
and a field-per-function decomposition cannot represent the difference at all.

- Immutable; build from `IDENTITY` + `then(...)`, or the `translate`/`scale`/`rotate` shorthands.
- `isIdentity()` is the fast path every caller checks first, so an untransformed tree pays nothing.
- **Layout-free by construction** — Taffy never sees it, so transforming an element cannot reflow its
  siblings. That's what makes a zoomable canvas possible (scale one container, not the window).
- Origin is **not** stored here — `transform-origin-x`/`-y` are separate cascading properties, so they
  theme and transition independently. `applyTo(...)` takes the origin already resolved to pixels.
- `applyTo` is the **single definition** used by both the hit-test transform chain and the render
  `PoseStack`. They must produce an identical matrix or clicks land somewhere other than what the user
  sees.
- Divergences: `matrix()` unsupported; axis variants (`translateX`) **collapse** into their
  two-argument form at parse time, so they interpolate against each other instead of snapping.

---

# Stack 3: Events, input, focus

## Three-phase dispatch

`ui/event/` — `UIEvent` (base: `target`, `bubbles`, `phase`, `stopPropagation` /
`stopPhasePropagation` / `preventDefault`), `PropagationPhase` (`CAPTURE`/`TARGET`/`BUBBLE`), and the
concrete types:

| Type | Bubbles? |
|---|---|
| `DOMEvent` — `ElementAdded`, `ElementRemoved` | no |
| `CloseEvent` — `Cancel` (cancelable; Escape on a modal) | no |
| `FocusEvent` — `Focus`, `Blur` | yes |
| `KeyboardEvent` — `Down`, `Up` | yes |
| `MouseEvent` — `Click`→`Down`/`Up`, `Scroll`, `Move` | yes |
| `MouseEvent` — `Enter`, `Leave` | no — but see below |
| `DragEvent` — `Enter`, `Leave` | no — chain-dispatched, like the mouse pair |
| `DragEvent` — `Over`, `Drop` | yes |
| `DragEvent` — `Cancel` | no — goes to the drag source |

> **`Enter`/`Leave` don't bubble, yet one is dispatched to *every* element in the entered/left chain**
> — outermost-first on entry, innermost-first on exit, exactly as the DOM does. Firing only on the
> precise hit target means a container with children never hears about the pointer at all: hovering a
> row's own label left the row with nothing, and only the bare gaps between children worked. The
> `:hover` pseudo-class always walked this chain, so the two used to disagree about what "hovered"
> meant.

`EventListenerGroup<T>` bundles four signals per (element, event-type): `capture`, `target`, `bubble`,
and `defaultEvents` for built-in behavior. `defaultEvents` fires only in the TARGET phase and only if
`!isDefaultPrevented()` — that is what `preventDefault()` actually suppresses.

> **`attachListener(l, capture, bubble)` always subscribes the target phase.** The two booleans are
> *additive*, not a mode selector.

## `UIInputHandler`

One class, merging what older notes split into "input manager" + "focus manager". It implements
`CgSystemInput.Mouse` and `CgSystemInput.Keyboard` directly as the raw-event sink, and owns:

- **The three-phase walk** (`sendInputEvent`): build `UITreeTraversal.pathToRoot`, walk root→target
  for CAPTURE, fire once for TARGET, walk target→root for BUBBLE if `bubbles`.
- **Hover**, via a `CacheCell<UIElement>` diffed once per frame inside `endFrame()`.
- **Focus** (`requestFocus`, `blurIfFocused`, `getFocusedElement`) and Tab traversal via
  `UITreeTraversal.{firstTabbableIn, lastTabbableIn, previousTabbable, nextTabbable}` — with
  `{first,last}FocusableIn` kept as a *separate* pair for focus delegation (see `FocusPolicy` below).
  Tab wraps around at both ends.
- **Press/click state** per button (`ButtonState`, multi-click `detail` counting), and
  `MouseEvent.Up.isWasPressTarget()`.
- **Pointer capture** (`setPointerCapture`/`releasePointerCapture`) — Pointer Events L3. While
  captured, hit testing is *substituted*: every pointer event targets the capturing element "as if the
  pointer is always over" it. Boundary events fall out of that for free — the hover diff sees no
  change, so nothing enters or leaves and `:hover` stays pinned. Released implicitly on button-up,
  *after* the up is delivered, which is what lets a drag end anywhere on screen.
- **Drag**, via `UIDragController` — capture plus an optional payload, drop targeting, an activation
  threshold, a ghost, and an Escape cancel path. **Not HTML5 drag-and-drop**; the web moved to
  pointer events, so this does too.
  - Drop targets get `DragEvent` `Enter`/`Leave`/`Over`/`Drop`/`Cancel`, dispatched to what is
    *geometrically* under the pointer — which is why `UIWindow.getHoveredElement` is deliberately
    left free of capture substitution. Conflating the two makes every drop land on the dragged thing.
  - **Rejection is the default**: a target accepts by calling `preventDefault()` on `DragOver`,
    re-read every frame and never latched. HTML5 DnD's one good idea, kept.
- **Keyboard activation** — Space/Enter on a focused element synthesizes the same `MouseEvent.Down`/
  `Up` a real click would. This is why `Button` contains **zero** keyboard code.

### The accumulate-then-dispatch model

`consumeMouseEvent`/`consumeKeyboardEvent` accumulate raw input during the frame. Click, focus and
keyboard events dispatch **immediately**; hover/enter/leave/move/scroll are **synthesized once per
frame** from `paintFrame()`'s `beginFrame()`/`endFrame()` pair.

> `beginFrame()` **only** invalidates the hover cache — forcing a fresh hit-test against this frame's
> layout, so a reflow under a stationary cursor doesn't leave hover stale until the next real mouse
> move. It must **not** also read or snapshot that cache: mouse-move events already invalidated it
> before `beginFrame()` ran, so reading there is really an eager recompute against the *new* position
> mislabeled as the *old* one. That was the original stuck-hover bug. The "last frame's hover"
> baseline is a plain field (`lastFrameHover`) snapshotted at the end of `fireAccumulatedMouseEvents()`.

### `inert` and modal dialogs

`inert` is the HTML content attribute, ported as a Java flag (`setInert`) rather than a CSS property —
same as `setHitTest`, and for the same reason top-layer promotion is imperative. An inert subtree keeps
laying out and painting but stops being interactive: unhittable, unfocusable, skipped by Tab. That it
**keeps its box** is the whole reason it exists alongside `display: none`.

`UIElement.isInert()` is the spec's full predicate: this element or an ancestor carries the attribute,
**or** a modal dialog is open and this element is not inside it. The second half is why it is not simply
an inherited flag — a modal makes *everything else* inert, which cannot be spelled by setting the flag on
a common ancestor, because the root's subtree contains the modal too.

> **Enforced at four points, deliberately not one.** The modal half changes for nearly every element in
> the tree the instant a modal opens, so anything *cached* that depended on it would need mass
> invalidation. Instead: `focusable()` and the `hasFocusableDescendant` cache see only the **attribute**
> half; Tab is **scoped** to the modal at the entry point (that is the focus trap); hit-testing skips
> inert subtrees and skips the main tree wholesale while a modal is open; `requestFocus` consults the
> **full** predicate. Each is pinned by its own test — a "simplify to one predicate" refactor that missed
> one would otherwise look green.

`UIWindow` owns the modal stack (`getActiveModal`/`isModalBlocked`/`pushModal`/`popModal`) because
modality is about inertness, not painting, and because the spec hangs it off the `Document`. Nesting
works and unwinds in order. `unregisterElement` pops it: a modal that left the tree without closing would
keep the whole window inert with nothing left to interact with.

`requestClose()` is the **close-watcher** hook — the web's `CloseWatcher` is a general primitive, so this
is a general element hook rather than something wired only to `Dialog`. Escape asks the active modal;
a live drag eats Escape first, because a drag is the innermost live interaction.

### Popovers — light dismiss and the two stacks

`Popover` is the Popover API port and the base under `Menu`/`Dropdown`. It is a **base class**, not an
attribute, and that is a deliberate divergence: unlike `inert` — one property with subtree semantics —
popover-ness is a bundle of behaviour (show/hide, placement, dismissal, focus restore) that is meaningless
piecemeal. What genuinely must be element-level to work *is* on `UIElement`: `popoverInvoker`, which light
dismiss has to consult for any promoted element.

`Mode.AUTO` joins the popover stack (light dismiss + Escape); `Mode.MANUAL` joins neither.

> **`UIWindow` keeps two separate stacks, and they are not redundant.** `autoPopovers` drives
> **light dismiss** (press outside); `closeWatchers` drives **Escape**. The same element is routinely in
> one and not the other — a modal dialog has a close watcher but is not light-dismissable, and a `MANUAL`
> popover is in neither. Collapsing them makes one of those two cases wrong.

**Light dismiss** (`UIWindow.lightDismiss`) is the spec's algorithm: find the press target's innermost
popover ancestor, then close everything above it. So a press inside a menu closes its submenus but not
itself, and a press anywhere unrelated closes the whole chain.

> **The invoker counts as part of its popover.** Without that carve-out a dropdown button dies on its own
> press: light dismiss closes the menu on mouse-down and the button's click reopens it, so it can never be
> shut by pressing the button again — and flickers while trying.

> **Light dismiss runs *after* the mouse-down event is dispatched**, so the press still reaches whatever it
> landed on (browsers both dismiss and activate). Dismissing first tears down the tree under an undelivered
> event. It fires on press, not the spec's press/release pair — that pairing exists for text-selection
> drags, which this engine has no equivalent of.

**Escape asks the topmost close watcher**, which is what makes nesting work: a dropdown opened inside a
modal closes first, and only a second Escape reaches the modal. A live drag still eats Escape before
either.

`AnchoredPlacement` owns positioning for every anchored popup — flip on the main axis, clamp on the cross
axis, anchor geometry read from the **transform chain** rather than the layout box. Extracted from
`Tooltip` when `Popover` became its second consumer. **Nothing else may write `left`/`top` on a promoted
popup**, or it fights placement every frame.

### `FocusPolicy` — four values, and two of them look alike

`NONE` / `FOCUSABLE` / `CLICK` / `CLICK_NOT_TABBABLE`, queried through `isFocusable()`,
`isTabbable()` and `focusesOnClick()` rather than by `==`.

`CLICK_NOT_TABBABLE` is the web's `tabindex="-1"` and exists for one purpose: the ARIA APG's **roving
tabindex**, where *"the tab sequence should include only one focusable element of a composite UI
component"* and *"the arrow keys move focus inside"* it. A ten-tab `TabView` is one Tab press to skip,
not ten.

That splits the tree walkers in two, and **the pair is not interchangeable**:

| Question | Predicate | Walkers | Asked by |
|---|---|---|---|
| May this hold focus at all? | `focusable()` | `firstFocusableIn`/`lastFocusableIn` | focus delegation (`Dialog.show()`), arrow keys inside a composite, `requestFocus` |
| Is it in the Tab sequence? | `tabbable()` | `firstTabbableIn`/`lastTabbableIn`/`nextTabbable`/`previousTabbable` | Tab / Shift+Tab |

> Click-focus therefore tests **`focusesOnClick()`, never `== CLICK`** — a `CLICK_NOT_TABBABLE`
> element is still fully clickable, so an equality check makes every unselected tab go dead to the
> mouse.

**Integer `tabindex` was deliberately not ported.** The roving pattern only ever uses `0` and `-1`;
positive values reorder the whole document from one element and are widely regretted. One enum
constant covers the pattern.

`hasFocusableDescendant` stays keyed on `focusable()` — a superset of `tabbable()`, so it remains a
valid fast-path filter for both walkers and needs no invalidation when only the tab stop moves.

---

# GL context lifecycle — `lifecycle/`

CrystalGUI owns GL resources and caches of GL-derived objects, but is not part of CrystalGraphics and
so cannot be enumerated in `CgGraphicsLifecycle`'s own teardown. The seam is
`CgLifecycleListener` (CrystalGraphics, `gl/lifecycle/`) — `onInit(w,h)` / `onFrame(frame)` /
`onDestroy()`, all default no-ops, registered via `CgGraphicsLifecycle.addListener(...)`.

**CrystalGUI registers exactly one**: `CgUiLifecycle`.

| Moment | What CrystalGUI does |
|---|---|
| `onInit` | Nothing — every GL resource is lazily built on first paint, and forcing them here would defeat `CgUiPaintContext`'s deliberate laziness. It *does* fire though (see below), so work added here will run |
| `onFrame` | Nothing — per-frame work is per-`UIWindow` (`paintFrame`, `UIFrameTicker`), not global |
| `onDestroy` | `CgUiPaintContext.destroy()`, and nothing else |

> **`onInit` reaches late registrants.** CrystalGUI registers from a class initializer on the *first
> paint*, which is always after `CgGraphicsLifecycle.initContext()` has run — so a fire-only-during-init
> design would mean `onInit` never fires for CrystalGUI at all. `addListener` therefore delivers
> `onInit` immediately when a context is already live. The guarantee is **exactly once per context,
> regardless of registration time**. Without it the miss would be invisible while the hook is empty
> and appear as silently-skipped setup the moment it isn't.

> **`destroyContext()` fires only at game shutdown.** There is no destroy-then-init cycle in a running
> process, so there is no "next context" to protect, and **nothing needs invalidating merely because
> CrystalGraphics is about to free it** — font families, cached stylesheets, sprite packs and shared
> materials all die with the process. Do not add cache-clearing or material-invalidation to
> `onDestroy`; it is ceremony, not correctness.

What `onDestroy` legitimately does is release the one thing nobody else frees: `CgUiPaintContext`'s
layer FBO pool is built with `CgFrameBuffer.createOwned`, which bypasses `CgFrameBufferRegistry`, so
`deleteAll()` never reaches it.

**`onDestroy` fires before CrystalGraphics frees anything** — the only window in which a listener can
release its own FBOs/renderers while the context is whole. Listeners dispatch in *reverse*
registration order.

> **Registration is automatic** — a `static` initializer in `CgUiPaintContext` calls
> `CgUiLifecycle.register()`, so CrystalGUI wires itself as soon as that class comes into play. Class
> init runs once per classloader, so registration cannot repeat across a destroy/recreate cycle. A
> process that never paints never touches the class, which keeps a dedicated server free of
> CrystalGraphics. `register()` is idempotent and public for explicit use.

---

# Stack 4: Render — immediate-mode

**The V3.1 draw-list design is gone.** `CgUiDrawList`, `CgUiDrawListExecutor`, `CgUiDrawState`,
`CgUiBatchSlots`, and `CgScissorRect` do not exist. Do not reference them.

## `CgUiPaintContext` — a singleton

Obtained via `CgUiPaintContext.getInstance()`, **not** owned per-`UIWindow`. Every `fillRect`/
`drawImage` call draws immediately; there is no recording phase and nothing to flush.

| Group | Methods |
|---|---|
| Frame | `beginFrame(w,h)` / `endFrame()` — save/restore GL via `CgGlScope`, ortho projection, bind `crystalgui:shaders/gui_quad.shader`, reset `ScissorStack` |
| Draw | `fillRect`, `drawImage`, `quad()` + `flush`, `curve()`, `bindTexture` (elides redundant rebinds), `text()` → a `CgTextRenderer` wired to this context's `PoseStack` |

> **`curve()` is `quad()`'s twin, and switching between them flushes.** Bézier strokes go through
> `CgVectorRenderer` with their own instance buffer and their own material (`gui_curve.shader`), and GL
> binds one program at a time — so the two cannot both be live. Every switch flushes the outgoing path,
> which is a **painter's-order requirement, not tidiness**: letting queued quads survive a switch would
> draw them after the curves regardless of submission order, so a stroke under a panel would jump on top
> — and only when the two happened to batch together, which reads as a z-order bug in the widget rather
> than a batching bug in the context. Because every switch flushes, at most one path ever holds pending
> work, which is what makes `CgUiRenderer.flush()` safe to run over both in any order. Alternating them
> per element costs a draw call each way; correctness never depends on batching.
>
> `curve()` applies the `PoseStack` exactly as `quad()` does — **never call `.pose(...)` on the result** —
> and stroke widths are scaled by the pose too, so a 2px stroke stays 2 *logical* px at any `uiScale`,
> the same as a 2px border.

> `quad()` returns `CgQuadRenderer.Quad` — `ctx.quad().at(x,y).size(w,h).uv(...).color(argb).submit()`,
> then `flush()` to draw (`submit()` only queues). **Never call `.pose(...)` on it**: `CgUiRenderer.quad()`
> is the single place the `PoseStack` is applied, and overwriting it silently drops `uiScale` and the
> element transform. It's re-applied per call because `CgQuadRenderer.quad()` resets the scratch
> instance's pose to null. The returned object is that shared scratch — build and `submit()` in one
> expression, never hold it.
| Clip | `pushScissor` / `popScissor` |
| Material | `withMaterial(material, body)` |
| Layers | `withLayerOpacity(opacity, body)`, `beginLayerFbo()` / `endLayerFbo()`, `blitLayer(fbo, opacity)`, `compositeMask(subtreeFbo, maskFbo)` |
| Lifecycle | `hasInstance()`, `destroy()` |

> **`destroy()` must be called on GL-context destruction.** The instance is `static`, so it outlives
> the context it was built against; without this the next context is handed an object whose material,
> VAO/VBO and FBO handles are all dead — which draws nothing or draws garbage rather than failing
> loudly. It frees only what the context genuinely owns (the layer FBO pool, the `CgUiRenderer`, the
> `CgTextRenderer`) and deliberately not what it borrows from CrystalGraphics registries (materials,
> the fallback white pixel, font atlases), since those are swept by `CgGraphicsLifecycle.destroyContext()`
> and freeing them here would be a double free. Use `hasInstance()` to check without *causing*
> construction.

> **Opacity isolation and masking go through an FBO layer pass, not a flat multiply.** The
> tint-vs-layer-opacity distinction is the thing most likely to be got wrong here — read
> `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8 before touching it.

## Supporting classes

- **`CgUiRenderer`** — thin wrapper over CrystalGraphics' `CgQuadRenderer` **and `CgVectorRenderer`**:
  instanced unit quads whose per-instance record (`origin` + `right`/`up` edge vectors, UVs, colour)
  lives in a class-wide SSBO/TBO. The `PoseStack` matrix is baked in at `submit()` time by
  `Quad.pose(...)` — three transforms per quad rather than four corners, and affine-correct under
  `transform:`. **Material bind/unbind is owned by `CgQuadRenderer.useMaterial()`**, which must be
  called before any `submit()` and again every frame; never call `material.bind()` yourself. Text goes
  through the same renderer (CrystalGraphics' `CgTextRenderer` owns its own `CgQuadRenderer` instance).
  The curve half mirrors all of it — `curve()` applies the pose, `useCurveMaterial()` binds, and
  `flushQuads()`/`flushCurves()` exist so `CgUiPaintContext` can flush one path without the other when
  it switches between them.
- **`ScissorStack`** — allocation-free nested clip stack (`int[64]`, 16 levels × 4 ints), applied via
  CrystalGraphics' `CgGL` facade. **No LWJGL imports** — the old "V3.x legacy, raw GL11, scheduled for
  deletion" note is obsolete.
- **`FontFamilyCache`** — `(font-family stack, target px)` → `CgFontFamily`, cached. Reference
  equality on the result is therefore meaningful and is relied on by `UIText`.

## Drawables — `render/texture/`

`CgUiDrawable` is the pluggable "paint yourself into a rect" SPI:
`draw(ctx, mouseX, mouseY, x, y, w, h)`, plus `intrinsicWidth()`/`intrinsicHeight()` returning `-1`
when the drawable has no inherent size (solid colours, SDF shapes). One `draw` call is expected to
issue exactly one GPU draw call, or zero for a fully transparent tint.

| Class | Role |
|---|---|
| `CgUiQuad` | Flat solid-colour fill; `CgUiDrawable.EMPTY` is one |
| `CgUiSprite` | Full 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`, lazy UV cache) |
| `CgUiRoundedRect` | SDF path — per-corner radii, morphing |
| `CgUiCrossFade` | Blends two drawables, for `background` transitions |
| `CgUiLayerBox` | Composites a stack; resolves `overlay-fit` via `intrinsicWidth()` |
| `CgUiRepeat` | Tiling modes |
| `ArgbMath` | Shared colour maths |
| `CgUiSvg` | Draws an `SvgDocument` into a rect — fitted and centred, never stretched. See below |
| `CgUiTransformDrawable` | Empty marker class — not implemented |

`render/texture/asset/CgUiSpriteRegistry` resolves `"namespace:name"` → sprite lazily from
`assets/{ns}/ui/sprites/{file}.json`. **A resource pack ships a theme by shipping JSON + PNG** — no
registration call. This is what `background: asset("crystalgui:ore", "button")` goes through.

`render/texture/svg/` is a **full SVG renderer** — scanner, path grammar, transforms, colour, inheritance,
scanline fills with holes cut, and real linear/radial gradients — parsing an `.svg` once into a cached list
of draw ops and submitting them through `ctx.curve()`/`ctx.triangle()`. **No atlas, no bake, no texture, one
instanced draw call for every icon on screen.** Full account in `ICONS.md`, including the nine things it
deliberately does not implement and why none of them matters for icons.

> **Two seams here are easy to get backwards, and both exist for the same reason: a document is shared.**
> `currentColor` is left unresolved in the cached ops and bound at draw time, so one parsed icon backs a
> selected row and an unselected one in the same frame. And `CgUiSvg` is a separate class rather than
> `SvgDocument implements CgUiDrawable`, because `draw()` has no tint parameter — a document implementing
> it would need a mutable tint field, and the two rows above would be writing to the same one.

`render/texture/geometry/` holds `Position` and `Size`, small Lombok `@Data(staticConstructor="of")`
int value types.

---

# Widgets

> **Full reference: `docs/CGUI_WIDGETS.md`** — per-widget API, internal-child class hooks,
> pseudo-classes, and covering harness scene. Read it before writing a new widget.

| Widget | Tag | Harness scene |
|---|---|---|
| `Button` | `button` | `cgui-button` |
| `Checkbox` | `checkbox` | `cgui-checkbox` |
| `CheckboxGroup` | — (not a `UIElement`) | `cgui-checkbox` |
| `DialogManager` | — (not a `UIElement`) | `cgui-gallery` (Dialog page) |
| `Switch` | `switch` | `cgui-switch` |
| `Slider` | `slider` | `cgui-slider` |
| `TextField` | `textfield` | `cgui-textfield` |
| `UIText` | `text` | `cgui-text`, `cgui-text-stress` |
| `Tooltip` | `tooltip` | `cgui-gallery` (Tooltip page) |
| `Dialog` | `dialog` | `cgui-gallery` (Dialog page, modal page) |
| `Popover` | `popover` | `cgui-gallery` (menus page) |
| `Menu` | `menu` | `cgui-gallery` (menus page) |
| `MenuItem` | `menuitem` | `cgui-gallery` (menus page) |
| `Dropdown` | `dropdown` | `cgui-gallery` (menus page) |
| `CanvasView` | `canvasview` | `cgui-gallery` (graph page) |
| `GraphView` | `graphview` | `cgui-gallery` (graph page) |
| `GraphNode` | `graphnode` | `cgui-gallery` (graph page) |
| `NodePort` | `nodeport` | `cgui-gallery` (graph page) |
| `Scroller` | `scroller` | `cgui-scroller` |
| `ScrollerView` | `scrollerview` | `cgui-scroller` |
| `SplitView` | `splitview` | `cgui-splitview` |
| `TabView` | `tabview` | `cgui-tabview` |
| `Tab` | `tab` | `cgui-tabview` |

## Conventions — all enforced in code

- **Composite widgets refuse public children.** `acceptsPublicChildren()` returns `false` on `Button`,
  `TabView`, `Switch` and friends — `addChild` throws. Only elements *designed* to hold children accept
  them (`UIElement`, `ScrollerView`, `SplitView` panes, `Tab.content()`). Give a new widget a named
  accessor for its content instead of opening the tree.
- **Structure is internal children.** `markAsInternal()`/`addInternalChild()` build a widget's parts.
  They're skipped by public traversal and by `UIDescriptionCodec`, and each carries a
  `__double-underscore__` class themes target. There are **no CSS pseudo-elements** in the style
  engine — this is the substitute. The full set currently in use:

  ```
  __bottom__  __corner__  __divider__  __fill__      __first__   __h-scroller__  __head__
  __knob__    __left__    __mark__     __pane__      __panes__   __post-icon__   __pre-icon__
  __rail__    __right__   __second__   __spacer__    __strip__   __strip-bar__   __tail__
  __backdrop__ __close__  __content__  __items__    __label__    __menu__
  __resizer__  __resizer-{top,bottom,left,right}__
  __resizer-{top,bottom}-{left,right}__  __thumb__   __title-bar__
  __top__     __track__   __v-scroller__  __vertical__
  ```
- **No sizes, no timings, no colours in Java.** Widgets write structure and state; `default.css` gives
  functional geometry, `ore.css` gives appearance. `Switch`'s knob animation is a CSS `transition` on
  `flex-grow`, not a Java tween. **If you are typing a pixel value into a widget, it belongs in
  `default.css`.**
- **`UIFrameTicker`** — implement it and call `registerTicker(this)`; return `false` from `tickFrame`
  to drop it. Registration is `HashSet`-backed so re-registering is idempotent, and there is no
  unregister by design.
- **New pseudo-class = override a getter.** See `PseudoClasses` above.

## `UIText` — the one widget with a non-obvious design

It does **not** use a Taffy `MeasureFunc`. Taffy 1.1.4's flex-wrap cross-size algorithm passes `NaN`
instead of an item's resolved column width under `flex-wrap: wrap` (the `nowrap` path is correct), so
a measured leaf wraps at the wrong width whenever any ancestor has wrapping enabled — unfixable
without forking a Maven dependency.

`text-overflow: ellipsis` truncates the **string** and re-shapes, never drops glyphs from the shaped run
— shaping is not a per-character mapping, so cutting the glyph array splits clusters. The ellipsis is
`…` when the font stack can draw U+2026 and `...` when it cannot, which is WebKit/Blink's own rule and
not hypothetical: the bundled `MinecraftRegular.otf` has no U+2026, and without the fallback a truncated
label draws a blank advance and is indistinguishable from `clip`. `displayedText()` returns what will
actually be painted — the only observable evidence that truncation fired, and the answer to
"tooltip only when the label is ellipsized".

Instead it is an ordinary Taffy leaf that recomputes *after* layout: `onLayoutChanged()` →
`recompute()` re-wraps against the box's just-settled `contentBoxWidth()` and pushes the resulting
height (and width, when self-sizing) back as `IMPORTANT` candidates via `StyleGroup.importantPipeline`.
Because `replaceOrPutCandidate` no-ops on an unchanged value, this **settles** — typically in 2–3
passes, all inside the same `calculateLayout()` `while (isLayoutDirty())` loop.

It retains a `CgShapedParagraph`, rebuilt only when the text or the resolved `CgFontFamily` instance
actually changes — never on a resize. Reference equality on the family is correct because
`FontFamilyCache.resolve` caches by `(paths, targetPx)`.

---

# Server layer — `serialization/` + `net/`

> **Full reference: `docs/CGUI_SERVER_AND_SERIALIZATION.md`.** Don't reverse-engineer it from classes.

A dedicated MC server builds a UI tree with **no CrystalGraphics present**, ships a description, and
talks to the client over RPC and bindings.

- **`serialization/`** — `Codec<A>`/`DynamicOps<T>`/`Codecs` (DFU-shaped), `JsonOps`, `PlainOps`,
  `StateMap` (widget state), `UIDescriptionCodec`, `ContentHash`; `serialization/style/` holds
  `StyleValueCodecs` and `InlineStyleCodec`.
- **`net/`** — `UIPacket`, `UIPacketCodec`, `UITransport`, `InMemoryTransport`, `ServerUiSession`,
  `ClientUiSession`, `RpcRegistry`, `NetworkIds`, `SheetRef`, `UiEventKinds`.

Three design facts worth knowing before you touch it:

1. **`ServerUiSession` holds no `UIWindow`.** That absence *is* the headless story, structurally
   rather than by flag: no window → no Taffy tree, no style engine, no layout → no path into text
   measurement, the one thing that genuinely needs a font stack.
2. **Descriptions are content-addressed.** `UIDescriptionCodec` output must be byte-identical for the
   same tree, so field order is fixed, maps are insertion-ordered, and absent optionals are omitted
   rather than written null. `OpenWindow` carries the *hash*, not the description — re-opening a UI
   costs one small packet however large the tree.
3. **Every packet carries a window id.** Resolving against "whatever menu is open" lets a packet in
   flight when a GUI closes land on the *next* one; four bytes makes that impossible.

`UIDescriptionCodec` encodes `{ tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]? }`,
skips internal children (the constructor rebuilds them), and **throws on an unknown tag**.

---

# Load-bearing invariants

The things that are invisible from any single class and expensive to rediscover.

| Invariant | Consequence if violated |
|---|---|
| `UIWindow.init(w,h)` must be called | `invalidateStyleMatch()` early-returns on detached elements → **no selector ever matches** |
| `calculateStyle` runs before `calculateLayout` | Style changes land one frame late |
| A window that is never painted never matches selectors | `drainDirtyMatch` only runs inside `calculateStyle` |
| `getRootTransform()` is the only definition of `uiScale` | Pose and hit-test caches drift apart by exactly `uiScale` until first paint |
| `uiScale` lives in the `PoseStack`, not the ortho projection | Glyphs rasterize at logical size and get magnified — blurry text |
| `sortedChildren` = z-descending, equal-z later-inserted-first; paint walks it reversed | Hit-testing and visual stacking disagree about which child is on top |
| A promoted element diverges from its DOM parent in **four** places (Taffy parent, `getX()/getY()`, `localToWorld`, paint+hit entry) — only the cascade stays | Fix three and it draws correctly but clicks land elsewhere, or the reverse |
| A promoted element's **containing block is the root** — so anything resolving `%`, `left`/`top`, or a clamp against "the parent" must ask the root, not `getParent()` | Percentages size against the wrong box, and a clamp stops a drag dead at the DOM parent's edge with the window still free |
| `UIWindow.getRootNodeId()` is **derived** from the root element, never stored | It was a field nothing assigned, so it was permanently null — and both reparenting methods bail out silently on null, which made top-layer promotion never move a Taffy node at all |
| Top-layer stacking is insertion order; `z-index` is irrelevant there (per spec) | Promoted elements stack unpredictably against each other |
| `Enter`/`Leave` dispatch to every element in the entered/left chain | A container with children never receives hover events at all |
| `setHitTest(false)` applies to the whole **subtree**, like CSS `pointer-events: none` | A transparent container is transparent everywhere except where its content is — hit testing looks random |
| While a pointer is captured, no boundary events reach anything else | `:hover` flickers across every element a drag crosses |
| `beginFrame()` only *invalidates* the hover cache, never reads it | Stuck hover (recompute against the new position labelled as the old) |
| `replaceOrPutCandidate` no-ops on unchanged values | Widget geometry feedback loops oscillate forever instead of settling |
| The cascade diff compares `realSlots`, not `computedSlots` | In-flight transitions can't be retargeted or cleaned up |
| Re-adding a stylesheet appends it at highest priority | Runtime theme switches apply in the wrong order |
| Taffy defaults are **not** CSS defaults | Silently wrong layout — see the table above |
| `flex-shrink: 0` means a `flex-grow: 1` child **overflows its parent** rather than shrinking — give it `height: 0`/`width: 0` as its basis | The child keeps its content size and anything stretching to that size spills with it. Cost a real session on the gallery: `__panes__` overhung the frame containing it, and only at small window sizes, because at large ones there was room. `FlexShrinkOverflowTest` pins both halves |
| **The fill idiom is `width: 100%; height: 0; flex-grow: 1`, and it never touches `flex-shrink`** | The corollary of the row above, and it caught three separate things in one session: the dock, a `RegionHost` and a `ViewContainer` each measured to nothing until they carried it. Overriding `flex-shrink: 1` to "help" does the opposite — the `0` default is what stops content being compressed below its own size, and with shrink on, every `ConfiguratorGroup` in the Inspector collapsed to a sliver. `.__inspector__` and the dock's pane host are the reference spellings |
| **A `SplitView` pane is a flex COLUMN regardless of the split's orientation**, so a pane's child always grows by height | Reasoning from the split instead — "this one is horizontal, so grow by width" — collapses whichever guess is wrong, and it fails as a *sibling* problem rather than a sizing one: the sidebar measured zero, so the file tree's rows existed and could not be clicked |
| **A `SplitView` cannot go below two panes, and a surplus pane cannot be made to occupy nothing** | `removePane` refuses and returns `false` — looping on it never terminates, which killed a test worker outright. And neither escape works: `applySplit` writes `flex-grow`, which only divides *free* space, while `setPaneSizeLimits` clamps *dragging*. The answer is to not use a split for one part — see `WorkbenchRegions.single` |
| **`flex-grow` summing to less than 1 leaves the remainder undistributed** | Not a bug, the flexbox rule. Weights authored as fractions of the whole sum to 1 only while every one of them is present, so it looks correct until one is hidden — then that fraction of the row is simply blank. Normalise before applying |
| **Never name a class `__content__` in a descendant selector** | Already stated on `CrystalEditor.CONTENT_CLASS` and `ProjectFileTree.CONTENT_CLASS` and broken a third time anyway. `ConfiguratorGroup` names its body that too, so `.__view-container__ .__content__` reached every group in every panel below it and zeroed their heights — chevrons open, content a sliver |
| **Cut/Copy/Paste is ONE command asking the position, never one command per widget** — `ClipboardActions` over `DataProvider.getData` | A menu bar has a single Cut row. Naming `editor.cut` there left it permanently greyed over the file tree while `explorer.cut` sat unreachable in the same registry; naming all three gives three rows, two of them always dead. IntelliJ's `$Cut` + `CutProvider` is the answer, and this engine already had the walk |
| **A compacted tree row is not the directory it looks like** — anything mapping a path back to the tree must go through `visibleRowFor` | With `explorer.compactFolders` on (the default, as in VS Code) an intermediate directory is not a row at all, so expanding it sets a flag nothing reads. `reveal` then walks the whole way down and finds nothing to select — silently, because every step "succeeded" |
| **A list restoring focus to a row must never take it from a CONTROL INSIDE one** — `ListView.restoreFocusIfRealised` asks whether the focused element *is* a realised row, not whether it is inside the list | The existing guard said "restore, never take" and stopped one step short: a list is not only its rows. The method exists to reattach focus to a row whose element went away and came back, so focus on something a row *contains* was never lost and taking it is theft. The explorer's inline rename is the shape that exposes it — F2 focused an input in a row, the next frame's restore pulled focus onto the row at `focusedIndex`, the editor read the blur as the user leaving and closed, so the field appeared for exactly one frame. Asked as "is it a row" rather than "is it inside the row I am restoring to", because the two rows need not be the same one |
| **A find bar closes when DISMISSED, never when its query happens to be empty** | Driving visibility from `!filter().isEmpty()` means backspacing the last character hides the box out from under the caret, so a query can never be cleared and retyped. VS Code's find widget stays until Escape or its close button; IntelliJ's speed search vanishes because it is a transient popup rather than a bar you can click into |
| **A blur raised by ROW RECYCLING is not a user gesture** — ask `ListView.isRecyclingRow()`, never assume | `recycle()` blurs a row before pooling it, deliberately and for a defect of its own, and at a listener that blur is indistinguishable from the user clicking away. Anything putting a focusable control in a row and treating lost focus as a decision has to ask. A flag the *widget* sets around its own `refresh()` calls cannot work: five call sites reach `treeView().refresh()` directly, so the list is the only thing that knows |
| **A row's inline editor is primed ONCE PER EDIT, never once per bind** | `bind` runs on every refresh — a listing arriving, a decoration changing, auto-reveal following the active tab, a fold — so priming there re-set the text, re-took focus and re-selected the stem several times a second. The reported symptom was a flicker; the real one was that the field could not be typed in at all, because the caret was put back every frame. Key the guard on the ROW ELEMENT, not a boolean: a row that scrolls out and back is a new template and does need priming |
| **A search box is a `TextField`, not a `UIText` showing what was typed** | The readout version looks identical and is not the same thing: it cannot be clicked into, cannot hold a caret, and Ctrl+A goes past it to the list and selects everything. If the query lives in two places — a string on the widget and a field on screen — one of them is wrong the moment anything writes the other |
| **A tree's inline editor is built in `createTemplate` and shown with `display`, never created in `bind`** | The edit begins from a key press *on the row*, so building the field then rebuilds the element that press is being dispatched through — the same trap the command palette's key chips and the editor's gutter arrows each paid for |
| **A placeholder path uses `U+0001`, and `U+0000` is refused outright by `CgPath`** | The obvious sentinel for "a row that is not a file yet" throws `path contains a NUL character` from the path type itself. Neither is legal in a real filename, so the row cannot collide with anything the workspace holds and a path that escaped to the server would be refused rather than creating something |
| **A menu bar must REMEMBER the focus owner, never read it when the menu opens** — the press that opens the menu has already destroyed it | `emitMouseDown` calls `emitAndLoseFocus` **before** it dispatches, and a bar title is `FocusPolicy.NONE`, so nothing takes the focus it just gave up. Falling back to the bar looks harmless and is not: the bar sits *above* the workbench content, so a context resolved from it sees `CrystalEditor` and not the dock, the editor, the graph or the explorer. The symptom is precise and misleading — File ▸ Save stayed enabled (it resolves against an ancestor of the bar) while Split Right, Next Tab, Close Panel and every Graph and Edit entry greyed out, reading as those commands being broken. IntelliJ records the focus owner at invoke time for exactly this. **And a test through `sendInputEvent` cannot see it** — that skips `emitMouseDown` entirely, which is how sixteen passing tests shipped the bug |
| **A menu bar resolves commands against the FOCUSED element; a context menu against the element that was CLICKED** | Opposite rules, and both are right: a right-click *names* its subject, a menu bar does not — File ▸ Save saves the active editor. Resolve the bar against itself and every `enabledWhen` in the application answers no, so the whole menu greys out and looks broken rather than empty |
| **The registry carries `enabled`; it never filters. Both menu renderers DIM rather than hide** | The palette copied VS Code's hide-disabled behaviour once and listed **1 of 9** commands, because every `enabledWhen` resolves outward from focus and "nothing focused" answers no to everything. A menu whose rows appear and vanish is also a menu whose rows are never in the same place twice. `CommandRegistry.menu()` is the deprecated version that filtered, and only a test ever called it |
| **`MenuBuilder` is the only thing that turns commands into menu rows** — `ContextMenu` and `MenuBarView` are both callers | Six rules live there and every one was learned from a bug: separators between sections but never leading/trailing/doubled, an unregistered command still gets a (disabled) row, enablement re-checked at activation, the command re-resolved through the registry when it runs, accelerators read live, an empty submenu dropped but a disabled one kept. A second builder gets some subset right and the two drift within a release |
| **A `MenuId.submenu` declaration is PERMANENT** — it lives on the interned id, not on any registry, so `CommandRegistry.resetForTesting()` cannot undo it | Correct (a submenu is a structural fact about a menu, like a class declaration) and a trap for tests: one test nesting a child left every later test seeing a stray section. Use a fresh `MenuId.of(name + counter)` per test rather than a shared constant |
| **`font-size` does not inherit, whatever `setInheritable(true)` says** — `default.css` opens with `* { font-size: 10 }`, which is a candidate at STYLESHEET origin on *every* element, and inheritance only applies where there is no candidate at any origin | A rule on a wrapper computes correctly on the wrapper and the label inside it still renders at 10. `statusbarview .__status-item__` works only because the status item **is** its `UIText`; any widget that wraps its label — for padding, a hover fill, an icon slot — must put the declaration on the text element itself. Cost a probe on the menu bar, where `height: 22px` on the same selector applied and the `font-size` beside it silently did not |
| **`font-weight`/`font-style` DO inherit — and are drawn by `UIText` alone** | The mirror of the row above, and both halves surprise. They inherit because nothing writes a universal rule for them, so a `font-weight` on a wrapper *does* reach the label inside it — do not "fix" the asymmetry with a `* { font-weight: normal }`, which would silently disable it (`FontFaceTest` pins this). And they reach the glyphs through a **`CgStyleSpan`**, because the two faces are *synthesised* — so `TextField` and `TextEditor`, which draw via `CgTextRenderer.Draw.text(String)` with a bare family rather than a styled paragraph, resolve them and paint nothing. Anything measuring styled text must measure on the same path it paints on: synthetic bold is wider, so `measureEllipsised` truncating against an unspanned probe cuts in the wrong place |
| **A dependency pinned only at its top artifact is not pinned** — `org.eclipse.jdt.core` declares its platform dependencies as OPEN RANGES (`[3.14.0,4.0.0)`) | The resolver takes whatever is newest on the day it runs, so the same build resolves differently in six months with no commit to blame — and the band-8 closure came back with 2024-era jars at class major 53+, unloadable on Java 8. **The top artifact was correct and the closure was not**, which fails only on the host nobody building it has. Every transitive artifact is pinned per band, and `:language:checkEngineBands` re-derives the floor from the bytes on every `check` |
| **Two jars that split a Java PACKAGE must share a signing certificate** | Eclipse rotated its cert between 4.19 and 4.20, and `org.eclipse.core.runtime` is split across `core.runtime`, `equinox.common` and `equinox.registry`. Picking each artifact's newest Java-8-compatible version took jars from both sides, and a JVM refuses such a package with `SecurityException: signer information does not match` — after everything resolved, every class file was major 52 and the ceiling check went green. **Pinning is constrained by signing era as well as by bytecode version.** Two traps in checking it: `JarEntry.getCertificates()` answers null unless the entry is drained (and sometimes even then), and hashing only the *first* certificate compares the shared DigiCert intermediate rather than the leaf — which made the check pass on the exact pin it was written to catch |
| **Kotlin block comments NEST, unlike Java's** | A slash-star sequence inside a `.gradle.kts` doc comment — writing a glob like `META-INF/` followed by a literal star — opens a nested comment whose close ends only the inner one. Everything after it silently stops registering: `gradlew tasks` still succeeds, the build still passes, and the only symptom is a task that "does not exist". Cost an hour of looking at the wrong thing entirely |
| **A script-level `fun` in `.gradle.kts` must be declared BEFORE its use** | The script runs top to bottom, so a forward reference to a helper does not resolve — unlike a class, where method order is free |
| **Execution must never touch the grammar natives** — `.java`/`.map`/`.run`/`.engine` may not name `org.treesitter` or `.grammar` | A dedicated server runs scripts and has no editor; making it load five platform natives to do so is paying for something it does not have. Enforced as a **bytecode scan** of our own class files (`ExecutionNeedsNoGrammarTest`) rather than by deleting jars and running a fixture: a reference in the constant pool is the real question, because if a class file names the type at all then some input can reach it — and a runtime check only ever proves it for the path that test happened to take |
| **The Run shell (`language.run`) is written against `ScriptRuntime` and names NO language** — a runtime contributes itself through `ScriptRuntimes.contribute(Language, provider)` from its own `register()`, and the shell finds a file's runtime through the `LanguageRegistry`, never through an extension check of its own | The shell was first written against the concrete Java host, and it did not read as wrong while Java was the only runtime: `ScriptWorkbench` asked `JavaLanguage.isAvailable()`, refused anything not ending in `.java`, and installed the JVM frame filter by name. A second language would have meant a second Run command and a second panel wiring — or a rewrite of both. `RunShellIsEngineNeutralTest` is the bytecode scan that fails the commit which reintroduces it; `ScriptRef.Origin` is the same seam one level down (a JVM class walks its frames for the line that printed; a Rhino script asks its context), which is why the console never learns how a line was located |
| **The band loader is opened ONCE per process (`EngineHost.shared`) and every language reaches its adapters through it** — `JavaEngine.over(host)` borrows, `JavaEngine.open(band, source)` owns, and only the owner closes | ECJ and Rhino ship pinned together in one band configuration, so a JavaScript engine that opened its own `EngineClassLoader` would be a second copy of twenty jars and a second identity for every type they share. Closing a borrowed host is how the *other* engine fails with a `NoClassDefFoundError` on a class it loaded fine a moment ago |
| **A CALL is a receiver, and BOTH engines got it wrong** — `list.get(0).` / `Files.emptyList().` put a `)` immediately before the dot, where no identifier covers the offset | Reported from the harness as "completion is flaky in places", which is what an EMPTY POPUP looks like: the popup opened, so nothing read as broken. JavaScript's `resolveAt` looked only for a `Name`; Java's walked up for a `SimpleName` and a closing bracket has none above it. Both now resolve the enclosing EXPRESSION and take its type — a call's type is its callee's, which composes to any chain depth. The Java gap was found only because a JS fixture caught it there first and the test was copied across: "it works in the other engine" is a claim, not a test |
| **`NodeFinder.perform(unit, offset, 0)` picks the node ENDING at the offset — ask with length 1** | JDT's covering test is `start <= offset && offset <= end`, so a zero-length range at the `)` of `get(0)` is covered by the `0` literal and the receiver resolves to `int`. That is worse than answering nothing: a non-null type stopped `JavaCompletionProvider` falling through to its probe re-parse, so the case the fix was written for stayed broken with a *different* cause. Asking about the character itself picks the node that contains it. And the provider now probes when the direct answer has NO MEMBERS as well as when it has no type — an empty member list is never a useful answer, and a type resolved from a recovered tree can be plausible and wrong |
| **`JsLanguage.register` must lend the Java engine on the ALREADY-REGISTERED path too** | The early `if (analyzer != null) return true` meant registering JavaScript before Java left the interop tier unlent for the life of the process, and a second `register()` — exactly what a host or a test opening both languages makes — returned without retrying. The member list behind `new java.util.ArrayList().` then fell back to reflection or to nothing, decided only by which language registered first. The lazy retry in `servicesFor` covered the document path and not this one, so the bug was *test-order dependent*: 17/17 scoped, four failures in the full suite |
| **A `SymbolInfo` carries no binding, so quoting a Java member's declaration needs a probe unit that NAMES that member** | `membersOf` deliberately attaches no signature — it answers with hundreds for a completion list that would never read one — and `Signature`'s own javadoc says so. Quoting from `src.zip` needs the member's binding KEY, which nothing on the seam carries. `InteropResolver.describeMember` therefore analyses `class $Probe { <fqn> $x; void $m(<T0> $p0) { $x.<member>($p0); } }`: a parameter of each declared type, passed at the call, so overload resolution is exact. Parameters rather than casts, because a cast of `null` is ambiguous for a primitive and a cast to a type variable does not parse. And it contributes the **signature and declaration site only** — it resolves against the generic declaration, so its container reads `java.util.ArrayList<E>` where `membersOf` says `java.util.ArrayList`, and returning it wholesale made one member describe itself two ways depending on which query asked |
| **Parameter NAMES are passed to a signature renderer, never carried on `SymbolInfo`** | Core's seam holds parameter TYPES on purpose: JDT reports `arg0` for an ordinary classpath member, so a names field would be populated with a placeholder by the engine that has most members — and a consumer cannot tell a real name from `arg0`. JavaScript always has the real names because the declaration is in the file, so `RhinoResolution` reads them off the AST and hands them to `JsSignatures`. This is why `JavaSignatures` shows `getProperty(String, String)` and `JsSignatures` shows `join(name, count)`, and neither is a shortcoming |
| **A stale `language/build/classes` presents as "the engine band loaded, but the adapter could not be instantiated"** | `EngineHost` puts our own class files on the band loader's URLs, so a half-written output directory makes the adapter unlinkable — the observable failure is `NoClassDefFoundError` on a nested class that plainly exists in source, or 21 unrelated Run tests failing at once. It is not a code fault and no amount of reading the diff will find it: `:language:clean` (after `--stop`, since the daemon holds the jars) and rebuild. Interrupting a compile is the usual cause. Same shape as the harness note in memory, one layer down |
| **A Rhino "statement" is a node whose PARENT is a `Block`, a `Scope` or the `AstRoot` — and `Block` must be named explicitly** | Asked structurally rather than by listing statement classes, because Rhino has a dozen and a list is a thing to forget an entry from. Leaving `Block` out is not a near miss: a function body *is* a `Block`, so the walk runs past every statement inside every function and answers the function itself — "insert above this statement" became "insert at the top of the file", which reads as off-by-one offset arithmetic rather than as the wrong node. And the ROOT is not a statement: returning it for an empty document made "surround with try/catch" offer to wrap nothing at all |
| **A fix's test asserts the TEXT the edit produces, never the edit's fields** | Applying the `ChangeSet` back to front and comparing the result is the only assertion that cannot pass against an edit at the wrong offsets — and back to front is what a `ChangeSet` means, since an earlier change must not move a later one's coordinates. A test that checked `from`/`to`/`insert` passes against a fix that lands one line up, which is exactly the failure mode of every offset-based rewrite |
| **A script sandbox has ONE entry point, read by four surfaces — never a field per consumer** | `ScriptPolicy` lives in `language.run` (the type index and Java compilation ask the same question, so it is not JavaScript's) and `JsLanguage.restrictTo` is the only setter; `JsHost.restrictTo` forwards to it. It started as a field on the host, which is a bug in waiting rather than a style question: the executor would obey one policy while resolution, completion and the index obeyed another, so a class could be **offered by the popup and refused at run time** — worse than either restriction alone, because the editor is then actively wrong. The child side receives a `Predicate<String>`, never the policy type, like every other crossing. An array is its element type, a primitive is always reachable, and an empty allowlist refuses everything rather than being helpfully widened |
| **A policy filters the type index through a VIEW, and a member list on the way OUT** | The index is shared per classpath and holds fifty thousand entries, and the policy belongs to the asker rather than to the classpath — so `TypeIndex.filtered` wraps the answer instead of copying the index. `InteropResolver` filters after the probe for the same reason: the Java engine's answer about a class does not depend on the policy, so the cached analysis stays reusable and only the member cache is dropped when the posture changes. A member whose DECLARING class is refused goes too — an inherited `toString()` is still a call into the type that declared it |
| **Renaming Rhino's Java members is a MEMBRANE, never a subclass and never a patched `JavaMembers`** | Three things forced it, each measured. `JavaMembers` is internal and differs per band, so a patched copy is a fork to re-derive. Subclassing `NativeJavaObject` compiles and throws `NoSuchMethodError` at the first binding — its `(Scriptable, Object, Class)` constructor is on band 8 and not on band 11. And overriding `wrapAsJavaObject` does **nothing**: Rhino's own `wrap` constructs the wrapper directly on this band, so the feature sat silently inert with the factory installed and the mapping non-identity. So: override `wrap`, wrap the RESULT in a `Scriptable`+`Wrapper` membrane. `Wrapper` is load-bearing — `NativeJavaMethod.call` unwraps its receiver through it, so a `Scriptable`-only membrane is found by the lookup and rejected by the call — and the membrane must also be a `Function` when the delegate is, or `new java.util.ArrayList()` fails with "is not a function". `NativeJavaArray` is excluded: a membrane in front of one intercepts its indexing to rename members an array has none of |
| **A remap test's fixture must use INHERITANCE, not composition** | A mapping names the type that *declares* a member while a script holds whatever it holds, so the translation walks the hierarchy. A delegating fixture declares the runtime name itself and passes without exercising the walk at all — green against a translation that cannot see a supertype, which on a real deployment is nearly every call |
| **A completion list is asserted on `filterKey()`, never on `label()`** | A method's label carries its parameters (`add(Object)`, `f()`) because that is what the row shows, while the name is what is typed against it. Every member assertion in the first draft of `JsCompletionTest` failed against a completely correct list — "add is missing from 43 rows", where all 43 were ArrayList's members under their decorated labels. And the corollary: `CompletionItem.builderFrom` already writes a method's insert snippet, with the caret BETWEEN the brackets when there is an argument and AFTER them when there is not, so a provider that writes its own always-`name($0)` version is both duplication and a downgrade |
| **A trailing dot needs a PROBE RE-PARSE in every language, dynamic ones included** | `list.` on its own is not a parseable expression — no node at the offset, no receiver, no members — which is why typing one more character makes the list appear and makes it look like a timing problem. `JsCompletionProvider` was written without one on the argument that JavaScript can fall back to the live scope instead; the fallback then fired for **every statically typed receiver in the file**, so a list appeared, it was the wrong list, and nothing failed. Probe first (an unlikely name inserted at the caret, IntelliJ's own trick), live scope only for a receiver that genuinely has no knowable type |
| **A prototype's members are NON-ENUMERABLE, so `getIds()` reports none of them** — `ScriptableObject.getAllIds()` is the accessor | `toString`, `valueOf` and `hasOwnProperty` are non-enumerable by specification, so reading `Object.prototype` with the obvious accessor answers an empty list and the inherited half of every member list vanishes without a symptom. `inheritedFromObject` then has nothing to mark, which reads as the flag being unused rather than as the ids being missing |
| **A Rhino `Token` constant may NEVER be compared against an `AstNode`'s type — they are `static final int`s, javac INLINES them, and the bands renumbered the set** | Measured, not feared: compiled against band 8's Rhino `Token.NUMBER=40` and `Token.TRUE=45`, while on band 11's Rhino a `NumberLiteral` reports **45** and a `KeywordLiteral(true)` reports **51**. So `getType() == Token.TRUE` is true **for a number literal** on the bands most users are on, and nothing throws — this is the `ObjectProperty.getLeft()` divergence in its nastier form, where that one failed loudly with `NoSuchMethodError` and this one silently answers a different question. It had already shipped: `RhinoScopes.isAssignmentTarget` compared `Token.INC`/`Token.DEC`, so `count++` stopped counting as a reassignment off band 8 and the colour was quietly wrong. Ask the node's **class** where one exists, and its **text** (`RhinoTokens`) where it does not — and note the class is not always available either, since `a++` is not a `UnaryExpression` at runtime and whatever holds it is absent from band 8's jar to name at compile time. Enum members (`Token.CommentType.JSDOC`) are references, not inlined ints, and are safe |
| **The JS resolution tiers live BESIDE the tree (child-side), not above the bridge** | `plan_m10.md` §3.1 put `JsResolver` host-side over a `JsAstView`; three of the four tiers read the tree (inference reads initializers, JSDoc reads the comment above a declaration, the declaration tier reads the scopes), so that shape is a bridge crossing per node walked, on every hover and every keystroke of a completion. The Java engine answers `resolveAt` on its own side and sends one `SymbolInfo` across — which is what the bridge is *for*: the answer crosses, never the tree |
| **A JSDoc tag is found by splitting on TAG BOUNDARIES, never by lines — and the comment is located from the STATEMENT's start, not the name's** | Both halves were silent. `/** Text. @type {string} */` on one line is the ordinary way to write a short doc comment, and a line-based reader folds the tag into the description and reports no type at all — while every multi-line fixture passes, because there the tag does start its line. And measuring the whitespace gap from the comment to the *identifier* finds `var ` in between, so it concludes the comment belongs to something else: that is every declaration in the language, so it found nothing anywhere while looking right on a bare `function` |
| **The interop probe cache belongs to the ANALYSER, and an analysis's `close()` must not empty it** | `InteropResolver` holds one resolved Java `Analysis` per class name (LRU, 12, closed on eviction — a bounded cache that does not close what it drops is a leak with a nicer name). It is keyed by class name and a Java class means the same thing in every open document, so sharing it across documents is what makes the second file to mention `java.util.ArrayList` free. Closing it from `ParsedScript.close()` would empty it whenever any one file was edited — a re-analysis of every Java class the next keystroke mentions |
| **The Java engine is lent to the JS analyser on every DOCUMENT OPEN, not only at registration** | Requiring Java to register first would work — every host we ship does it that way — and the failure would be silent: the member list falls back to reflection, which answers plausibly and less well. An ordering rule nothing enforces is one somebody eventually breaks, and a document cannot open before both languages have registered, so that is the last moment the order could still be wrong and the first at which it certainly is not |
| **Rhino's application class loader is the HOST's loader plus `org.mozilla.*` from the band — never the child loader, and never the host's alone** | Rhino refuses a loader that cannot resolve its own classes (`Loader can not resolve Rhino classes`), and the host by design cannot; the child loader is child-first over the language jar and would define its OWN copy of every host class, so a binding the host handed over and the same class named through `Java.type` become two types with one name. `RhinoExecutor.APPLICATION_LOADER` is parent-first over the *bridge interface's* loader (the host's, by parent-first construction) with `findClass` answering only Rhino's package. And a `Class` handed to a script goes through `WrapFactory.wrapJavaClass`, not `Context.javaToJS` — the latter wraps it as an object whose members are `getName()` and friends, and every static call is "Cannot find function" |
| **A JS stop names its THREAD (`JsExecutor.stop(Thread)`), sets a per-run flag AND interrupts, and reaches the host as `InterruptedException`** | One `RhinoExecutor` serves every host in the process, so a stop with no argument would end somebody else's script. The flag rather than the interrupt status alone because `Thread.sleep` clears the status when it throws — a script that swallowed the exception would run on unstoppable. The observer throws an `Error`, which Rhino's interpreter refuses to let a script's `catch` take (`aStopCannotBeCaughtByTheScript` pins it); the child cannot name `ScriptStoppedException`, so the boundary translates to the JDK's own type and `JsHost` translates again. `console.warn` goes to the error consumer, as Node sends it to stderr |
| **A runtime's verdict about a document rides `AnalysedLanguageServices.reportRuntimeProblems`, found through `attachedTo(Resource)` and hopped through the scheduler — never a second owner, never inline from the script thread** | The editor files one owner per services object, so a `js-runtime` diagnostic is a *source* on a diagnostic in the engine's own list, held in a second tracked lane so an edit above it moves it and the next run withdraws it. Announced at the current analysis's version: a stale editor refuses it and the pending analysis carries it — the invariant about row/columns being legal only against the document the analysis saw is kept, not bent |
| **The attachment is written once (`AnalysedLanguageServices`) and an engine supplies only `analyse(source, version)` → `Analysis`** — debounce, install/dispose, retention of optional warnings through a syntax error, semantic tokens, resolution and the versioned announcement all live in the base | `JavaLanguageServices` was 437 lines of which perhaps forty were Java; a JavaScript copy would have drifted from it inside a release on precisely the policies that must be decided once (a stale list is dropped, a stale colour is kept). The request shape stays per language — `SourceAnalyzer.analyze` names a class, a classpath and a release level — and the **answer** is the shared `bridge.Analysis`, which is what lets one install path serve every engine. And a `start()` call ends every subclass constructor: the base cannot run the first analysis itself without calling into a subclass whose fields do not exist yet |
| **`core/`'s language SPI names no language's facts** — which type is the universal root (`java.lang.Object`, `Object.prototype`) is the engine's to say via `CompletionItem.builderFrom(symbol).inheritedFromObject(…)`, and every `SymbolInfo.with*` carries every field | `CompletionItem.from` used to compare the container against `"java.lang.Object"` inside core, and the withers routed through the seven-component constructor, so `of(...).withSignature(s).withType(t)` silently dropped the signature — an order-sensitive builder on a seam is a trap for exactly the engine that comes second |
| **A cooperative safepoint must be injected as a CALL, never as a read-and-branch** | A new branch target in a Java 7+ class file needs a new `StackMapTable` entry → `COMPUTE_FRAMES` → ASM calling `getCommonSuperClass` → **loading classes at instrumentation time**, which on a Minecraft host means loading MC classes while compiling and fails outright for a type not yet loadable. A single `invokestatic` of a void no-arg method adds no branch, no local and no stack depth, so every frame and max stays valid — and HotSpot inlines the callee back to the volatile read the obvious version would have emitted |
| **The kill flag is the thread's own interrupt status, and a stop is an `Error`** | One `interrupt()` then reaches a *spinning* script through the injected check and a *blocked* one through `InterruptedException`; a private static would cover only the busy half. `Error` rather than `Exception` because scripts are full of `catch (Exception e)` around exactly the loop a stop must break out of. `catch (Throwable)` still defeats it and nothing cooperative can beat that — the trust model is the answer |
| **`core/` must never depend on `language/`** — the language SPIs live in `core/src/main/java/com/crystalgui/text/lang/` and the engines implement them from the other side | `TextEditor` consumes `LanguageServices`, so the interface has to be where the editor is. Invert the dependency and tree-sitter's five platform natives — and later ECJ's ~15MB with its DOM stack — land on a dedicated server's classpath, which is the one thing `headlessTest` exists to prevent |
| **A language capability is absent in three independent tiers, and each absence is silent** | No engine → grammar colouring; no grammar module → `KeywordTokenizer`; neither → plain text. All three are spelled as "is this reference null", which is why there is **no** `enableSemanticHighlighting` setting: a boolean can disagree with what actually loaded, and then two things claim to answer the same question |
| **`LanguageServices` belongs to the DOCUMENT, not to the editor or the widget** | The same file in two split panes is one document: two service sets would double every compile, publish two competing slices into one `DiagnosticSet`, and disagree about which version they reached. So `setLanguageServices` unsubscribes but never closes, and `TextFileDocument.dispose()` is the owner. **That dispose is also what finally calls `SyntaxTokenizer.close()`** — the method existed since the seam did, `OpenDocuments.close` only disposes documents implementing `Disposable`, and a text document was not one, so every native parse tree in the application survived until the process ended |
| **A provider's own order is NOT a ranking signal, and an empty prefix must still be sorted** | `collectMembers` walks every declared method and *then* every declared field — two loops, not a judgement. `CompletionSession` skipped sorting when the prefix was empty on the stated grounds that the provider had put the useful things first, which put `System.out`, `err` and `in` at position forty-one: below the window, and indistinguishable from missing. The empty prefix skips the **matcher** (which returns null for every row and would empty the list) and never the **ranking** |
| **Rank by the match TIER, never by `SearchMatch.score()`** | The score folds earliness and *brevity* into the tier, so comparing on it makes those outrank proximity outright: `pr` ranked a local `precision` below a class `Printer` purely because `Printer` is two characters shorter. Found in a harness **log** — eleven rows make an ordering legible in a way a test asserting on the top row does not |
| **Position a popup from `getWindowX/Y` (the layout chain), never from `localToWorld`** | The transform chain is in *surface* pixels with the root transform baked in, while `left`/`top` are logical and get scaled again — so the anchor comes out multiplied by `uiScale`. And it is only populated during `drawSubtree`, so anything asking before that element has painted reads an identity matrix and gets the window's corner. Both faults place the box neatly somewhere wrong |
| **`gap-all` applies between EVERY pair of children** | Which is wrong the moment two of them are one thing: it put a space inside a signature — `arraycopy (Object, int, …)` — reading as two controls rather than one name. A gap wanted in exactly one place is a margin there, not a gap on the parent |
| **`icon()` in CSS resolves the light/dark variant only through `CgUiSvg.ofIcon`** | `TextureValue.parseIcon` called `of(toResourcePath(...))` instead, so every `icon()` in every stylesheet drew the LIGHT file forever and a theme swap changed nothing. Invisible for as long as the shipped icons were `currentColor` chrome marks with no dark drawing, where `withVariant` falls back to the base file and the two spellings agree |
| **A modifier overlay is a FULL-SIZE layer, not a badge in a corner box** | JetBrains draws `staticMark`/`finalMark` on their own 16×16 canvases with the glyph already placed — static bottom-left, final top-left — so they compose by stacking at the icon's size and both can show at once. Scaled into a small corner instead, a mark silently draws a third too large and in the wrong corner, which reads as bad artwork. **They must carry the icon's size**, not the artwork's |
| **A hint strip must be DERIVED from the key table its handler reads** | "Press Enter to insert, Tab to replace" written as a literal is a promise that stops being kept the first time a binding moves, with nothing failing when it does. And it was already untrue — both keys merely accepted — so making the strip honest meant implementing replace. A strip is not decoration: the two keys differ and nothing else says so |
| **The bundled `MinecraftRegular.otf` has no U+2026 and no U+22EE** | The ellipsis fallback in `UIText` is documented; the vertical ellipsis is the same trap and has no fallback, so a kebab spelled as a glyph draws tofu. Three dots are not creative work — draw the icon |
| **A diagnostic is row/column and a squiggle is offsets — the conversion is only legal against the document the analysis SAW** | Hence two separate mechanisms that are easy to confuse. `LanguageServices.onDiagnostics` carries a `Versioned`, and a list describing an older document is **refused at the point of entry** — it is as wrong in the Problems panel as under the text, so one gate covers both. Then `DecorationSet` keeps the offsets right *afterwards*, through the 300ms of typing before the next compile. Without the second half every mark below the caret pointed at whatever had shifted into its offsets, and it corrected itself on the next compile — which is why it read as the analyser lagging rather than as a broken mark |
| **The diagnostic tracking hangs off `DiagnosticSet.onChanged`, never off the engine's push** | The shader graph writes four owners of its own on every compile and has no version to offer, and a future linter will be a fifth. Wiring the tracking to the engine covers only engine-reported problems and leaves every other producer silently untracked — the failure mode that looks exactly like the feature working |
| **`Stickiness` is a property a range is CREATED with, never inferred** | Insertion *at* a boundary is the one case with two defensible answers, and which is right depends on what the range means: a new character at the start of an error squiggle belongs to the error, and at the start of a fold marker does not. Same edit, same offsets, opposite correct answers. Monaco's four modes are each a pair of `ChangeSet.mapPos` assoc values and nothing downstream knows the mode exists |
| **Mapping is monotonic only for a FIXED assoc, so a `DecorationSet` must re-check its order** | Two ranges starting at the same offset under different stickiness come out of one insertion in the opposite order. Assume order is preserved and the binary search in `overlapping` walks an unsorted list and silently misses ranges. The scan is one pass and skips the sort almost always |
| **A range that COLLAPSED because its text was deleted is not a range that was born empty** | Both answer true to `isEmpty()`. A zero-width diagnostic is real — "expected ';'" points between two characters and is widened to one so it can be seen — and widening the other kind paints a mark over whatever innocent text moved into its place. `TrackedRange.collapsedByEdit()` is the whole distinction |
| **Completion ranks by match TIER then proximity — never by the matcher's score, which folds in brevity** | `SearchMatch.score()` adds earliness and *shortness* to the tier, so comparing on it makes those outrank proximity outright: typing `pr` with a local `precision` in scope ranked it below a class `Printer`, purely because `Printer` is two characters shorter. Found in the harness **log**, where eleven rows make the order legible in a way a test asserting on the top row does not. Brevity stays, as a tiebreak after proximity |
| **A subsequence tier is opt-in per CONSUMER, and both consumers are right** | A completion list needs `fMS` → `fooMethodStuff`; a create menu deliberately refuses it, because over a few hundred short labels the same rule returns a long tail nobody meant. One matcher with a flag, not two matchers — the second matcher is how a panel's filter and its search come to disagree |
| **Position a popup from the LAYOUT chain (`getWindowX/Y`), not from `localToWorld`** | The transform chain is in *surface* pixels with the root transform baked in, while `left`/`top` are logical and get scaled again — so the anchor comes out multiplied by `uiScale`. And it is populated during `drawSubtree`, so anything asking before that element has painted reads an identity matrix and gets the window's corner. Both faults place the box neatly somewhere wrong, which is far harder to notice than a box that is obviously broken. The transform chain stays the definition for hit-testing, which must agree with what was drawn |
| **`TextEditor.getScrollTop()` can be NaN, and NaN poisons a whole layout silently** | Every view part computes a line's top as `origin + line * lineHeight - scrollTop`, so one NaN stacks every row at the same y. It survives `setScrollImmediate(0, 0)` and survives making the editor the window root, so something recomputes it during layout — `getMaxScrollTop` reading an unmeasured viewport is the likeliest source. **Open**; reproduced by `cgui-completion`, absent in `cgui-dock`. Anything consuming the offset outside the editor should treat a non-finite value as zero |
| **Semantic tokens REPLACE grammar tokens where they overlap; they do not layer** | Merged into one per-row bucket in `ensureRowSyntax`. Two overlapping ranges under unrelated names leave the winner to paint order, and **both names resolve to real colours** — so the wrong one reads as a scheme bug rather than an ordering one, which is exactly how the capture-precedence bug cost two rounds. Note the corollary when writing a test: a dotted capture is *also* published under its general form, so grammar-`variable` vs engine-`variable.parameter` cannot show the difference — assert on a pair where neither is the other's general form |
| CSS text belongs in `test`, never `headlessTest` | `StyleSheet` class-init reads `default.css` via `CgIO` → unloadable headlessly |
| JOML + Taffy must stay on the headless classpath | Field descriptors resolve at class load; `UIElement`/`ElementStyle` have fields of those types |
| CrystalGraphics `platform` must stay on the headless classpath too — the excluded module is CG **core** | `UIInputHandler` *implements* `CgSystemInput`; a supertype resolves at class load, so stripping it fails every input test with `NoClassDefFoundError` |
| CrystalGUI has no platform registry — input, sound, clipboard and cursor all come from `CgPlatform` | Two registries let a loader wire up one and not the other: a working GL backend and a dead keyboard, with nothing to report it |
| Composites return `acceptsPublicChildren() == false` | `addChild` throws; widgets need named content accessors |
| `attachListener`'s two booleans are additive | Target phase is *always* subscribed — and the corollary is the one that bites, because the row above reads as *more* subscription: **`(false, false)` is target-only, so a container hears nothing a descendant was targeted with.** `RegionDropOverlay` listened on the workbench's content box for a bubbled `DragEvent.Over` that is dispatched to whatever is geometrically under the pointer — a tree row, a rail button, an editor — and content is never that thing. No highlight, no label, and a drop that could not be accepted because `preventDefault()` was never reached, with every individual piece correct |
| **`stopPropagation()` is `stopImmediatePropagation` WITHIN a phase — so a listener attached to a widget's own event group after its constructor may never run** | `EventListenerGroup.emitTarget` emits through `continueEmittingUnderCondition(..., UIEvent::isPropagationStopped)`, which halts the remaining listeners **on the same element and phase**, not merely the walk to the next element. A widget that stops propagation therefore pre-empts every later subscriber to that group, and a widget always subscribes first because it does so in its own constructor. `TextEditor`'s `MouseEvent.Down` ends with an unconditional `stopPropagation()`, so the Run console's stack-frame links could not see a press at all — while its `Up` does not, which is the only reason clicking works. **The symptom is the opposite of the cause**: the caret moved and double-click selected a word, so events were plainly arriving, and two rounds of diagnosis went to the click's coordinates before the phase. Anything that must run before a widget's own handler has to use the CAPTURE phase on an ancestor — the same element is not early enough |
| The popover stack and the close-watcher stack are separate | A modal is Escape-closable but not light-dismissable; a MANUAL popover is neither — one list gets one of them wrong |
| Light dismiss runs after the mouse-down dispatch, and spares the invoker | Dismiss-first tears down the tree under an undelivered event; no invoker carve-out and a dropdown button flickers instead of closing |
| Light dismiss considers the popovers open **before** the dispatch, not the live stack | A popover opened from a mouse-down handler is closed by the very press that opened it — it appears never to open at all |
| A widget's cascade identity is its **tag**, never its Java supertype | `Dropdown extends Button` but `button {}` does not match `dropdown` — it laid out at zero height until `default.css` named it |
| Only `AnchoredPlacement` writes `left`/`top` on an anchored popup | Any other writer fights placement every frame |
| Transitioning *into* view needs a resting value in the sheet, never a one-frame write from Java | The write is itself transitionable, so the engine eases toward it and the cleanup retargets it back — nothing animates, and no test sees it |
| `TransitionEngine` advances on `System.nanoTime()`, ignoring the delta it is passed | A test loop cannot step transition time; assert the inputs (resting value, state class, ANIMATION-origin candidate) rather than intermediate values |
| **Document state goes through `Edit`s; view state is mutated directly** | The boundary the whole undo mechanism rests on. Anything a reload should give back is an edit (text, nodes, connections); anything that is only how you are *looking* at the document is not (scroll, selection, column widths, pan/zoom). VS Code, Photoshop and Godot all draw it here, and it is why re-sorting a table is undoable in none of them. Get it wrong and Ctrl+Z scrolls instead of undoing |
| An `UndoStack` belongs to a **document**, not a `UIWindow` | Two editor tabs in one window must not braid histories. Impossible to retrofit once a shared stack exists, which is why there is deliberately no `UIWindow.getUndoStack()` |
| A `CompositeEdit` undoes in **reverse** | Each edit assumed the state the previous one left. The disconnect-then-connect pair is the smallest example: unwinding forwards restores an edge into an input that is still occupied |
| `System.nanoTime()` has an **arbitrary origin and may be negative** — never use `Long.MIN_VALUE` as a "long ago" sentinel against it | `nanoTime() - Long.MIN_VALUE` overflows to a *small* elapsed time, so "never merge again" evaluates as "merge immediately". Caught here by a test; in the wild it is undo swallowing two actions on some machines and not others. Use an explicit flag |
| `requestFocus` is **PROGRAMMATIC and therefore rings**; pointer-driven focus must use `requestPointerFocus` | `:focus-visible` exists to ring keyboard focus and *not* clicks. A widget that focuses itself on press through the wrong one outlines its whole viewport on every click — which is the noise the pseudo-class was added to remove |
| A widget whose keys are **commands** must be able to hold focus, or every one of them silently disables | Commands resolve their target from the *focused* element. `FocusPolicy` defaults to `NONE`, so a container that never sets one takes no focus and its whole command set is inert while the widget looks alive. `GraphView` shipped exactly this way |
| **A `ListView` is the tab stop of its own composite, and nothing had made it one** — rows are `CLICK_NOT_TABBABLE`, so `FocusPolicy.NONE` on the list left the composite with *zero* focusable entry points | The row comment has said "the LIST is the tab stop" since rows became focusable; the other half was never written. Not a tab-order nicety: `consumeKeyboardEvent` dispatches **nothing at all** while `focusedElement` is null, so a list nobody had clicked a *row* in heard no keys whatsoever — not the arrows attached in its own constructor, not type-ahead, not Ctrl+F. Clicking the empty space under the rows was worse than not clicking, because `emitMouseDown` blurs before it dispatches and handed focus to nothing. It surfaced in the Problems panel, which has no reason to have been clicked into; the explorer hid it by being a thing you click a file in immediately. **And a test cannot find it through `sendInputEvent`** — dispatching straight at an element skips focus resolution, so the test passes against a widget that can never be focused |
| `UIInputHandler` must **forget a detached element** — hover, press target, pointer capture, and any drag anchored on it | Focus already did. Hover did not, so deleting the element under the pointer left `lastFrameHover` in a detached subtree and the next hover diff asked for a common ancestor across two different trees: the walk never converges and runs off the end of both. `commonAncestor` now returns null rather than throwing, but the fix is to drop the reference |
| A press on an **already-selected** node must not collapse the selection | "Click one of the five I selected and drag them all" is the most common gesture in a graph editor, and "a press selects only what it hit" breaks it silently — four deselect and the drag moves one. Press selects-only when the node was *not* already selected; Shift always toggles |
| A marquee selects what it **touches**, not what it encloses | No vendor documents which they use, so it is a decision: at any zoom where a node is larger than the viewport, enclose-only makes it unselectable by marquee at all |
| Selection is **not** undoable | The majority choice and a live disagreement — Blender records it and is criticised for being "counter to basically all other applications"; Figma has a standing request for it as a preference. Ours is VS Code's: selection is view state, and an *edit*'s undo restores the selection that edit applied to |
| A drag's own delta is the truth at drag end, **never a re-read of the layout** | `worldBoundsOf()` reports the last *computed* layout, and the final `moveNode` of a drag writes insets Taffy has not resolved yet — so asking at drag end returns the position before the last move, and a short drag records a delta of zero and no undo step at all |
| A wire's colour is **read back out of the cascade** — `NodePort.typeColor()` returns the dot's computed `border-color` | `CgVectorRenderer` needs an ARGB int, so something must hand it a number; reading it from the dot keeps Unity's per-type palette in `graph.css` instead of putting GLSL's type system and its colours in Java. Hard-code it and adding a type means editing two languages |
| An input port takes **one** edge, an output **many** — so connecting to an occupied input *replaces* | Refusing it looks like correct validation and makes rewiring take two gestures. The displaced edge must leave through the same `disconnect` as a manual one, or 6.2.4's undo will not know it happened |
| `nodeport:blank` means *unconnected*, and a connect must `invalidateStyleMatch()` | It drives both the hollow-vs-filled dot and whether the inline editor shows. Without the invalidation a pseudo-class is never re-evaluated: the dot stays hollow under a live wire and the editor stays visible beneath it, which reads as a paint bug |
| Click-focus targets **the exact element hit**, never the nearest focusable ancestor | The DOM focuses the ancestor, which is why clicking a `<button>`'s inner text focuses the button. Every composite here dodges it by making its parts `setHitTest(false)` — unavailable when a part is itself interactive (a node's title bar carries the collapse chevron), so `GraphNode` calls `requestFocus` itself. Fixing `emitMouseDown` to walk up would cover every composite at once |
| A `graphnode` paints **no background of its own**; each region paints one, and the port band deliberately paints none | That is what lets a wire — drawn under every node — show through and read as plugged into its dot rather than cut off at the border. The cost is that a region which forgets to paint is see-through, so the regions are named together in `graph.css` rather than left to inherit |
| A drag ends when **the button that started it** is released — `startDrag` takes one, defaulting to left | The handler used to assume button 0 unconditionally: invisible for every left-button drag in the engine, and fatal for any other. A middle-button pan is never told its button came up, while the implicit capture release still fires — so a drag with no button held keeps eating every mouse move, and the canvas slides around on its own |
| A **positive** `MouseEvent.Scroll` notch means the wheel rolled **down** — `ScrollerView` is the only statement of it, via `setScrollTop(before + delta)` | Any new wheel consumer that takes the sign at face value is inverted. `CanvasView` shipped zooming *in* on scroll-down: nothing failed, because a test written from the implementation agrees with it, and the first person to touch a wheel found it in a second |
| A pan drag's source is the **viewport**, never the transformed plane | Every `DragListener` coordinate is converted through the source's own transform, so panning the plane you are dragging from moves the frame the delta is measured in — the view accelerates away from the cursor instead of following it |
| The canvas culls with `opacity: 0`, **not** `display: none` | A culled node's layout rect is the input its own cull decision is computed from. Collapse it and the node can never be un-culled without a cache of where it used to be — which then goes stale whenever anything moves it. Keeping layout live is what makes the decision self-correcting |
| The plane's `transform-origin` is pinned to `0 0` at IMPORTANT | It defaults to 50%, so every world↔screen conversion in `CanvasView` would be off by half a viewport times the zoom — and it would look plausible, since the picture is still internally consistent |
| A drag ghost is registered **per drag** — `UIDragController` drops it when the drag ends | Register once and you get a ghost for the first drag only; retaining it was a real bug, where a ghost outlived its drag and reappeared on unrelated screens |
| **Use `DragGhost`; do not hand `setGhost` a hand-built element** | The setter takes any `UIElement` on purpose (the input layer must not import `ui.elements`), but it is not a usable API alone: three rules are invisible in its signature and each is silent when broken — it must be **in the tree** before a drag can promote it, it must be `position: absolute; display: none` written **from Java at IMPORTANT at construction** (the first layout runs before any rule matches, and `UIText` latches its self-sizing from that pass — parked in a file panel it stretched to the panel width, parked in a 20px rail it would clamp to 20), and it must be re-registered per drag. Two widgets had written the same thirty lines, comments included, which is where the second one's differing padding came from |
| A `SplitView` divider must clamp against the pane's **CSS** `min-width`, not only against `setPaneSizeLimits` | Taffy refuses to shrink the pane, but the weight is a number SplitView keeps and nothing stopped it going lower. Drag past the minimum and release, and the stored split says 20px where the layout shows 150 — so the next drag back spends 130px before anything moves. **It cannot be fixed inside the drag**: `applyDividerDelta` already measures from the drag's own start so clamping cannot accumulate *within* a gesture, which is a different bug with the same symptom. This one accumulates *between* them. **And the limit is read from the pane's CONTENT as well as the pane** — `split.first()` is the `__split-pane__` wrapper SplitView makes for itself, so every real rule (`workbench .__region-sidebar__ { min-width: 120px }`) lands one level below where the clamp looks. Reading only the pane made the clamp as absent as before it was written, and the test that covered it styled the wrapper. It does not present as a divider bug at all: the pane **overhangs the split**, whatever paints later covers the overhang, and a `ListView` inside sizes its viewport to the full overhanging width — so the file tree's horizontal scroll ran out while names were still cut off, underneath the editor |
| A shipped third-party asset needs a notice file, not a javadoc comment | MIT requires its copyright notice to travel with the distribution and Apache 2.0 requires the licence, any NOTICE, and a statement of modifications. Naming the source in a class comment is good practice and satisfies neither. `THIRD-PARTY.md` indexes them |
| Trademarks survive every licence — Apache 2.0 § 6 says so outright | The IntelliJ Platform *file-type* icons are JetBrains' own drawings and ship; the IntelliJ IDEA *logo* is a mark and lives in `core/src/test/resources/`, where it is the SVG renderer's torture test and not in the jar |
| A recycled row must **swap** its data-driven classes, never merely add them | A template is a different row every time the view reuses it, so adding `filetype-java` without removing `filetype-md` leaves both on the element — and the cascade then resolves whichever rule happens to win, which reads as a random colour rather than as a stale class. Same for `decoration-*`. `ProjectFileTree.swapPrefixedClass` is the one definition |
| A row's slots are built in `createTemplate`, never in `bind` | An element created during bind lands after that frame's layout pass. Cost a session on the command palette's key chips and again on the editor's gutter arrows, which toggled whichever row their slot was first used for |
| A `FileDecorations` change must reach the rows, and through the deferred refresh | Decorations are read during `bind`, so an already-bound tree shows whatever was true when it last bound. Routed through `pendingRefresh` rather than an immediate `refresh()` because a provider may fire from inside a click handler on a row — and a widget must never rebuild the elements it is being clicked on |
| A bubbled decoration keeps the colour and drops the badge | A folder showing `M` claims the folder itself is modified. The colour on the folder and the badge on the file is the entire information content of the bubble |
| An icon's colour comes from the `.filetype-*` class, never from the theme JSON | A dozen languages share one `code` glyph and still need their own colours — keying colour to the icon cannot express that. Same split `graph.css` already makes for port types |
| Leading (top/left) resize handles exist only for out-of-flow elements | `left`/`top` on an in-flow box is a *relative offset* — it slides over the sibling above and reflows nothing, so the panel eats its neighbour |
| A leading resize derives its origin from the size **achieved**, never from the pointer delta | The box shrinks to `min-height` and then keeps travelling — while the trailing edge, which moves nothing, correctly just stops |
| A widget must never rebuild the elements it is being clicked or dragged on — update them in place | The mouse-down handler detaches the element under the cursor, and a drag's source detaches on its first update, so `screenToLocal` goes stale and every later frame feeds the drag garbage. The table header froze exactly this way: sort once and no header could be clicked or resized again |
| `resizeOriginLeft()`/`Top()` read the **live Taffy inset**, not a field — and **measure the offset when that inset is `auto`**, never answer 0 | A field only knows the positions the resizer wrote, so an element placed by a stylesheet reports origin 0 and the first leading drag teleports it to the corner. Answering 0 for `auto` is the same teleport wearing a different hat, and it survived the first fix for a year: `auto` means "wherever the static position put it", which is only zero for a box with *no* inset on that axis — a panel anchored by `right`/`bottom` has an `auto` `left` and is nowhere near it. `UIResizer` reads this as a **leading** edge's origin, so a *press* on the top or left border of either floating graph panel wrote `left: 0; top: 0` before the pointer moved at all. `CanvasOverlayMove` carried a paragraph warning that this method "is not the answer" and measured the offset itself; the warning never reached the resize path |
| **A flex item with `flex-shrink: 1` contributes ZERO to its row's min-content width** — and an explicit `width: 0` basis on a `UIText` latches it as "does not size itself", permanently | Invisible while rows are stretched to their container, because nothing asks what a row's content width is. It surfaces the moment one is sized to content: the Blackboard's rows scroll sideways for a long name, and the type column — carrying both — left the row exactly as wide as the capsule, so `Vector 2` rendered as `Ve…` jammed against the panel edge, *outside* the scrollable width the long name had just created. `UIText.forceSelfSizeWidth()` exists for the second half; the first is `flex-shrink: 0` |
| **`getScrollWidth()`/`getScrollHeight()` measure direct children only** — a descendant overflowing its parent adds nothing | Deliberate and cheap, but it means "make the content scrollable" is a statement about the *rows*, not about what is in them. A capsule spilling out of a full-width row painted over the canvas behind the panel and reported `maxScrollLeft: 0` — there was no way to reach it, by bar or by wheel, because nothing knew it was there |
| Resize is clamped to the containing block for out-of-flow elements only — and it is `resizeContainingBlock()`, not `getParent()` | Clamp only the move and a box parked at the corner can be *grown* out through it; clamp against the DOM parent and a promoted dialog stops short with window to spare |
| `inert` keeps its box — it is not a spelling of `display: none` | The one reason both exist; if inert ever stops laying out, it has become a worse `display: none` |
| Modal inertness is enforced at four points, and `focusable()` is deliberately **not** one of them | Fold it in and every cached focusable-descendant answer needs invalidating whenever a modal opens |
| Hit-testing an inert subtree **falls through** to what is behind it | `pointer-events: none` passes the pointer over a node; it does not punch a hole in the document |
| A detached modal must be popped from the modal stack | The window stays inert forever with nothing left to interact with — unrecoverable from the user's side |
| `StyleSheet.DEFAULT` is not installed for you | A test asserting on user-agent-sheet behaviour tests nothing and goes green |
| A compound selector carrying `::highlight()` must never match the originating element | Every highlight colour repaints the whole paragraph — and looks plausible, because the highlighted words are the right colour too |
| Pseudo-elements weigh **1** (type component), not 10 | Wrong by analogy with pseudo-classes; a `::highlight()` rule then silently outranks the class rules around it |
| **`::highlight()` CAN paint a background band, and the geometry was in the layout all along** | It sat in `NOT_YET_PAINTABLE` on the grounds that a band needs per-range rects and a `CgStyleSpan` carries nothing positional. Both true and beside the point: shaping breaks a run at every span boundary, so a highlighted range **is** one or more `CgShapedRun`s and each carries `sourceStart`/`sourceEnd`/`totalAdvance`. `UIText.paintHighlightBands` walks them — no measurement, no second shaping pass. `text-shadow` stays refused because it is a second *draw* of a range rather than a rect behind it |
| **A search box owns every CARET key; navigation gets only Up/Down and Enter** | Left, Right, Home and End all move a caret in a real text field, so taking any of them back undoes the reason the box is a field. Ctrl+Home/Ctrl+End were tried for first/last match and removed — `TextField` consumes Home and End for the caret whether or not Ctrl is held, so the binding was dead on arrival and only a capture-phase listener above the field could have taken it |
| **Arrowing through search results moves the SELECTION and never the focus** | The ARIA combobox pattern, which `ListView.restoreFocusIfRealised` already names and `QuickPick` already implements: the field owns the caret and the arrows, the list is a *view* of the selection. Focusing the row would take the caret out of the box on the first press. It is also why the list must never take focus from a control inside a row — the two rules are the same rule from opposite ends |
| **"Jump to the first match" belongs to the ONE place a query changes, not to whoever typed it** | Three routes set the filter — the search box, type-ahead in the tree, and a caller setting it outright — and putting the jump on the box alone made the same keystroke behave differently depending on where the caret was. `ProjectFileTree.setFilter` is the single entry point |
| **A search marks the matched CHARACTERS, never the whole row** | `SearchMatch` has carried `ranges` all along and nothing asked for them. IntelliJ bands the query span in amber, VS Code recolours it; a row-wide mark says "something here matched" and leaves the eye to do the work the mark was meant to save. Registered as a `::highlight()` range rather than as spans, or a filename with three matching letters grows a real Taffy node around them |
| A highlight property that resolves but is never painted is worse than one refused — `HighlightStyle` splits `ALLOWED` from `NOT_YET_PAINTABLE` | `background-color`/`text-shadow` sat in the allowed set doing nothing; the rule looked right, the band never appeared, and there was nothing to search for |
| **A `::highlight()` BAND must be cleared on the no-styles path, not merely assigned on the styled one** — `toCgSpans` returns early twice for "nothing to style", and both returns used to leave the previous `highlightPerChar` in place | Not a stale style — a band over the wrong text entirely. An unhighlighted label shapes as ONE run starting at character 0 and the band pass reads each run's first character, so a single leftover entry at index 0 paints across the whole string. Rows are pooled, so every explorer row element that had ever shown a match went on banding whatever filename landed on it next, **full width, for a query matching one file**. Everything else was right: the registered range was empty, the resolved style was correct, the counter said "1 of 1". `UIText.highlightBandCount()` is the only observable — asserting the range or the computed style passes against the broken version |
| **A shared row component must reach the rows ITSELF** — `TreeSearch` decorates the tree's renderer rather than requiring the host to call `markRow` | VS Code and IntelliJ both put highlighting in the renderer, and it is defensible (a renderer knows which element is the label). But it makes adoption a two-part contract whose second half fails silently: the Problems panel installed the component and got a working bar, working arrows and a truthful "1 of 1" while highlighting **nothing at all**. Nothing threw. A host that calls `markRow` itself still wins for that row — the explorer does, because it has a folder badge to write and a label this could only guess at. The decorator is applied at install, so a renderer set afterwards loses it |
| **A search bar is either TRANSIENT or PERMANENT, and it is the host that knows which** — `TreeSearch.Presentation` | Both references ship both and the difference is not cosmetic. VS Code's tree find widget and IntelliJ's find toolbar are summoned with Ctrl+F and dismissed; a settings window's search box is the first thing in the sidebar and is how you are expected to start. Dismissing the second kind leaves a panel whose main affordance vanished with no visible way back, so a permanent bar has no close button, refuses to hide, and reads Escape as "clear what I typed". The refusal lives in `close()` because every route — the X, Escape, a host call — goes through it |
| **FILTERING REVEALS; HIGHLIGHTING DOES NOT — and which one a tree does is a question about the MODE, not about the panel** | In Filter mode the tree *is* the result set: everything left is there because it matched or contains something that did, so a match inside a collapsed branch is not hidden, it is **missing**. Preferences showed exactly that for `gene` — it kept `Editor`, because `Editor ▸ General` matched, and drew one collapsed row and "no matches here", with the count agreeing because a count can only see visible rows. In Highlight mode the tree is untouched by construction (that *is* the distinction) and expanding would move rows under the cursor on every keystroke, which is what somebody chose Highlight to avoid — IntelliJ's speed search behaves this way, and it is why a folder carries a count badge instead. VS Code auto-expands on filter and **restores expansion when the filter clears**; without the restore one search leaves the tree sprawled open and the user re-folds by hand what they never unfolded |
| **A tree that restores selection BY ITEM must clear the index-based one first** — `ListView`'s clamp only discards indices that are now OUT OF RANGE | An index that is still in range survives pointing at a different row, and restoring the remembered items on top leaves **both**. Collapsing hides it (the list shrinks and stale indices fall off the end); expanding is where it bites — everything below moves down, every old index stays valid, and each re-flatten leaves one more row selected. It presented as the file tree gaining a selected row on every flip of the search mode, which reads as the search being additive when nothing about it is |
| **A panel's FILTER and its SEARCH must share one notion of "matches"** | The Problems filter read diagnostic messages while the search treated a heading as searchable by its FILE NAME, so `g` listed both shadergraphs and `graph` listed one: `new.shadergraph` has "graph" in its name and not in its message, so the row the search would have marked was filtered away before the marking could run. Two matchers in one panel disagree the moment a query hits only one of them |
| **Pass the SearchQuery, never the text** — the type that carries the options is the type the matcher takes | `TreeSearch.Model.setQuery` took a `String`, so every model rebuilt its own query and silently dropped Match Case, Words and Regex: `GRAPH` with two toggles lit still matched `shadergraph`. Handing over the text is the shape that lets a caller lose half the query, and it recurred in three places at once — `ProblemsTreeSource` (a private `toLowerCase().contains()`), `WorkspaceTreeSource.setFilter`, and `Preferences.matches` rebuilding one from `navigator.query()` |
| **FILTERING NEITHER DIMS NOR BRIGHTENS — the whole three-state colouring belongs to Highlight mode** | Highlight paints an answer over a *complete* tree: match white, ordinary grey, irrelevant dimmed — colour is the only thing that can say where to look. Filter has already said it by narrowing, so recolouring survivors is redundant, and it does not read as redundancy: the Preferences sidebar was `#CCCCCC` with no query and white with one, so typing appeared to restyle the whole panel. Worst in a navigator, whose matcher answers "does anything at or under this path match" and so makes nearly every surviving row a match. Dimming is additionally *untrue* there — `FilteredTreeSource` keeps a matching node's **whole subtree unfiltered** by design, so `Code Style` greyed out purely for sitting under a category that matched. The amber band stays in both modes, because it says *where* rather than *whether* |
| **A tree filter's predicate must answer for the NODE ITSELF — never "me or anything under me"** | `FilteredTreeSource` has two branches and the predicate chooses between them: a node whose predicate is true "keeps its whole subtree, unfiltered" (its own words), while one kept by the descendant walk brings only the branches that matched. A recursive predicate therefore reports every ancestor as a match and silently selects the wrong branch — Preferences' `matches` walked `idsUnder`, so `ge` listed Appearance and Code Style beside General purely for being Editor's children. Its own javadoc already said keeping the path to a deep match reachable was the source's job. VS Code draws the same line (Recurse for a node that matched, Visible for one merely carrying a match) |
| **A filtered TREE must match only what is written on the row — searching DESCRIPTIONS is a ranked-list affordance** | `ge` kept Appearance, Shaders and Workbench because their settings say "arran**ge**ment", "Percenta**ge**" and "chan**ge** it". Every hit was real and none was visible, and an unexplainable row in a tree is indistinguishable from a bug — it was reported as one twice. VS Code searches descriptions because its settings search is a **ranked list**, where a weak hit sinks to the bottom and costs nothing; a tree shows every survivor as an equal. IntelliJ indexes them and *tells you* it did. Until there is somewhere to say so, match the label (the setting's name — what people type) and not the prose about it |
| **A pane MINIMUM measured from realised rows may grow freely but must only shrink when the CONTENT changed** | `NavigatorView` sized the settings sidebar to its widest realised row and grew monotonically. The reason was sound — the measure reads *realised* rows, so a plain scroll changes the answer and an ungated fall makes the pane breathe as you scroll — but it is a **minimum**, so one long label seen once pinned the floor for the whole session: unfold a deep name and the split could never be dragged narrow again, even after folding it away. Gate the *shrink* on `onExpandChanged` (the only event that changes what the widest row could be) and leave growth ungated. Reveal-on-filter is what made this reachable — it realises deep rows nobody expanded |
| **...and "the content changed" is EVERY way the row set changes, not just a fold** | The same ratchet then returns in a new shape. `NavigatorView` gated its shrink on `onExpandChanged` alone, so a *filter* — which replaces the row set outright, and whose reveal/restore goes through the bulk `setExpandedItems` that emits no signal at all — widened the sidebar permanently, and clearing the query left the split stuck. A gate keyed to one cause is a gate that will be reached from another |
| **An unknown pseudo-class POISONS the sheet — it is not ignored** | One `:focus-within` rule broke **six** unrelated layout tests in panels that had never heard of a search box. The supported set is on `PseudoClasses` (`enabled`/`disabled`/`checked`/`blank`/`invalid`/`hover`/`active`/`focus`); anything outside it has to be a class somebody maintains — `SearchField.FOCUSED_CLASS` is the focus ring done that way |
| **`markAsInternal()` on a widget ITSELF makes it unstyleable as a selector SUBJECT** — it still works as an ancestor | `.__search-field__ .__icon__` matched while `.__search-field__` did not, so the box had no border, no sizing and no icon while its children looked fine. `ListView`'s constructor already carries the warning and `MenuBarView` paid for it once; the parts are made internal *individually* by `addInternalChild`, which is the correct half of that pair |
| **`markAsInternal()` RECURSES, and `removeChild` silently REFUSES an internal child** — so `addInternalChild(container)` makes every child the container already had undetachable through the public API | `removeChild` ends `if (child.isInternalUI()) return false`, and it returns a boolean nobody checks. The Run panel's empty-state note was drawn over a live console with a full rail beside it: every other line of the method worked — toolbar attached, stripe attached, rail built — and the one call that was supposed to take the note away was a no-op that threw nothing and logged nothing. The trap is the ORDER, not the API: children added *before* the `addInternalChild(container)` call are internal, children added *after* it are public, so two lines that look identical behave differently. The engine already knows the pair — `addChildAtInternal` reparents with `if (!previous.removeChild(child)) previous.removeInternalChild(child)`, which is why *moving* an internal child into a `SplitView` pane works while *removing* it does not, and why the same latent bug in `showRail` was invisible for a release |
| **A hidden child still counts for a `gap-all`** | `SearchField`'s option strip was a permanent child hidden with `display: none` when empty, and every existing consumer — palette, create menu, Blackboard — silently gained a gap; the Blackboard's overflow tests caught it. Create the container on first use instead: not existing is the only spelling of "costs nothing" that is actually free |
| **The fill idiom in a ROW is `width: 0; flex-grow: 1`** — neither `flex-grow` alone nor `width: 100%` works | Already stated for columns and it is the same rule sideways. But check the PARENT first: a bar with no `width` is sized to its own children, so its width *is* their width and there is no free space in it at all — every flex property tried on the child does nothing, and the row was never short of a rule, it was short of a parent |
| **`min-height` is a DIFFERENT PROPERTY from `height`, so a base rule's minimum beats any size you write at higher specificity** | `button { min-height: 14px }` held the search toggles open at 14px through three rounds of shrinking `height` — 13, then 12, then 10 — with nothing in the cascade to look at, because the rule that was winning was not competing for the same property. The chip stayed exactly as tall as the field's interior every time. Anything restyling a `button` into a compact glyph has to clear `min-height` **and** the base padding, not just set a size |
| **`background` and `background-color` are SEPARATE properties — a rule that sets only the colour is painted over by whatever drawable won** | `button:hover` shipped with `background: #FF0000`, a placeholder nobody saw while every button was a labelled rectangle. Used as small glyphs in a row it was unmissable: the search toggles flashed red under the pointer and stayed red while focused, *through* a `:hover` rule that set `background-color` to a grey. Restyling a button state means setting both — `background: none` to drop the drawable, then `background-color` for the fill |
| **`:disabled` and `:hover` tie on specificity, so the LATER rule wins** — a disabled control written above its own hover rule still lights up under the pointer | Both are one pseudo-class (10). The find bar's match arrows were correctly `setEnabled(false)` and correctly styled, and still looked live the moment the cursor touched them. Put the disabled rule after the hover one and repeat it for `:disabled:hover`. Also: `button:disabled` repaints the whole face, which is right for a labelled button and wrong for a glyph — dim the glyph instead |
| **A disabled control must leave HIT TESTING, not just gain a `:disabled` rule** | `:disabled` and `:hover` tie on specificity (one pseudo-class each) and a `:disabled:hover` compound does not match here, so a disabled button kept lighting up under the pointer *and kept showing its tooltip* — a dead control explaining what it would have done. `setHitTest(false)` alongside `setEnabled(false)` means `:hover` can never match and no boundary event fires, which is what a disabled control should do anyway |
| **A one-sided `border-width-*` either draws ALL FOUR SIDES or none — never the side you named** | The paint path takes `border().left` as its stroke width and strokes a uniform box, so the spelling decides which wrong answer you get: `border-width-bottom` (or `-right`, or `-top`) resolves, lays out and draws **nothing**, while `border-width-left` draws a **frame on all four edges**. Both shipped in the Run panel in one sitting — the toolbar had no rule under it and the console's control stripe had a grey box around its top, right and bottom, from the same property used two ways. The left-hand spelling is the dangerous one, because it looks like the feature works until somebody notices the three edges nobody asked for. The find bar's note records only the draws-nothing half. **A single edge is an ELEMENT** — 1px tall for a rule, 1px wide for a divider — which is how `statusbarview` spells its separators and why `.__status-sep__` exists at all |
| **A signal emitted by a WORKER thread carries that thread into every listener — and the engine's first touch is usually invisible** | `RunSessions` is written by the thread whose script just changed state and emits inline, so `onDidChange` handlers run on a script thread by construction. `ScriptWorkbench` pushed the Stop button's enablement from one; `setEnabled` ends in `invalidateStyleMatch()`, which added to `StyleEngine`'s dirty-match **HashSet** while the UI thread was copying it — `ArrayIndexOutOfBoundsException: Index 358 out of bounds for length 358` from `HashMap.keysToArray`, thrown in `advanceFrame` with **nothing about the Run panel anywhere in the trace**. Nothing at the call site looks threaded, which is the whole difficulty: one innocuous setter reaches the cascade. **The fix is PULL, not a lock and not a hop** — `RunPanel.refreshActions` already recomputed the same value every frame from the same object, and a per-frame reader cannot race the frame it reads in. Where a push is genuinely required, hop through `JobScheduler`, whose `onDone` is documented to run on the UI thread during `drain()` — `RunIndicators` is the reference. A `ConcurrentLinkedQueue` drained in the frame is the other safe shape, which is exactly why `RunConsole`'s transcript is one |
| **A paint method may skip the DRAW, never the METHOD** — an early `return` strands whatever the pass has already pushed | `TextField` grew a "do not draw the placeholder unless focused" guard as a `return`, which skipped the matching `popScissor`: the window flickered and then threw *"Unbalanced scissor stack after the main paint pass: depth 1, expected 0"*. Decide what to draw by choosing the string; fall through to the same teardown either way |
| **A `TextField` must refuse ALT chords as it already refuses CTRL ones** | It bailed on Ctrl and not Alt, so Alt+W inserted a `w` *and* consumed the event — and the keymap resolves **after** dispatch and only if nothing stopped it. Every Alt shortcut in the application was dead in exactly the place its own tooltip said to press it |
| **A menu MNEMONIC must not fire while a text field has focus** | `MenuBarView` matched any Alt+letter regardless of focus, so Alt+E in the find bar opened the Edit menu instead of toggling Preserve Case — and no per-field workaround can fix it, because that listener sees the key first. A mnemonic is a global affordance and a focused input is a local one; the local one wins. Same predicate `allowWhileTyping` uses |
| **Restore focus on the mouse-DOWN, not on the press** | `emitMouseDown` blurs before it dispatches and a `Button`'s `onPressed` fires on the mouse-UP, so refocusing there leaves the field drawn unfocused for every frame in between — visible as a flicker. Handing focus back in the same frame it was taken closes the gap |
| **A tooltip must read its accelerator from the KEYMAP, never spell it** | `Keymap.acceleratorFor` is what the menus already use, and it resolves outward from the element. A literal "Alt+X" is a promise the widget cannot keep the moment anything rebinds the command |
| **`Tooltip.attach` ADDS a listener pair — it does not replace** | Calling it again to update the text leaves the first tooltip in place and showing, so the accelerator never appeared however correct the lookup was. Retain the returned `Tooltip` and call `setText`; `StatusBarView` carries the same note |
| **An absolute child of a scroller still SCROLLS — `top: 0` means the top of the document** | The editor's find bar was positioned absolutely and scrolled away with the text, leaving the editor behind it. `setScrollExempt(true)` is what pins a decoration to the viewport; position alone never does |
| **Search results are offsets, so they must be re-run from the BUFFER's change signal** | Every edit invalidates them — typing, paste, replace, and **undo and redo**. Offsets found against the old text describe the new one wrongly: the count goes stale and the highlights sit over whatever moved into their place. Re-running from the one signal every edit passes through is what makes undo correct without the undo path knowing search exists |
| **ESCAPE IS A CASCADE — a control must stop consuming it once it has nothing left to do** | The engine already applies this at the top (a live drag eats Escape before a close watcher; a nested popover before the modal behind it), and any control that takes Escape owes the same courtesy. A **permanent** search bar clears its query on the first press and must let the second through, or whatever contains it can never be closed from the keyboard — the settings dialog swallowed every Escape, because its search is permanent and always focused |
| **State a widget flips from its own listener belongs on a CLASS, not a pseudo-class** — `:checked`, `:disabled` and `:hover` have all now cost a round each | The engine re-evaluates a pseudo-class on its terms; a class is re-evaluated on yours. The search toggles use `__on__`, the dead match arrows use `__off__`, and both were written only after the pseudo-class version was styled correctly and still drew the old state. `invalidateStyleMatch()` is protected, so there is not even a way to force it from outside |
| **"The user took ownership of this size" is latched by MOVEMENT, never by a press** | `NavigatorView` set its `userSizedSidebar` flag from the divider's mouse-DOWN, so a click that moved nothing — landing on the handle, a drag that ended where it began — permanently switched off the auto-sizing. It fails silently and does not look like a click: unfolding a long page name simply stops widening the sidebar for the rest of the session, with the label clipped at the pane edge. Reading `onPercentageChanged` instead also covers the keyboard resize the mouse-down never saw; guard the widget's own writes with a flag so its auto-size is not mistaken for the user |
| **A panel that exists to be READ opens its groups; a panel you navigate does not** | The Problems view shipped folded, so it showed a filename and a count — which is what the status bar already says. Both references expand theirs. The trap is the second half: this runs on every refresh (a diagnostic arriving, a tab switching, a filter changing), so re-opening whatever is closed makes a group impossible to fold at all. Track which groups have been auto-opened **once** — that set is what separates "new" from "closed on purpose" |
| **Match-stepping arrows and row-browsing arrows are different gestures; a host that navigates its own rows turns the component's off** — `setArrowNavigation(false)` | `NavigatorView`'s arrows walk the visible tree and *open the page* for whatever they land on, with or without a query. Match-stepping would fight that and go dead the moment the box was empty. Filtering already narrows the rows, which makes "arrow through what is left" the same gesture with a better answer — which is also why the settings search is FILTER mode and hides the mode button |
| **`flex-shrink` defaults to 0 here, so in any packed row exactly one element absorbs every squeeze** | The find bar put it on the input, so in the Preferences sidebar the count and the mode button stayed rigid and the *box* collapsed to ~40px — four characters of query rendering as `nt`. A control that can shrink to nothing is not a control: the input needs a `min-width` floor and something else in the row has to be the thing that gives (here, the count, which is the one part losable without losing the control) |
| `::highlight()` accepts only non-layout properties, enforced with a warning | The restriction *is* the feature: a highlight that could set `font-size` would reflow the text being searched as you type |
| Highlights re-shape here, but **overlay** on the web — so un-highlighted text must stay on the unspanned path | A span boundary is a shaping-run boundary; route plain text through a one-span document and every label in the engine shifts by a fraction of a pixel |
| `text-overflow` does **not** inherit — it must sit on the `UIText` itself | Set on a wrapper it silently never arrives, and the row renders as plain `clip` (`white-space` *does* inherit, which masks it) |
| Truncation changes no geometry — `UIText.displayedText()` is the only way to observe it | An ellipsis that never fires looks identical to one that does, in every test and every layout dump |
| A closed `Dialog` is `display: none`, so every box in it measures 0 | Any "does it fit?" assertion passes against `0 <= 0` — call `show()` first |
| Tab traversal gates on `tabbable()`; focus delegation gates on `focusable()` | Either a composite is N tab stops again, or a dialog's focus delegate skips its own first control |
| Click-focus tests `focusesOnClick()`, not `== FocusPolicy.CLICK` | Every non-selected member of a composite stops responding to the mouse |
| Exactly one tab in a `TabView` strip is tabbable, falling back to the first when nothing is selected | Zero tab stops — the whole tablist disappears from the keyboard |
| `Property.set()` silently drops re-entrant sets from inside its own emit | A listener cannot fight the value it's being notified about |
| A `.shader`'s `#include` reaches the **vertex** stage too — fragment-only lib code must self-guard with `#ifndef CG_VERTEX_STAGE` | NVIDIA compiles it and AMD refuses, so the UI is fine here and completely unlaunchable there |
| **Folding is view state** — it never touches `UndoStack` | Ctrl+Z would unfold instead of undoing, which is the same boundary the document/view rule already draws for scroll and selection. VS Code and IntelliJ both put it here |
| A collapsed region's **first row stays visible** — `hiddenRows()` starts at `startRow + 1` | That row carries the fold arrow and is the only handle left on the block. Hide it and a collapsed region is unreachable: the rows are gone and so is the way back |
| Once folding exists, `ProjectedLines.modelAt` **cannot use `Arrays.binarySearch`** | A hidden row projects onto ZERO view lines, so adjacent prefix-sum entries are equal — and the JDK's search over duplicates may return either, landing on a row that is not on screen. VS Code's `PrefixSumComputer.getIndexOf` tests the half-open span `[start, stop)` instead, so a zero-width row fails both bounds and is stepped over |
| Folding a block the caret is in must **move the caret to the block's header** | A caret on a hidden row has no view line, so it cannot be painted, scrolled to, or typed at — the editor looks focused and silently does nothing |
| A pooled gutter arrow's row is read **per frame**, never captured in its listener | Arrows recycle as the view scrolls, and a listener may only be attached once. Capture the row and the arrow keeps toggling whatever row its slot was first used for — which keeps working for exactly as long as nobody scrolls |
| A failed material compile latches (`hasCompileFailed`) and is cleared only by `markDirty()` | Without the latch every draw retries the compile — 3044 log lines a second; without the clear, hot-reload can never fix a broken shader |
| `Rope.fromChildren` must never return a lone child bare | It drops the rebuilt subtree by a level, and `concat`'s two unequal-height branches both read a height mismatch as "the join grew a level" and cast to `Internal` — so a join that *shrank* one throws `Leaf cannot be cast to Internal`. `build` groups leaves eight at a time, so every level whose count is ≡ 1 (mod 8) hits it: **`new TextEditor(text)` threw outright at ~8.2 KB, ~16.4 KB and up**, because the constructor reads every row. Found by a throwaway perf probe, not by the suite — the sizes in between are fine |
| `TextEditor.getScrollWidth` is a pure accessor; the scan is `measureWidestRealisedLine`, once a frame | `getMaxScrollLeft` reads it, `horizontalBarThickness` reads that, `viewportHeight` reads that, and `getScrollHeight` reads `viewportHeight` — so a dozen field-looking reads fan back into one loop over every realised line. **Measured at 54 entries per settled frame**; splitting measurement from query took a settled frame from 1277 µs to ~240 µs. Same trap `refreshGutterMetrics` already documents |
| A view part is a piece of the editor, not a client of it — the parts sit BESIDE `TextEditor` in its package and reach it through package-private accessors | Monaco needs a `ViewContext` because a part may not touch the view; with one view implementation in one package that indirection is a layer to keep in step rather than a seam. What is worth porting is the decomposition and the render protocol. `TextEditor` went 4159 → 3278 lines across ten parts; **the extraction is a pure code move**, so the 226 widget tests are a real net under it |
| `measuredRows` may be invalidated one row at a time **only** when the edit left the line count alone | The map is keyed by row index, so adding or removing a line renumbers every row below and their cached widths now describe someone else's text — which both places the caret and, via `viewLineDisplayText`, decides what the row *paints*. Removing that guard broke no existing test; `aLineCountChangeDropsEveryCachedRowMeasurement` was written from the surviving mutant |
| **ECJ reports NOTHING optional for a file that does not parse** — and "did the optional pass run" must be asked of the compiler (`Analysis.optionalProblemsAnalysed`), never inferred from the list | A unit with a syntax error gets `ignoreFurtherInvestigation`, which skips `analyseCode()` — and unused imports and unused locals both come out of the passes that skips. So one stray `.` turns four warnings into zero, and they "reappear" when the file parses again, which reads as the panel hiding them. `JavaLanguageServices` retains the last analysed set in a `DecorationSet` lane so they survive; **the retention must be gated on the compiler's own answer**, because "errors and no warnings" is equally the shape of a file that parses fine and resolves badly — treating that as suppression resurrects warnings the user has already fixed, which is worse than the bug being fixed. `CategorizedProblem.CAT_SYNTAX` is the published signal; the ID ranges are internal |
| **The JDK's own sources must be parsed at compliance 8, and every other attached source at the band's ceiling** — the rule keys on which ARCHIVE the bytes came from, never on the package name | A file out of `src.zip` declares `package java.util`, which `java.base` already owns, so at 9+ it lands in the unnamed module and the compiler refuses the clash: *"The package java.util conflicts with a package accessible from another module"*. **That one error is not local — it poisons resolution for the entire unit**, so every type reference in the file becomes unresolvable. The symptom is not a failure: `java.util.List` still quotes perfectly and draws `SequencedCollection` in the plain type colour, three lines under an editor drawing that same word interface-coloured, which reads as a scheme bug. 8 is the last level with **no module system**, so it is derived rather than tuned, and it is strictly better rather than a trade — at 9+ *no* platform type resolves anything. A `java.`-prefixed fixture inside an ordinary `-sources.jar` is not a platform source, which is why provenance and not the name decides |
| **`src.zip` ships with a JDK and NOT with a JRE, so the JDK half of source attachment is absent for most real players** | Verified rather than assumed: `jdk1.8.0_311/src.zip` exists, `jre1.8.0_311` has none anywhere. Mojang's launcher-bundled runtimes (`jre-legacy`, `java-runtime-*`) are jlink'd JREs, so a player launching from the vanilla launcher gets the **assembled** form for every `java.*` hover while a developer — and any modded player who installed a full JDK, which pack guides often tell them to — gets the quoted one. The feature costs nothing when it cannot help (three `isFile()` misses, one empty archive list, cached), but *"it works on my machine"* is the literal failure mode here, and a screenshot from a dev environment proves nothing about production |
| **The Gradle sibling-directory search must be GATED on the directory looking like a cache entry** | It exists for one layout — `…/version/<sha1>/foo.jar` beside `…/version/<othersha1>/foo-sources.jar` — and is waste everywhere else. A `mods/` folder is the case that matters: three hundred jars share one grandparent, so an ungated rule runs the same directory listing three hundred times on the first hover of every launch and then offers `config/foo-sources.jar` and `saves/foo-sources.jar` as candidates. Forty hex characters is the test, it is one string comparison before any filesystem call, and nothing but Gradle names a directory that way |
| **A signature is QUOTED first and assembled only as a fallback — and the fallback is the Minecraft case, so it is not legacy** | `AttachedSources` made "quote what is written down" reach the classpath, which retires the assembled path for anything with a `-sources.jar` or a `src.zip` — but an obfuscated 1.7.10 jar, a mod shipped without sources and a plain directory of class files have none, and that is §15.5's whole world. So `MAX_SIGNATURE_LINE`, `spaces()` and the hanging indent stay load-bearing. The corollary is a testing trap: with `src.zip` on disk, **every JDK type now quotes**, so a test that picks `Map.merge` or `ArrayList` to exercise assembly is silently testing quoting instead. Drive it with a **directory** classpath entry, which source discovery does not look beside |
| **A declaration is matched across two parses by BINDING KEY, and only if both resolved the same classpath** | A JDT key is derived from the signature, so the key the editor's unit reports for `List.add` is character-for-character the one a unit parsed out of `src.zip` reports for its declaration — which is why `AttachedSources` is handed the analysis's own classpath rather than deriving one. And it must be the **declaration** binding: `List<String>.add` and `List<E>.add` have different keys and only the second exists in a file |
| A retained diagnostic is re-announced from its **tracked offsets**, never from the row/column it was first reported at — and a range that `collapsedByEdit()` is dropped | The two halves of making retention honest rather than merely persistent. The file goes on being edited while it is broken, so a warning re-stated at its original row points at whatever has since moved there and the Problems row navigates to innocent text. And deleting the unused import while the file is still broken collapses the range holding its warning, so the warning goes with it instead of waiting for the next successful parse |
| **An announcement with a side effect is computed once per ANALYSIS, never once per listener** | `JavaLanguageServices.announcement` replaces the retained lane, and its inputs are row/columns that only mean anything against the document the analysis saw. Recomputing it when a view attaches maps those against a buffer that has since been edited and overwrites correctly-tracked ranges with wrong offsets — silently, since the late listener gets a plausible list and the early one never sees it change. `aLateListenerDoesNotCorruptTheRetainedPositions` fails by exactly one line when the replay is turned back into a recompute |

---

# Global coding rules

## Port, don't reinvent

**Anything that has been solved thousands of times — text editing, cursor movement, click and drag
selection, undo coalescing, layout — is ported from a battle-tested source and fine-tuned for this
codebase. It is not derived from first principles.**

These behaviours are *conventions, not derivable answers*. Each is one line, each is invisible when
wrong, and each was learned by shipping to millions of users. Four from `text/cursor/` alone:

| Rule | What happens without it |
|---|---|
| Auto-close fires on an **allowlist** (`;:.,=}])> \n\t`), never a denylist | "suppress before a letter" still opens a pair before `$foo` and `#define` |
| A plain arrow collapses a selection to its **edge**, regardless of which way the gesture went | Left-then-right on a backwards selection walks the caret instead of collapsing |
| A partly-commented block **comments out**, it does not half-toggle | Selecting a block with one commented line inverts half of it |
| A backwards word-drag **unions with the anchor word** | Word-granularity drag eats into the word it started on and stops feeling like words |

### Licences are load-bearing here

| Source | Licence | What you may do |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file. |
| **Zed** | **GPL-3.0** | **Read for shape only.** Copying it would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; not a line of its code. |

### Port the module boundaries too

`com.crystalgui.text.cursor` takes its boundaries from VS Code, though **not file-for-file** — the
mapping is worth stating because two of the five come from elsewhere in that tree:

| Ours | VS Code |
|---|---|
| `CursorColumns` | `common/core/cursorColumns.ts` |
| `MoveOperations` | `common/cursor/cursorMoveOperations.ts` |
| `TypeOperations` | `common/cursor/cursorTypeOperations.ts` |
| `LineOperations` | `contrib/linesOperations/browser/linesOperations.ts` — **not** `common/cursor/` |
| `MouseSelection` | `browser/controller/mouseHandler.ts` — **not** `common/cursor/` |
| `ColumnSelection` | `common/cursor/cursorColumnSelection.ts` |

Monaco's `common/cursor/` holds twelve files; the six we have no counterpart for are its orchestration
layer (`cursor.ts`, `cursorCollection.ts`, `oneCursor.ts`, `cursorContext.ts`, `cursorMoveCommands.ts`)
plus **one** remaining feature gap: `cursorAtomicMoveOperations.ts` — `editor.useTabStops` for *arrows*,
where a left or right arrow steps a whole indent unit through leading whitespace. Backspace already does
(`TypeOperations.backspaceFrom` counts visual columns), which is the half that was reported; the arrows
still move by one character.

> **Every column question in this package is a VISUAL one**, and that is the single most portable thing
> about it. A box selection computed from character offsets is not a box — two rows whose indentation
> differs in tabs have the same character column at different places, so the "rectangle" comes out as a
> ragged edge following the text. The same rule is why Backspace takes one tab rather than an
> indent's worth of characters, and why a paste is re-indented by columns rather than by string length.

> This is not tidiness. The same logic first went in as private methods on `TextEditor`, which reached
> **2556 lines, larger than the entire `com.crystalgui.text` package combined**, and could only be
> reached through a `UIWindow` with fonts, a style engine and an input handler. Extracting it exposed a
> real bug within minutes — deleting the *last* line left a blank line, because the last row has no
> trailing newline to take and must swallow the one before it instead. The widget test never caught it:
> it only ever deleted a middle line. **Porting the algorithms without the boundaries keeps the
> algorithms and throws away the testability that keeps them correct.**

## CrystalGraphics ownership boundary

**CrystalGraphics owns the rendering backend. CrystalGUI consumes it.**

- CrystalGUI may define renderer-facing abstractions and UI draw orchestration.
- Fonts, shaders, framebuffers, VAO/VBO, draw submission, GPU resource ownership, and modern GL
  pipeline capability belong in **CrystalGraphics**.
- Never write raw GL, raw `float[]` vertex packing, or a hand-rolled buffer in `core/`.

Because CrystalGraphics lives in this repo and is directly writable, if CrystalGUI needs a new backend
capability we **add it to CrystalGraphics** and integrate against the new API — we do not reimplement
the backend here.

> The full infrastructure ownership map (`CgStreamBuffer`, `CgStagingBuffer`, `CgVertexWriter`,
> `CgInstanceWriter`, `CgBufferWriter`, `CgShaderBuffer`, `CgMesh`, `CgVertexArray`,
> `CgAbstractShaderProgram`, and the decision tree for picking between them) lives in
> **`CrystalGraphics/AGENTS.md`**, which `CLAUDE.md` already loads every session. Read it there;
> it is not duplicated here.

## Platform-agnostic core

`core/` must stay fully cross-platform — the import guard enforces no `net.minecraft.*`,
`cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`.

**The platform seam is CrystalGraphics'.** CrystalGUI has no registry of its own — it reads everything
through `CgPlatform`, and a loader registers exactly one `CgPlatformService` bundle:

| Need | Reached via | Lives in |
|---|---|---|
| Key/mouse codes, modifier state, **and the clipboard** | `CgPlatform.input()` | `platform/service/CgInputService` |
| UI sounds | `CgPlatform.sound()` | `platform/service/CgSoundService` |
| Presenting a cursor | `CgPlatform.cursor()` | `platform/service/CgCursorService` |
| Raw event sink (`UIInputHandler` implements it) | — | `platform/input/CgSystemInput` |
| Code constants, cursor enum, cursor artwork | — | `platform/input/CgKeyCodes`, `CgMouseCodes`, `CgModifiers`, `CgCursor`, `CgCursorBitmaps` |

> **The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
> is reached the same way and needed by exactly the code that handles keys — two methods do not earn a
> registration slot. Both default to a no-op pair.

**No method in this SPI has a default, and neither sound nor cursor ships a `NOOP` constant.** A default is
an answer chosen for someone who never saw the question: a new platform compiles cleanly while silently
inheriting "no sound, no cursor, no clipboard", and inheriting a no-op is indistinguishable from deciding
on one. Abstract methods make the compiler the reminder — and a platform with nothing to offer still says
so, with an empty body in its own source.

> **Why this stopped being CrystalGUI's own registry.** `CrystalGuiCore` used to hold four static fields
> with setters. CrystalGraphics is the parent project and is always present, so two registries meant a
> loader had to find both — and could wire up one, leaving a UI with a working GL backend and no keyboard.
> One bundle makes a platform either registered or not. `CrystalGuiCore` now holds only `LOGGER`.

## Lombok

Prioritize Lombok to eliminate handwritten accessor boilerplate. It generates Java 8-compatible
bytecode and is `compileOnly` — no runtime dependency.

| Annotation | Use when |
|---|---|
| `@Data` | Simple POJOs, all fields in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — apply at field level when only some fields need accessors |
| `@RequiredArgsConstructor` | Immutable classes — pairs with `@Getter` only |
| `@Builder` | 4+ constructor parameters, or many optional ones |
| `@Value` | Fully immutable data carriers |
| `@ToString` / `@EqualsAndHashCode` | When you need one without full `@Data` |
| `@Accessors(chain = true)` | Fluent setters — used widely here |

Do **not** use `@Data` on classes with inheritance — use explicit annotations and always
`@EqualsAndHashCode(callSuper = true)` on subclasses.

---

# Package map

```
com.crystalgui.core            CrystalGuiCore — the global LOGGER, and nothing else. The platform
                               registry it used to hold now lives in CrystalGraphics; see below.
  .data                        CacheCell / IntCacheCell / LongCacheCell (dirty-flag memoization),
                               ReadOnlyVec2f (immutable view over a mutable JOML Vector2f), Transform2D
  .dispose                     Disposable, Disposable.Gl, Disposer — the ownership tree. NOT a
                               replacement for CgGraphicsLifecycle's registry sweep; it exists to
                               release on CLOSE rather than on exit, and to reach createOwned GL
                               objects no registry can see. docs/CGUI_WORKBENCH_SERVICES.md
  .property                    Property<T> (binding, equality-suppressing set), ObservableList<T>
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
  .command                     Command (a named invocable action), CommandContext, CommandRegistry —
                               what a key binding, a menu item and the palette all point at. Plus the
                               MENU MODEL: MenuId (a named place a menu is drawn, interned, with nested
                               submenus), MenuSection (a group + its rows — what a separator is drawn
                               from), MenuEntry (Item/Submenu, sealed; an Item carries enabled/checkable/
                               checked so the RENDERER decides), MenuContributor (rows computed at open
                               time — the Window menu's editor list). CommandRegistry.sections() is the
                               one query every menu renderer reads; menu() is its deprecated flat view
  .undo                        Edit (one undoable change), CompositeEdit, UndoStack — one history per
                               DOCUMENT, never per window

com.crystalgraphics.platform   NOT CrystalGUI's code — CrystalGraphics' platform SPI, which CrystalGUI
                               consumes. Listed here because the engine's input, sound, clipboard and
                               cursor seams all live in it.
  (root)                       CgPlatform (the registry), CgPlatformService (the bundle a loader registers)
  .input                       CgSystemInput (raw Mouse/Keyboard event sink + event types),
                               CgKeyCodes (LWJGL2-shaped, no LWJGL import), CgMouseCodes,
                               CgModifiers (bitmask), CgCursor (the cursor keyword set),
                               CgCursorBitmaps (procedural 32x32 cursor art)
  .service                     CgInputService (codes, modifier/key/button state, AND the clipboard),
                               CgSoundService, CgCursorService — plus CrystalGraphics' own six

com.crystalgui.lifecycle       CgUiLifecycle — the ONE CgLifecycleListener CrystalGUI registers with
                               CrystalGraphics; drives paint-context teardown + cache invalidation

com.crystalgui.render          CgUiPaintContext (singleton), CgUiRenderer, ScissorStack
  .text                        FontFamilyCache — (font stack, px) -> CgFontFamily
  .texture                     CgUiDrawable (SPI), CgUiQuad, CgUiSprite (9-slice), CgUiRoundedRect (SDF),
                               CgUiCrossFade, CgUiLayerBox, CgUiRepeat, ArgbMath, CgUiSvg,
                               CgUiTransformDrawable (stub)
    .svg                       A full SVG renderer, parse to cached draw ops: SvgScanner (nested tags),
                               SvgPath (the d grammar), SvgTransform, SvgColor, SvgStyle (inheritance),
                               SvgGradient, SvgTriangulator (scanline fills, holes cut), SvgDocument
    .asset                     CgUiSpriteRegistry — lazy "ns:name" -> sprite from ui/sprites/*.json;
                               FileIconTheme — VS Code's file-icon-theme model, ui/icons/*.json
    .geometry                  Position, Size

com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin,
                               TaffyBridge, PseudoClasses, StyleEngine, CssParsingUtil, CssAngle
  .sheet                       StyleSheet (+DEFAULT), StyleRule, DeclarationParser (var(--x)),
                               StyleSheetRegistry
  .selector                    Selector, CompoundSelector, SelectorType
  .transition                  TransitionEngine, TransitionSpec, ActiveTransition, TransitionValue
  .easing                      Easing, Linear, CubicBezier, LinearPiecewise, ConstantEasing,
                               ProgressFunctions
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue,
                               IValueInterpolator
    .general.{bools,enums,floats,ints,strings}   scalar StyleValue/StyleProperty flavors
    .layout                    LayoutProperties, BoxEdgeShorthands
    .layout.{dimension,grid,length}              Taffy-shaped value types (LPA*, LPSize, Grid*)
    .visual                    Overflow, OverflowClip, ScrollBehavior, BoxOrigin, DrawableAlign,
                               DrawableFit, OutlineShorthand, OutlineOffsetShorthand (per-edge,
                               unlike CSS)
    .visual.border             BorderRadiusProperties, BorderRadiusShorthand, LengthPercent(+Property/Value)
    .visual.color              ColorProperty, ColorValue
    .visual.text               FontFamilyValue
    .visual.texture            TextureProperty, TextureValue
    .visual.transform          TransformProperty, TransformValue, TransformOriginShorthand

com.crystalgui.ui              UIElement, UIWindow, Ui, UITransform, EventListenerGroup,
                               ElementRegistry, UIFrameTicker (SPI), UITreeObserver, TopLayer, UIResizer,
                               AnchoredPlacement (CSS Anchor Positioning subset)
  .tree                        UITreeTraversal — stateless ancestor/tab-order queries
  .event                       UIEvent, PropagationPhase, CloseEvent, DOMEvent, DragEvent, FocusEvent,
                               KeyboardEvent, MouseEvent
  .input                       UIInputHandler, UIDragController, FocusPolicy, ButtonState
  .text                        TextRange, HighlightRegistry — CSS Custom Highlight API (ranges in Java,
                               styling in CSS via ::highlight(name)); see StyleEngine.highlightStyle
  .elements                    Button, Checkbox, CheckboxGroup, Dialog, DialogManager, DragGhost,
                               Dropdown, Menu, MenuItem, Popover, Scroller, ScrollerView, Slider,
                               SplitView, Switch, Tab, TabView, TextField, Tooltip, UIText
    .chrome                    The shell's own widgets, none of them general-purpose: MenuBarView (the
                               main menu bar), MenuBuilder (sections -> rows; the ONE place a Menu is
                               populated from commands, and ContextMenu is a caller of it), ContextMenu,
                               MainMenuCommands, CommandPalette + QuickPick*, StatusBarView,
                               NotificationsView/Balloons/Card, ProblemsPanel + ProblemNode +
                               ProblemsTreeSource, Breadcrumbs, NavigatorView, Preferences, PageStack,
                               InputDialog, ChromeCommands
    .editor                    TextEditor (the widget), EditorCommands (its named actions), plus VS
                               Code's VIEW-PART decomposition: EditorViewPart (the base + Monaco's
                               shouldRender protocol), DecorationPool (the pool/hide idiom), and one
                               part each — LineNumbersPart, ViewCursorsPart, SelectionsPart,
                               CurrentLinePart, IndentGuidesPart, WhitespacePart, RulersPart,
                               GutterEdgePart, FoldingDecorationsPart, ZoomIndicatorPart
    .workbench                 The shell's file panel, split the way VS Code's explorer is: ProjectFileTree
                               is the VIEW (explorerView.ts), FilesRenderer builds and fills a row,
                               ExplorerDragAndDrop is FileDragAndDrop, ExplorerFind is ExplorerFindProvider
                               plus the bar it lacks, ExplorerEditing is setEditable + renderInputBox, and
                               WorkspaceTreeSource is the MODEL (explorerModel.ts) — listings, sorting,
                               compact folders, what matches. The parts sit BESIDE the view and reach it
                               through package-private accessors, as TextEditor's ten view parts do
    .workbench.decoration      FileDecoration, FileDecorationProvider, FileDecorations — VS Code's
                               IDecorationsProvider. Independent contributors (dirty, read-only, errors,
                               VCS) merged per field, with bubbling up to ancestor folders
    .canvas                    CanvasView (pan/zoom viewport), WorldRect — the node graph's substrate
    .graph                     GraphView, GraphNode, NodePort, NodeWireLayer, GraphConnection,
                               GraphSelection, GraphCommands, PortType (SPI) + BasicPortType +
                               PortTypeRegistry, PortDirection

com.crystalgui.text            Rope, TextBuffer, TextSummary, Change/ChangeSet, Selection,
                               SelectionModel, TextPoint, TextRange, WordClassifier, WordOperations,
                               LineEnding — the document model, all headless
  .cursor                      CursorColumns, MoveOperations, TypeOperations, LineOperations,
                               MouseSelection, ColumnSelection — VS Code's boundaries, but NOT
                               file-for-file: MouseSelection and LineOperations come from
                               browser/controller/ and contrib/linesOperations/. See "Port the module
                               boundaries too" for the full mapping and the ONE unimplemented gap left
                               (atomic tab moves for the ARROW keys; Backspace already steps by column)
  .syntax                      Language, LanguageRegistry, SyntaxToken, SyntaxTokenizer (SPI),
                               KeywordTokenizer — the ENGINELESS tier, and what a dedicated server has
  .lang                        The semantic layer's contracts, INTERFACES ONLY: LanguageServices (the
                               per-DOCUMENT facade), SemanticTokenProvider, Resolver, CompletionProvider
                               + CompletionItem/CompletionList, SymbolInfo/SymbolKind/SymbolModifier,
                               TypeRef, DeclarationSite, Versioned. Every engine lives in language/;
                               this package is the whole footprint in core/, and its absence at runtime
                               is the only feature flag. docs/CGUI_WORKBENCH_SERVICES.md
  .diagnostic                  Diagnostic, DiagnosticSet, DiagnosticSeverity, DiagnosticTag, Markers,
                               RelatedInformation — LSP-shaped, per-owner. NOT duplicated in .lang
  .wrap                        LineProjection, ProjectedLines, LineBreaksComputer (SPI),
                               MonospaceLineBreaks, ShapedLineBreaks, BreakOpportunities, WrapIndent —
                               soft wrap, and the model/view coordinate seam the whole editor rests on
  .view                        IndentLevels, WhitespaceMarkers, RenderWhitespace
  .fold                        FoldingRegions (+Region), FoldingModel, FoldingRangeProvider (SPI),
                               IndentRangeProvider — folding. INDENT-based by default, which is Monaco's
                               default too and deliberately not brackets; see the class javadoc for why

com.crystalgui.serialization   Codec<A>, DynamicOps<T>, Codecs, CodecException, JsonOps, PlainOps,
                               StateMap, UIDescriptionCodec, ContentHash
  .style                       StyleValueCodecs, InlineStyleCodec

com.crystalgui.net             UIPacket, UIPacketCodec, UITransport, InMemoryTransport,
                               ServerUiSession, ClientUiSession, RpcRegistry, NetworkIds, SheetRef,
                               UiEventKinds
```

**Naming corrections vs. older notes:** `render/` is top-level (`com.crystalgui.render`), *not* nested
under `core/`. There is **no `core/input/` or `core/sound/` package any more** — the raw platform I/O
layer moved wholesale to `com.crystalgraphics.platform`, and `com.crystalgui.core` is now the logger plus
three small utility packages. Dispatch and focus were always in `ui/input/` and stayed there. The
three-phase event types are in `ui/event/` — there is no `core/event/` package.

---

# Shipped assets

`core/src/main/resources/assets/crystalgui/`

| Path | Notes |
|---|---|
| `ui/styles/ua/*.css` | **User-agent sheet, in nine domain parts** (core, widgets, editor, overlays, config-kit, inspector, workbench, panels, search) concatenated in `StyleSheetRegistry.DEFAULT_SHEET_PARTS` order into `StyleSheet.DEFAULT` — one sheet, one parse, one variable scope, and cross-part order is as load-bearing as order within a file. Functional geometry for every widget with no theme loaded; every colour is `var(--token, #fallback)`. Was a single 6,200-line `default.css` until plan_styling.md step 8. |
| `ui/themes/base.css`, `ui/themes/crystal-dark.css` | The token tables: component→system derivations, and the default theme (pins today's look exactly). See `docs/CGUI_THEMING.md`. |
| `ui/schemes/dark-plus.css` | The default editor colour scheme — the second, independently-selectable axis. |
| `ui/styles/ore.css` | Minecraft Ore UI theme, ported from LDLib2's `ore.lss`. |
| `ui/styles/graph.css` | Node-graph theme — Unity Shader Graph's look, including the per-type port palette every wire reads its colour from. |
| `ui/styles/filetypes.css` | Per-file-type colour palette, keyed on the `.filetype-*` class `FileIconTheme.classFor` returns. **Not** in `default.css` — that is the UA sheet and carries geometry only. |
| `ui/styles/decorations.css` | Decoration palette, keyed on the `decoration-*` class a `FileDecoration` names. Same split, same reason. |
| `ui/icons/*.svg` | Feather icons (MIT), stroked `currentColor` chrome marks. `icon("crystalgui:folder")` in CSS. |
| `ui/icons/filetypes/*.svg` | 50 IntelliJ Platform icons (Apache 2.0), filled, 16px, carrying their own palette. What the file tree draws. |
| `ui/icons/default.json` | The default file-icon theme: extension/name → icon. Colour is deliberately not in it. |
| `ui/icons/ATTRIBUTION.md` | **An obligation, not documentation** — MIT and Apache 2.0 both require notices to travel with the distribution. Indexed from the repo-root `THIRD-PARTY.md`. |
| `ui/sprites/ore.json` | Sprite definitions backing `ore.css`. |
| `textures/gui/ore_styles.png` | Ore theme atlas. |
| `textures/gui/gdp_styles.png` | **Unreferenced by any code today.** |
| `textures/gui/Spritesheet_UI_Flat.png` | Unreferenced by any stylesheet today. |
| `ui/fonts/Minecraft.otf`, `MinecraftRegular.otf` | Public-domain MC fonts. |
| `shaders/gui_quad.shader` | Default material bound by `beginFrame`. |
| `shaders/gui_rounded_rect.shader` | SDF rounded rects. |
| `shaders/gui_layer_blit.shader` | Visual-layer FBO composite. |
| `shaders/gui_curve.shader` | Bézier strokes, via `ctx.curve()`. Declares `#pragma cg_use curve`, not `quad`. |

> **`gui_curve.shader` holds no stroke maths** — it `#include`s `crystalgraphics:shaders/lib/stroke.glsl`,
> which is shared verbatim with the engine's own `curve.shader`. The two materials differ in exactly
> three things: `DepthTest ALWAYS` (UI paints in painter's order over whatever the world left in the
> depth buffer — `LEQUAL` is right for a 3D stroke and wrong here), the `_LayerOpacity` property, and the
> one line that multiplies it in. **A Pass's `RenderState` is fixed at author time and cannot vary per
> keyword variant**, which is why this cannot collapse into one material with a `#pragma cg_feature` —
> the same constraint `text.shader` documents about its own depth state.
>
> It was briefly a full copy of the fragment body, which is worth recording because the cap logic in
> there was wrong three separate times: two copies means the fourth fix lands in one file and the other
> keeps the bug, silently, while still rendering something plausible.

> **All three declare `#pragma cg_use quad`, and any new CrystalGUI shader must too.** Everything
> here draws through `CgQuadRenderer`, whose per-instance buffer supplies `CG_QUAD_WORLD_POS` /
> `CG_QUAD_UV` / `CG_QUAD_COLOR` — the pragma is what wires it, during parsing, before anything can
> compile. Omitting it is a parse error naming the missing line (it used to be a GLSL error about an
> undefined `QUAD_DATA`, reported four layers up as an unrelated `#pragma cg_feature` complaint).
> Never attach the buffer from Java. See `CrystalGraphics/AGENTS.md` § *Engine Buffers*.

> **A `#include` in a `.shader` is compiled into the vertex stage as well as the fragment stage.**
> The material compiler hoists every material-scope `#`-line into both. `gui_rounded_rect.shader` is
> the only shipped shader with an include, and its `sdf.glsl` needed `#ifndef CG_VERTEX_STAGE` around
> `sdf_coverage` — `fwidth` is fragment-only, NVIDIA accepted it anyway, and AMD's refusal made the
> whole gallery unlaunchable on that hardware. Guard fragment-only code inside the lib, with
> `#ifndef CG_VERTEX_STAGE` and never `#ifdef CG_FRAGMENT_STAGE` (raw `.vert`/`.frag` get neither
> define). `ShippedShaderStagePurityTest` enforces it GL-free; `--mode=shader-compile-audit` checks it
> against a real driver. See `CrystalGraphics/AGENTS.md` § *Stage defines*.

---

# Documentation index

| Doc | Status | Contents |
|---|---|---|
| `docs/CGUI_STYLE_RENDER_PIPELINE.md` | **current** | Cascade, selectors, stylesheets, transitions, frame lifecycle, drawables & compositing channels, `background:` grammar, border-radius layer, visual layers (opacity + masking), `transform`/`transform-origin`, known gaps vs. the web, file map |
| `docs/CGUI_WIDGETS.md` | **current** | All thirteen widgets: API, internal-child class hooks, pseudo-classes, covering harness scene |
| `docs/CGUI_SERVER_AND_SERIALIZATION.md` | **current** | Codecs, `StateMap`, descriptions, content hashing, network ids, `SheetRef`, packets/sessions/RPC, known gaps, the headless contract |
| `docs/CGUI_WORKBENCH_SERVICES.md` | **current** | The service layer under the dock/workbench/editor — what a widget may *ask* rather than reach through the application for. `Disposer` today; `DataContext`, service events, `Resource`, `DockPane` and `DockService` as they land. **Every new service API is added here in the same commit** |
| `docs/CGUI_THEMING.md` | **current** | Themes, editor colour schemes, the token vocabulary and the anti-rot rules. Its token table is **generated and machine-checked** (`StyleGovernanceTest.theDocumentedTokenTableIsCurrent`) — regenerate from the failing test's output, never hand-edit |
| `plan_styling.md` (repo root) | **live** | The styling overhaul plan: audit, reference research, token architecture, governance, the step-by-step migration and its recorded revisions |
| `plan.md` (repo root) | **live** | The architecture review this layer is being rebuilt from: audit, VS Code/IntelliJ research, the six-step port, and what each step deliberately does not do |

These four are the only docs under `docs/` — the first three audited against the code on
2026-07-29, `CGUI_THEMING.md` added 2026-08-10 with its token table machine-checked against the
css on every test run. `CRYSTALGUI_OVERHAUL_V4.md` (the historical decision record for why CrystalGUI stopped
owning rendering infrastructure) was deleted; its one durable conclusion — CrystalGraphics owns the
backend, CrystalGUI is a thin immediate-mode paint surface — is recorded in
[CrystalGraphics ownership boundary](#crystalgraphics-ownership-boundary) above.

## External references

- **LDLib2** — pattern prior art for widgets and the Ore theme. An **in-repo checkout** at
  `research_repos/LDLib2`, never a dependency. Stylesheets at
  `research_repos/LDLib2/src/main/resources/assets/ldlib2/lss/` (`gdp.lss`, `mc.lss`, `modern.lss`).
  Java sources under `src/main/java/com/lowdragmc/lowdraglib2/`; note `bin/` also holds compiled
  `.class` files, so search `src/` explicitly. *(Was documented as a sibling checkout at `../LDLib2`,
  which does not exist.)*
- **Taffy** — consumed as the Gradle artifact `dev.vfyjxf:taffy` (version in `gradle.properties`), but
  **extracted Java sources are checked in** at `research_repos/taffy/dev/vfyjxf/taffy/`. Read them
  directly — there is no need to decompile through the IDE, and no need to guess at layout semantics
  (containing blocks, absolute positioning, flex-wrap cross-sizing) that the engine's own behaviour
  depends on. *(Previously documented as "no source checkout"; it exists.)*
- **Minecraft sources** — not extracted at the paths the MC modules would produce
  (`mc1201/*/build/mc-src/`, `build/rfg/minecraft-src/java`), since neither MC module is in the build.
  **But an extracted 1.20.1 tree is checked in** at `research_repos/mc1201_sources/`
  (`com/`, `mcp/`, `net/`). Cite that path, not the build ones.

---

# For future reference

- **Cg** → CrystalGraphics
- **Cgui** → CrystalGUI
